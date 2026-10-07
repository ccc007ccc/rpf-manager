package com.gtav.rpfmanager.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark =
    darkColorScheme(
        primary = Amber,
        onPrimary = Color(0xFF302414),
        primaryContainer = Color(0xFF332B20),
        onPrimaryContainer = Amber,
        secondary = MapMint,
        onSecondary = Graphite,
        tertiary = CollisionOrange,
        onTertiary = Graphite,
        background = Graphite,
        onBackground = Chalk,
        surface = Ink,
        surfaceDim = Graphite,
        surfaceBright = Color(0xFF3B3E42),
        surfaceContainerLowest = Color(0xFF101214),
        surfaceContainerLow = Color(0xFF1A1C1F),
        surfaceContainer = Color(0xFF202327),
        surfaceContainerHigh = Color(0xFF2A2D31),
        surfaceContainerHighest = Color(0xFF34373B),
        onSurface = Chalk,
        surfaceVariant = Color(0xFF25282C),
        onSurfaceVariant = Color(0xFFA3A6AB),
        outline = Color(0xFF5D6065),
        outlineVariant = Color(0xFF34373B),
        error = Color(0xFFEF9A91),
        onError = Graphite,
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF815716),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF5E5CB),
        onPrimaryContainer = Color(0xFF6C4714),
        secondary = Color(0xFF33765D),
        onSecondary = Color.White,
        tertiary = Color(0xFF9C622A),
        onTertiary = Color.White,
        background = Color(0xFFF4F3EF),
        onBackground = Color(0xFF202321),
        surface = Color.White,
        surfaceDim = Color(0xFFDCDDD7),
        surfaceBright = Color(0xFFFAFAF7),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF5F5F0),
        surfaceContainer = Color(0xFFEFEFEA),
        surfaceContainerHigh = Color(0xFFE9EAE4),
        surfaceContainerHighest = Color(0xFFE3E4DE),
        onSurface = Color(0xFF202321),
        surfaceVariant = Color(0xFFECECE7),
        onSurfaceVariant = Color(0xFF656965),
        outline = Color(0xFF848780),
        outlineVariant = Color(0xFFD9DAD4),
        error = Color(0xFFAF4134),
    )

@Composable
fun RpfManagerTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = RpfTypography,
        content = content,
    )
}
