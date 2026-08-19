package dev.soupslurpr.transcribro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * La bulle du widget : une sphère en lévitation, entourée d'anneaux orbitaux.
 *
 * Au repos elle est immobile — c'est délibéré, une fenêtre superposée qui se
 * redessine en continu réveille le GPU en permanence et vide la batterie.
 * Dès que la dictée commence, la sphère s'anime : elle s'étire et s'aplatit au
 * rythme de la voix, et les anneaux tournent d'autant plus vite que le niveau
 * sonore est élevé.
 */
class OrbView(context: Context) : View(context) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 235, 245, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8FA0FF")
    }

    // Coche affichée sur la sphère pendant la dictée : elle indique que toucher
    // la sphère valide et lance la transcription.
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val checkPath = Path()

    private val ringBounds = RectF()

    /** Niveau affiché, lissé pour éviter les à-coups d'une image à l'autre. */
    private var level = 0f
    private var targetLevel = 0f

    /** Angle de rotation des anneaux, en degrés. */
    private var phase = 0f

    private var active = false
    private var coreRadius = 0f

    /** Démarre ou arrête l'animation. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            targetLevel = 0f
            level = 0f
        }
        postInvalidateOnAnimation()
    }

    /** Alimente la déformation avec le niveau du micro, en dB. */
    fun setLevel(db: Float) {
        targetLevel = ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f
        coreRadius = min(w, h) / 2f * 0.62f
        ringPaint.strokeWidth = coreRadius * 0.09f
        checkPaint.strokeWidth = coreRadius * 0.17f

        // La coche est construite une fois pour toutes, en coordonnées absolues :
        // la recalculer à chaque image n'apporterait rien, sa taille ne dépend
        // que de celle de la vue.
        checkPath.reset()
        checkPath.moveTo(centerX - coreRadius * 0.34f, centerY + coreRadius * 0.02f)
        checkPath.lineTo(centerX - coreRadius * 0.08f, centerY + coreRadius * 0.28f)
        checkPath.lineTo(centerX + coreRadius * 0.36f, centerY - coreRadius * 0.26f)

        // Les dégradés sont construits ici plutôt que dans onDraw : en allouer
        // un par image provoquerait un ramasse-miettes visible à l'écran.
        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            coreRadius * 1.85f,
            intArrayOf(
                Color.argb(120, 124, 124, 240),
                Color.argb(40, 124, 124, 240),
                Color.argb(0, 124, 124, 240)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // Source lumineuse décalée en haut à gauche : c'est ce décalage qui
        // donne le relief d'une sphère plutôt que d'un disque.
        corePaint.shader = RadialGradient(
            centerX - coreRadius * 0.35f,
            centerY - coreRadius * 0.4f,
            coreRadius * 1.6f,
            intArrayOf(
                Color.parseColor("#B9E4FF"),
                Color.parseColor("#5B5BD6"),
                Color.parseColor("#191937")
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (coreRadius <= 0f) return

        level += (targetLevel - level) * SMOOTHING
        if (active) {
            phase += ROTATION_DEGREES_AT_REST + ROTATION_DEGREES_PER_LEVEL * level
            if (phase >= 360f) phase -= 360f
        }

        val centerX = width / 2f
        val centerY = height / 2f

        // Conservation approximative du volume : ce que la sphère gagne en
        // largeur, elle le perd en hauteur, comme une goutte qui vibre.
        val stretch = 1f + 0.20f * level
        val squash = 1f - 0.13f * level

        canvas.drawCircle(centerX, centerY, coreRadius * 1.85f, glowPaint)

        drawRings(canvas, centerX, centerY, stretch, squash)

        canvas.save()
        canvas.scale(stretch, squash, centerX, centerY)
        canvas.drawCircle(centerX, centerY, coreRadius, corePaint)
        canvas.drawCircle(
            centerX - coreRadius * 0.34f,
            centerY - coreRadius * 0.38f,
            coreRadius * 0.16f,
            highlightPaint
        )
        canvas.restore()

        // Dessinée hors de la déformation : une coche étirée deviendrait
        // illisible au moment où l'utilisateur en a le plus besoin.
        if (active) canvas.drawPath(checkPath, checkPaint)

        if (active) postInvalidateOnAnimation()
    }

    private fun drawRings(canvas: Canvas, centerX: Float, centerY: Float, stretch: Float, squash: Float) {
        for (index in 0 until RING_COUNT) {
            // Vitesses et inclinaisons différentes par anneau : sans ça, les
            // trois ellipses tourneraient comme un bloc rigide.
            val angle = phase * (1f + index * 0.4f) + index * 55f
            val radiusX = coreRadius * (1.12f + 0.15f * index) * stretch
            val radiusY = coreRadius * (0.30f + 0.14f * index) * squash

            ringBounds.set(
                centerX - radiusX,
                centerY - radiusY,
                centerX + radiusX,
                centerY + radiusY
            )

            ringPaint.alpha = (70 + 110 * level).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.rotate(angle, centerX, centerY)
            canvas.drawOval(ringBounds, ringPaint)
            canvas.restore()
        }
    }

    companion object {
        private const val RING_COUNT = 3
        private const val SMOOTHING = 0.22f
        private const val ROTATION_DEGREES_AT_REST = 1.4f
        private const val ROTATION_DEGREES_PER_LEVEL = 9f
    }
}
