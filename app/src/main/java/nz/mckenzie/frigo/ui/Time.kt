package nz.mckenzie.frigo.ui

import android.text.format.DateUtils

/** "Just now", "14 min. ago", "1 hr. ago": how long ago something happened. */
fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis < 60_000) return "Just now"
    return DateUtils.getRelativeTimeSpanString(
        millis,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}
