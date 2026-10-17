package app.show.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.show.tv.data.Channel
import app.show.tv.ui.theme.ShowColors
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

/**
 * Apple TV-style "top shelf": full-bleed immersive artwork that slowly drifts (Ken Burns),
 * parallaxes against the swipe and against the page scroll ([scrollOffset]), with a title stack
 * anchored bottom-left, a liquid-glass capsule Play button and flat outlined circular actions.
 * Auto-advances every 8 s unless the user turned animations off.
 */
@Composable
fun HeroCarousel(
    featured: List<Channel>,
    favoriteIds: Set<String>,
    onPlay: (Channel) -> Unit,
    onToggleList: (Channel) -> Unit,
    onInfo: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    scrollOffset: () -> Float = { 0f },
) {
    if (featured.isEmpty()) return
    val reduceMotion = rememberReduceMotion()
    // 1.5.3: no motion at all in battery saver, and nothing runs unless Home is actually resumed.
    val calm = reduceMotion || rememberPowerSave()
    val resumed = rememberIsResumed()
    val pager = rememberPagerState { featured.size }
    LaunchedEffect(pager, featured.size, calm, resumed) {
        while (featured.size > 1 && !calm && resumed) {
            delay(8_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % featured.size, animationSpec = tween(900))
        }
    }
    // 1.5.3: Ken Burns is a short settle (1.2 s) when a page lands instead of an infinite 16 s loop. The
    // loop redrew three zoomed, RenderEffect-blurred layers every frame (60 fps forever while Home was
    // open): the main cause of the lag, heat and battery drain reports.
    val zoomAnim = remember { Animatable(BACKDROP_ZOOM) }
    LaunchedEffect(pager.settledPage, calm) {
        if (calm) { zoomAnim.snapTo(BACKDROP_ZOOM); return@LaunchedEffect }
        zoomAnim.snapTo(BACKDROP_ZOOM + 0.08f)
        zoomAnim.animateTo(BACKDROP_ZOOM, tween(1_200, easing = LinearOutSlowInEasing))
    }

    BoxWithConstraints(modifier.fillMaxWidth().testTag("hero")) {
        val wide = maxWidth > 600.dp
        val height = if (wide) 520.dp else 580.dp
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth().height(height).graphicsLayer {
                // Scroll parallax: the shelf moves at half speed and fades as content slides over it.
                val y = scrollOffset()
                translationY = y * 0.5f
                alpha = (1f - y / size.height).coerceIn(0f, 1f)
            },
            beyondViewportPageCount = 1,
        ) { page ->
            val ch = featured[page]
            val pageOffset = (pager.currentPage - page + pager.currentPageOffsetFraction)
            // clipToBounds: the backdrop is scaled ~1.65x and shifted for parallax, so without a clip the
            // NEXT page's artwork spilled across the current page as a grey panel (1.5.0/1.5.1 bug).
            Box(Modifier.fillMaxSize().clipToBounds().semantics { contentDescription = "Featured: ${ch.name}" }) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    Box(Modifier.fillMaxSize().background(ShowColors.tileBrush(ch.name)))
                    ChannelArtwork(
                        ch,
                        Modifier.fillMaxSize().graphicsLayer {
                            val z = if (page == pager.settledPage) zoomAnim.value else BACKDROP_ZOOM
                            scaleX = z; scaleY = z; alpha = 0.6f
                            translationX = pageOffset * size.width * 0.45f // backdrop lags the swipe
                        },
                        logoPadding = 36.dp,
                        backdrop = true, // blurred once on decode (BackdropBlur), not every frame
                    )
                    Box(Modifier.fillMaxSize().background(ShowColors.TopScrim))
                    Box(Modifier.fillMaxSize().background(ShowColors.BottomScrim))
                    if (wide) Box(Modifier.fillMaxSize().background(ShowColors.SideScrim))
                }

                val textAlpha = 1f - (pageOffset.absoluteValue * 1.6f).coerceIn(0f, 1f)
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(start = if (wide) 40.dp else 20.dp, end = 20.dp, bottom = if (wide) 44.dp else 36.dp)
                        .graphicsLayer { alpha = textAlpha; translationY = pageOffset.absoluteValue * 60f },
                ) {
                    TitleTreatment(ch, Modifier.width(if (wide) 260.dp else 188.dp).height(if (wide) 146.dp else 106.dp))
                    Spacer(Modifier.height(16.dp))
                    HeroText(ch, ch.id in favoriteIds, onPlay, onToggleList, onInfo, Modifier.widthIn(max = 560.dp))
                }
            }
        }
        // Capsule page indicator, bottom-right like tvOS.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 14.dp).testTag("hero_dots"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(featured.size) { i ->
                val w by animateDpAsState(if (i == pager.currentPage) 20.dp else 6.dp, label = "dot")
                Box(Modifier.height(4.dp).width(w).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)))
            }
        }
    }
}

private const val BACKDROP_ZOOM = 1.55f

/** True while the system battery saver is on (re-read when it toggles). */
@Composable
internal fun rememberPowerSave(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pm = remember { ctx.getSystemService(android.os.PowerManager::class.java) }
    var on by remember { androidx.compose.runtime.mutableStateOf(pm?.isPowerSaveMode == true) }
    androidx.compose.runtime.DisposableEffect(pm) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { on = pm?.isPowerSaveMode == true }
        }
        val filter = android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return on
}

/** True while the hosting lifecycle is RESUMED (false behind a dialog-less other activity or when stopped). */
@Composable
internal fun rememberIsResumed(): Boolean {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val state by owner.lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
}

/** The channel's logo on a frosted plate, standing in for a film's title art. */
@Composable
private fun TitleTreatment(ch: Channel, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.3f))
            .border(1.dp, ShowColors.Hairline, RoundedCornerShape(16.dp)),
    ) {
        ChannelArtwork(ch, Modifier.fillMaxSize(), logoPadding = 16.dp, monogramSize = 40)
    }
}

@Composable
private fun HeroText(
    ch: Channel,
    inList: Boolean,
    onPlay: (Channel) -> Unit,
    onToggleList: (Channel) -> Unit,
    onInfo: (Channel) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
            Text("LIVE NOW", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(ch.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(ch.categories.filter { it != Channel.UNCATEGORISED }.take(2).joinToString(" · ").ifEmpty { null },
                ch.countries.firstOrNull(), ch.languages.firstOrNull(), ch.resolution).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium, color = ShowColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassPlayButton(onClick = { onPlay(ch) }, label = "Play", modifier = Modifier.testTag("hero_play"))
            OutlineCircleButton(
                if (inList) "Remove from My List" else "Add to My List", onClick = { onToggleList(ch) }, tag = "hero_list",
            ) { Icon(if (inList) Icons.Rounded.Check else Icons.Rounded.Add, null) }
            OutlineCircleButton("Details", onClick = { onInfo(ch) }, tag = "hero_info") { Icon(Icons.Rounded.Info, null) }
        }
    }
}
