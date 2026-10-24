package app.freedrive.data

import kotlin.math.abs

/**
 * How long a shared image is allowed to live. One list serves both jobs: it is the timer a
 * new upload inherits, and the timer the share sheet stamps onto a file it is about to hand out.
 *
 * [KEEP] is the only value that means "do not delete", and it is the default everywhere so
 * nothing self-destructs unless it was asked to.
 */
enum class Lifetime(val label: String, val ms: Long) {
    KEEP("Keep", 0L),
    ONE_SECOND("1 s", 1_000L),
    SEVEN_SECONDS("7 s", 7_000L),
    ONE_MINUTE("1 min", 60_000L),
    ONE_HOUR("1 hour", 3_600_000L),
    ONE_DAY("1 day", 86_400_000L),
    SEVEN_DAYS("7 days", 604_800_000L),
    ;

    companion object {
        /**
         * Seven days is the ceiling SigV4 itself enforces on a presigned URL, so it is the
         * longest window this app can honestly promise on any provider.
         */
        const val MAX_LINK_SECONDS: Long = 604_800L

        /** The chip that best matches a file's remaining time, for preselecting the sheet. */
        fun nearest(remainingMs: Long?): Lifetime {
            if (remainingMs == null || remainingMs <= 0L) return KEEP
            return entries.drop(1).minByOrNull { abs(it.ms - remainingMs) } ?: KEEP
        }
    }
}

/** Where the bytes for a shared image come from. */
enum class ShareKind {
    /** A link the provider makes and serves itself — the phone can be off. */
    NATIVE,

    /** A signature over one object for one window, made locally and served by the bucket. */
    SIGNED,

    /** This phone, over the Wi-Fi both devices are already on. */
    LAN,
}

/**
 * One shareable URL, plus the two things that must be said next to it: when it stops working,
 * and who can open it.
 */
data class ShareLink(
    val url: String,
    val kind: ShareKind,
    /** Wall-clock expiry of the URL itself. Null only when the provider imposes none. */
    val diesAt: Long?,
    /** One sentence, shown verbatim under the link. */
    val note: String,
)

/**
 * A running Wi-Fi handoff. [url] is a bearer token on the local network — unguessable, but
 * readable by anyone who is sent it, so it dies when [diesAt] passes or the sheet closes.
 */
data class LanLink(
    val url: String,
    val fileName: String,
    val port: Int,
    val served: Int,
    val startedAt: Long,
    val diesAt: Long,
)
