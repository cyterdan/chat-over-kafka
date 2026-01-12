package org.github.cyterdan.chat_over_kafka.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Winamp-inspired retro dark theme
private val RetroColorScheme = darkColorScheme(
    // Primary - Neon Green (LCD display, active elements)
    primary = NeonGreen,
    onPrimary = MetallicBlack,
    primaryContainer = NeonGreenDark,
    onPrimaryContainer = NeonGreen,

    // Secondary - Amber (accents, highlights)
    secondary = Amber,
    onSecondary = MetallicBlack,
    secondaryContainer = MetallicGray,
    onSecondaryContainer = Amber,

    // Tertiary - Dim green for subtle accents
    tertiary = NeonGreenDim,
    onTertiary = MetallicBlack,
    tertiaryContainer = MetallicMidGray,
    onTertiaryContainer = NeonGreenDim,

    // Error - Hot red for recording indicator
    error = HotRed,
    onError = Color.White,
    errorContainer = HotRedContainer,
    onErrorContainer = HotRed,

    // Background - Deep dark metallic
    background = MetallicBlack,
    onBackground = TextBright,

    // Surface - Slightly lighter for cards/panels
    surface = MetallicDarkGray,
    onSurface = TextBright,
    surfaceVariant = MetallicGray,
    onSurfaceVariant = TextDim,

    // Outline for borders
    outline = MetallicLightGray,
    outlineVariant = MetallicMidGray,

    // Inverse colors
    inverseSurface = TextBright,
    inverseOnSurface = MetallicBlack,
    inversePrimary = NeonGreenDark,

    // Scrim for overlays
    scrim = Color.Black
)

@Composable
fun ChatoverkafkaTheme(
    darkTheme: Boolean = true,  // Always use dark theme for retro look
    dynamicColor: Boolean = false,  // Disable dynamic colors for consistent retro aesthetic
    content: @Composable () -> Unit
) {
    // Always use our retro color scheme
    val colorScheme = RetroColorScheme

    // Make status bar match our theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = MetallicBlack.toArgb()
            window.navigationBarColor = MetallicBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
