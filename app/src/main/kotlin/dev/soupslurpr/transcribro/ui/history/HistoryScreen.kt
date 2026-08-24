package dev.soupslurpr.transcribro.ui.history

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.memory.Dictee
import dev.soupslurpr.transcribro.memory.EtatDictee
import dev.soupslurpr.transcribro.privacy.sensitivePlainText
import dev.soupslurpr.transcribro.recognitionservice.RetryTranscriptionManager
import dev.soupslurpr.transcribro.recognitionservice.RetryTranscriptionPolicy
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn

/**
 * L'accueil de Chuchote Flow : les dernières dictées, prêtes à être
 * recherchées, copiées ou supprimées. La base reste privée et hors sauvegarde
 * cloud/transfert Android; seul le relais facultatif reçoit les données
 * annoncées dans la politique lorsqu'il est activé.
 */
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val store = remember { ChuchoteStore.get(context) }
    val retryManager = remember { RetryTranscriptionManager.get(context) }
    val dictees by store.dictees.collectAsState()

    var recherche by rememberSaveable { mutableStateOf("") }
    var confirmerToutEffacer by remember { mutableStateOf(false) }
    var dicteeARetranscrire by remember { mutableStateOf<Dictee?>(null) }
    val supprimables = dictees.count {
        it.etat == EtatDictee.A_REESSAYER || it.etat == EtatDictee.TERMINEE
    }

    val visibles = if (recherche.isBlank()) {
        dictees
    } else {
        dictees.filter { it.texte.contains(recherche.trim(), ignoreCase = true) }
    }

    if (confirmerToutEffacer) {
        AlertDialog(
            onDismissRequest = { confirmerToutEffacer = false },
            title = { Text("Effacer l'historique ?") },
            text = {
                Text(
                    "Les $supprimables dictées terminées ou interrompues et leurs audios " +
                            "sauvegardés " +
                            "seront supprimés définitivement."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.effacerHistorique()
                    confirmerToutEffacer = false
                }) {
                    Text("Tout effacer")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmerToutEffacer = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    dicteeARetranscrire?.let { dictee ->
        AlertDialog(
            onDismissRequest = { dicteeARetranscrire = null },
            title = { Text("Retranscrire cet audio ?") },
            text = {
                Text(
                    "Une nouvelle transcription utilisera le WAV sauvegardé. " +
                        "Le texte actuel sera remplacé seulement si elle réussit.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dicteeARetranscrire = null
                    lancerRetranscription(context, retryManager, dictee.id)
                }) {
                    Text("Retranscrire")
                }
            },
            dismissButton = {
                TextButton(onClick = { dicteeARetranscrire = null }) {
                    Text("Annuler")
                }
            },
        )
    }

    ScreenLazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        if (dictees.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(CircleShape)
                            .background(colorResource(id = R.color.ic_launcher_background))
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(240.dp)
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = "Tes dictées apparaîtront ici",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Chaque dictée et son audio de reprise sont gardés dans le " +
                                "stockage privé. Si tu actives le relais, les segments audio " +
                                "sont envoyés au serveur que tu as configuré.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = recherche,
                    onValueChange = { recherche = it },
                    singleLine = true,
                    label = { Text("Rechercher dans les dictées") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (recherche.isNotEmpty()) {
                            IconButton(onClick = { recherche = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Effacer la recherche")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (recherche.isBlank()) {
                            "${dictees.size} dictée${if (dictees.size > 1) "s" else ""}"
                        } else {
                            "${visibles.size} résultat${if (visibles.size > 1) "s" else ""}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = supprimables > 0,
                        onClick = { confirmerToutEffacer = true },
                    ) {
                        Text("Tout effacer")
                    }
                }
            }
            items(count = visibles.size, key = { visibles[it].id }) { index ->
                val dictee = visibles[index]
                ElevatedCard {
                    Column(Modifier.padding(16.dp)) {
                        if (dictee.texte.isNotBlank()) {
                            Text(
                                text = dictee.texte,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            Text(
                                text = libelleEtat(dictee.etat, dictee.erreur),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val afficherEtatSecondaire = dictee.texte.isNotBlank() && (
                                dictee.etat != EtatDictee.TERMINEE ||
                                        !dictee.erreur.isNullOrBlank()
                                )
                        if (afficherEtatSecondaire) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = libelleEtat(dictee.etat, dictee.erreur),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.size(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // La durée affichée est le délai vécu : entre la
                            // validation de la dictée et l'arrivée du texte.
                            val details = buildString {
                                append(
                                    DateUtils.getRelativeTimeSpanString(
                                        dictee.creeLe,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS
                                    )
                                )
                                when (dictee.source) {
                                    "relais" -> append(" · relais")
                                    "local" -> append(" · sur l'appareil")
                                    "mixte" -> append(" · relais + appareil")
                                }
                                dictee.dureeMs?.takeIf { it > 0 }?.let {
                                    append(" · transcription ${"%.1f".format(it / 1000f)} s")
                                }
                                dictee.dureeAudioMs?.takeIf { it > 0 }?.let {
                                    append(" · audio ${formaterDureeAudio(it)}")
                                }
                            }
                            Text(
                                text = details,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (RetryTranscriptionPolicy.canRetry(
                                    state = dictee.etat,
                                    audioPath = dictee.cheminAudio,
                                    errorCode = dictee.erreur,
                                )
                            ) {
                                IconButton(onClick = {
                                    if (RetryTranscriptionPolicy.requiresConfirmation(
                                            state = dictee.etat,
                                            transcript = dictee.texte,
                                        )
                                    ) {
                                        dicteeARetranscrire = dictee
                                    } else {
                                        lancerRetranscription(context, retryManager, dictee.id)
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = if (dictee.etat == EtatDictee.TERMINEE) {
                                            "Retranscrire l’audio sauvegardé"
                                        } else {
                                            "Réessayer la transcription"
                                        },
                                    )
                                }
                            }
                            if (dictee.texte.isNotBlank()) {
                                IconButton(onClick = { copier(context, dictee.texte) }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copier"
                                    )
                                }
                            }
                            val peutSupprimer = dictee.etat == EtatDictee.A_REESSAYER ||
                                    dictee.etat == EtatDictee.TERMINEE
                            IconButton(
                                enabled = peutSupprimer,
                                onClick = { store.supprimerDictee(dictee.id) },
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Supprimer"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun libelleEtat(etat: EtatDictee, erreur: String?): String = when (etat) {
    EtatDictee.ENREGISTREMENT -> "Enregistrement en cours…"
    EtatDictee.EN_ATTENTE -> "Audio sauvegardé — transcription en attente…"
    EtatDictee.TRANSCRIPTION -> "Transcription en cours…"
    EtatDictee.A_REESSAYER -> when (erreur) {
        "out_of_memory" -> "Mémoire saturée — audio sauvegardé, prêt à réessayer"
        "audio_missing", "retry_audio_missing" -> "Audio introuvable"
        "empty_audio", "retry_audio_invalid" -> "Audio vide ou inutilisable"
        "cancelled" -> "Enregistrement interrompu — audio sauvegardé"
        "audio_delete_failed" ->
            "Suppression impossible — audio conservé, touche la corbeille pour réessayer"
        "audio_path_invalid" ->
            "Suppression bloquée — chemin audio invalide conservé pour vérification"
        else -> "Transcription interrompue — audio sauvegardé, prêt à réessayer"
    }
    EtatDictee.TERMINEE -> when (erreur) {
        "retry_audio_missing" ->
            "Texte conservé — audio introuvable pour une nouvelle transcription"
        "retry_audio_invalid" ->
            "Texte conservé — audio inutilisable pour une nouvelle transcription"
        "out_of_memory" ->
            "Texte conservé — mémoire insuffisante pour préparer la retranscription"
        "retry_failed" ->
            "Texte conservé — préparation de la retranscription impossible"
        "audio_delete_failed" ->
            "Suppression impossible — audio conservé, touche la corbeille pour réessayer"
        "audio_path_invalid" ->
            "Suppression bloquée — chemin audio invalide conservé pour vérification"
        else -> "Transcription terminée"
    }
}

private fun formaterDureeAudio(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes} min ${seconds.toString().padStart(2, '0')} s" else "${seconds} s"
}

private fun copier(context: Context, texte: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(sensitivePlainText("Dictée", texte))
    // Android 13+ affiche déjà sa propre confirmation de copie.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, "Copié", Toast.LENGTH_SHORT).show()
    }
}

private fun lancerRetranscription(
    context: Context,
    retryManager: RetryTranscriptionManager,
    dictationId: Long,
) {
    val started = retryManager.retry(dictationId)
    Toast.makeText(
        context,
        if (started) {
            "Préparation de la transcription…"
        } else {
            "Impossible de lancer la transcription maintenant"
        },
        Toast.LENGTH_SHORT,
    ).show()
}
