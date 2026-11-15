package pl.btmap.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Colours outside Material's scheme: signal quality, cards, data accents. */
@Immutable
data class BtmapColors(
    val isDark: Boolean,
    val good: Color,
    val fair: Color,
    val poor: Color,
    val bad: Color,
    val muted: Color,
    val faint: Color,
    val card: Color,
    val cardRaised: Color,
    val divider: Color,
    val accent: Color,
    val meterTrack: Color,
) {
    /** Operator brand colours are tuned for dark backgrounds; darken them on light ones. */
    fun brand(color: Color): Color = if (isDark) color else lerp(color, Color.Black, 0.28f)
}

private val BrandGreen = Color(0xFF176F5B)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF52D6A9),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF123B31),
    onPrimaryContainer = Color(0xFFB4F1DA),
    background = Color(0xFF090B0A),
    onBackground = Color(0xFFEEF1EF),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFEEF1EF),
    surfaceVariant = Color(0xFF181C1A),
    onSurfaceVariant = Color(0xFF98A29D),
    outline = Color(0xFF272D2A),
)

private val LightScheme = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F1E7),
    onPrimaryContainer = Color(0xFF0B3B2F),
    background = Color(0xFFF3F5F4),
    onBackground = Color(0xFF111413),
    surface = Color.White,
    onSurface = Color(0xFF111413),
    surfaceVariant = Color(0xFFEDF0EE),
    onSurfaceVariant = Color(0xFF5B6560),
    outline = Color(0xFFDCE1DE),
)

private val DarkExtra = BtmapColors(
    isDark = true,
    good = Color(0xFF52D6A9), fair = Color(0xFFD4D65A), poor = Color(0xFFF2A54A), bad = Color(0xFFF2675F),
    muted = Color(0xFF8A938F), faint = Color(0xFF5A635F),
    card = Color(0xFF111513), cardRaised = Color(0xFF181D1B), divider = Color(0xFF212725),
    accent = Color(0xFF52D6A9), meterTrack = Color(0xFF232A27),
)

private val LightExtra = BtmapColors(
    isDark = false,
    good = Color(0xFF119067), fair = Color(0xFF8C9A10), poor = Color(0xFFD27A1F), bad = Color(0xFFD23F3A),
    muted = Color(0xFF6B7570), faint = Color(0xFFA3ABA7),
    card = Color.White, cardRaised = Color(0xFFF4F6F5), divider = Color(0xFFE5E9E7),
    accent = BrandGreen, meterTrack = Color(0xFFE6EAE8),
)

private val BaseTypography = Typography()

/** Tabular figures keep columns of numbers from jittering on every refresh. */
private val Tnum = "tnum"

private val BtmapTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum, letterSpacing = (-1).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFeatureSettings = Tnum),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFeatureSettings = Tnum),
    bodySmall = BaseTypography.bodySmall.copy(fontFeatureSettings = Tnum),
    labelLarge = BaseTypography.labelLarge.copy(fontFeatureSettings = Tnum),
    labelMedium = BaseTypography.labelMedium.copy(fontFeatureSettings = Tnum, fontWeight = FontWeight.SemiBold),
    labelSmall = BaseTypography.labelSmall.copy(fontFeatureSettings = Tnum, letterSpacing = 0.6.sp),
)

val LocalBtmapColors = staticCompositionLocalOf { DarkExtra }

@Composable
fun BtmapTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalBtmapColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, typography = BtmapTypography) {
            // Surface sets LocalContentColor, so plain Text follows the theme.
            Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

object Btmap {
    val colors: BtmapColors
        @Composable get() = LocalBtmapColors.current

    /** Large numeric readout style (signal hero). */
    val numeric: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
}
