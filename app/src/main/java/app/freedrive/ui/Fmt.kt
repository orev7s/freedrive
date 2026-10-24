package app.freedrive.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = 1024.0 * 1024.0 * 1024.0
    val mb = 1024.0 * 1024.0
    val kb = 1024.0
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.1f MB".format(bytes / mb)
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

fun fmtDate(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

/** A span in the largest unit that still reads as a number: "42 s", "9 min", "3 h", "6 d". */
private fun span(ms: Long): String = when {
    ms < 1_000L -> "1 s"
    ms < 60_000L -> "${ms / 1_000L} s"
    ms < 3_600_000L -> "${ms / 60_000L} min"
    ms < 86_400_000L -> "${ms / 3_600_000L} h"
    else -> "${ms / 86_400_000L} d"
}

/** "deletes in 42 s" — shown next to a file that has a deadline. */
fun fmtRemaining(at: Long, now: Long = System.currentTimeMillis()): String {
    val left = at - now
    return if (left <= 0L) "deleting…" else "deletes in ${span(left)}"
}

/** "in 42 s" — shown next to a link or a running hand-off. */
fun fmtIn(at: Long, now: Long = System.currentTimeMillis()): String {
    val left = at - now
    return if (left <= 0L) "now" else "in ${span(left)}"
}

fun shortHash(hash: String?): String =
    when {
        hash == null || hash.isEmpty() -> "—"
        hash.length <= 16 -> hash
        else -> hash.take(16) + "…"
    }
