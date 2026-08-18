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
import android.os.IBinder
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService
import kotlin.math.abs

/**
 * Widget de dictée flottant, à la manière de Wispr Flow : une bulle posée
 * par-dessus toutes les applications, qu'on touche (ou qu'on appelle en
 * secouant le téléphone) pour dicter, avec un niveau sonore en direct et une
 * confirmation explicite avant que le texte soit inséré.
 *
 * Service de premier plan de type micro : c'est ce qui autorise la capture
 * audio alors que l'application n'est pas à l'écran.
 */
class FloatingWidgetService : Service() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var waveformView: WaveformView? = null
    private var statusView: TextView? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null

    private var state = State.IDLE
    private var panelAttached = false

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
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(shakeDetector)
        shakeDetector = null

        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        hidePanel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null

        super.onDestroy()
    }

    // --- Interface flottante ---------------------------------------------

    private fun addBubble() {
        val bubble = TextView(this).apply {
            text = "🎤"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            val side = dp(56)
            minWidth = side
            minHeight = side
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1B1B3A"))
                setStroke(dp(2), Color.parseColor("#7C7CF0"))
            }
        }

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
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        // Un simple appui lance la dictée ; un glissement ne fait
                        // que déplacer la bulle.
                        if (!dragging) {
                            view.performClick()
                            startRecording()
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

    private fun buildPanel(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F01B1B3A"))
                setStroke(dp(1), Color.parseColor("#7C7CF0"))
            }
        }

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        statusView = status
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            circleButton("✕", "#3A1B2A", "#F07C9A") { cancelRecording() },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        val waveform = WaveformView(this)
        waveformView = waveform
        row.addView(
            waveform,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
        )

        row.addView(
            circleButton("✓", "#1B3A2A", "#7CF0B0") { confirmRecording() },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        return root
    }

    private fun circleButton(
        label: String,
        fillColor: String,
        strokeColor: String,
        onClick: () -> Unit
    ): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(fillColor))
                setStroke(dp(2), Color.parseColor(strokeColor))
            }
            setOnClickListener { onClick() }
        }

    private fun showPanel() {
        if (panelAttached) return
        val panel = panelView ?: buildPanel().also { panelView = it }
        panelParams.gravity = Gravity.BOTTOM
        panelParams.y = dp(48)
        runCatching { windowManager.addView(panel, panelParams) }
            .onSuccess { panelAttached = true }
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

        waveformView?.reset()
        showPanel()
        statusView?.text = "Parle, puis touche ✓"
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
        statusView?.text = "Transcription…"
        speechRecognizer?.stopListening()
    }

    private fun cancelRecording() {
        speechRecognizer?.cancel()
        state = State.IDLE
        hidePanel()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            waveformView?.addLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            state = State.IDLE
            hidePanel()
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisation du micro refusée"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance déjà en cours"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Rien n'a été entendu"
                else -> "Échec de la transcription"
            }
            Toast.makeText(this@FloatingWidgetService, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            state = State.IDLE
            hidePanel()
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
        val detector = ShakeDetector { startRecording() }
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
    }
}
