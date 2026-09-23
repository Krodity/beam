package uk.krodity.beam.ui.screens

import android.net.Uri

/**
 * URLs that are unmistakably "one video, play it now". These are sent to the
 * PC on navigation rather than waiting for a `play` event, because on some of
 * these sites the player sits in a cross-origin iframe the page hook can't see,
 * or the phone would start playing sound before the event arrives.
 */
object WatchPages {

    private val patterns = listOf(
        Regex("""^https?://(www\.|m\.|music\.)?youtube\.com/(watch\?|shorts/|live/)"""),
        Regex("""^https?://youtu\.be/[\w-]+"""),
        Regex("""^https?://(www\.)?crunchyroll\.com/(\w+/)?watch/"""),
        Regex("""^https?://(www\.)?vimeo\.com/\d+"""),
        Regex("""^https?://(www\.|m\.)?twitch\.tv/videos/\d+"""),
        Regex("""^https?://(www\.)?dailymotion\.com/video/"""),
        Regex("""^https?://(www\.)?netflix\.com/watch/"""),
        Regex("""^https?://(www\.)?archive\.org/details/[^/?#]+"""),
    )

    fun isWatch(url: String): Boolean = patterns.any { it.containsMatchIn(url) }

    /**
     * Identity of "the same video", so repeated play events and SPA history
     * updates for one video hand it off once. Drops the fragment and position
     * parameters that change while watching; YouTube is keyed by its video id
     * because its URLs pick up extra parameters as you click around.
     */
    fun key(url: String): String {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = u.host.orEmpty().removePrefix("www.").removePrefix("m.")
        if (host == "youtube.com" && u.path == "/watch") {
            u.getQueryParameter("v")?.let { return "yt:$it" }
        }
        if (host == "youtu.be") return "yt:" + u.path.orEmpty().trim('/')
        val q = u.queryParameterNames.filterNot { it in setOf("t", "time", "start", "pp", "si") }
            .sorted().joinToString("&") { "$it=${u.getQueryParameter(it)}" }
        return "$host${u.path}?$q"
    }

    /** A `t=` in the URL (YouTube "share at 1:23" links), in seconds. */
    fun startTime(url: String): Double {
        val t = runCatching { Uri.parse(url).getQueryParameter("t") }.getOrNull() ?: return 0.0
        t.toDoubleOrNull()?.let { return it }
        // 1h2m3s / 2m3s / 45s
        val m = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(t) ?: return 0.0
        val (h, mi, s) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600.0 + (mi.toIntOrNull() ?: 0) * 60.0 + (s.toIntOrNull() ?: 0)
    }
}
