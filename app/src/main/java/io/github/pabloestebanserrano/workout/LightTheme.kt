package io.github.pabloestebanserrano.workout

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Monochrome palette matching LightOS — no accent color, by design.
 *
 * Two variants: `Normal` (black background, white text) which is what the
 * Light Phone's display was designed around, and `Inverted` (white
 * background, black text) for e-ink hardware like the Mudita Kompakt, where
 * a black background doesn't render as true black — it's a dim, ghosty grey
 * that's much harder to read than black text on the panel's natural white.
 * Held as a data class + CompositionLocal (same pattern as
 * LocalLightTypography below) rather than a plain object so the whole
 * screen tree can react to the person's Settings choice without every
 * Composable needing its own conditional.
 */
data class LightColorScheme(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
)

val NormalLightColors = LightColorScheme(
    background = Color.Black,
    content = Color.White,
    contentSecondary = Color(0xFFBBBBBB),
)

val InvertedLightColors = LightColorScheme(
    background = Color.White,
    content = Color.Black,
    contentSecondary = Color(0xFF444444),
)

val LocalLightColors = staticCompositionLocalOf { NormalLightColors }

data class LightTypography(
    val title: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val button: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
)

private fun buildTypography(fontFamily: FontFamily) = LightTypography(
    title = TextStyle(fontSize = 45.sp, fontFamily = fontFamily, fontWeight = FontWeight.Light),
    heading = TextStyle(fontSize = 28.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal),
    subheading = TextStyle(fontSize = 20.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal),
    button = TextStyle(fontSize = 19.sp, fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 3.sp),
    detail = TextStyle(fontSize = 18.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal),
    fine = TextStyle(fontSize = 15.sp, fontFamily = fontFamily, fontWeight = FontWeight.Medium),
)

/**
 * LP3 hardware has Akkurat installed as a system font, so on the real device
 * this finds and uses it automatically. Everywhere else (emulator, other
 * phones) it falls back to the platform default — we deliberately don't
 * bundle Akkurat ourselves since it's a commercial font we have no license for.
 */
private fun lightFontFamily(context: Context): FontFamily {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return FontFamily.Default
    val fonts = SystemFonts.getAvailableFonts()
        .mapNotNull { font ->
            val file = font.file ?: return@mapNotNull null
            if (!file.name.startsWith("Akkurat", ignoreCase = true)) return@mapNotNull null
            Font(
                file = file,
                weight = FontWeight(font.style.weight),
                style = if (font.style.slant != 0) FontStyle.Italic else FontStyle.Normal,
            )
        }
    return if (fonts.isNotEmpty()) FontFamily(fonts) else FontFamily.Default
}

val LocalLightTypography = staticCompositionLocalOf { buildTypography(FontFamily.Default) }

/**
 * @param inverted When true, uses the white-background/black-text palette
 * instead of the default black/white — this is the e-ink-friendly Settings
 * toggle. MaterialTheme's colorScheme is still built with `darkColorScheme()`
 * as a base either way; that call just supplies the specific role colors we
 * actually use (background/content), so nothing else about it depends on
 * "dark" in the visual sense.
 */
@Composable
fun LightWorkoutTheme(inverted: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val typography = remember(context) { buildTypography(lightFontFamily(context)) }
    val colors = if (inverted) InvertedLightColors else NormalLightColors
    CompositionLocalProvider(
        LocalLightTypography provides typography,
        LocalLightColors provides colors,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = colors.background,
                surface = colors.background,
                onBackground = colors.content,
                onSurface = colors.content,
                primary = colors.content,
                onPrimary = colors.background,
            ),
            content = content,
        )
    }
}
