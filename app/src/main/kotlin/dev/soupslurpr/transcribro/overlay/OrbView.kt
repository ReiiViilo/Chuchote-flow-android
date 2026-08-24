package dev.soupslurpr.transcribro.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import dev.soupslurpr.transcribro.recognitionservice.audio.TranscriptionProgressEstimator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La bulle du widget : un voile de fumée lumineuse en suspension autour d'un
 * cœur clair, qui tourne lentement sur lui-même.
 *
 * Chaque particule est un dégradé doux plutôt qu'un disque plein : c'est ce
 * flou qui fait lire l'ensemble comme une aura de fumée et non comme un amas
 * de points. Les particules sont réparties sur une sphère puis projetées en
 * deux dimensions ; celles qui passent devant sont plus claires et dessinées
 * par-dessus, ce qui donne le volume.
 *
 * Au repos le nuage respire à peine, à cadence réduite pour ménager la
 * batterie. Pendant la dictée il tourne au rythme de la voix. Pendant la
 * transcription il pulse et sa couleur glisse du violet vers le vert — le
 * vert plein signale que le texte part s'insérer.
 */
class OrbView(context: Context) : View(context) {

    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spriteRect = RectF()
    private var sprite: Bitmap? = null

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

    // Positions des particules sur la sphère unité, calculées une seule fois.
    private val pointX = FloatArray(POINT_COUNT)
    private val pointY = FloatArray(POINT_COUNT)
    private val pointZ = FloatArray(POINT_COUNT)

    private var level = 0f
    private var targetLevel = 0f
    private var phase = 0f
    private var active = false
    private var transcribing = false
    private var pulse = 0f
    private var idlePulse = 0f
    private var progress = 0f
    private var transcriptionStartedAtMs = 0L
    private var audioDurationMs = 0L
    private var completedSegments = 0
    private var totalSegments = 0
    private var radius = 0f

    // Rayon relatif réellement affiché : il glisse vers sa cible au lieu de la
    // suivre d'un coup, parce que la progression avance par paliers (segments
    // terminés) et qu'un rayon qui saute d'un palier à l'autre saccade.
    private var shownShrink = 1f

    // La finale est une chorégraphie au chronomètre, jouée en entier quelle
    // que soit la durée de la dictée : elle capture l'état où la transcription
    // a laissé l'orbe et le mène elle-même jusqu'au noyau vert, à l'éclat,
    // puis au cyan de repos.
    private var finaleStartMs = 0L
    private var finaleFromShrink = 1f
    private var finaleFromProgress = 0f
    private var finaleFromSwell = 0f
    private var lastSwell = 0f

    private val animating: Boolean
        get() = active || transcribing || finaleStartMs != 0L

