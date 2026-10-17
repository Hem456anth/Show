package app.show.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** Human labels for audio tracks: "Telugu · 5.1 · Dolby Digital", "Hindi (Commentary) · Stereo". */
@OptIn(UnstableApi::class)
object AudioLabels {
    private val undetermined = setOf("und", "unk", "mul", "zxx", "mis", "qaa")

    /** Normalised BCP-47 tag, or null for "undetermined" style codes. */
    fun language(tag: String?): String? = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.substringBefore('-') !in undetermined }
        ?.let { androidx.media3.common.util.Util.normalizeLanguageCode(it) ?: it }

    fun languageName(tag: String?): String? = language(tag)?.let { t ->
        Locale.forLanguageTag(t).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(t, true) }
            ?: t.uppercase()
    }

    fun channels(count: Int): String? = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count ch"
    }

    fun codec(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
        else -> null // AAC is the default; naming it adds noise
    }

    fun label(f: Format): String {
        val lang = languageName(f.language)
        // Manifest names like "Undetermined", "audio_208482_und" or "hin a2" carry little; keep what's useful.
        val name = f.label?.trim()
            ?.let { n ->
                // "hin a2": drop a leading language code that repeats the track's language.
                val first = n.substringBefore(' ')
                if (n.contains(' ') && lang != null && languageName(first) == lang) n.substringAfter(' ').trim() else n
            }
            ?.takeIf { it.isNotEmpty() && language(it) != null && !it.equals("undetermined", true) && !it.startsWith("audio_", true) }
        val head = when {
            lang != null && name != null && !name.equals(lang, true) && !name.equals(f.language, true) ->
                if (name.contains(lang, true)) name else "$lang ($name)"
            lang != null -> lang
            name != null -> name
            else -> "Audio"
        }
        val described = (f.roleFlags and androidx.media3.common.C.ROLE_FLAG_DESCRIBES_VIDEO) != 0
        val commentary = (f.roleFlags and androidx.media3.common.C.ROLE_FLAG_COMMENTARY) != 0
        return listOfNotNull(
            head + when { described -> " (Audio description)"; commentary -> " (Commentary)"; else -> "" },
            channels(f.channelCount), codec(f),
        ).joinToString(" · ")
    }
}
