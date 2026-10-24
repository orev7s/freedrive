package app.freedrive.ui

import android.content.Context

/**
 * The two answers that outlive a session: how long new uploads should live, and whether
 * this phone is allowed to serve a file over Wi-Fi.
 *
 * Wi-Fi sending is false until someone turns it on. That is not caution for its own sake —
 * it opens a socket other devices on the network can reach, so it stays off until asked.
 */
class SharePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("freedrive.share", Context.MODE_PRIVATE)

    var wifiSending: Boolean
        get() = prefs.getBoolean(KEY_WIFI, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI, value).apply()

    /** Milliseconds, or 0 for "keep". Applies to the next upload, not to what is already stored. */
    var defaultLifetimeMs: Long
        get() = prefs.getLong(KEY_LIFETIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LIFETIME, value).apply()

    private companion object {
        const val KEY_WIFI = "wifi_sending"
        const val KEY_LIFETIME = "default_lifetime"
    }
}
