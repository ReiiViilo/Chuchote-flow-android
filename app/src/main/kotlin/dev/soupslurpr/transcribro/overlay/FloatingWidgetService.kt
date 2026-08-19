package dev.soupslurpr.transcribro.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Widget de dictée flottant, à la manière de Wispr Flow : une sphère posée
 * par-dessus toutes les applications, qu'on touche (ou qu'on appelle en
 * secouant le téléphone) pour dicter.
 *
 * Pendant la dictée la sphère porte une coche — la toucher valide — et une
 * petite pastille apparaît en bas avec le signal sonore et une croix pour
 * annuler.
 *
 * Service de premier plan de type micro : c'est ce qui autorise la capture
 * audio alors que l'application n'est pas à l'écran.
 */
class FloatingWidgetService : Service() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var orbView: OrbView? = null
    private var panelView: View? = null
    private var waveView: SineWaveView? = null
    private var statusView: TextView? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null

    private var dismissView: View? = null
    private var dismissCircle: View? = null
    private var dismissAttached = false
    private var bubbleHidden = false

    private var state = State.IDLE
    private var panelAttached = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Filet de sécurité : si la transcription ne rend jamais la main, le widget
     * resterait bloqué sur « Transcription… » sans aucun moyen d'en sortir.
     */
    private val transcriptionTimeout = Runnable {
        if (state != State.TRANSCRIBING) return@Runnable
        speechRecognizer?.cancel()
        resetToIdle()
        Toast.makeText(this, "Transcription trop longue, dictée abandonnée", Toast.LENGTH_LONG).show()
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

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisation d'affichage par-dessus les autres apps requise", Toast.LENGTH_LONG)
                .show()
            stopSelf()
            return
        }

        addBubble()
        registerShakeDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Relancer le widget depuis l'application le fait réapparaître s'il
        // avait été glissé dans la corbeille.
        if (bubbleHidden) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(transcriptionTimeout)

        sensorManager?.unregisterListener(shakeDetector)
        shakeDetector = null

        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        hidePanel()
        hideDismissTarget()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        orbView = null

        super.onDestroy()
    }

    // --- Interface flottante ---------------------------------------------

    private fun addBubble() {
        val bubble = OrbView(this)
        orbView = bubble

        // Une vue dessinée sur mesure n'a pas de taille intrinsèque : sans
        // dimensions explicites, WRAP_CONTENT la réduirait à zéro pixel.
        bubbleParams.width = dp(64)
        bubbleParams.height = dp(64)
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = dp(16)
        bubbleParams.y = dp(240)

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
                            bubbleParams.x = originX + dx.toInt()
                            bubbleParams.y = originY + dy.toInt()
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
                            if (dropped) hideBubble()
                            return true
                        }
                        // Un appui lance la dictée, puis la valide : c'est la
                        // coche affichée sur la sphère qui annonce ce second rôle.
                        view.performClick()
                        when (state) {
                            State.IDLE -> startRecording()
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

    private fun hideBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleHidden = true
        Toast.makeText(this, "Widget masqué — secoue le téléphone pour le rappeler", Toast.LENGTH_LONG).show()
    }

    private fun showBubble() {
        val bubble = bubbleView ?: return
        if (!bubbleHidden) return
        runCatching { windowManager.addView(bubble, bubbleParams) }
            .onSuccess { bubbleHidden = false }
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

        val cancel = TextView(this).apply {
            text = "✕"
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
        row.addView(cancel, LinearLayout.LayoutParams(dp(32), dp(32)))

        val wave = SineWaveView(this)
        waveView = wave
        row.addView(
            wave,
            LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(10) }
        )

        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#9FE8E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        statusView = status
        row.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        )

        return row
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
     * La croix et le tracé restent sur la même ligne horizontale que la sphère,
     * à sa gauche — ou à sa droite si la sphère est collée au bord gauche, pour
     * qu'ils ne sortent jamais de l'écran.
     */
    private fun positionPanelBesideOrb() {
        val panelWidth = dp(PANEL_WIDTH_DP)
        val gap = dp(8)
        val onTheLeft = bubbleParams.x - panelWidth - gap

        panelParams.x = if (onTheLeft >= dp(4)) {
            onTheLeft
        } else {
            bubbleParams.x + bubbleParams.width + gap
        }
        panelParams.y = bubbleParams.y + (bubbleParams.height - dp(PANEL_HEIGHT_DP)) / 2
    }

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

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisation du micro requise", Toast.LENGTH_LONG).show()
            return
        }

        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(
            this,
            // Viser explicitement notre service évite de dépendre du choix
            // « application de saisie vocale » du système, que certains
            // constructeurs (Samsung) ne laissent pas modifier.
            ComponentName(this, MainRecognitionService::class.java)
        ).also {
            it.setRecognitionListener(recognitionListener)
            speechRecognizer = it
        }

        showPanel()
        waveView?.setActive(true)
        statusView?.visibility = View.GONE
        waveView?.visibility = View.VISIBLE
        orbView?.setActive(true)
        state = State.RECORDING

        recognizer.startListening(Intent().apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // false : c'est l'utilisateur qui décide quand il a fini, pas un
            // détecteur de silence.
            putExtra(MainRecognitionService.EXTRA_AUTO_STOP, false)
        })
    }

    private fun confirmRecording() {
        if (state != State.RECORDING) return
        state = State.TRANSCRIBING

        waveView?.setActive(false)
        waveView?.visibility = View.GONE
        statusView?.text = "Transcription…"
        statusView?.visibility = View.VISIBLE
        orbView?.setActive(false)

        speechRecognizer?.stopListening()
        mainHandler.postDelayed(transcriptionTimeout, TRANSCRIPTION_TIMEOUT_MS)
    }

    private fun cancelRecording() {
        speechRecognizer?.cancel()
        resetToIdle()
    }

    private fun resetToIdle() {
        mainHandler.removeCallbacks(transcriptionTimeout)
        state = State.IDLE
        waveView?.setActive(false)
        orbView?.setActive(false)
        hidePanel()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            waveView?.setLevel(rmsdB)
            orbView?.setLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            resetToIdle()
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisation du micro refusée"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance déjà en cours"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Rien n'a été entendu"
                else -> "Échec de la transcription"
            }
            Toast.makeText(this@FloatingWidgetService, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            resetToIdle()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) deliver(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Remet le texte dicté dans le champ actif ; à défaut, le dépose dans le
     * presse-papiers pour qu'il ne soit jamais perdu.
     */
    private fun deliver(text: String) {
        if (TextInsertionAccessibilityService.insertText(text)) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Chuchote Flow", text))
        Toast.makeText(this, "Aucun champ actif : texte copié dans le presse-papiers", Toast.LENGTH_LONG).show()
    }

    // --- Divers -----------------------------------------------------------

    private fun registerShakeDetector() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val detector = ShakeDetector {
            // Secouer rappelle d'abord la sphère si elle a été rangée ; sinon
            // cela lance directement la dictée.
            if (bubbleHidden) showBubble() else if (state == State.IDLE) startRecording()
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
            .setContentTitle("Chuchote Flow")
            .setContentText("Widget de dictée actif")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "dev.soupslurpr.transcribro.STOP_FLOATING_WIDGET"

        private const val CHANNEL_ID = "chuchote_floating_widget"
        private const val NOTIFICATION_ID = 4711
        private const val TRANSCRIPTION_TIMEOUT_MS = 120_000L
        private const val DISMISS_MARGIN_DP = 96
        private const val DISMISS_WINDOW_DP = 96
        private const val DISMISS_CIRCLE_DP = 56
        private const val PANEL_WIDTH_DP = 186
        private const val PANEL_HEIGHT_DP = 44
    }
}
