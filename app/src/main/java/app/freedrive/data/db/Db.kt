package app.freedrive.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class ProviderStatus { UNTESTED, CHECKING, OK, FAILED }

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val specId: String,
    val label: String,
    val kind: String,
    /** Non-secret credential values, JSON object. */
    val configJson: String,
    /** Secret credential values, JSON object. Plain on disk in 0.1 — see README. */
    val secretJson: String,
    val quotaBytes: Long,
    val usedBytes: Long,
    val status: String,
    val lastError: String?,
    val detail: String?,
    val createdAt: Long,
    val lastCheckAt: Long,
)

@Entity(tableName = "entries", indices = [Index("parentId"), Index("providerId")])
data class EntryEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val providerId: String?,
    val providerName: String?,
    val remoteKey: String?,
    val thumbPath: String?,
    val sha256: String?,
    val createdAt: Long,
    /** Wall-clock deadline after which the sweeper deletes this file. Null means it stays. */
    val expiresAt: Long? = null,
)

/** Write-ahead intent. Every remote mutation is recorded here before it is attempted. */
@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val at: Long,
    val op: String,
    val state: String,
    val payload: String,
)

/**
 * One file waiting to go somewhere. Lives in the database rather than in a coroutine so
 * that a closed app resumes the same queue instead of silently losing what you picked.
 *
 * QUEUED → RUNNING → DONE | FAILED. A FAILED row keeps its error and can be retried.
 */
@Entity(tableName = "uploads", indices = [Index("state")])
data class UploadEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val parentId: String?,
    val name: String,
    val mime: String,
    val size: Long,
    val state: String,
    val sentBytes: Long,
    val providerName: String?,
    /** Provider the uploader asked for, or null for "any available". Copied at pick time so
     *  changing the selector afterwards cannot move a file that is already queued. */
    val targetProviderId: String? = null,
    /** How long the finished file should live, in milliseconds. Null means forever.
     *  Stored as a duration, not a deadline, so the clock starts when the upload lands
     *  rather than when the picker closed. */
    val lifetimeMs: Long? = null,
    val error: String?,
    val attempt: Int,
    val createdAt: Long,
)

object UploadState {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"

    /** Identical bytes are already in the drive; nothing was uploaded. */
    const val SKIPPED = "SKIPPED"
}

/**
 * One link that was handed out. Kept so a URL can be found and killed later instead of being
 * remembered only by whoever received it. The URL itself is not a secret worth encrypting —
 * it is a capability that expires — but it is the only record that it exists.
 */
@Entity(tableName = "shares", indices = [Index("entryId")])
data class ShareEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val entryName: String,
    val providerName: String?,
    val kind: String,
    val url: String,
    val createdAt: Long,
    val diesAt: Long?,
)

