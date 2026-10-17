package app.show.tv.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
 * anchored bottom-left, a white capsule Play button and frosted circular actions.
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
    val pager = rememberPagerState { featured.size }
    LaunchedEffect(pager, featured.size, reduceMotion) {
        while (featured.size > 1 && !reduceMotion) {
            delay(8_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % featured.size, animationSpec = tween(900))
        }
    }
    val drift = rememberInfiniteTransition(label = "kenBurns")
    val zoom by drift.animateFloat(
        initialValue = 1.55f, targetValue = if (reduceMotion) 1.55f else 1.75f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom",
    )

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
            Box(Modifier.fillMaxSize().semantics { contentDescription = "Featured: ${ch.name}" }) {
                Box(Modifier.fillMaxSize().background(ShowColors.tileBrush(ch.name)))
                ChannelArtwork(
                    ch,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; alpha = 0.6f
                        translationX = pageOffset * size.width * 0.45f // backdrop lags the swipe
                    }.then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier),
                    logoPadding = 36.dp,
                )
                Box(Modifier.fillMaxSize().background(ShowColors.TopScrim))
                Box(Modifier.fillMaxSize().background(ShowColors.BottomScrim))
                if (wide) Box(Modifier.fillMaxSize().background(ShowColors.SideScrim))

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
            ShowPrimaryButton(
                onClick = { onPlay(ch) },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(50.dp).widthIn(min = 150.dp).testTag("hero_play"),
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Play", style = MaterialTheme.typography.labelLarge)
            }
            GlassCircleButton(
                if (inList) "Remove from My List" else "Add to My List", onClick = { onToggleList(ch) }, tag = "hero_list",
            ) { Icon(if (inList) Icons.Rounded.Check else Icons.Rounded.Add, null) }
            GlassCircleButton("Details", onClick = { onInfo(ch) }, tag = "hero_info") { Icon(Icons.Rounded.Info, null) }
        }
    }
}

/** 50 dp frosted circle with a focus lift, for secondary actions over artwork. */
@Composable
fun GlassCircleButton(label: String, onClick: () -> Unit, tag: String, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(50.dp).lift(interaction, CircleShape).clip(CircleShape).background(ShowColors.Glass)
            .border(1.dp, ShowColors.Hairline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}
