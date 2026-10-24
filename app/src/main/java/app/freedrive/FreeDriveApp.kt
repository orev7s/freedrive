package app.freedrive

import android.app.Application
import app.freedrive.data.FreeDriveRepo
import app.freedrive.data.db.FreeDriveDb

/**
 * One database, one repository, one OkHttp client for the whole process.
 *
 * Kept on Application rather than in a ViewModel so a rotation cannot drop the repository
 * mid-upload, and so the build needs no DI graph at all. The expiry receiver reaches it by
 * casting its context, which is the whole reason this class is not just a lazy holder.
 */
class FreeDriveApp : Application() {

    val db: FreeDriveDb by lazy { FreeDriveDb.build(this) }

    val repo: FreeDriveRepo by lazy { FreeDriveRepo(this, db) }
}
