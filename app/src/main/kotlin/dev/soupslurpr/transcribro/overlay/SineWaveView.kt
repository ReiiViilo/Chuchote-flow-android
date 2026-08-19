package dev.soupslurpr.transcribro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Signal sonore façon oscilloscope : un trait turquoise électrique qui ondule
 * avec la voix, sans fond ni cadre.
 *
 * Deux sinusoïdes de fréquences non multiples sont superposées : une seule
 * donnerait une vague trop régulière, presque décorative, alors que leur
 * battement produit un tracé vivant, proche d'un vrai signal.
 */
class SineWaveView(context: Context) : View(context) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val wavePath = Path()

    private var level = 0f
    private var targetLevel = 0f
    private var phase = 0f
    private var active = false

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            level = 0f
            targetLevel = 0f
        }
        postInvalidateOnAnimation()
    }

    fun setLevel(db: Float) {
        targetLevel = ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        wavePaint.strokeWidth = h * 0.09f
        wavePaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            0f,
            intArrayOf(
                Color.parseColor("#00E5D0"),
                Color.parseColor("#38E8FF"),
                Color.parseColor("#7C7CF0")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        level += (targetLevel - level) * SMOOTHING
        if (active) phase += PHASE_STEP

        val centerY = h / 2f
        // Un minimum d'amplitude au repos : un trait parfaitement plat donnerait
        // l'impression que le widget ne capte rien.
        val amplitude = h * (0.06f + 0.38f * level)
        val waveNumber = (2.0 * PI * WAVE_CYCLES / w).toFloat()

        wavePath.reset()
        var x = 0f
        while (x <= w) {
            // L'enveloppe en demi-sinus fait mourir le tracé aux deux bouts,
            // pour qu'il flotte au lieu d'être coupé net par le bord.
            val envelope = sin(PI * x / w).toFloat()
            val primary = sin(waveNumber * x + phase)
            val secondary = sin(waveNumber * x * SECONDARY_RATIO - phase * 1.7f)
            val y = centerY + amplitude * envelope * (0.62f * primary + 0.38f * secondary).toFloat()

            if (x == 0f) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            x += STEP_PX
        }

        canvas.drawPath(wavePath, wavePaint)

        if (active) postInvalidateOnAnimation()
    }

    companion object {
        private const val SMOOTHING = 0.25f
        private const val PHASE_STEP = 0.22f
        private const val WAVE_CYCLES = 2.6
        private const val SECONDARY_RATIO = 2.3f
        private const val STEP_PX = 3f
    }
}
