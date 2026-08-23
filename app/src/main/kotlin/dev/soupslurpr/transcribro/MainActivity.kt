package dev.soupslurpr.transcribro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import dev.soupslurpr.transcribro.ui.ReviewPrivacyPolicyAndLicense
import dev.soupslurpr.transcribro.ui.action_recognize_speech.toValidatedActionRecognitionRequest
import dev.soupslurpr.transcribro.ui.theme.TranscribroTheme

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "preferences")

open class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Une requête de reconnaissance exportée est validée avant setContent:
        // aucun policy screen ni recognizer ne peut démarrer sur un contrat
        // invalide ou sans consentement courant. Les lancements ordinaires de
        // MainActivity ne passent pas par cette barrière ACTION.
        if (!canCreateContentFor(intent)) {
            onContentRequestRejected(intent)
            return
        }

        setContent {
            val preferencesViewModel: PreferencesViewModel = viewModel(
                factory = PreferencesViewModel.PreferencesViewModelFactory(dataStore)
            )

            val preferencesUiState by preferencesViewModel.uiState.collectAsState()

            TranscribroTheme(
                preferencesViewModel = preferencesViewModel
            ) {
                if (!preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                    ReviewPrivacyPolicyAndLicense(preferencesViewModel = preferencesViewModel)
                } else if (preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                    TranscribroApp(
                        intent.action == Intent.ACTION_APPLICATION_PREFERENCES,
                        intent.action == RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
                    )
                }
            }
        }
    }

    protected open fun canCreateContentFor(intent: Intent): Boolean {
        if (intent.action != RecognizerIntent.ACTION_RECOGNIZE_SPEECH) return true
        return intent.toValidatedActionRecognitionRequest() != null &&
            PrivacyConsent.isAcceptedBlocking(applicationContext)
    }

    protected open fun onContentRequestRejected(intent: Intent) {
        // La méthode de base ne rejette que ACTION_RECOGNIZE_SPEECH; une
        // Activity ordinaire conserve donc son comportement historique.
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
