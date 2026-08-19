package dev.soupslurpr.transcribro.overlay

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Détecte une secousse du téléphone pour déclencher la dictée sans toucher
 * l'écran, comme le widget de Wispr Flow.
 *
 * L'accéléromètre mesure aussi la gravité (~9,81 m/s² au repos) : on la
 * soustrait pour ne garder que l'accélération réellement imprimée par la main.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var lastShakeAtMs = 0L

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce > SHAKE_G_THRESHOLD) {
            val now = SystemClock.elapsedRealtime()
            // Une secousse dure plusieurs échantillons : sans ce délai, un seul
            // geste déclencherait la dictée des dizaines de fois.
            if (now - lastShakeAtMs < SHAKE_COOLDOWN_MS) return
            lastShakeAtMs = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Sans objet : seul le franchissement du seuil nous intéresse.
    }

    companion object {
        private const val SHAKE_G_THRESHOLD = 2.2f
        private const val SHAKE_COOLDOWN_MS = 1500L
    }
}
