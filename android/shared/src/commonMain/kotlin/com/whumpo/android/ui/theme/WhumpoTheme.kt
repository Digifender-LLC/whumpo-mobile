package com.whumpo.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.whumpo.shared.resources.Res
import com.whumpo.shared.resources.inter_medium
import com.whumpo.shared.resources.inter_regular
import com.whumpo.shared.resources.inter_semibold
import com.whumpo.shared.resources.poppins_bold
import com.whumpo.shared.resources.poppins_medium

/** Roles Material 3 lacks, plus the fixed brand six for illustration. Values come from [WhumpoTokens]. */
class WhumpoColors(p: WhumpoPalette) {
    val actionHover = Color(p.actionHover)
    val readySoft = Color(p.readySoft)
    val readyInk = Color(p.readyInk)
    val sparkSoft = Color(p.sparkSoft)
    val sparkInk = Color(p.sparkInk)
    val surfaceSunken = Color(p.surfaceSunken)
    val inkMuted = Color(p.inkMuted)
    val line = Color(p.line)
    val focusRing = Color(p.focusRing)
    val skyBlue = Color(WhumpoTokens.BrandSkyBlue)
    val deepSky = Color(WhumpoTokens.BrandDeepSky)
    val cloudCoral = Color(WhumpoTokens.BrandCloudCoral)
    val sunshine = Color(WhumpoTokens.BrandSunshine)
    val mint = Color(WhumpoTokens.BrandMint)
    val cloudWhite = Color(WhumpoTokens.BrandCloudWhite)
}

val LocalWhumpoColors = staticCompositionLocalOf { WhumpoColors(WhumpoTokens.Light) }

/** Uppercase section label, used sparingly (not an M3 role). */
val LocalWhumpoOverline = staticCompositionLocalOf { TextStyle.Default }

object WhumpoTheme {
    val colors: WhumpoColors @Composable @ReadOnlyComposable get() = LocalWhumpoColors.current
    val overline: TextStyle @Composable @ReadOnlyComposable get() = LocalWhumpoOverline.current
}

/** Motion tokens as Compose values. Only opacity and transform should animate with them. */
object WhumpoMotion {
    const val FastMs = WhumpoTokens.DurationFastMs
    const val BaseMs = WhumpoTokens.DurationBaseMs
    const val SlowMs = WhumpoTokens.DurationSlowMs
    val Standard = bezier(WhumpoTokens.EaseStandard)
    val Enter = bezier(WhumpoTokens.EaseEnter)
    val Exit = bezier(WhumpoTokens.EaseExit)
    private fun bezier(b: WhumpoBezier) = CubicBezierEasing(b.x1, b.y1, b.x2, b.y2)
}

// Every mapped role is set, so lightColorScheme's defaults only fill the unmapped *Fixed roles.
internal fun WhumpoScheme.toColorScheme(): ColorScheme {
    fun c(v: Long) = Color(v)
    return lightColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        inversePrimary = c(inversePrimary),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(surfaceTint),
        inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        outline = c(outline), outlineVariant = c(outlineVariant), scrim = c(scrim),
        surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest), surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
    )
}

internal val WhumpoShapes = Shapes(
    extraSmall = RoundedCornerShape(WhumpoTokens.RadiusSmDp.dp),
    small = RoundedCornerShape(WhumpoTokens.RadiusMdDp.dp),
    medium = RoundedCornerShape(WhumpoTokens.RadiusMdDp.dp),
    large = RoundedCornerShape(WhumpoTokens.RadiusLgDp.dp),
    extraLarge = RoundedCornerShape(WhumpoTokens.RadiusXlDp.dp),
)

@Composable
private fun whumpoTypography(): Pair<Typography, TextStyle> {
    val poppins = FontFamily(
        Font(Res.font.poppins_medium, FontWeight.Medium),
        Font(Res.font.poppins_bold, FontWeight.Bold),
    )
    val inter = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
    )
    fun s(t: WhumpoTypeToken) = TextStyle(
        fontFamily = if (t.display) poppins else inter,
        fontSize = t.size.sp,
        lineHeight = t.line.sp,
        fontWeight = FontWeight(t.weight),
        letterSpacing = t.tracking.em,
    )
    val typography = Typography(
        displayLarge = s(WhumpoTokens.TypeHero),
        displayMedium = s(WhumpoTokens.TypeDisplay),
        displaySmall = s(WhumpoTokens.TypeTitle),
        headlineLarge = s(WhumpoTokens.TypeTitle),
        headlineMedium = s(WhumpoTokens.TypeTitle),
        headlineSmall = s(WhumpoTokens.TypeHeading),
        titleLarge = s(WhumpoTokens.TypeWordmark),
        titleMedium = s(WhumpoTokens.TypeHeading),
        titleSmall = s(WhumpoTokens.TypeLabel),
        bodyLarge = s(WhumpoTokens.TypeBody),
        bodyMedium = s(WhumpoTokens.TypeBodySm),
        bodySmall = s(WhumpoTokens.TypeCaption),
        labelLarge = s(WhumpoTokens.TypeLabel),
        labelMedium = s(WhumpoTokens.TypeCaption),
        labelSmall = s(WhumpoTokens.TypeCaption),
    )
    return typography to s(WhumpoTokens.TypeOverline)
}

/** Whumpo Material 3 theme. Dynamic colour is deliberately not used (docs/android/app-ux.md). */
@Composable
fun WhumpoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val (typography, overline) = whumpoTypography()
    CompositionLocalProvider(
        LocalWhumpoColors provides WhumpoColors(if (darkTheme) WhumpoTokens.Dark else WhumpoTokens.Light),
        LocalWhumpoOverline provides overline,
    ) {
        MaterialTheme(
            colorScheme = (if (darkTheme) WhumpoTokens.DarkScheme else WhumpoTokens.LightScheme).toColorScheme(),
            typography = typography,
            shapes = WhumpoShapes,
            content = content,
        )
    }
}