    init {
        buildSphere()
        buildSprite()
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
     * Mode attente : pendant la transcription, le nuage respire de lui-même et
     * sa couleur glisse du violet vers le vert, pour donner à voir que le
     * texte s'en vient.
     */
    fun setTranscribing(value: Boolean) {
        if (transcribing == value) return
        transcribing = value
        if (value) finaleStartMs = 0L
        pulse = 0f
        progress = 0f
        transcriptionStartedAtMs = if (value) SystemClock.elapsedRealtime() else 0L
        if (!value) {
            audioDurationMs = 0L
            completedSegments = 0
            totalSegments = 0
        }
        postInvalidateOnAnimation()
    }

    /** Lie la vitesse du dégradé à la longueur et aux segments réels. */
    fun setTranscriptionProgress(
        audioDurationMs: Long,
        completedSegments: Int,
        totalSegments: Int,
    ) {
        this.audioDurationMs = audioDurationMs.coerceAtLeast(0L)
        this.completedSegments = completedSegments.coerceAtLeast(0)
        this.totalSegments = totalSegments.coerceAtLeast(0)
        postInvalidateOnAnimation()
    }

    /** Lance la finale : fin de contraction, éclat vert, retour au repos. */
    fun flashSuccess() {
        finaleStartMs = SystemClock.elapsedRealtime()
        finaleFromShrink = shownShrink
        finaleFromProgress = progress
        finaleFromSwell = lastSwell
        postInvalidateOnAnimation()
    }

    fun setLevel(db: Float) {
        // Même seuil de bruit que le tracé : sans lui le nuage frémissait dans
        // le silence, ce qui laissait croire qu'il captait la voix.
        val normalized = ((db - NOISE_FLOOR_DB) / (0f - NOISE_FLOOR_DB)).coerceIn(0f, 1f)
        targetLevel = if (normalized <= GATE) 0f else (normalized - GATE) / (1f - GATE)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Répartition en spirale dorée : elle éparpille les particules de façon
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

    /**
     * La particule de fumée : un dégradé blanc qui s'évanouit vers le bord,
     * rendu une seule fois puis teinté au dessin. Dessiner ce flou à chaque
     * particule et à chaque image coûterait bien plus cher qu'étirer cette
     * petite image.
     */
    private fun buildSprite() {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            size / 2f,
            size / 2f,
            size / 2f,
            intArrayOf(
                Color.WHITE,
                Color.argb(150, 255, 255, 255),
                Color.argb(40, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        sprite = bitmap
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
        // lire comme une étoile voilée plutôt que comme une constellation.
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dot = sprite
        if (radius <= 0f || dot == null) return

        level += (targetLevel - level) * SMOOTHING
        if (animating) {
            phase += ROTATION_AT_REST + ROTATION_PER_LEVEL * level
        } else {
            // Au repos le nuage dérive à peine, à cadence réduite : assez pour
            // avoir l'air vivant, pas assez pour peser sur la batterie.
            phase += IDLE_ROTATION
            idlePulse += IDLE_PULSE_STEP
        }
        if (transcribing) {
            pulse += PULSE_STEP
            val elapsed = (SystemClock.elapsedRealtime() - transcriptionStartedAtMs)
                .coerceAtLeast(0L)
            // La durée audio ralentit réellement la couleur. Les segments
            // terminés font avancer l'estimation, mais le vert plein reste
            // réservé au callback de réussite.
            progress = maxOf(
                progress,
                TranscriptionProgressEstimator.estimate(
                    elapsedMs = elapsed,
                    audioDurationMs = audioDurationMs,
                    completedSegments = completedSegments,
                    totalSegments = totalSegments,
                ),
            )
        } else if (finaleStartMs != 0L) {
            // L'ondulation continue de vivre pendant son fondu de finale.
            pulse += PULSE_STEP
        }
        val centerX = width / 2f
        val centerY = height / 2f

        // La finale, entièrement au chronomètre : recueillement vers le noyau
        // en finissant de virer au vert, éclat, puis fondu du vert vers le
        // cyan de repos pendant que la sphère se dépose. Comme elle capture
        // l'état laissé par la transcription, la séquence est identique pour
        // une dictée d'une seconde ou d'une minute, sans jamais sauter.
        var finaleShrink = -1f
        var finaleFront = 0
        var finaleGlow = 0f
        var finaleSettle = 0f
        var finaleSwellFade = 0f
        if (finaleStartMs != 0L) {
            val t = (SystemClock.elapsedRealtime() - finaleStartMs) /
                FINALE_TOTAL_MS.toFloat()
            if (t >= 1f) {
                finaleStartMs = 0L
                shownShrink = 1f
            } else if (t < FINALE_GATHER_END) {
                val k = t / FINALE_GATHER_END
                val eased = k * k * (3f - 2f * k)
                finaleShrink = finaleFromShrink +
                    (SHRINK_FLOOR - finaleFromShrink) * eased
                finaleFront = transcriptionColor(
                    finaleFromProgress + (1f - finaleFromProgress) * eased,
                )
                finaleGlow = eased * 0.6f
                finaleSwellFade = 1f - eased
            } else {
                val u = (t - FINALE_GATHER_END) / (1f - FINALE_GATHER_END)
                finaleShrink = popCurve(u)
                finaleGlow = (1f - u) * 0.9f
                finaleSettle = if (u > 0.45f) {
                    val k = (u - 0.45f) / 0.55f
                    k * k * (3f - 2f * k)
                } else {
                    0f
                }
                finaleFront = lerpColor(GREEN, FRONT_COLOR, finaleSettle)
            }
        }
        val finaleActive = finaleStartMs != 0L

        // Pendant l'attente, les particules s'écartent et se resserrent pendant
        // que le cœur fait l'inverse : le noyau reste dense quand la couronne se
        // déploie, ce qui donne une respiration plutôt qu'un simple grossissement.
        // La respiration se calme à mesure que la sphère se contracte : le
        // nuage se recueille avant le point final.
        // Au démarrage de la finale, le renflement en cours n'est pas coupé :
        // il est capturé puis résorbé dans le même geste que la contraction.
        // Sans cela, des points en pleine expansion s'arrêtaient net avant de
        // refermer — c'était là que le mouvement semblait accrocher.
        val swell = when {
            finaleActive -> finaleFromSwell * finaleSwellFade
            transcribing -> sin(pulse) * (1f - 0.6f * progress)
            !animating -> IDLE_SWELL * sin(idlePulse)
            else -> 0f
        }
        if (transcribing) lastSwell = swell
        val breathing = radius * (0.88f + 0.22f * level + 0.26f * swell)

        // Le point final en deux temps. Pendant la transcription, le rayon se
        // resserre graduellement avec la progression — la sphère se condense en
        // glissant du violet au vert. À la réussite, Big Bang : le nuage vert
        // éclate depuis son état le plus dense, dépasse un instant sa taille de
        // repos, puis s'y dépose. Le cœur suit le nuage, mais moins vite : la
        // lumière se concentre à mesure que la sphère rapetisse.
        val shrink = if (finaleActive && finaleShrink >= 0f) {
            shownShrink = finaleShrink
            finaleShrink
        } else {
            val target = if (transcribing) 1f - (1f - SHRINK_FLOOR) * progress else 1f
            shownShrink += (target - shownShrink) * SHRINK_SMOOTHING
            shownShrink
        }
        val coreScale = (
            1f - 0.30f * (if (transcribing) swell else 0f) + 0.10f * finaleGlow
            ) * (0.35f + 0.65f * shrink)

        canvas.drawCircle(centerX, centerY, radius * 1.5f, backdropPaint)
        canvas.drawCircle(centerX, centerY, radius * 1.15f * coreScale, corePaint)

        // Couleur du voile : cyan/indigo en temps normal, violet glissant vers
        // le vert pendant la transcription — jamais de rouge, qui se lisait
        // comme une erreur —, puis la finale ramène le vert au cyan en fondu.
        val frontColor = when {
            finaleActive -> finaleFront
            transcribing -> transcriptionColor(progress)
            else -> FRONT_COLOR
        }
        val backColor = when {
            finaleActive -> lerpColor(
                lerpColor(finaleFront, Color.BLACK, 0.40f),
                BACK_COLOR,
                finaleSettle,
            )
            transcribing -> lerpColor(frontColor, Color.BLACK, 0.40f)
            else -> BACK_COLOR
        }

        val cosPhase = cos(phase)
        val sinPhase = sin(phase)
        val cosTilt = cos(TILT)
        val sinTilt = sin(TILT)

        // Pendant la transcription, la déformation n'est plus une simple
        // ellipse : deux ondulations à deux et trois lobes tournent en sens
        // contraires et se superposent, ce qui donne à la goutte un profil
        // qui change sans cesse, comme une fumée qui se cherche une forme.
        // L'ondulation aussi se fond au lieu de disparaître d'un coup.
        val wobbleAmp = when {
            transcribing -> 0.075f * (0.5f + 0.5f * swell)
            finaleActive -> 0.075f * (0.5f + 0.5f * finaleFromSwell) * finaleSwellFade
            else -> 0f
        }

        // Deux passes : d'abord l'arrière, ensuite l'avant. Trier les
        // particules à chaque image coûterait plus cher que ce découpage, et le
        // rendu est indiscernable à ce nombre de points.
        for (pass in 0..1) {
            spritePaint.colorFilter = PorterDuffColorFilter(
                if (pass == 0) backColor else frontColor,
                PorterDuff.Mode.SRC_IN
            )
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

                spritePaint.alpha = (40 + 165 * nearness).toInt().coerceIn(0, 255)

                // Le rayon couvre tout le dégradé de la particule : son cœur
                // visible ne fait qu'un tiers de cette taille, le reste n'est
                // que voile — c'est là que naît l'effet de fumée. Des points
                // fins et nombreux plutôt que gros et rares : la densité fait
                // le voile.
                val half = radius * (0.042f + 0.064f * nearness) * (1f + 0.4f * level)

                // L'ondulation déplace chaque particule le long de son rayon
                // selon son angle à l'écran : deux et trois lobes en sens
                // contraires, la goutte se cherche une forme au lieu de
                // s'étirer en simple ellipse.
                val angle = kotlin.math.atan2(tiltedY, rotatedX)
                val wobble = 1f + wobbleAmp * (
                    sin(2f * angle + pulse * 1.35f) * 0.6f +
                        sin(3f * angle - pulse * 0.85f) * 0.4f
                    )
                val dist = breathing * wobble * shrink
                val px = centerX + rotatedX * dist
                val py = centerY + tiltedY * dist
                spriteRect.set(px - half, py - half, px + half, py + half)
                canvas.drawBitmap(dot, null, spriteRect, spritePaint)
            }
        }

        // La coche n'a plus lieu d'être une fois la dictée validée : il n'y a
        // plus rien à confirmer pendant la transcription.
        if (active && !transcribing) canvas.drawPath(checkPath, checkPaint)

        if (animating) {
            postInvalidateOnAnimation()
        } else if (isAttachedToWindow) {
            postInvalidateDelayed(IDLE_FRAME_MS)
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }

    /**
     * Le Big Bang, sur le temps écoulé du flash (0 → 1). Il part du rayon le
     * plus contracté — exactement là où la transcription a laissé la sphère —
     * éclate d'un coup au-delà de la taille de repos, puis s'y dépose en
     * douceur. La continuité avec la contraction graduelle évite tout à-coup.
     */
    private fun popCurve(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        return if (t < POP_END) {
            val k = t / POP_END
            val eased = 1f - (1f - k) * (1f - k) * (1f - k)
            SHRINK_FLOOR + (POP_OVERSHOOT - SHRINK_FLOOR) * eased
        } else {
            val k = (t - POP_END) / (1f - POP_END)
            val eased = k * k * (3f - 2f * k)
            POP_OVERSHOOT - (POP_OVERSHOOT - 1f) * eased
        }
    }

    private fun transcriptionColor(value: Float): Int {
        val progress = value.coerceIn(0f, 1f)
        return if (progress < SKY_THRESHOLD) {
            lerpColor(VIOLET, SKY, progress / SKY_THRESHOLD)
        } else {
            lerpColor(
                SKY,
                GREEN,
                (progress - SKY_THRESHOLD) / (1f - SKY_THRESHOLD),
            )
        }
    }

    companion object {
        private const val POINT_COUNT = 420
        private const val SMOOTHING = 0.22f
        private const val ROTATION_AT_REST = 0.010f
        private const val ROTATION_PER_LEVEL = 0.055f
        private const val TILT = 0.42f
        private const val NOISE_FLOOR_DB = -45f
        private const val GATE = 0.22f
        private const val PULSE_STEP = 0.075f
        private const val SHRINK_FLOOR = 0.52f
        private const val POP_OVERSHOOT = 1.14f
        private const val POP_END = 0.26f
        private const val FINALE_TOTAL_MS = 950L
        private const val FINALE_GATHER_END = 0.34f
        private const val SHRINK_SMOOTHING = 0.055f
        private const val SKY_THRESHOLD = 0.50f

        // Cadence du repos : ~12 images par seconde suffisent à une dérive
        // lente, pour une fraction du coût d'une animation pleine cadence.
        private const val IDLE_FRAME_MS = 84L
        private const val IDLE_ROTATION = 0.006f
        private const val IDLE_PULSE_STEP = 0.045f
        private const val IDLE_SWELL = 0.035f

        private val FRONT_COLOR = Color.rgb(150, 245, 255)
        private val BACK_COLOR = Color.rgb(120, 120, 240)
        private val VIOLET = Color.rgb(138, 126, 255)
        private val SKY = Color.rgb(118, 178, 255)
        private val GREEN = Color.rgb(105, 240, 155)
    }
}
