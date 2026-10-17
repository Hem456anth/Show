package app.show.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Show palette: strictly black and white on deep ink. Interactive accents are white with black
 * content, secondary actions are black with a white outline. The only hue left is [Danger], kept
 * for error states so they don't rely on wording alone.
 */
object ShowColors {
    // 1.4.0: Apple TV-style true-black canvas with neutral (hue-free) greys.
    val Ink = Color(0xFF000000)
    val Night = Color(0xFF0E0E10)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceHigh = Color(0xFF2C2C2E)
    val Accent = Color.White
    val OnAccent = Color.Black
    /** Secondary label grey: 7.9:1 on black, so captions pass WCAG AAA. */
    val Mist = Color(0xFFA1A1A6)
    val Danger = Color(0xFFFF5370)
    /** Frosted-glass fill and hairline for buttons, chips and bars that float over artwork. */
    val Glass = Color.White.copy(alpha = 0.14f)
    val GlassStrong = Color(0xE6121214)
    val Hairline = Color.White.copy(alpha = 0.10f)

    val AccentBrush = androidx.compose.ui.graphics.SolidColor(Accent)
    val BottomScrim = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Ink.copy(alpha = 0.35f), 0.8f to Ink.copy(alpha = 0.85f), 1f to Ink)
    val SideScrim = Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.8f), Color.Transparent))
    val TopScrim = Brush.verticalGradient(listOf(Ink.copy(alpha = 0.75f), Color.Transparent))

    /** Deterministic graphite backdrop for channels (monochrome, varied by seed) so tiles stay distinguishable. */
    fun tileBrush(seed: String): Brush {
        val v = 0.16f + ((seed.hashCode() and 0x7fffffff) % 14) / 100f // 0.16..0.29
        return Brush.linearGradient(listOf(Color.hsv(0f, 0f, v), Color.hsv(0f, 0f, v * 0.45f)))
    }
}

private val scheme = darkColorScheme(
    primary = ShowColors.Accent,
    onPrimary = ShowColors.OnAccent,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = ShowColors.Accent,
    onSecondary = ShowColors.OnAccent,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    tertiary = ShowColors.Accent,
    onTertiary = ShowColors.OnAccent,
    background = ShowColors.Ink,
    onBackground = Color.White,
    surface = ShowColors.Night,
    onSurface = Color.White,
    surfaceVariant = ShowColors.Surface,
    onSurfaceVariant = ShowColors.Mist,
    surfaceContainer = ShowColors.Night,
    surfaceContainerHigh = ShowColors.Surface,
    surfaceContainerHighest = ShowColors.SurfaceHigh,
    error = ShowColors.Danger,
    outline = Color(0xFF3A3A3C),
)

/** Apple-style type ramp: Large Title 34, Title 2 22, Headline 17, Body 15, Caption 12. */
private val type = Typography(
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp),
)

@Composable
fun ShowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
