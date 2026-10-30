package com.audipp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.audipp.domain.model.ThemeMode

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

val AudippLightColors: ColorScheme = lightColorScheme(
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF161D28),
    surface = Color.White,
    onSurface = Color(0xFF161D28),
    surfaceContainer = Color(0xFFECEFF3),
    surfaceVariant = Color(0xFFECEFF3),
    onSurfaceVariant = Color(0xFF526072),
    outline = Color(0xFF526072),
    outlineVariant = Color(0xFFDDE2E9),
    primary = Color(0xFF265E9A),
    onPrimary = Color.White,
    secondary = Color(0xFF1B2635),
    onSecondary = Color.White,
)

val AudippDarkColors: ColorScheme = darkColorScheme(
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFF7F8FA),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFF242833),
    surfaceVariant = Color(0xFF242833),
    onSurfaceVariant = Color(0xFFB8BEC9),
    outline = Color(0xFFB8BEC9),
    outlineVariant = Color(0xFF353A45),
    primary = Color(0xFF9AC8FF),
    onPrimary = Color(0xFF13141A),
    secondary = Color(0xFFF5F2F1),
    onSecondary = Color(0xFF13141A),
)

object AudippSpacing {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 24.dp
    val screenVertical = 32.dp
    val contentMaxWidth = 600.dp
    val minimumTouchTarget = 48.dp
}

object AudippMotion {
    const val stateChangeMillis = 150
    const val navigationMillis = 270
}

val AudippTypography = Typography()

@Composable
fun AudippTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (mode.isDark(systemDark)) AudippDarkColors else AudippLightColors,
        typography = AudippTypography,
        content = content,
    )
}
