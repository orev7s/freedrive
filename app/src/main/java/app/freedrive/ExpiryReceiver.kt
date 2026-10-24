package app.freedrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fires when a file's deadline passes, even if no screen has been open for days.
 *
 * A receiver gets about ten seconds, which is enough for a local delete and not always enough
 * for a round trip to a provider. The work is therefore time-boxed: whatever is unfinished
 * stays in the index with its deadline already in the past, and the next sweep picks it up.
 * Nothing is ever half-deleted, because the row only disappears after its remote delete has
 * been attempted.
 */
class ExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? FreeDriveApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000L) { app.repo.sweep() }
            } finally {
                pending.finish()
            }
        }
    }
}
