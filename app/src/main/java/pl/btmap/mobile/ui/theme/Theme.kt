package pl.btmap.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Signal-quality and data colours that are not part of Material's scheme. */
@Immutable
data class BtmapColors(
    val good: Color,
    val fair: Color,
    val poor: Color,
    val bad: Color,
    val muted: Color,
    val card: Color,
    val divider: Color,
    val station: Color,
)

private val BrandGreen = Color(0xFF176F5B)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD1A5),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F3B30),
    onPrimaryContainer = Color(0xFFB4F1DA),
    background = Color(0xFF0B0D0C),
    onBackground = Color(0xFFECEFED),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFECEFED),
    surfaceVariant = Color(0xFF1A1E1C),
    onSurfaceVariant = Color(0xFF9AA39F),
    outline = Color(0xFF2A302D),
)

private val LightScheme = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F0E6),
    onPrimaryContainer = Color(0xFF0B3B2F),
    background = Color(0xFFF7F8F7),
    onBackground = Color(0xFF131614),
    surface = Color.White,
    onSurface = Color(0xFF131614),
    surfaceVariant = Color(0xFFEEF1EF),
    onSurfaceVariant = Color(0xFF5C6661),
    outline = Color(0xFFDDE2DF),
)

private val DarkExtra = BtmapColors(
    good = Color(0xFF4FD1A5), fair = Color(0xFFC9D35A), poor = Color(0xFFF2A94B), bad = Color(0xFFF26B6B),
    muted = Color(0xFF7D8682), card = Color(0xFF141816), divider = Color(0xFF232826), station = Color(0xFF4FD1A5),
)

private val LightExtra = BtmapColors(
    good = Color(0xFF14946E), fair = Color(0xFF8A9A14), poor = Color(0xFFD9822B), bad = Color(0xFFD64545),
    muted = Color(0xFF7A847F), card = Color.White, divider = Color(0xFFE6EAE8), station = BrandGreen,
)

val LocalBtmapColors = staticCompositionLocalOf { DarkExtra }

@Composable
fun BtmapTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBtmapColors provides if (dark) DarkExtra else LightExtra) {
        val scheme = if (dark) DarkScheme else LightScheme
        MaterialTheme(colorScheme = scheme) {
            // Surface sets LocalContentColor, so plain Text follows the theme instead of defaulting to black.
            androidx.compose.material3.Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}

object Btmap {
    val colors: BtmapColors
        @Composable get() = LocalBtmapColors.current
}
