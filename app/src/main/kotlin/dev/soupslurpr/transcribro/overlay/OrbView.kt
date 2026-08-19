package dev.soupslurpr.transcribro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La bulle du widget : un nuage de points en suspension, qui tourne sur
 * lui-même comme un amas d'étoiles.
 *
 * Les points sont répartis sur une sphère puis projetés en deux dimensions.
 * Ceux qui passent devant sont dessinés plus gros, plus clairs et par-dessus
 * les autres : c'est cette différence de profondeur qui donne le volume, là où
 * des anneaux dessinés à plat auraient toujours l'air posés derrière.
 *
 * Au repos le nuage est immobile — une fenêtre superposée qui se redessine en
 * continu réveille le GPU en permanence. Pendant la dictée il tourne, respire
 * au rythme de la voix, et porte la coche de validation.
 */
class OrbView(context: Context) : View(context) {

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Fond sombre posé derrière le nuage. Le halo lumineux seul se perdait sur
    // un fond clair ou bleu : ce disque assombri garantit un contraste quel que
    // soit l'écran par-dessus lequel le widget flotte.
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val checkPath = Path()

    // Positions des points sur la sphère unité, calculées une seule fois.
    private val pointX = FloatArray(POINT_COUNT)
    private val pointY = FloatArray(POINT_COUNT)
    private val pointZ = FloatArray(POINT_COUNT)

    private var level = 0f
    private var targetLevel = 0f
    private var phase = 0f
    private var active = false
    private var transcribing = false
    private var pulse = 0f
    private var radius = 0f

    private val animating: Boolean
        get() = active || transcribing

