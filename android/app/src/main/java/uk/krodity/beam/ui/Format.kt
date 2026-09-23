package uk.krodity.beam.ui

/** Seconds -> "12:04" or "1:02:03". Used for both positions and durations. */
fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(millis: Long?): String =
    millis?.let { "${it / 60000} min" } ?: ""
