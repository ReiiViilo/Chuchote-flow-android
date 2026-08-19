package dev.soupslurpr.transcribro.overlay

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Détecte une secousse volontaire du téléphone pour appeler le widget.
 *
 * Un seuil d'accélération seul ne suffit pas : marcher, poser l'appareil ou le
 * sortir d'une poche produit facilement une pointe isolée au-dessus du seuil.
 * Une vraie secousse, elle, est un va-et-vient — plusieurs pointes rapprochées.
 * On exige donc [REQUIRED_PEAKS] pointes dans une même fenêtre de temps, ce qui
 * écarte les gestes de la vie courante sans obliger à secouer violemment.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var peakCount = 0
    private var firstPeakAtMs = 0L
    private var lastPeakAtMs = 0L
    private var lastShakeAtMs = 0L

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // La gravité (~9,81 m/s²) est toujours mesurée : on la retire pour ne
        // garder que l'accélération réellement imprimée à l'appareil.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        val now = SystemClock.elapsedRealtime()

        if (gForce < PEAK_G_THRESHOLD) return

        // Deux échantillons consécutifs d'une même pointe ne comptent que pour
        // une : sans ce délai, une seule impulsion suffirait à atteindre le
        // compte exigé.
        if (now - lastPeakAtMs < MIN_MS_BETWEEN_PEAKS) return

        if (now - firstPeakAtMs > PEAK_WINDOW_MS) {
            peakCount = 0
            firstPeakAtMs = now
        }

        lastPeakAtMs = now
        peakCount++

        if (peakCount < REQUIRED_PEAKS) return
        if (now - lastShakeAtMs < SHAKE_COOLDOWN_MS) return

        peakCount = 0
        lastShakeAtMs = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Sans objet : seul le franchissement du seuil nous intéresse.
    }

    companion object {
        private const val PEAK_G_THRESHOLD = 2.7f
        private const val REQUIRED_PEAKS = 3
        private const val PEAK_WINDOW_MS = 900L
        private const val MIN_MS_BETWEEN_PEAKS = 90L
        private const val SHAKE_COOLDOWN_MS = 1500L
    }
}
