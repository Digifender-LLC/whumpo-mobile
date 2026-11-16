package com.whumpo.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Native type and warm surfaces for account entry and the setup workspace. */
@Composable
fun WhumpoEntryTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val background = if (darkTheme) 0xFF1C1A17L else 0xFFFAF7F0L
    val panel = if (darkTheme) 0xFF27231EL else 0xFFFFFFFFL
    val inset = if (darkTheme) 0xFF332C23L else 0xFFF0E9DEL
    val ink = if (darkTheme) 0xFFF8F1E6L else 0xFF29241DL
    val muted = if (darkTheme) 0xFFCBBEACL else 0xFF65594BL
    val line = if (darkTheme) 0xFF574C3FL else 0xFFDCD0BFL
    val outline = if (darkTheme) 0xFFAA9780L else 0xFF887666L
    val accent = if (darkTheme) 0xFFF4CA78L else 0xFF68470CL
    val gold = if (darkTheme) 0xFFE9B95EL else 0xFFE7B759L
    val onGold = 0xFF2B1C06L
    val base = if (darkTheme) WhumpoTokens.Dark else WhumpoTokens.Light
    val colors = WhumpoColors(
        base,
        actionHover = Color(if (darkTheme) 0xFFF8CC7DL else 0xFFF5C675L),
        surfaceSunken = Color(inset),
        inkMuted = Color(muted),
        line = Color(line),
        focusRing = Color(accent),
    )
    val scheme = (if (darkTheme) WhumpoTokens.DarkScheme else WhumpoTokens.LightScheme).toColorScheme()
    CompositionLocalProvider(LocalWhumpoColors provides colors) {
        MaterialTheme(
            colorScheme = scheme.copy(
                background = Color(background), onBackground = Color(ink),
                surface = Color(background), onSurface = Color(ink),
                surfaceVariant = Color(inset), onSurfaceVariant = Color(muted),
                primary = Color(accent), onPrimary = Color(if (darkTheme) onGold else panel),
                primaryContainer = Color(gold), onPrimaryContainer = Color(onGold),
                secondary = Color(accent), onSecondary = Color(if (darkTheme) onGold else panel),
                secondaryContainer = Color(inset), onSecondaryContainer = Color(ink),
                tertiary = Color(accent), onTertiary = Color(if (darkTheme) onGold else panel),
                tertiaryContainer = Color(inset), onTertiaryContainer = Color(ink),
                outline = Color(outline), outlineVariant = Color(line), surfaceTint = Color(accent),
                surfaceDim = Color(inset), surfaceBright = Color(panel),
                surfaceContainerLowest = Color(panel), surfaceContainerLow = Color(panel),
                surfaceContainer = Color(panel), surfaceContainerHigh = Color(inset), surfaceContainerHighest = Color(inset),
                inverseSurface = Color(ink), inverseOnSurface = Color(background),
                inversePrimary = Color(if (darkTheme) 0xFF68470CL else 0xFFF4CA78L),
            ),
            typography = Typography(),
            content = content,
        )
    }
}
