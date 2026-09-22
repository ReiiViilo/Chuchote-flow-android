package dev.soupslurpr.transcribro.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.soupslurpr.transcribro.BuildConfig
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.privacy.sensitivePlainText
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService
import dev.soupslurpr.transcribro.recognitionservice.RecognitionSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Widget de dictée flottant, à la manière de Wispr Flow : une sphère posée
 * par-dessus toutes les applications. La secousse peut rappeler une sphère
 * masquée, mais seule une pression volontaire sur la sphère lance la dictée.
 *
 * Pendant la dictée la sphère porte une coche — la toucher valide — et une
 * petite pastille apparaît en bas avec le signal sonore et une croix pour
 * annuler.
 *
 * Service de premier plan de type micro : c'est ce qui autorise la capture
 * audio alors que l'application n'est pas à l'écran.
 */
class FloatingWidgetService : Service() {

    private enum class State { IDLE, PENDING, RECORDING, TRANSCRIBING }

    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var orbView: OrbView? = null
    private var panelView: View? = null
    private var waveView: SineWaveView? = null
    private var cancelView: View? = null
    private var panelOnLeft = true

    private var speechRecognizer: SpeechRecognizer? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null

    private var dismissView: View? = null
    private var dismissCircle: View? = null
    private var dismissAttached = false
    private var bubbleHidden = false

    // Position de la sphère avant le geste qui l'a menée à la corbeille : c'est
    // là qu'elle réapparaîtra, et non sur la corbeille elle-même.
    private var restoreX = 0
    private var restoreY = 0

    private var state = State.IDLE
    private var panelAttached = false
    private var foregroundStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognitionSessions = RecognitionSessionTracker()
    private val recognitionAttempts = WidgetRecognitionAttemptGate()
    private var activeAttemptGeneration: Long? = null
    private var deliveryTarget: FocusedTextTarget? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var consentJob: Job? = null
    private val positionPreferences by lazy {
        getSharedPreferences(POSITION_PREFERENCES, Context.MODE_PRIVATE)
    }

