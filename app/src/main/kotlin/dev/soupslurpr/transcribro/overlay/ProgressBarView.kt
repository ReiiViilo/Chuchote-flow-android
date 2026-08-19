package dev.soupslurpr.transcribro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Barre d'attente pendant la transcription : un segment lumineux qui balaie
 * une piste arrondie, aux mêmes couleurs que le tracé de la voix.
 *
 * Elle est volontairement indéterminée : la durée d'une transcription dépend de
 * la longueur de la dictée et de la charge du processeur, et afficher une
 * progression chiffrée qu'on ne peut pas tenir serait pire que rien.
 */
class ProgressBarView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 150, 220, 255)
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackPath = Path()
    private val trackBounds = RectF()
    private val segmentBounds = RectF()

    private var phase = 0f
    private var active = false

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        phase = 0f
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val barHeight = h * BAR_HEIGHT_RATIO
        val top = (h - barHeight) / 2f
        trackBounds.set(0f, top, w.toFloat(), top + barHeight)

        val radius = barHeight / 2f
        trackPath.reset()
        trackPath.addRoundRect(trackBounds, radius, radius, Path.Direction.CW)

        segmentPaint.shader = LinearGradient(
            0f,
            0f,
            w * SEGMENT_RATIO,
            0f,
            intArrayOf(
                Color.argb(0, 0, 229, 208),
                Color.parseColor("#38E8FF"),
                Color.argb(0, 124, 124, 240)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        if (w <= 0f || trackBounds.isEmpty) return

        canvas.drawPath(trackPath, trackPaint)

        if (!active) return

        phase += PHASE_STEP
        if (phase > 1f) phase -= 1f

        // Le segment part hors du bord gauche et sort par la droite : il entre
        // et sort donc du champ au lieu d'apparaître et disparaître d'un coup.
        val segmentWidth = w * SEGMENT_RATIO
        val left = -segmentWidth + phase * (w + segmentWidth)
        segmentBounds.set(left, trackBounds.top, left + segmentWidth, trackBounds.bottom)

        // Le dégradé est construit à l'origine : on translate le repère plutôt
        // que de reconstruire un dégradé à chaque image.
        canvas.save()
        canvas.clipPath(trackPath)
        canvas.translate(left, 0f)
        segmentBounds.offsetTo(0f, trackBounds.top)
        canvas.drawRect(segmentBounds, segmentPaint)
        canvas.restore()

        postInvalidateOnAnimation()
    }

    companion object {
        private const val BAR_HEIGHT_RATIO = 0.18f
        private const val SEGMENT_RATIO = 0.42f
        private const val PHASE_STEP = 0.012f
    }
}
