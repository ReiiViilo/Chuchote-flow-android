package dev.soupslurpr.transcribro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel

/**
 * Thème sombre aux couleurs de Chuchote Flow : la nuit bleutée du logo en
 * fond, le cyan de l'orbe en accent. Les couleurs dynamiques d'Android sont
 * volontairement écartées — l'application garde son identité quel que soit
 * le fond d'écran.
 */
private val DarkColorScheme = darkColorScheme(
    primary = CyanChuchote,
    onPrimary = SurCyanChuchote,
    primaryContainer = ConteneurCyan,
    onPrimaryContainer = SurConteneurCyan,
    secondary = IndigoChuchote,
    onSecondary = SurIndigoChuchote,
    tertiary = LavandeChuchote,
    background = NuitChuchote,
    onBackground = SurNuitChuchote,
    surface = NuitChuchote,
    onSurface = SurNuitChuchote,
    surfaceVariant = VarianteNuit,
    onSurfaceVariant = SurVarianteNuit,
    surfaceContainerLowest = Color(0xFF0B0B20),
    surfaceContainerLow = Color(0xFF171735),
    surfaceContainer = Color(0xFF1B1B3C),
    surfaceContainerHigh = Color(0xFF212145),
    surfaceContainerHighest = Color(0xFF26264E),
)

/**
 * Thème clair assorti : mêmes teintes, portées sur fond pâle.
 */
private val LightColorScheme = lightColorScheme(
    primary = CyanProfond,
    primaryContainer = ConteneurCyanClair,
    onPrimaryContainer = SurConteneurCyanClair,
    secondary = IndigoProfond,
    tertiary = LavandeProfonde,
)

@Composable
fun TranscribroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preferencesViewModel: PreferencesViewModel,
    content: @Composable () -> Unit
) {
    val settingsUiState by preferencesViewModel.uiState.collectAsState()

    val pitchBlackBackground = settingsUiState.pitchBlackBackground.second.value and darkTheme

    val colorScheme = when {
        darkTheme -> {
            if (pitchBlackBackground) {
                DarkColorScheme.copy(
                    background = Color.Black,
                    surface = Color.Black
                )
            } else {
                DarkColorScheme
            }
        }

        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