    /** Une attente longue informe l'utilisateur sans détruire son travail. */
    private val slowTranscriptionNotice = Runnable {
        if (state != State.TRANSCRIBING) return@Runnable
        Toast.makeText(
            this,
            "La transcription continue. Ton audio est sauvegardé dans l’historique.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_FOCUSABLE est indispensable : sans lui, la fenêtre volerait le
        // focus au champ de saisie de l'app hôte et il n'y aurait plus rien où
        // insérer le texte.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private val panelParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private val dismissParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted && !promoteAndInitialize(intent)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Relancer le widget depuis l'application le fait réapparaître s'il
        // avait été glissé dans la corbeille.
        if (bubbleHidden) showBubble()
        return START_NOT_STICKY
    }

    private fun promoteAndInitialize(intent: Intent?): Boolean {
        val canPromote = WidgetForegroundStartPolicy.canPromote(
            launchedFromVisibleActivity =
                intent?.getBooleanExtra(EXTRA_VISIBLE_LAUNCH, false) == true,
            consentAccepted = PrivacyConsent.isAcceptedBlocking(this),
            microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            overlayGranted = Settings.canDrawOverlays(this),
        )
        if (!canPromote) {
            Toast.makeText(
                this,
                "Ouvre Chuchote Flow pour autoriser le widget et le microphone.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            Toast.makeText(
                this,
                "Android a refusé le démarrage du widget microphone.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }

        foregroundStarted = true
        instance = WeakReference(this)
        consentJob = serviceScope.launch {
            PrivacyConsent.acceptanceFlow(this@FloatingWidgetService)
                .collect { accepted ->
                    if (!accepted) stopForRevokedConsent()
                }
        }
        addBubble()
        registerShakeDetector()
        return true
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(slowTranscriptionNotice)
        consentJob?.cancel()
        consentJob = null

        if (bubbleView != null && !bubbleHidden) saveBubblePosition()

        sensorManager?.unregisterListener(shakeDetector)
        shakeDetector = null

        recognitionSessions.invalidate()
        recognitionAttempts.cancel()
        activeAttemptGeneration = null
        deliveryTarget = null
        cancelAndDestroyRecognizerBestEffort()

        hidePanel()
        hideDismissTarget()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        orbView = null
        if (instance?.get() === this) instance = null
        serviceScope.cancel()

        super.onDestroy()
    }

    private fun stopForRevokedConsent() {
        recognitionSessions.invalidate()
        recognitionAttempts.cancel()
        activeAttemptGeneration = null
        deliveryTarget = null
        cancelAndDestroyRecognizerBestEffort()
        mainHandler.removeCallbacks(slowTranscriptionNotice)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::windowManager.isInitialized) return
        val (boundedX, boundedY) = loadSavedOrbPosition(this)
        bubbleParams.x = boundedX
        bubbleParams.y = boundedY
        restoreX = boundedX
        restoreY = boundedY
        bubbleView
            ?.takeUnless { bubbleHidden }
            ?.let { runCatching { windowManager.updateViewLayout(it, bubbleParams) } }
        updatePanelPosition()
        saveBubblePosition(boundedX, boundedY)
    }

    // --- Interface flottante ---------------------------------------------

    private fun addBubble() {
        val bubble = OrbView(this)
        bubble.contentDescription = "Démarrer une dictée Chuchote Flow"
        bubble.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        orbView = bubble

        // Une vue dessinée sur mesure n'a pas de taille intrinsèque : sans
        // dimensions explicites, WRAP_CONTENT la réduirait à zéro pixel.
        bubbleParams.width = dp(BUBBLE_SIZE_DP)
        bubbleParams.height = dp(BUBBLE_SIZE_DP)
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        // Revenir exactement là où l'utilisateur l'avait laissée, avec un
        // bornage pour les changements d'orientation ou de résolution.
        val (savedX, savedY) = loadSavedOrbPosition(this)
        bubbleParams.x = savedX
        bubbleParams.y = savedY
        restoreX = bubbleParams.x
        restoreY = bubbleParams.y

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var originX = 0
            private var originY = 0
            private var downRawX = 0f
            private var downRawY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        originX = bubbleParams.x
                        originY = bubbleParams.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragging = true
                        if (dragging) {
                            bubbleParams.x = (originX + dx.toInt())
                                .coerceIn(minBubbleX(), maxBubbleX())
                            bubbleParams.y = (originY + dy.toInt())
                                .coerceIn(minBubbleY(), maxBubbleY())
                            runCatching { windowManager.updateViewLayout(view, bubbleParams) }
                            updatePanelPosition()
                            showDismissTarget()
                            highlightDismissTarget(isOverDismissTarget())
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        hideDismissTarget()
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            val dropped = isOverDismissTarget()
                            hideDismissTarget()
                            if (dropped) {
                                hideBubble(originX, originY)
                            } else {
                                saveBubblePosition()
                            }
                            return true
                        }
                        // Un appui lance la dictée, puis la valide : c'est la
                        // coche affichée sur la sphère qui annonce ce second rôle.
                        view.performClick()
                        when (state) {
                            State.IDLE -> startRecording()
                            State.PENDING -> Unit
                            State.RECORDING -> confirmRecording()
                            State.TRANSCRIBING -> Unit
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = bubble
        runCatching { windowManager.addView(bubble, bubbleParams) }
            .onSuccess { TextInsertionAccessibilityService.onWidgetServiceAvailable() }
            .onFailure {
                bubbleView = null
                orbView = null
                stopSelf()
            }
    }

    /**
     * Corbeille qui apparaît en bas de l'écran dès qu'on déplace la sphère :
     * y déposer la sphère la fait disparaître. Elle ne reçoit jamais le
     * toucher elle-même (FLAG_NOT_TOUCHABLE) — c'est la position de la sphère
     * au moment du relâchement qui décide, sinon la cible intercepterait le
     * glissement en cours.
     */
    private fun buildDismissTarget(): View {
        val circle = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC2A1520"))
                setStroke(dp(2), Color.parseColor("#FF9BB0"))
            }
        }
        dismissCircle = circle

        // Le rond est centré dans une fenêtre nettement plus grande que lui :
        // sinon, en grossissant à l'approche de la sphère, il déborderait des
        // limites de sa fenêtre et se retrouverait rogné sur tous les côtés.
        return FrameLayout(this).apply {
            addView(
                circle,
                FrameLayout.LayoutParams(dp(DISMISS_CIRCLE_DP), dp(DISMISS_CIRCLE_DP), Gravity.CENTER)
            )
        }
    }

    private fun showDismissTarget() {
        if (dismissAttached) return
        val target = dismissView ?: buildDismissTarget().also { dismissView = it }
        dismissParams.width = dp(DISMISS_WINDOW_DP)
        dismissParams.height = dp(DISMISS_WINDOW_DP)
        dismissParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dismissParams.y = dp(DISMISS_MARGIN_DP)
        runCatching { windowManager.addView(target, dismissParams) }
            .onSuccess { dismissAttached = true }
    }

    private fun hideDismissTarget() {
        val target = dismissView
        if (target != null && dismissAttached) {
            runCatching { windowManager.removeView(target) }
        }
        dismissAttached = false
        dismissCircle?.scaleX = 1f
        dismissCircle?.scaleY = 1f
    }

    private fun highlightDismissTarget(near: Boolean) {
        val scale = if (near) 1.25f else 1f
        dismissCircle?.scaleX = scale
        dismissCircle?.scaleY = scale
    }

    /** Vrai si le centre de la sphère est assez proche de la corbeille. */
    private fun isOverDismissTarget(): Boolean {
        val metrics = resources.displayMetrics
        val targetCenterX = metrics.widthPixels / 2f
        val targetCenterY = metrics.heightPixels - dp(DISMISS_MARGIN_DP) - dp(DISMISS_WINDOW_DP) / 2f

        val bubbleCenterX = bubbleParams.x + bubbleParams.width / 2f
        val bubbleCenterY = bubbleParams.y + bubbleParams.height / 2f

        return hypot(bubbleCenterX - targetCenterX, bubbleCenterY - targetCenterY) < dp(80)
    }

    /**
     * Range la sphère. [previousX] et [previousY] sont sa position avant le
     * geste qui vient de la mener à la corbeille : c'est là qu'elle reviendra,
     * plutôt que sur la corbeille où le doigt l'a lâchée.
     */
    private fun hideBubble(previousX: Int, previousY: Int) {
        restoreX = previousX
        restoreY = previousY
        saveBubblePosition(previousX, previousY)

        // Une dictée pouvait être en cours : sans cela, le tracé et la croix
        // restaient affichés alors que la sphère venait de disparaître.
        recognitionSessions.invalidate()
        cancelAndDestroyRecognizerBestEffort()
        resetToIdle()

        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleHidden = true
        Toast.makeText(this, "Widget masqué — secoue le téléphone pour le rappeler", Toast.LENGTH_LONG).show()
    }

    private fun showBubble() {
        val bubble = bubbleView ?: return
        if (!bubbleHidden) return
        bubbleParams.x = restoreX.coerceIn(minBubbleX(), maxBubbleX())
        bubbleParams.y = restoreY.coerceIn(minBubbleY(), maxBubbleY())
        runCatching { windowManager.addView(bubble, bubbleParams) }
            .onSuccess {
                bubbleHidden = false
                saveBubblePosition()
            }
    }

    private fun saveBubblePosition(
        x: Int = bubbleParams.x,
        y: Int = bubbleParams.y,
    ) {
        positionPreferences.edit()
            .putInt(POSITION_X, x.coerceIn(minBubbleX(), maxBubbleX()))
            .putInt(POSITION_Y, y.coerceIn(minBubbleY(), maxBubbleY()))
            .apply()
    }

    /**
     * Pastille compacte : croix d'annulation et signal sonore, rien de plus.
     * Elle est volontairement étroite et centrée plutôt qu'étalée sur toute la
     * largeur, pour masquer le moins possible de l'écran pendant la dictée.
     */
    private fun buildPanel(): View {
        // Aucun fond ni cadre : seuls la croix et le tracé flottent au-dessus
        // de l'écran, pour masquer le moins possible de ce qu'on est en train
        // de lire.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        cancelView = TextView(this).apply {
            text = "✕"
            contentDescription = "Annuler la dictée"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC2A1520"))
                setStroke(dp(1), Color.parseColor("#FF9BB0"))
            }
            setOnClickListener { cancelRecording() }
        }

