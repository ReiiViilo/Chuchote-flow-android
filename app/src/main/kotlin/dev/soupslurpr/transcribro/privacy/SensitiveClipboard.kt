package dev.soupslurpr.transcribro.privacy

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

internal object ClipboardSensitivityPolicy {
    fun shouldMarkSensitive(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU
}

/** Crée le même clip sensible pour l'historique et le fallback du widget. */
internal fun sensitivePlainText(
    label: CharSequence,
    text: CharSequence,
    sdkInt: Int = Build.VERSION.SDK_INT,
): ClipData = ClipData.newPlainText(label, text).apply {
    if (ClipboardSensitivityPolicy.shouldMarkSensitive(sdkInt)) {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
}
