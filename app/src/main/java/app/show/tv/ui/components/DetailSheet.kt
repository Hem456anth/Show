package app.show.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.show.tv.data.Channel
import app.show.tv.ui.theme.ShowColors

/**
 * Apple TV-style product page in a full-height sheet: a blurred, full-bleed backdrop that fades
 * into black, the logo as title art, a capsule Play button with a frosted My List circle, facts as
 * capsules and a "More like this" shelf.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelDetailSheet(
    channel: Channel,
    related: List<Channel>,
    inList: Boolean,
    favoriteIds: Set<String>,
    onDismiss: () -> Unit,
    onPlay: (Channel) -> Unit,
    onToggleList: (Channel) -> Unit,
    onOpen: (Channel) -> Unit,
    relatedTitle: String = "More like this",
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShowColors.Ink,
        modifier = Modifier.testTag("detail_sheet"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                ChannelArtwork(
                    channel,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = 1.6f; scaleY = 1.6f; alpha = 0.55f }
                        .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp) else Modifier),
                    logoPadding = 30.dp,
                )
                Box(Modifier.fillMaxSize().background(ShowColors.BottomScrim))
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.62f).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, ShowColors.Hairline, RoundedCornerShape(18.dp)),
                ) {
                    ChannelArtwork(channel, Modifier.fillMaxSize(), logoPadding = 22.dp, monogramSize = 56)
                    LiveCapsule(offline = false, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(channel.name, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        channel.categories.filter { it != Channel.UNCATEGORISED }.take(2).joinToString(" · ").ifEmpty { null },
                        channel.countries.firstOrNull(), channel.languages.take(2).joinToString(", ").ifEmpty { null }, channel.resolution,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium, color = ShowColors.Mist,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShowPrimaryButton(
                        onClick = { onPlay(channel) }, shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).height(52.dp).testTag("detail_play"),
                    ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play Live", style = MaterialTheme.typography.labelLarge) }
                    GlassCircleButton(if (inList) "Remove from My List" else "Add to My List", onClick = { onToggleList(channel) }, tag = "detail_list") {
                        Icon(if (inList) Icons.Rounded.Check else Icons.Rounded.Add, null)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    buildString {
                        append("Free-to-air live channel listed by the iptv-org community index.")
                        if (channel.isGeoBlocked) append(" The broadcaster limits this stream to certain regions, so it may not play where you are.")
                        if (channel.isNot247) append(" It doesn't broadcast around the clock.")
                        if (channel.alternates.isNotEmpty()) append(" ${channel.alternates.size} backup stream(s) are tried automatically if the main one is down.")
                        if (!channel.protocol.playableInApp) append(" ${channel.protocol.label} needs an external player.")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = ShowColors.Mist,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    channel.categories.filter { it != Channel.UNCATEGORISED }.forEach { Badge(it) }
                    channel.resolution?.let { Badge(it, emphasis = true) }
                    channel.countries.take(3).forEach { Badge(it) }
                    channel.languages.take(3).forEach { Badge(it) }
                    Badge(channel.protocol.label)
                    channel.flags.forEach { Badge(it.uppercase()) }
                }
            }
            if (related.isNotEmpty()) {
                ChannelRow(relatedTitle, related, { ch, _ -> onOpen(ch) }, favoriteIds, 156.dp)
            }
        }
    }
}