        waveView = SineWaveView(this)

        return row
    }

    /**
     * Range la croix et le tracé selon le côté où le panneau se trouve.
     *
     * L'ordre est toujours le même vu depuis la sphère : le tracé lui est
     * adjacent, la croix se place à l'extérieur. Quand le panneau passe à
     * droite, l'ensemble est donc lu en miroir plutôt que simplement déplacé.
     */
    private fun layoutPanel(onLeft: Boolean) {
        val row = panelView as? LinearLayout ?: return
        val cancel = cancelView ?: return
        val wave = waveView ?: return

        row.removeAllViews()

        val cancelParams = LinearLayout.LayoutParams(dp(32), dp(32))
        val waveParams = LinearLayout.LayoutParams(dp(CONTENT_WIDTH_DP), dp(34))

        // L'onde est décalée de quelques pixels vers la croix : la distance
        // croix-orbe ne change pas, mais le tracé respire côté sphère.
        if (onLeft) {
            waveParams.marginStart = dp(5)
            waveParams.marginEnd = dp(3)
            row.addView(cancel, cancelParams)
            row.addView(wave, waveParams)
        } else {
            waveParams.marginStart = dp(3)
            cancelParams.marginStart = dp(5)
            row.addView(wave, waveParams)
            row.addView(cancel, cancelParams)
        }
    }

    private fun showPanel() {
        if (panelAttached) return
        val panel = panelView ?: buildPanel().also { panelView = it }
        panelParams.width = dp(PANEL_WIDTH_DP)
        panelParams.height = dp(PANEL_HEIGHT_DP)
        panelParams.gravity = Gravity.TOP or Gravity.START
        positionPanelBesideOrb()
        runCatching { windowManager.addView(panel, panelParams) }
            .onSuccess { panelAttached = true }
    }

    /**
     * La croix et le tracé restent sur la même ligne horizontale que la sphère.
     * Ils se déploient à sa gauche quand la place le permet, sinon à sa droite —
     * et dans ce cas leur ordre est inversé pour que le tracé reste toujours du
     * côté de la sphère.
     */
    private fun positionPanelBesideOrb() {
        val panelWidth = dp(PANEL_WIDTH_DP)
        val gap = dp(PANEL_GAP_DP)
        val onTheLeft = bubbleParams.x - panelWidth - gap

        val fitsOnLeft = onTheLeft >= dp(4)
        panelParams.x = if (fitsOnLeft) onTheLeft else bubbleParams.x + bubbleParams.width + gap
        panelParams.y = bubbleParams.y + (bubbleParams.height - dp(PANEL_HEIGHT_DP)) / 2

        if (fitsOnLeft != panelOnLeft || (panelView as? LinearLayout)?.childCount == 0) {
            panelOnLeft = fitsOnLeft
            layoutPanel(fitsOnLeft)
        }
    }

    // La sphère ne peut pas sortir de l'écran.
    private fun minBubbleX(): Int = dp(4)

    private fun maxBubbleX(): Int =
        (resources.displayMetrics.widthPixels - dp(BUBBLE_SIZE_DP) - dp(4))
            .coerceAtLeast(minBubbleX())

    private fun minBubbleY(): Int = dp(28)

    private fun maxBubbleY(): Int =
        (resources.displayMetrics.heightPixels - dp(BUBBLE_SIZE_DP) - dp(28))
            .coerceAtLeast(minBubbleY())

    private fun updatePanelPosition() {
        if (!panelAttached) return
        positionPanelBesideOrb()
        panelView?.let { runCatching { windowManager.updateViewLayout(it, panelParams) } }
    }

    private fun hidePanel() {
        val panel = panelView
        if (panel != null && panelAttached) {
            runCatching { windowManager.removeView(panel) }
        }
        panelAttached = false
    }

    // --- Dictée -----------------------------------------------------------

    private fun startRecording() {
        if (state != State.IDLE) return

        if (!PrivacyConsent.isAcceptedBlocking(this)) {
            Toast.makeText(
                this,
                "Ouvrez Chuchote Flow pour lire et accepter la politique actuelle.",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisation du micro requise", Toast.LENGTH_LONG).show()
            return
        }

        // L'identité est figée avant tout démarrage audio. Une absence de
        // cible n'empêche pas la sauvegarde dans l'historique, mais interdit
        // plus tard toute insertion ou copie implicite.
        val capturedTarget = TextInsertionAccessibilityService.captureFocusedTarget()
        val generation = recognitionAttempts.begin() ?: return
        activeAttemptGeneration = generation
        deliveryTarget = capturedTarget
        recognitionSessions.invalidate()
        // Chaque tentative possède aussi son propre objet SpeechRecognizer.
        // Android peut avoir des messages Binder déjà en file; garder l'objet
        // précédent permettrait qu'ils soient redistribués au listener suivant.
        destroyRecognizerBestEffort()
        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(
                this,
                // Viser explicitement notre service évite de dépendre du choix
                // « application de saisie vocale » du système, que certains
                // constructeurs (Samsung) ne laissent pas modifier.
                ComponentName(this, MainRecognitionService::class.java),
            ).also {
                speechRecognizer = it
                // Un listener distinct par tentative conserve sa génération
                // locale. Un callback Binder tardif ne peut donc jamais être
                // confondu avec le listener d'une tentative suivante, même
                // avant validation de l'UUID.
                it.setRecognitionListener(createRecognitionListener(generation))
            }
        }.getOrElse {
            recognitionAttempts.fail(generation)
            destroyRecognizerBestEffort()
            resetToIdle()
            Toast.makeText(
                this,
                "Impossible de préparer la reconnaissance vocale",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        showPanel()
        waveView?.setActive(true)
        waveView?.visibility = View.VISIBLE
        orbView?.setActive(true)
        orbView?.contentDescription = "Préparation de la dictée"
        state = State.PENDING

        runCatching {
            recognizer.startListening(Intent().apply {
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // false : c'est l'utilisateur qui décide quand il a fini, pas un
                // détecteur de silence.
                putExtra(MainRecognitionService.EXTRA_AUTO_STOP, false)
            })
        }.onFailure {
            if (recognitionAttempts.fail(generation)) {
                recognitionSessions.invalidate()
                destroyRecognizerBestEffort()
                resetToIdle()
                Toast.makeText(
                    this,
                    "Impossible de démarrer la reconnaissance vocale",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun confirmRecording() {
        val generation = activeAttemptGeneration ?: return
        if (state != State.RECORDING || !recognitionAttempts.confirm(generation)) return
        state = State.TRANSCRIBING

        // La dictée est validée : le tracé et la croix n'ont plus d'objet, il
        // n'y a plus rien à annuler ni à montrer de la voix. C'est la sphère
        // qui porte seule l'attente.
        waveView?.setActive(false)
        hidePanel()
        orbView?.setActive(false)
        orbView?.setTranscribing(true)
        orbView?.contentDescription = "Transcription en cours"

        val recognizer = speechRecognizer
        val stopSucceeded = recognizer != null && RecognizerCommandBoundary.execute(
            command = { recognizer.stopListening() },
        )
        if (!stopSucceeded) {
            recognitionSessions.invalidate()
            recognitionAttempts.fail(generation)
            cancelAndDestroyRecognizerBestEffort()
            resetToIdle()
            Toast.makeText(
                this,
                "Connexion vocale interrompue; l'audio reste récupérable dans l'historique.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        mainHandler.postDelayed(slowTranscriptionNotice, SLOW_TRANSCRIPTION_NOTICE_MS)
    }

    private fun cancelRecording() {
        recognitionSessions.invalidate()
        cancelAndDestroyRecognizerBestEffort()
        resetToIdle()
    }

    private fun destroyRecognizerBestEffort() {
        val recognizer = speechRecognizer ?: return
        RecognizerCommandBoundary.execute(command = { recognizer.destroy() })
        if (speechRecognizer === recognizer) speechRecognizer = null
    }

    private fun cancelAndDestroyRecognizerBestEffort() {
        val recognizer = speechRecognizer ?: return
        RecognizerCommandBoundary.cleanup(
            cancel = { recognizer.cancel() },
            destroy = { recognizer.destroy() },
        )
        if (speechRecognizer === recognizer) speechRecognizer = null
    }

    private fun resetToIdle() {
        mainHandler.removeCallbacks(slowTranscriptionNotice)
        recognitionSessions.invalidate()
        recognitionAttempts.cancel()
        activeAttemptGeneration = null
        deliveryTarget = null
        state = State.IDLE
        waveView?.setActive(false)
        orbView?.setActive(false)
        orbView?.setTranscribing(false)
        orbView?.contentDescription = "Démarrer une dictée Chuchote Flow"
        hidePanel()
    }

    private fun createRecognitionListener(generation: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!recognitionAttempts.ready(generation)) return
            val accepted = recognitionSessions.activate(
                params?.getString(MainRecognitionService.EXTRA_SESSION_ID),
            )
            if (!accepted) {
                if (recognitionAttempts.fail(generation)) {
                    cancelAndDestroyRecognizerBestEffort()
                    resetToIdle()
                    Toast.makeText(
                        this@FloatingWidgetService,
                        "Session de transcription invalide; veuillez réessayer.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }
            if (activeAttemptGeneration != generation) return
            state = State.RECORDING
            orbView?.contentDescription = "Terminer la dictée et lancer la transcription"
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            if (
                !recognitionAttempts.isPending(generation) &&
                !recognitionAttempts.isRecording(generation)
            ) return
            waveView?.setLevel(rmsdB)
            orbView?.setLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!recognitionAttempts.fail(generation)) return
            val wasTranscribing = state == State.TRANSCRIBING
            recognitionSessions.invalidate()
            val consentAccepted = PrivacyConsent.isAcceptedBlocking(this@FloatingWidgetService)
            cancelAndDestroyRecognizerBestEffort()
            resetToIdle()
            if (!consentAccepted) return
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisation du micro refusée"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance déjà en cours"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (wasTranscribing) {
                        "Aucun texte produit. L’audio est sauvegardé dans l’historique."
                    } else {
                        "Rien n'a été entendu"
                    }
                else -> if (wasTranscribing) {
                    "Échec de la transcription. L’audio est sauvegardé dans l’historique."
                } else {
                    "Échec de la transcription"
                }
            }
            Toast.makeText(this@FloatingWidgetService, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            val sessionId = results?.getString(MainRecognitionService.EXTRA_SESSION_ID)
            if (
                state != State.TRANSCRIBING ||
                !recognitionAttempts.isTranscribing(generation)
            ) return
            if (!recognitionSessions.complete(sessionId)) {
                if (recognitionAttempts.fail(generation)) {
                    val consentAccepted =
                        PrivacyConsent.isAcceptedBlocking(this@FloatingWidgetService)
                    cancelAndDestroyRecognizerBestEffort()
                    resetToIdle()
                    if (consentAccepted) {
                        Toast.makeText(
                            this@FloatingWidgetService,
                            "Résultat de transcription invalide; veuillez réessayer.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                return
            }
            if (!recognitionAttempts.complete(generation)) {
                cancelAndDestroyRecognizerBestEffort()
                resetToIdle()
                return
            }
            val target = deliveryTarget
            val consentAccepted = PrivacyConsent.isAcceptedBlocking(this@FloatingWidgetService)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            cancelAndDestroyRecognizerBestEffort()
            resetToIdle()
            if (!consentAccepted) {
                Toast.makeText(
                    this@FloatingWidgetService,
                    "Résultat sauvegardé dans l’historique; acceptez la politique actuelle avant l’insertion.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            // L'éclat vert : la transcription est terminée, le texte part.
            orbView?.flashSuccess()
            if (!text.isNullOrBlank()) deliver(text, target)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val sessionId = partialResults?.getString(MainRecognitionService.EXTRA_SESSION_ID)
            if (
                state != State.TRANSCRIBING ||
                partialResults == null ||
                !recognitionAttempts.isTranscribing(generation) ||
                !recognitionSessions.accepts(sessionId)
            ) return
            orbView?.setTranscriptionProgress(
                audioDurationMs = partialResults.getLong(
                    MainRecognitionService.EXTRA_AUDIO_DURATION_MS,
                    0L,
                ),
                completedSegments = partialResults.getInt(
                    MainRecognitionService.EXTRA_COMPLETED_SEGMENTS,
                    0,
                ),
                totalSegments = partialResults.getInt(
                    MainRecognitionService.EXTRA_TOTAL_SEGMENTS,
                    0,
                ),
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Remet le texte dicté uniquement dans le champ capturé au départ. Le
     * presse-papiers n'est un repli que si ce même champ reste actif et que le
     * consentement courant est encore confirmé; l'historique demeure la source
     * de récupération dans tous les autres cas.
     */
    /** Journal des décisions de livraison — jamais le contenu dicté. */
    private fun deliverLog(reason: String) {
        if (BuildConfig.DEBUG) Log.d("ChuchoteDeliver", reason)
    }

    private fun deliver(text: String, target: FocusedTextTarget?) {
        // Le consentement et l'identité du champ sont relus au dernier moment.
        // Sans preuve que la cible de départ est toujours active, ni insertion
        // ni copie implicite ne sont permises.
        if (!PrivacyConsent.isAcceptedBlocking(this)) {
            Toast.makeText(
                this,
                "Résultat sauvegardé dans l’historique; acceptez la politique actuelle avant l’insertion.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (target != null && !TextInsertionAccessibilityService.isTargetStillFocused(target)) {
            deliverLog("target_lost_before_insert")
            Toast.makeText(
                this,
                "Le champ actif a changé. La transcription reste sauvegardée dans l’historique.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // Aucun champ n'était actif au départ de la dictée : il n'y a ni cible
        // à protéger ni risque de double livraison. Le presse-papiers est alors
        // la livraison normale, pas un repli — comportement historique que le
        // durcissement fail-closed avait involontairement supprimé.
        val insertion = if (target == null) {
            TextInsertionResult.NO_FOCUSED_FIELD
        } else {
            TextInsertionAccessibilityService.insertText(text, target)
        }
        deliverLog("insertion=$insertion targetCaptured=${target != null}")
        when (insertion) {
            TextInsertionResult.INSERTED -> return
            TextInsertionResult.INSERTED_CURSOR_UNCONFIRMED -> {
                Toast.makeText(
                    this,
                    "Texte inséré; vérifiez la position du curseur avant de continuer.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            TextInsertionResult.ACTION_ACCEPTED_UNCONFIRMED -> {
                Toast.makeText(
                    this,
                    "L’app a accepté l’insertion, mais Chuchote Flow n’a pas pu la confirmer. Vérifiez le champ; le résultat reste dans l’historique.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            TextInsertionResult.CONSENT_REQUIRED -> {
                Toast.makeText(
                    this,
                    "Résultat sauvegardé dans l’historique; acceptez la politique actuelle avant l’insertion.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            TextInsertionResult.TARGET_CHANGED -> {
                Toast.makeText(
                    this,
                    "Le champ actif a changé. La transcription reste sauvegardée dans l’historique.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            else -> Unit
        }

        if (!PrivacyConsent.isAcceptedBlocking(this)) {
            Toast.makeText(
                this,
                "Résultat sauvegardé dans l’historique; acceptez la politique actuelle avant la copie.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (target != null && !TextInsertionAccessibilityService.isTargetStillFocused(target)) {
            deliverLog("target_lost_before_copy")
            Toast.makeText(
                this,
                "Le champ actif a changé. Aucun texte n’a été copié; le résultat reste dans l’historique.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = sensitivePlainText("Chuchote Flow", text)
        // `setPrimaryClip` sans exception suffit comme preuve : Android peut
        // refuser la relecture du presse-papiers à une app hors focus alors
        // que l'écriture, elle, a bien eu lieu. Exiger la relecture faisait
        // annoncer « Insertion impossible » avec le texte pourtant copié.
        val copied = clipboard?.let {
            runCatching {
                it.setPrimaryClip(clip)
                true
            }.getOrDefault(false)
        } == true
        if (!copied) {
            Toast.makeText(
                this,
                "Insertion impossible. La transcription reste sauvegardée dans l’historique.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val message = when (insertion) {
            TextInsertionResult.SERVICE_DISCONNECTED -> {
                if (TextInsertionAccessibilityService.isEnabledInSettings(this)) {
                    "Le service d’accessibilité s’est interrompu. Texte copié; " +
                            "désactive puis réactive Chuchote Flow dans Accessibilité."
                } else {
                    "Active Chuchote Flow dans Accessibilité. Texte copié dans le presse-papiers."
                }
            }
            TextInsertionResult.NO_FOCUSED_FIELD ->
                "Aucun champ de texte actif : texte copié dans le presse-papiers"
            TextInsertionResult.UNSAFE_FIELD_STATE ->
                "Ce champ riche ou sa sélection ne permet pas une réécriture sûre : " +
                    "texte copié dans le presse-papiers"
            TextInsertionResult.ACTION_REJECTED ->
                "Ce champ a refusé l’insertion : texte copié dans le presse-papiers"
            TextInsertionResult.INSERTED -> return
            TextInsertionResult.INSERTED_CURSOR_UNCONFIRMED -> return
            TextInsertionResult.ACTION_ACCEPTED_UNCONFIRMED -> return
            TextInsertionResult.CONSENT_REQUIRED -> return
            TextInsertionResult.TARGET_CHANGED -> return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // --- Divers -----------------------------------------------------------

    private fun registerShakeDetector() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val detector = ShakeDetector {
            // La secousse ne démarre jamais le microphone : elle ne fait que
            // rappeler une sphère volontairement rangée. La dictée exige un tap.
            if (bubbleHidden) showBubble()
        }
        manager.registerListener(detector, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager = manager
        shakeDetector = detector
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Widget de dictée", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Widget de dictée actif")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "dev.soupslurpr.transcribro.STOP_FLOATING_WIDGET"
        const val EXTRA_VISIBLE_LAUNCH =
            "dev.soupslurpr.transcribro.extra.VISIBLE_WIDGET_LAUNCH"

        private const val CHANNEL_ID = "chuchote_floating_widget"
        private const val NOTIFICATION_ID = 4711
        private const val SLOW_TRANSCRIPTION_NOTICE_MS = 120_000L
        private const val DISMISS_MARGIN_DP = 96
        private const val DISMISS_WINDOW_DP = 96
        private const val DISMISS_CIRCLE_DP = 56
        private const val BUBBLE_SIZE_DP = 64
        private const val CONTENT_WIDTH_DP = 72
        private const val PANEL_WIDTH_DP = 112
        // Le tracé colle presque à la sphère : ils forment un seul objet.
        // Négatif à dessein : la sphère visible ne remplit pas sa fenêtre (le
        // voile de fumée s'estompe bien avant le bord). Le panneau chevauche
        // cette zone transparente pour que l'onde naisse au bord visible de
        // la sphère, à peu près à égale distance entre elle et la croix.
        private const val PANEL_GAP_DP = -12
        private const val PANEL_HEIGHT_DP = 44

        private const val POSITION_PREFERENCES = "floating_widget_position"
        private const val POSITION_X = "x"
        private const val POSITION_Y = "y"

        @Volatile
        private var instance: WeakReference<FloatingWidgetService>? = null

        /** Rappelle une instance existante sans tenter un démarrage interdit. */
        fun showForFocusedField(): Boolean {
            val service = instance?.get() ?: return false
            if (service.bubbleView == null) return false
            service.mainHandler.post {
                if (service.state == State.IDLE) service.showBubble()
            }
            return true
        }

        /**
         * Relit et reborne la dernière position de l'orbe avec les dimensions
         * courantes. Le lanceur d'accessibilité et le widget utilisent ainsi
         * exactement la même identité de position après rotation ou reprise.
         */
        internal fun loadSavedOrbPosition(context: Context): Pair<Int, Int> {
            val density = context.resources.displayMetrics.density
            fun px(dp: Int): Int = (dp * density).toInt()

            val minX = px(4)
            val minY = px(28)
            val maxX = (
                context.resources.displayMetrics.widthPixels - px(BUBBLE_SIZE_DP) - px(4)
            ).coerceAtLeast(minX)
            val maxY = (
                context.resources.displayMetrics.heightPixels - px(BUBBLE_SIZE_DP) - px(28)
            ).coerceAtLeast(minY)
            val preferences = context.getSharedPreferences(
                POSITION_PREFERENCES,
                Context.MODE_PRIVATE,
            )
            val rawX = preferences.getInt(POSITION_X, maxX)
            val rawY = preferences.getInt(POSITION_Y, px(240))
            val boundedX = rawX.coerceIn(minX, maxX)
            val boundedY = rawY.coerceIn(minY, maxY)
            if (boundedX != rawX || boundedY != rawY) {
                preferences.edit()
                    .putInt(POSITION_X, boundedX)
                    .putInt(POSITION_Y, boundedY)
                    .apply()
            }
            return boundedX to boundedY
        }
    }
}
