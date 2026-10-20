package app.show.tv.vod

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import java.util.Locale

/**
 * Audio / subtitle track menus and picture modes for the VOD player (1.8.0). Pure logic over Media3's
 * [Tracks] and [TrackSelectionParameters], so it is unit-tested without a player or a screen.
 */
object VodTracks {
    /** One menu entry. [groupIndex]/[trackIndex] point into [Tracks.getGroups]; both -1 for "Off". */
    data class Option(val groupIndex: Int, val trackIndex: Int, val label: String, val language: String?, val selected: Boolean) {
        val isOff: Boolean get() = groupIndex < 0
    }

    const val OFF = "off"

    fun audio(tracks: Tracks): List<Option> = options(tracks, C.TRACK_TYPE_AUDIO)

    /** Subtitle tracks, led by "Off" (selected when no text track is playing). */
    fun subtitles(tracks: Tracks): List<Option> {
        val list = options(tracks, C.TRACK_TYPE_TEXT)
        return listOf(Option(-1, -1, "Off", null, list.none { it.selected })) + list
    }

    private fun options(tracks: Tracks, type: Int): List<Option> {
        val out = mutableListOf<Option>()
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val f = group.getTrackFormat(t)
                out += Option(g, t, label(f, type), normLang(f.language), group.isTrackSelected(t))
            }
        }
        // Same-language duplicates get a numeric suffix so the menu never shows two identical rows.
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return out.map { o -> if ((counts[o.label] ?: 0) > 1) o.copy(label = "${o.label} ${(seen.merge(o.label, 1, Int::plus))}") else o }
    }

    fun label(f: Format, type: Int): String {
        val lang = languageName(f.language)
        val parts = mutableListOf<String>()
        parts += f.label?.takeIf { it.isNotBlank() && !it.equals(lang, true) }?.let { if (lang != null) "$lang · $it" else it } ?: lang ?: "Unknown"
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) { 1 -> parts += "Mono"; 2 -> parts += "Stereo"; 6 -> parts += "5.1"; 8 -> parts += "7.1" }
            if (f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) parts += "Commentary"
            if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) parts += "Audio description"
        } else {
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
            if (f.roleFlags and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_CAPTION) != 0) parts += "SDH"
        }
        return parts.joinToString(" · ")
    }

    /** "eng" / "en-US" / "en" -> "English"; null or "und" -> null. */
    fun languageName(code: String?): String? {
        val c = normLang(code) ?: return null
        val name = Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(c, true) }?.replaceFirstChar { it.uppercase() } ?: c
    }

    /** Lowercase primary language subtag, ISO 639-2 mapped to 639-1 where Java knows it ("eng" -> "en"). */
    fun normLang(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" } ?: return null
        if (c.length == 3) iso3[c]?.let { return it }
        return c
    }

    /** ISO 639-2 -> 639-1, built once ("eng" -> "en", also bibliographic "fre"/"ger" -> "fr"/"de"). */
    private val iso3: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { l -> runCatching { Locale(l).isO3Language to l }.getOrNull() }.toMap() +
            mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el", "per" to "fa", "cze" to "cs", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy")
    }

    /** Parameters for a menu pick: an explicit override, or text disabled for "Off". */
    @OptIn(UnstableApi::class)
    fun select(params: TrackSelectionParameters, tracks: Tracks, type: Int, option: Option): TrackSelectionParameters {
        val b = params.buildUpon()
        if (option.isOff) return b.clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
        val group = tracks.groups.getOrNull(option.groupIndex) ?: return params
        return b.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    /** Start-of-playback parameters from the viewer's remembered languages ([OFF] disables subtitles). */
    fun preferred(params: TrackSelectionParameters, audioLang: String?, subtitleLang: String?): TrackSelectionParameters {
        val b = params.buildUpon()
        audioLang?.let { b.setPreferredAudioLanguage(it) }
        when (subtitleLang) {
            null -> {}
            OFF -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(subtitleLang)
        }
        return b.build()
    }

    /** What to remember after a menu pick: a language, [OFF], or null when the track has no language. */
    fun rememberedLanguage(option: Option): String? = if (option.isOff) OFF else option.language
}

/** Picture modes for the VOD player (1.8.0), cycled from a player button and remembered in [VodSettings]. */
@OptIn(UnstableApi::class)
enum class ResizeMode(val label: String, val media3: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): ResizeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(name: String?): ResizeMode = entries.firstOrNull { it.name == name } ?: FIT
    }
}

/** Playback speeds offered in the player (1.8.0). Pitch is corrected by Media3. */
object VodSpeeds {
    val all = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    fun label(s: Float): String = if (s == 1f) "Normal" else (if (s % 1f == 0f) "${s.toInt()}" else "$s".trimEnd('0')) + "×"
}