    init {
        buildSphere()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            targetLevel = 0f
            level = 0f
        }
        postInvalidateOnAnimation()
    }

    /**
     * Mode attente : pendant la transcription, le nuage respire de lui-même.
     * C'est lui qui porte l'attente, ce qui évite de garder à l'écran une
     * pastille dont les commandes n'ont plus cours une fois la dictée validée.
     */
    fun setTranscribing(value: Boolean) {
        if (transcribing == value) return
        transcribing = value
        pulse = 0f
        postInvalidateOnAnimation()
    }

    fun setLevel(db: Float) {
        // Même seuil de bruit que le tracé : sans lui le nuage frémissait dans
        // le silence, ce qui laissait croire qu'il captait la voix.
        val normalized = ((db - NOISE_FLOOR_DB) / (0f - NOISE_FLOOR_DB)).coerceIn(0f, 1f)
        targetLevel = if (normalized <= GATE) 0f else (normalized - GATE) / (1f - GATE)
    }

    /**
     * Répartition en spirale dorée : elle éparpille les points de façon
     * régulière sur toute la sphère, alors qu'un tirage au hasard laisserait
     * des paquets et des trous bien visibles à ce nombre de points.
     */
    private fun buildSphere() {
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until POINT_COUNT) {
            val y = 1f - 2f * (i + 0.5f) / POINT_COUNT
            val ringRadius = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = goldenAngle * i
            pointX[i] = cos(theta) * ringRadius
            pointY[i] = y
            pointZ[i] = sin(theta) * ringRadius
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f
        radius = min(w, h) / 2f * 0.66f

        checkPaint.strokeWidth = radius * 0.16f
        checkPath.reset()
        checkPath.moveTo(centerX - radius * 0.32f, centerY + radius * 0.02f)
        checkPath.lineTo(centerX - radius * 0.08f, centerY + radius * 0.26f)
        checkPath.lineTo(centerX + radius * 0.34f, centerY - radius * 0.24f)

        backdropPaint.shader = RadialGradient(
            centerX,
            centerY,
            radius * 1.5f,
            intArrayOf(
                Color.argb(170, 8, 8, 22),
                Color.argb(120, 8, 8, 22),
                Color.argb(0, 8, 8, 22)
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )

        // Halo central : il donne au nuage un cœur lumineux, ce qui le fait
        // lire comme une étoile plutôt que comme une simple constellation.
        corePaint.shader = RadialGradient(
            centerX,
            centerY,
            radius * 1.15f,
            intArrayOf(
                Color.argb(215, 175, 235, 255),
                Color.argb(110, 120, 140, 250),
                Color.argb(0, 90, 90, 210)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        level += (targetLevel - level) * SMOOTHING
        if (animating) phase += ROTATION_AT_REST + ROTATION_PER_LEVEL * level
        if (transcribing) pulse += PULSE_STEP

        val centerX = width / 2f
        val centerY = height / 2f

        // Pendant l'attente, les points s'écartent et se resserrent pendant que
        // le cœur fait l'inverse : le noyau reste dense quand la couronne se
        // déploie, ce qui donne une respiration plutôt qu'un simple grossissement.
        val swell = if (transcribing) sin(pulse) else 0f
        val breathing = radius * (0.88f + 0.22f * level + 0.26f * swell)
        val coreScale = 1f - 0.30f * swell

        canvas.drawCircle(centerX, centerY, radius * 1.5f, backdropPaint)
        canvas.drawCircle(centerX, centerY, radius * 1.15f * coreScale, corePaint)

        val cosPhase = cos(phase)
        val sinPhase = sin(phase)
        val cosTilt = cos(TILT)
        val sinTilt = sin(TILT)

        // Étirement en contre-phase : ce que le nuage gagne en largeur, il le
        // perd en hauteur, comme une goutte qui vibre.
        if (transcribing) {
            canvas.save()
            canvas.scale(1f + 0.09f * swell, 1f - 0.09f * swell, centerX, centerY)
        }

        // Deux passes : d'abord l'arrière, ensuite l'avant. Trier les points à
        // chaque image coûterait plus cher que ce découpage, et le rendu est
        // indiscernable à ce nombre de points.
        for (pass in 0..1) {
            for (i in 0 until POINT_COUNT) {
                val x = pointX[i]
                val y = pointY[i]
                val z = pointZ[i]

                // Rotation autour de l'axe vertical, puis basculement de l'axe
                // pour qu'on ne regarde pas le nuage pile par l'équateur.
                val rotatedX = x * cosPhase + z * sinPhase
                val rotatedZ = -x * sinPhase + z * cosPhase
                val tiltedY = y * cosTilt - rotatedZ * sinTilt
                val depth = y * sinTilt + rotatedZ * cosTilt

                val isFront = depth >= 0f
                if ((pass == 0) == isFront) continue

                // depth va de -1 (au fond) à 1 (au premier plan).
                val nearness = (depth + 1f) / 2f

                pointPaint.color = if (isFront) FRONT_COLOR else BACK_COLOR
                pointPaint.alpha = (55 + 200 * nearness).toInt().coerceIn(0, 255)

                canvas.drawCircle(
                    centerX + rotatedX * breathing,
                    centerY + tiltedY * breathing,
                    radius * (0.030f + 0.055f * nearness) * (1f + 0.5f * level),
                    pointPaint
                )
            }
        }

        if (transcribing) canvas.restore()

        // La coche n'a plus lieu d'être une fois la dictée validée : il n'y a
        // plus rien à confirmer pendant la transcription.
        if (active && !transcribing) canvas.drawPath(checkPath, checkPaint)

        if (animating) postInvalidateOnAnimation()
    }

    companion object {
        private const val POINT_COUNT = 110
        private const val SMOOTHING = 0.22f
        private const val ROTATION_AT_REST = 0.010f
        private const val ROTATION_PER_LEVEL = 0.055f
        private const val TILT = 0.42f
        private const val NOISE_FLOOR_DB = -45f
        private const val GATE = 0.22f
        private const val PULSE_STEP = 0.075f

        private val FRONT_COLOR = Color.rgb(150, 245, 255)
        private val BACK_COLOR = Color.rgb(120, 120, 240)
    }
}
