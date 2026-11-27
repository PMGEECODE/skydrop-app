package com.famage.skydrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── SkyDrop Dark Color Scheme ─────────────────────────────────────────────
private val SkyDropDarkColorScheme = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color.White,
    primaryContainer = SkyBlueDark,
    onPrimaryContainer = DarkTextMain,

    secondary = TealAccentDark,
    onSecondary = DarkBgApp,
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = Color(0xFFCCFBF1),

    tertiary = SuccessGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFD1FAE5),

    background = DarkBgApp,
    onBackground = DarkTextMain,

    surface = DarkBgSurface,
    onSurface = DarkTextMain,
    surfaceVariant = DarkBgElevated,
    onSurfaceVariant = DarkTextSecondary,

    outline = DarkTextMuted,
    outlineVariant = DarkBorderVariant,

    error = DangerRed,
    onError = Color.White,
    errorContainer = ErrorRedContainerDark,
    onErrorContainer = Color(0xFFFECACA),

    inverseSurface = DarkTextMain,
    inverseOnSurface = DarkBgApp,
    inversePrimary = SkyBlueHover,
)

// ─── SkyDrop Light Color Scheme ────────────────────────────────────────────
private val SkyDropLightColorScheme = lightColorScheme(
    primary = SkyBlue,
    onPrimary = Color.White,
    primaryContainer = SkyBlueContainerLight,
    onPrimaryContainer = Color(0xFF0369A1),

    secondary = TealAccent,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = Color(0xFF134E4A),

    tertiary = SuccessGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),

    background = LightBgApp,
    onBackground = LightTextMain,

    surface = LightBgSurface,
    onSurface = LightTextMain,
    surfaceVariant = LightBgElevated,
    onSurfaceVariant = LightTextSecondary,

    outline = LightTextMuted,
    outlineVariant = LightBorder,

    error = DangerRed,
    onError = Color.White,
    errorContainer = ErrorRedContainerLight,
    onErrorContainer = Color(0xFF7F1D1D),

    inverseSurface = LightTextMain,
    inverseOnSurface = LightBgSurface,
    inversePrimary = SkyBlueHover,
)

/**
 * SkyDrop application theme.
 *
 * @param themePreference Explicit override: [ThemePreference.SYSTEM] follows
 *   the device setting, [ThemePreference.DARK] / [ThemePreference.LIGHT] force
 *   that mode regardless of the system setting.
 * @param content Composable content rendered inside this theme.
 */
@Composable
fun SkyDropTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        ThemePreference.DARK   -> true
        ThemePreference.LIGHT  -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (darkTheme) SkyDropDarkColorScheme else SkyDropLightColorScheme,
        typography = Typography,
        content = content,
    )
}