package app.show.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/**
 * Show's "liquid glass" material (after Apple's 2025 design language), kept monochrome:
 *
 *  1. **Backdrop:** with a [HazeState] the content behind is blurred (RenderEffect on Android 12+;
 *     older devices get Haze's translucent fallback tint, so it still reads as glass).
 *  2. **Body:** a faint white veil so the glass is visible over pure black.
 *  3. **Specular:** a soft top highlight, like light caught on a curved surface.
 *  4. **Rim:** a 1 dp edge that is bright top-left and bottom-right and fades in between,
 *     which gives the lens-like thickness of liquid glass without coloured refraction.
 *
 * Used only where the design calls for it: the floating tab bar, the logomark and Play buttons.
 */
object LiquidGlass {
    fun hazeStyle(strong: Boolean = false) = HazeStyle(
        backgroundColor = Color.Black,
        tint = HazeTint(Color.Black.copy(alpha = if (strong) 0.42f else 0.22f)),
        blurRadius = if (strong) 28.dp else 20.dp,
        noiseFactor = 0.04f,
        fallbackTint = HazeTint(Color(0xE0141416)),
    )

    val Veil = Color.White.copy(alpha = 0.10f)
    val Rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.55f),
        0.35f to Color.White.copy(alpha = 0.10f),
        0.65f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = 0.32f),
        start = Offset.Zero, end = Offset.Infinite,
    )
}

/** Height a floating bar occupies over the content; scrolling screens pad their end by it. */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

/** Applies the liquid-glass layers to [shape]. Pass [haze] for a true backdrop blur. */
fun Modifier.liquidGlass(shape: Shape, haze: HazeState? = null, strong: Boolean = false, elevation: Dp = 0.dp): Modifier {
    var m = this
    if (elevation > 0.dp) m = m.shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    m = m.clip(shape)
    if (haze != null) m = m.hazeChild(state = haze, style = LiquidGlass.hazeStyle(strong))
    return m
        .background(LiquidGlass.Veil, shape)
        .drawWithContent {
            drawContent()
            // Specular: brightest at the top edge, gone by the middle.
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.5f to Color.Transparent), size = size)
        }
        .border(1.dp, LiquidGlass.Rim, shape)
}

/**
 * The glass Play button: a capsule (or a circle with [compact]) with a white play glyph.
 * The only glass element on Home's content; everything else stays flat.
 */
@Composable
fun GlassPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Play",
    haze: HazeState? = null,
    compact: Boolean = false,
    height: Dp = 50.dp,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = if (compact) CircleShape else RoundedCornerShape(50)
    Box(
        modifier
            .then(if (compact) Modifier.size(height) else Modifier.defaultMinSize(minWidth = 150.dp, minHeight = height))
            .lift(interaction, shape)
            .liquidGlass(shape, haze, elevation = 10.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (compact) Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(height * 0.55f))
            else Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                if (content != null) content() else {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}

/** A flat outlined circle for secondary actions (My List, Details): deliberately not glass. */
@Composable
fun OutlineCircleButton(label: String, onClick: () -> Unit, tag: String, size: Dp = 50.dp, content: @Composable BoxScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size).lift(interaction, CircleShape).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides Color.White) { content() } }
}

/** One destination of [FloatingGlassTabBar]. */
data class GlassTab(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * Floating liquid-glass tab bar: a capsule that hovers over the content (which scrolls and blurs
 * underneath), with a glass "lens" that glides to the selected tab. 64 dp tall, 48 dp+ targets.
 */
@Composable
fun FloatingGlassTabBar(
    tabs: List<GlassTab>,
    selectedKey: String,
    onSelect: (GlassTab) -> Unit,
    haze: HazeState?,
    modifier: Modifier = Modifier,
) {
    val selected = tabs.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val reduceMotion = rememberReduceMotion()
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.widthIn(max = 520.dp).fillMaxWidth().height(TabBarHeight)
            .liquidGlass(RoundedCornerShape(50), haze, strong = true, elevation = 18.dp)
            .testTag("nav_bar"),
    ) {
        val itemWidth = maxWidth / tabs.size
        val x by androidx.compose.animation.core.animateDpAsState(
            itemWidth * selected,
            animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f),
            label = "lens",
        )
        // The lens: a brighter glass droplet under the selected tab.
        Box(
            Modifier.offset(x = x).width(itemWidth).fillMaxHeight().padding(5.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, LiquidGlass.Rim, RoundedCornerShape(50)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                val on = i == selected
                val interaction = remember { MutableInteractionSource() }
                androidx.compose.foundation.layout.Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(tab) })
                        .semantics { this.selected = on; contentDescription = tab.label }
                        .testTag("tab_${tab.key}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    val tint = if (on) Color.White else Color.White.copy(alpha = 0.62f)
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                }
            }
        }
    }
}

val TabBarHeight = 64.dp
