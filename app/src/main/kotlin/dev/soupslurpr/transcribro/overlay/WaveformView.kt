package dev.soupslurpr.transcribro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Barres de niveau sonore défilantes, affichées pendant la dictée pour montrer
 * que la voix est bien captée.
 *
 * Les niveaux arrivent en dB (négatifs, 0 dB = saturation) via [addLevel].
 */
class WaveformView(context: Context) : View(context) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val levels = FloatArray(BAR_COUNT)
    private var writeIndex = 0
    private val barRect = RectF()

    /** Ajoute un niveau en dB et redessine. */
    fun addLevel(db: Float) {
        // dB -> 0..1. En dictée normale le signal vit entre -60 et 0 dB.
        val normalized = ((db + 60f) / 60f).coerceIn(0f, 1f)
        levels[writeIndex] = normalized
        writeIndex = (writeIndex + 1) % BAR_COUNT
        postInvalidateOnAnimation()
    }

    /** Remet les barres à plat entre deux dictées. */
    fun reset() {
        levels.fill(0f)
        writeIndex = 0
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slot = w / BAR_COUNT
        val barWidth = slot * 0.55f
        val radius = barWidth / 2f
        val centerY = h / 2f

        for (i in 0 until BAR_COUNT) {
            // Lire à partir de writeIndex fait défiler les barres vers la gauche,
            // la plus récente à droite.
            val level = levels[(writeIndex + i) % BAR_COUNT]
            val barHeight = (h * 0.9f * level).coerceAtLeast(barWidth)
            val left = i * slot + (slot - barWidth) / 2f

            barRect.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f)
            canvas.drawRoundRect(barRect, radius, radius, barPaint)
        }
    }

    companion object {
        private const val BAR_COUNT = 28
    }
}
