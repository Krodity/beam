package uk.krodity.beam.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Playback state of the PC's Beam tab. Field names mirror what the extension
 *  emits, so Gson maps them without annotations. */
data class PlayerStatus(
    val hasVideo: Boolean = false,
    val kind: String? = null,
    val playing: Boolean = false,
    val paused: Boolean = true,
    val ended: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val live: Boolean = false,
    val buffered: Double = 0.0,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val rate: Double = 1.0,
    val fullscreen: Boolean = false,
    /** YouTube is showing an ad; the remote offers "Skip ad". */
    val ad: Boolean = false,
    val loading: Boolean = false,

    val pageTitle: String? = null,
    val url: String? = null,
    val favicon: String? = null,
    val site: String? = null,
    /** From the page's own media-session metadata, when it publishes any. */
    val title: String? = null,
    val artist: String? = null,
    val artwork: String? = null,
    val reason: String? = null,
) {
    val progress: Float
        get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    /** The best name for what's on: media-session title, else the tab title. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: pageTitle?.takeIf { it.isNotBlank() }
            ?: ""

    val displaySubtitle: String
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, site?.takeIf { it.isNotBlank() })
            .distinct().joinToString(" · ")
}

data class HostInfo(
    val id: String = "",
    val name: String = "",
    val connected_at: Double = 0.0,
    val status: PlayerStatus? = null,
)

/** A tab open in the PC browser, for picking something started at the PC. */
data class PcTab(
    val id: Long = 0,
    val title: String = "",
    val url: String = "",
    val favicon: String = "",
    val audible: Boolean = false,
    val active: Boolean = false,
    val beam: Boolean = false,
)

data class Bookmark(val title: String, val url: String) {
    companion object {
        /** What a fresh install shows on the start page. */
        val DEFAULTS = listOf(
            Bookmark("YouTube", "https://m.youtube.com/"),
            Bookmark("Crunchyroll", "https://www.crunchyroll.com/"),
            Bookmark("Twitch", "https://m.twitch.tv/"),
            Bookmark("Vimeo", "https://vimeo.com/watch"),
            Bookmark("Dailymotion", "https://www.dailymotion.com/"),
            Bookmark("Internet Archive", "https://archive.org/details/movies"),
        )

        private val type = object : TypeToken<List<Bookmark>>() {}.type

        fun decode(raw: String?): List<Bookmark> =
            if (raw.isNullOrBlank()) DEFAULTS
            else runCatching { Gson().fromJson<List<Bookmark>>(raw, type) }.getOrNull() ?: DEFAULTS

        fun encode(list: List<Bookmark>): String = Gson().toJson(list)
    }
}

/** A broker call that failed, carrying a message already fit to show the user. */
class BrokerException(message: String, val recoverable: Boolean = true) : Exception(message)
