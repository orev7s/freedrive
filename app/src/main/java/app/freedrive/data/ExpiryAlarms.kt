package app.freedrive.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.freedrive.ExpiryReceiver

/**
 * One alarm for the whole library, always set to the earliest deadline there is.
 *
 * The in-process sweeper can only run while FreeDrive is open, and a file set to die in ten
 * minutes should not survive because the phone went back in a pocket. `setWindow` is the
 * inexact form on purpose: it needs no special permission on Android 12+, survives Doze, and
 * a minute of slop on a "delete in seven days" timer costs nothing.
 *
 * Null means there is nothing left to delete, so the alarm is cancelled outright rather than
 * left armed to wake the device for no reason.
 */
object ExpiryAlarms {

    private const val REQUEST = 9182

    fun sync(context: Context, at: Long?) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, ExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) {
            runCatching { manager.cancel(pending) }
            return
        }
        val soonest = at.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        runCatching { manager.setWindow(AlarmManager.RTC_WAKEUP, soonest, 60_000L, pending) }
    }
}
