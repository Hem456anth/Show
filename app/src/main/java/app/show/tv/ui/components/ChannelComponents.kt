package app.show.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.show.tv.data.Channel
import app.show.tv.ui.theme.ShowColors
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Ids of channels whose link failed recently; cards dim and badge them. */
val LocalUnavailableIds = compositionLocalOf { emptySet<String>() }

/**
 * Logo over a seeded gradient; falls back to a monogram when artwork is missing or broken.
 *
 * 1.5.3: plain [AsyncImage] (SubcomposeAsyncImage ran a subcomposition per tile, the main cause of
 * scroll jank in rows of hundreds of cards) and decoded at the tile's measured size instead of a fixed
 * 480x270. [backdrop] decodes a tiny 48x27 copy: drawn full-bleed it is naturally soft, replacing the
 * per-frame RenderEffect blur the hero used to run.
 */
@Composable
fun ChannelArtwork(channel: Channel, modifier: Modifier = Modifier, logoPadding: Dp = 14.dp, monogramSize: Int = 28, backdrop: Boolean = false) {
    Box(modifier.background(ShowColors.tileBrush(channel.name)), contentAlignment = Alignment.Center) {
        val ctx = LocalContext.current
        var failed by remember(channel.logoUrl) { mutableStateOf(false) }
        if (channel.logoUrl == null || failed) {
            if (!backdrop) Text(
                channel.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Black,
                fontSize = androidx.compose.ui.unit.TextUnit(monogramSize.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp),
            )
        } else {
            val request = remember(channel.logoUrl, backdrop) {
                ImageRequest.Builder(ctx).data(channel.logoUrl).allowHardware(true)
                    .apply { if (backdrop) size(48, 27) }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = if (backdrop) androidx.compose.ui.graphics.FilterQuality.High else androidx.compose.ui.graphics.FilterQuality.Low,
                modifier = Modifier.fillMaxSize().padding(logoPadding),
                onError = { failed = true },
            )
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasis) Color.Black else Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (emphasis) ShowColors.Accent else Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * 16:9 tile in the Apple TV style: rounded artwork that lifts when focused, sinks when pressed,
 * and toggles My List on long-press (with a haptic tick) like a context-menu shortcut.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 168.dp,
    isFavorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val offline = channel.id in LocalUnavailableIds.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .semantics(mergeDescendants = true) {
                contentDescription = "Channel ${channel.name}" + (if (isFavorite) ", in My List" else "") + if (offline) ", recently unavailable" else ""
            }
            .testTag("channel_card"),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .lift(interaction, shape)
                .clip(shape)
                .combinedClickable(
                    interactionSource = interaction, indication = null, onClick = onClick,
                    onLongClickLabel = if (onLongClick != null) (if (isFavorite) "Remove from My List" else "Add to My List") else null,
                    onLongClick = onLongClick?.let { cb -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); cb() } },
                )
                .border(1.dp, ShowColors.Hairline, shape)
                .graphicsLayer { alpha = if (offline) 0.5f else 1f },
        ) {
            ChannelArtwork(channel, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                channel.resolution?.let { if (ChannelResolution.isHd(it)) Badge("HD", emphasis = true) }
                if (channel.isGeoBlocked) Badge("GEO")
            }
            if (isFavorite) Icon(
                Icons.Rounded.Bookmark, null, tint = ShowColors.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp),
            )
            LiveCapsule(offline, Modifier.align(Alignment.BottomStart).padding(7.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            channel.subtitle(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "News · India" — the best two facts for a caption, skipping placeholders. */
fun Channel.subtitle(): String =
    listOfNotNull(categories.firstOrNull { it != Channel.UNCATEGORISED } ?: languages.firstOrNull(), countries.firstOrNull()).joinToString(" · ")

/** Frosted "● LIVE" capsule (or OFFLINE for a recently dead link). */
@Composable
fun LiveCapsule(offline: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!offline) { Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color.White)); Spacer(Modifier.width(4.dp)) }
        Text(if (offline) "OFFLINE" else "LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

object ChannelResolution {
    fun isHd(res: String) = (res.filter(Char::isDigit).toIntOrNull() ?: 0) >= 720
}

/** A titled shelf: Apple TV-style "Title ›" header (tappable for See All) over a lazy row. */
@Composable
fun ChannelRow(
    title: String,
    channels: List<Channel>,
    onChannel: (Channel, List<Channel>) -> Unit,
    favoriteIds: Set<String>,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onLongPress: ((Channel) -> Unit)? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier.padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).then(
                    if (onSeeAll != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "See all $title", onClick = onSeeAll).testTag("see_all_$title")
                    else Modifier,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (onSeeAll != null) Icon(Icons.Rounded.ChevronRight, null, tint = ShowColors.Mist)
            }
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.testTag("row_$title"),
        ) {
            items(channels, key = { it.id }, contentType = { "card" }) { ch ->
                ChannelCard(
                    ch, onClick = { onChannel(ch, channels) }, width = cardWidth, isFavorite = ch.id in favoriteIds,
                    onLongClick = onLongPress?.let { { it(ch) } },
                )
            }
        }
    }
}
