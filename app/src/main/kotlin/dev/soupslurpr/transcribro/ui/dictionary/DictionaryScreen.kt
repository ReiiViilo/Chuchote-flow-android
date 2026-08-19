package dev.soupslurpr.transcribro.ui.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn

/**
 * Le dictionnaire personnel : les mots que la transcription doit connaître
 * (prénoms, noms d'entreprise…) et les corrections automatiques à appliquer
 * à chaque dictée.
 */
@Composable
fun DictionaryScreen() {
    val context = LocalContext.current
    val store = remember { ChuchoteStore.get(context) }
    val entrees by store.dictionnaire.collectAsState()

    var nouveauMot by rememberSaveable { mutableStateOf("") }
    var nouveauRemplacement by rememberSaveable { mutableStateOf("") }

    ScreenLazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ElevatedCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Ajoute les mots que la dictée écorche : prénoms, noms " +
                                "d'entreprise, expressions à toi. Avec seulement un mot, " +
                                "la transcription apprend à le reconnaître. Avec un " +
                                "remplacement, la correction s'applique automatiquement " +
                                "à chaque dictée — par exemple « chichotte » → « Chuchote »."
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = nouveauMot,
                        onValueChange = { nouveauMot = it },
                        singleLine = true,
                        label = { Text("Mot ou expression") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(4.dp))
                    OutlinedTextField(
                        value = nouveauRemplacement,
                        onValueChange = { nouveauRemplacement = it },
                        singleLine = true,
                        label = { Text("Remplacer par (facultatif)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(
                        enabled = nouveauMot.isNotBlank(),
                        onClick = {
                            store.ajouterEntree(nouveauMot, nouveauRemplacement)
                            nouveauMot = ""
                            nouveauRemplacement = ""
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("Ajouter au dictionnaire")
                    }
                }
            }
        }
        if (entrees.isEmpty()) {
            item {
                Text(
                    "Le dictionnaire est vide pour l'instant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(count = entrees.size, key = { entrees[it].id }) { index ->
                val entree = entrees[index]
                ListItem(
                    headlineContent = {
                        Text(
                            text = if (entree.remplacerPar.isEmpty()) {
                                entree.entendu
                            } else {
                                "${entree.entendu} → ${entree.remplacerPar}"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = {
                        Text(
                            if (entree.remplacerPar.isEmpty()) {
                                "Mot à reconnaître"
                            } else {
                                "Correction automatique"
                            }
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { store.supprimerEntree(entree.id) }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Supprimer"
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}
