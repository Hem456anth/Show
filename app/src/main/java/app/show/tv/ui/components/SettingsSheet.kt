package app.show.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.show.tv.BuildConfig
import app.show.tv.data.PlaylistSource
import app.show.tv.data.Settings
import app.show.tv.data.SettingsStore
import app.show.tv.ui.theme.ShowColors

/** Playlist source, browsing filters, privacy and attribution in one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onDismiss: () -> Unit,
    onPlaylistUrl: (String) -> SettingsStore.UrlResult,
    onHideGeo: (Boolean) -> Unit,
    onHideUnavailable: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onSource: (PlaylistSource) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShowColors.Night,
        modifier = Modifier.testTag("settings_sheet"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))

            Text("Channel source", style = MaterialTheme.typography.titleMedium)
            Text(
                "Official iptv-org playlists. Every source gets country, language and category filters.",
                style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist,
            )
            PlaylistSource.entries.forEach { src ->
                SourceRow(src, selected = settings.source == src) { if (settings.source != src) onSource(src) }
            }
            Spacer(Modifier.height(10.dp))
            Text("Or your own M3U", style = MaterialTheme.typography.bodyMedium)
            var url by remember(settings.playlistUrl) { mutableStateOf(if (settings.isCustomPlaylist) settings.playlistUrl else "") }
            var error by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = url, onValueChange = { url = it; error = null },
                placeholder = { Text("https://example.com/playlist.m3u") },
                singleLine = true, isError = error != null,
                supportingText = { error?.let { Text(it) } ?: Text("HTTPS only. Leave empty to go back to iptv-org.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("playlist_url"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShowPrimaryButton(onClick = {
                    when (val r = onPlaylistUrl(url)) {
                        is SettingsStore.UrlResult.Invalid -> error = r.reason
                        SettingsStore.UrlResult.Ok -> onDismiss()
                    }
                }, modifier = Modifier.testTag("save_playlist")) { Text("Load playlist") }
                if (settings.isCustomPlaylist) ShowSecondaryButton(onClick = { onPlaylistUrl(""); onDismiss() }) { Text("Use iptv-org") }
            }

            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color.White.copy(alpha = 0.08f))
            Text("Browsing", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Hide geo-blocked channels", "Skip channels the broadcaster limits to certain regions.", settings.hideGeoBlocked, onHideGeo, "toggle_geo")
            ToggleRow("Working channels only", "Hide channels that failed to play in the last 24 hours.", settings.hideUnavailable, onHideUnavailable, "toggle_dead")

            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color.White.copy(alpha = 0.08f))
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text("Show has no accounts, ads or analytics. Your list and history stay on this device.",
                style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist)
            TextButton(onClick = onClearHistory, modifier = Modifier.testTag("settings_clear_history")) { Text("Clear watch history") }

            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color.White.copy(alpha = 0.08f))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "Show ${BuildConfig.VERSION_NAME}. Channel index by iptv-org (github.com/iptv-org/iptv, Unlicense). " +
                    "Show hosts no streams. Each channel is a public link owned by its broadcaster. " +
                    "Playback by AndroidX Media3 (Apache 2.0). Source in use: ${settings.playlistUrl}",
                style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist,
            )
        }
    }
}

@Composable
private fun SourceRow(source: PlaylistSource, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp).testTag("source_row_${source.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = ShowColors.Mist),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(source.title, style = MaterialTheme.typography.bodyMedium)
            Text(source.subtitle, style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(checkedTrackColor = ShowColors.Accent, checkedThumbColor = Color.Black),
        )
    }
}
