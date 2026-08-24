package dev.soupslurpr.transcribro.ui.action_recognize_speech

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.recognitionservice.MainRecognitionService

class ActionRecognizeSpeechActivity : MainActivity() {
    override fun canCreateContentFor(intent: Intent): Boolean =
        intent.action == RecognizerIntent.ACTION_RECOGNIZE_SPEECH &&
            super.canCreateContentFor(intent)

    override fun onContentRequestRejected(intent: Intent) {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}

private val supportedRecognitionExtras = setOf(
    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
    RecognizerIntent.EXTRA_PROMPT,
    RecognizerIntent.EXTRA_LANGUAGE,
    RecognizerIntent.EXTRA_MAX_RESULTS,
    MainRecognitionService.EXTRA_AUTO_STOP,
    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT,
    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE,
)

/**
 * Valide et réduit la surface Intent exportée avant toute composition UI.
 * Les extras de sortie sont validés ici, mais ne seront jamais transmis au
 * RecognitionService; le ViewModel reconstruit ensuite son Intent allowlisté.
 * EXTRA_PROMPT, EXTRA_LANGUAGE et EXTRA_MAX_RESULTS sont acceptés pour rester
 * compatibles avec RecognizerIntent, puis ignorés honnêtement: cette UI
 * française minimale n'affiche pas de prompt et le service ne promet qu'un
 * résultat sans capacité d'imposer la langue demandée.
 */
internal fun Intent.toValidatedActionRecognitionRequest(): ActionRecognitionRequest? {
    val extrasBundle = runCatching { extras }.getOrNull()
    val keys = runCatching { extrasBundle?.keySet().orEmpty() }.getOrElse { return null }
    val hasUnsupportedExtras = keys.any { key -> key !in supportedRecognitionExtras }
    // Ne pas forcer la désérialisation d'un Parcelable inconnu fourni à cette
    // Activity exportée une fois que sa clé suffit déjà à rejeter la requête.
    if (hasUnsupportedExtras) return null
    val values = runCatching {
        keys.associateWith { key ->
            @Suppress("DEPRECATION")
            extrasBundle?.get(key)
        }
    }.getOrElse { return null }

    val extrasHaveExpectedTypes = keys.all { key ->
        when (key) {
            RecognizerIntent.EXTRA_LANGUAGE_MODEL -> values[key] is String
            RecognizerIntent.EXTRA_PROMPT,
            RecognizerIntent.EXTRA_LANGUAGE -> values[key] is String

            RecognizerIntent.EXTRA_MAX_RESULTS -> values[key] is Int
            RecognizerIntent.EXTRA_PARTIAL_RESULTS,
            MainRecognitionService.EXTRA_AUTO_STOP -> values[key] is Boolean

            RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT -> values[key] is PendingIntent
            RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE -> values[key] is Bundle
            else -> true
        }
    }
    val hasPendingIntent = keys.contains(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT)
    val hasPendingBundle = keys.contains(
        RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE,
    )

    return ActionRecognitionRequestContract.validate(
        actionMatches = action == RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
        languageModel = values[RecognizerIntent.EXTRA_LANGUAGE_MODEL] as? String,
        partialResults = values[RecognizerIntent.EXTRA_PARTIAL_RESULTS] as? Boolean ?: false,
        autoStop = values[MainRecognitionService.EXTRA_AUTO_STOP] as? Boolean ?: true,
        hasUnsupportedExtras = false,
        pendingBundleWithoutPendingIntent = hasPendingBundle && !hasPendingIntent,
        extrasHaveExpectedTypes = extrasHaveExpectedTypes,
        prompt = values[RecognizerIntent.EXTRA_PROMPT] as? String,
        language = values[RecognizerIntent.EXTRA_LANGUAGE] as? String,
        maxResults = values[RecognizerIntent.EXTRA_MAX_RESULTS] as? Int,
    )
}
