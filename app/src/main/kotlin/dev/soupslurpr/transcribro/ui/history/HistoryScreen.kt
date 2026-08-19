package dev.soupslurpr.transcribro.ui.history

import android.content.ClipData
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
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn

/**
 * L'accueil de Chuchote Flow : les dernières dictées, prêtes à être
 * recherchées, copiées ou supprimées. Tout reste sur l'appareil.
 */
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val store = remember { ChuchoteStore.get(context) }
    val dictees by store.dictees.collectAsState()

    var recherche by rememberSaveable { mutableStateOf("") }
    var confirmerToutEffacer by remember { mutableStateOf(false) }

    val visibles = if (recherche.isBlank()) {
        dictees
    } else {
        dictees.filter { it.texte.contains(recherche.trim(), ignoreCase = true) }
    }

    if (confirmerToutEffacer) {
        AlertDialog(
            onDismissRequest = { confirmerToutEffacer = false },
            title = { Text("Effacer l'historique ?") },
            text = { Text("Les ${dictees.size} dictées seront supprimées définitivement.") },
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
                        text = "Chaque texte dicté est gardé sur l'appareil, " +
                                "prêt à être retrouvé et copié. Rien ne quitte le téléphone.",
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
                    TextButton(onClick = { confirmerToutEffacer = true }) {
                        Text("Tout effacer")
                    }
                }
            }
            items(count = visibles.size, key = { visibles[it].id }) { index ->
                val dictee = visibles[index]
                ElevatedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = dictee.texte,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.size(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = DateUtils.getRelativeTimeSpanString(
                                    dictee.creeLe,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                ).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { copier(context, dictee.texte) }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copier"
                                )
                            }
                            IconButton(onClick = { store.supprimerDictee(dictee.id) }) {
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

private fun copier(context: Context, texte: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Dictée", texte))
    // Android 13+ affiche déjà sa propre confirmation de copie.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, "Copié", Toast.LENGTH_SHORT).show()
    }
}
