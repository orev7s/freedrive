package app.freedrive.ui

import android.content.Context

/**
 * Where the next upload should go, and whether that choice survives a restart.
 *
 * Nothing is stored unless the Remember box is ticked: the default is always
 * "Any available", and a one-off choice dies with the session.
 */
class UploadTargetStore(context: Context) {

    private val prefs = context.getSharedPreferences("freedrive.upload", Context.MODE_PRIVATE)

    /** The remembered provider id, or "" for a remembered "any". Null when not remembering. */
    val rememberedTarget: String?
        get() = if (remembering) prefs.getString(KEY_TARGET, "") ?: "" else null

    val remembering: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)

    fun save(targetId: String?, remember: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_REMEMBER, remember)
            if (remember) putString(KEY_TARGET, targetId ?: "") else remove(KEY_TARGET)
        }.apply()
    }

    private companion object {
        const val KEY_TARGET = "target_id"
        const val KEY_REMEMBER = "remember"
    }
}
