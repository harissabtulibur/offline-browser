package com.offlinecyberdeck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.offlinecyberdeck.core.model.ThemeMode

val DeckBgDark = Color(0xFF0B0F14)
val DeckSurfaceDark = Color(0xFF121820)
val DeckSurfaceElevatedDark = Color(0xFF18212C)
val DeckCyan = Color(0xFF22D3EE)
val DeckTeal = Color(0xFF14B8A6)
val DeckAmber = Color(0xFFF59E0B)
val DeckRed = Color(0xFFEF4444)
val DeckGreen = Color(0xFF10B981)
val DeckBorderDark = Color(0xFF243040)

private val DarkColors = darkColorScheme(
    primary = DeckCyan,
    onPrimary = Color(0xFF042229),
    primaryContainer = Color(0xFF0E3A46),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = DeckTeal,
    onSecondary = Color(0xFF04241F),
    secondaryContainer = Color(0xFF133B36),
    onSecondaryContainer = Color(0xFF99F6E4),
    background = DeckBgDark,
    onBackground = Color(0xFFE6EDF3),
    surface = DeckSurfaceDark,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = DeckSurfaceElevatedDark,
    onSurfaceVariant = Color(0xFF9FB0C3),
    outline = DeckBorderDark,
    error = DeckRed,
    onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF083344),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    background = Color(0xFFF3F6F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val DeckTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun CyberDeckTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = DeckTypography,
        content = content
    )
}
