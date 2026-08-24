package dev.soupslurpr.transcribro.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.preferences.PrivacyConsent

/**
 * Pont visible requis par Android 14+ avant de créer un service microphone.
 * Il ne capture aucun son : il valide les préconditions, demande au besoin la
 * permission runtime, démarre le widget, puis se ferme immédiatement.
 */
class WidgetLaunchActivity : Activity() {
    private var permissionRequestInFlight = false
    private var permissionGrantedPending = false
    private var launchRequested = false
    private var activityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
    }

    override fun onPostResume() {
        super.onPostResume()
        activityResumed = true
        continueLaunchFlow()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    private fun continueLaunchFlow() {
        if (permissionRequestInFlight || launchRequested) return

        if (permissionGrantedPending) {
            launchAfterPermissionIfVisible()
            return
        }

        if (!PrivacyConsent.isAcceptedBlocking(this)) {
            openMainApp()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Autorisation d'affichage par-dessus les autres apps requise",
                Toast.LENGTH_LONG,
            ).show()
            openMainApp()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequestInFlight = true
            // Ne jamais réutiliser l'état RESUMED antérieur au dialogue.
            activityResumed = false
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        launchWidget()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        permissionRequestInFlight = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionGrantedPending = true
            window.decorView.post { launchAfterPermissionIfVisible() }
        } else {
            Toast.makeText(this, "Autorisation du micro refusée", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun launchAfterPermissionIfVisible() {
        val microphoneGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!VisibleWidgetLaunchPolicy.canLaunch(activityResumed, microphoneGranted)) return
        permissionGrantedPending = false
        launchWidget()
    }

    private fun launchWidget() {
        if (launchRequested) return
        launchRequested = true
        try {
            startForegroundService(
                Intent(this, FloatingWidgetService::class.java)
                    .putExtra(FloatingWidgetService.EXTRA_VISIBLE_LAUNCH, true),
            )
        } catch (_: RuntimeException) {
            Toast.makeText(this, "Impossible de démarrer le widget", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    private fun openMainApp() {
        launchRequested = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val REQUEST_MICROPHONE = 4101
    }
}
