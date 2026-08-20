package dev.soupslurpr.transcribro.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.dataStore
import dev.soupslurpr.transcribro.overlay.FloatingWidgetService
import dev.soupslurpr.transcribro.overlay.TextInsertionAccessibilityService
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.remote.RemoteTranscriptionSettings
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsStartScreen(
    onClickLicense: () -> Unit,
    onClickPrivacyPolicy: () -> Unit,
    onClickCredits: () -> Unit,
    onClickDonate: () -> Unit,
) {
    val preferencesViewModel: PreferencesViewModel = viewModel(
        factory = PreferencesViewModel.PreferencesViewModelFactory(LocalContext.current.dataStore)
    )

    val preferencesUiState by preferencesViewModel.uiState.collectAsState()

    val localUriHandler = LocalUriHandler.current

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val microphonePermissionState = rememberPermissionState(
        Manifest.permission.RECORD_AUDIO
    )

    var isMyInputMethodEnabled by rememberSaveable {
        mutableStateOf(isMyInputMethodEnabled(context))
    }

    var canDrawOverlays by rememberSaveable {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    var isTextInsertionEnabled by rememberSaveable {
        mutableStateOf(TextInsertionAccessibilityService.isConnected())
    }

    val remoteSettings = remember { RemoteTranscriptionSettings(context) }
    var remoteEnabled by rememberSaveable { mutableStateOf(remoteSettings.enabled) }
    var remoteBaseUrl by rememberSaveable { mutableStateOf(remoteSettings.baseUrl) }
    var remoteToken by rememberSaveable { mutableStateOf(remoteSettings.token) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                isMyInputMethodEnabled = isMyInputMethodEnabled(context)
                // Les autorisations se donnent dans les réglages système : il
                // faut donc les relire au retour dans l'application.
                canDrawOverlays = Settings.canDrawOverlays(context)
                isTextInsertionEnabled = TextInsertionAccessibilityService.isConnected()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ScreenLazyColumn(
        modifier = Modifier
            .fillMaxSize()
    ) {
        item {
            SettingsCategory("Widget de dictée")
        }
        item {
            ElevatedCard {
                Column(
                    Modifier.padding(16.dp)
                ) {
                    Text(
                        "Widget de dictée flottant : une bulle posée par-dessus toutes les applications. " +
                                "Touche-la — ou secoue le téléphone — parle, puis confirme avec ✓. " +
                                "Le texte s'insère dans le champ de saisie actif."
                    )
                    Spacer(Modifier.padding(8.dp))
                    FilledTonalButton(
                        enabled = !canDrawOverlays,
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    ) {
                        Text("1. Autoriser l'affichage par-dessus les autres apps")
                    }
                    Spacer(Modifier.padding(4.dp))
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    ) {
                        Text(
                            if (isTextInsertionEnabled) {
                                "2. Accessibilité activée ✓ (rouvrir les réglages)"
                            } else {
                                "2. Activer Chuchote Flow dans Accessibilité"
                            }
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    FilledTonalButton(
                        enabled = canDrawOverlays && microphonePermissionState.status.isGranted,
                        onClick = {
                            context.startForegroundService(
                                Intent(context, FloatingWidgetService::class.java)
                            )
                        }
                    ) {
                        Text("3. Démarrer le widget")
                    }
                    Spacer(Modifier.padding(4.dp))
                    FilledTonalButton(
                        onClick = {
                            context.stopService(
                                Intent(context, FloatingWidgetService::class.java)
                            )
                        }
                    ) {
                        Text("Arrêter le widget")
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        "Sans l'étape 2, le texte dicté est déposé dans le presse-papiers " +
                                "au lieu d'être écrit directement."
                    )
                }
            }
        }
        item {
            SettingsCategory("Transcription")
        }
        item {
            ElevatedCard {
                Column(
                    Modifier.padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = remoteEnabled,
                            onCheckedChange = {
                                remoteEnabled = it
                                remoteSettings.enabled = it
                            }
                        )
                        Spacer(Modifier.padding(8.dp))
                        Text("Transcription par relais")
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        "Envoie la dictée à ton relais, bien plus rapide que le téléphone. " +
                                "Si le relais ne répond pas, la transcription se fait sur l'appareil " +
                                "comme d'habitude — une dictée n'est jamais perdue."
                    )
                    Spacer(Modifier.padding(8.dp))
                    OutlinedTextField(
                        value = remoteBaseUrl,
                        onValueChange = { valeur ->
                            // Un lien de configuration complet (adresse#jeton)
                            // collé ici remplit les deux champs d'un coup —
                            // c'est le format produit par « Partager ».
                            if (valeur.contains('#')) {
                                val adresse = valeur.substringBefore('#').trim()
                                val jeton = valeur.substringAfter('#').trim()
                                remoteBaseUrl = adresse
                                remoteSettings.baseUrl = adresse
                                if (jeton.isNotEmpty()) {
                                    remoteToken = jeton
                                    remoteSettings.token = jeton
                                }
                            } else {
                                remoteBaseUrl = valeur
                                remoteSettings.baseUrl = valeur
                            }
                        },
                        singleLine = true,
                        label = { Text("Adresse du relais (ou lien complet)") },
                        placeholder = { Text("https://mon-relais.vercel.app") },
                        supportingText = {
                            Text("Astuce : colle un lien de configuration « adresse#jeton » et les deux champs se remplissent.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = remoteToken,
                        onValueChange = {
                            remoteToken = it
                            remoteSettings.token = it
                        },
                        singleLine = true,
                        label = { Text("Jeton") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (remoteBaseUrl.isNotBlank() && remoteToken.isNotBlank()) {
                        Spacer(Modifier.padding(4.dp))
                        FilledTonalButton(
                            onClick = {
                                val lien = "${remoteBaseUrl.trim().trimEnd('/')}#${remoteToken.trim()}"
                                val partage = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, lien)
                                }
                                context.startActivity(
                                    Intent.createChooser(partage, "Partager la configuration du relais")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        ) {
                            Text("Partager la configuration")
                        }
                        Spacer(Modifier.padding(2.dp))
                        Text(
                            "Le lien contient le jeton actuellement saisi. Pour équiper " +
                                    "quelqu'un d'autre, saisis d'abord son jeton à lui, partage, " +
                                    "puis remets le tien.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        "Tant que ce réglage est désactivé, l'application n'envoie rien : " +
                                "tout reste sur l'appareil."
                    )
                }
            }
        }
        item {
            ElevatedCard {
                Column(
                    Modifier.padding(16.dp)
                ) {
                    Text(
                        "Le micro est indispensable : c'est lui qui permet de dicter. " +
                                "Choisir « Pendant l'utilisation de l'app » suffit.",
                    )
                    Spacer(Modifier.padding(8.dp))
                    FilledTonalButton(
                        enabled = !microphonePermissionState.status.isGranted,
                        onClick = {
                            microphonePermissionState.launchPermissionRequest()
                        }
                    ) {
                        Text(
                            if (microphonePermissionState.status.isGranted) {
                                "Micro autorisé ✓"
                            } else {
                                "Autoriser le micro"
                            }
                        )
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        "Le réglage système « application de saisie vocale » (Système > Langues > " +
                                "Saisie vocale) n'est pas nécessaire : le widget et le clavier de Chuchote " +
                                "s'adressent directement à leur propre moteur de transcription. Beaucoup " +
                                "d'appareils, dont les Samsung, ne permettent d'ailleurs pas d'y choisir une " +
                                "application tierce. Ce réglage ne concerne que les autres apps qui demandent " +
                                "la saisie vocale du système, comme le bouton micro du clavier d'origine."
                    )
                }
            }
        }
        item {
            SettingsCategory(
                stringResource(R.string.voice_input_keyboard_setting_category)
            )
        }
        item {
            if (!isMyInputMethodEnabled) {
                ElevatedCard {
                    Column(
                        Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Pour dicter directement depuis le clavier, active le clavier " +
                                    "de saisie vocale de Chuchote Flow dans les réglages système."
                        )
                        Spacer(Modifier.padding(8.dp))
                        FilledTonalButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Ouvrir les réglages du clavier")
                        }
                    }
                }
            }
        }
        item {
            val preference = preferencesUiState.autoSwitchToPreviousInputMethod
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_switch_to_previous_input_method_setting_name),
                description = stringResource(id = R.string.auto_switch_to_previous_input_method_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            val preference = preferencesUiState.autoStopRecognition
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_stop_recognition_setting_name),
                description = stringResource(id = R.string.auto_stop_recognition_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            val preference = preferencesUiState.autoStartRecognition
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_start_recognition_setting_name),
                description = stringResource(id = R.string.auto_start_recognition_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            val preference = preferencesUiState.autoSendTranscription
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_send_transcription_setting_name),
                description = stringResource(id = R.string.auto_send_transcription_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            SettingsCategory(
                stringResource(R.string.theme)
            )
        }
        item {
            val preference = preferencesUiState.pitchBlackBackground
            SettingsSwitchItem(
                name = stringResource(id = R.string.pitch_black_background_setting_name),
                description = stringResource(id = R.string.pitch_black_background_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            SettingsCategory(
                stringResource(R.string.about_setting_category)
            )
        }
        item {
            SettingsIconItem(
                name = "Code source de Chuchote Flow",
                description = "Voir le code source sur GitHub",
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                onClick = {
                    localUriHandler.openUri("https://github.com/ReiiViilo/Chuchote-Flow-Android")
                }
            )
        }
        item {
            SettingsIconItem(
                name = "Soutenir Transcribro",
                description = "Chuchote Flow est bâti sur Transcribro, le projet libre de soupslurpr",
                icon = Icons.Filled.VolunteerActivism,
                onClick = onClickDonate
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.license_setting_name),
                description = stringResource(id = R.string.license_setting_description),
                icon = Icons.Filled.Info,
                onClick = onClickLicense
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.privacy_policy_setting_name),
                description = stringResource(id = R.string.privacy_policy_setting_description),
                icon = Icons.Filled.Info,
                onClick = onClickPrivacyPolicy
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.credits_setting_name),
                description = stringResource(id = R.string.credits_setting_description),
                icon = Icons.Filled.Info,
                onClick = onClickCredits
            )
        }
    }
}

fun isMyInputMethodEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    val enabledInputMethods = imm.enabledInputMethodList

    val myInputMethodPackageName = context.packageName

    for (inputMethod in enabledInputMethods) {
        if (myInputMethodPackageName == inputMethod.packageName) {
            return true
        }
    }

    return false
}

@Composable
fun SettingsCategory(category: String) {
    Text(
        text = category,
        modifier = Modifier.padding(top = 8.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun SettingsSwitchItem(
    modifier: Modifier = Modifier,
    name: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = modifier
            .toggleable(
                value = checked,
                onValueChange = { onCheckedChange(it) }
            ),
        headlineContent = {
            Text(
                name,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = run {
            if (description != null) {
                { Text(description) }
            } else {
                null
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null
            )
        }
    )
}

@Composable
fun SettingsIconItem(
    modifier: Modifier = Modifier,
    name: String,
    description: String? = null,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        modifier = modifier
            .clickable(
                onClick = onClick
            ),
        headlineContent = {
            Text(
                name,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = run {
            if (description != null) {
                { Text(description) }
            } else {
                null
            }
        },
        trailingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        }
    )
}