@Dao
interface FreeDriveDao {

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun providers(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    suspend fun providersNow(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE status = 'OK' ORDER BY createdAt ASC")
    suspend fun liveProviders(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun provider(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(p: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("UPDATE providers SET status = :status, lastError = :error, detail = :detail, quotaBytes = :quota, lastCheckAt = :at WHERE id = :id")
    suspend fun setCheck(id: String, status: String, error: String?, detail: String?, quota: Long, at: Long)

    @Query("UPDATE providers SET usedBytes = usedBytes + :delta WHERE id = :id")
    suspend fun addUsed(id: String, delta: Long)

    @Query("SELECT COALESCE(SUM(usedBytes), 0) FROM providers WHERE status = 'OK'")
    fun usedTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(quotaBytes), 0) FROM providers WHERE status = 'OK'")
    fun quotaTotal(): Flow<Long>

    // Root listing and child listing are separate queries so that `parentId IS NULL`
    // stays a literal rather than a bound NULL, which keeps the plan index-friendly.
    @Query("SELECT * FROM entries WHERE parentId IS NULL ORDER BY isFolder DESC, name COLLATE NOCASE ASC")
    fun rootEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE parentId = :parentId ORDER BY isFolder DESC, name COLLATE NOCASE ASC")
    fun childEntries(parentId: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE parentId = :parentId ORDER BY isFolder DESC, name COLLATE NOCASE ASC")
    suspend fun childrenOf(parentId: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE parentId IS NULL ORDER BY isFolder DESC, name COLLATE NOCASE ASC")
    suspend fun rootNow(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: String): EntryEntity?

    @Query("SELECT * FROM entries WHERE id = :id")
    fun entryFlow(id: String): Flow<EntryEntity?>

    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    fun allEntries(): Flow<List<EntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(e: EntryEntity)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("SELECT COUNT(*) FROM entries WHERE parentId = :parentId")
    suspend fun childCount(parentId: String): Int

    // ---- expiring files

    @Query("SELECT * FROM entries WHERE expiresAt IS NOT NULL AND expiresAt <= :now ORDER BY expiresAt ASC")
    suspend fun expiredEntries(now: Long): List<EntryEntity>

    /** The next deadline there is, so exactly one alarm can cover the whole library. */
    @Query("SELECT MIN(expiresAt) FROM entries WHERE expiresAt IS NOT NULL")
    suspend fun nextExpiry(): Long?

    @Query("UPDATE entries SET expiresAt = :at WHERE id = :id")
    suspend fun setEntryExpiry(id: String, at: Long?)

    @Query("SELECT * FROM entries WHERE expiresAt IS NOT NULL ORDER BY expiresAt ASC")
    fun expiringEntries(): Flow<List<EntryEntity>>

    @Insert
    suspend fun log(j: JournalEntity): Long

    @Query("UPDATE journal SET state = :state WHERE seq = :seq")
    suspend fun setJournal(seq: Long, state: String)

    @Query("SELECT * FROM journal ORDER BY seq ASC")
    suspend fun journal(): List<JournalEntity>

    // ---- upload queue

    @Query("SELECT * FROM uploads ORDER BY createdAt ASC")
    fun uploads(): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE state IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC")
    suspend fun pendingUploads(): List<UploadEntity>

    @Query("SELECT COUNT(*) FROM uploads WHERE state IN ('QUEUED', 'RUNNING')")
    fun pendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUpload(u: UploadEntity)

    @Query("UPDATE uploads SET state = :state, error = :error WHERE id = :id")
    suspend fun setUploadState(id: String, state: String, error: String?)

    @Query("UPDATE uploads SET sentBytes = :sent, providerName = :provider WHERE id = :id")
    suspend fun setUploadProgress(id: String, sent: Long, provider: String?)

    @Query("UPDATE uploads SET state = 'QUEUED', error = NULL, sentBytes = 0, attempt = attempt + 1 WHERE id = :id")
    suspend fun requeueUpload(id: String)

    @Query("DELETE FROM uploads WHERE id = :id")
    suspend fun deleteUpload(id: String)

    @Query("DELETE FROM uploads WHERE state IN ('DONE', 'FAILED', 'SKIPPED')")
    suspend fun clearFinishedUploads()

    // ---- search, duplicates, links

    @Query("SELECT * FROM entries WHERE isFolder = 0 AND name LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 200")
    suspend fun searchEntries(q: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE sha256 = :hash LIMIT 1")
    suspend fun entryBySha(hash: String): EntryEntity?

    @Query("SELECT * FROM shares ORDER BY createdAt DESC")
    fun shares(): Flow<List<ShareEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShare(s: ShareEntity)

    @Query("DELETE FROM shares WHERE id = :id")
    suspend fun deleteShare(id: String)

    @Query("DELETE FROM shares WHERE entryId = :entryId")
    suspend fun deleteSharesFor(entryId: String)
}

@Database(
    entities = [ProviderEntity::class, EntryEntity::class, JournalEntity::class, UploadEntity::class, ShareEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class FreeDriveDb : RoomDatabase() {

    abstract fun dao(): FreeDriveDao

    companion object {
        /** 0.2 added a per-upload target provider. Additive, so no reason to throw the
         *  database away and make everyone re-enter their keys. */
        private val V2_TO_V3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE uploads ADD COLUMN targetProviderId TEXT")
            }
        }

        /** 0.4 added the two clocks: one deadline on the file, one lifetime on the upload. */
        private val V3_TO_V4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN expiresAt INTEGER")
                db.execSQL("ALTER TABLE uploads ADD COLUMN lifetimeMs INTEGER")
            }
        }

        /** 0.5 added the share log, so links can be found and revoked later. */
        private val V4_TO_V5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS shares (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "entryId TEXT NOT NULL, " +
                        "entryName TEXT NOT NULL, " +
                        "providerName TEXT, " +
                        "kind TEXT NOT NULL, " +
                        "url TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "diesAt INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shares_entryId ON shares(entryId)")
            }
        }

        fun build(context: Context): FreeDriveDb =
            Room.databaseBuilder(context, FreeDriveDb::class.java, "freedrive.db")
                .addMigrations(V2_TO_V3, V3_TO_V4, V4_TO_V5)
                .fallbackToDestructiveMigration()
                .build()
    }
}
