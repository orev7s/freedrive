package app.freedrive.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.freedrive.data.adapters.AdapterException
import app.freedrive.data.adapters.Adapters
import app.freedrive.data.adapters.Json
import app.freedrive.data.adapters.PCloudAdapter
import app.freedrive.data.adapters.StorageAdapter
import app.freedrive.data.adapters.WebDavAdapter
import app.freedrive.data.adapters.S3Adapter
import app.freedrive.data.adapters.readable
import app.freedrive.data.db.EntryEntity
import app.freedrive.data.db.FreeDriveDb
import app.freedrive.data.db.JournalEntity
import app.freedrive.data.db.ProviderEntity
import app.freedrive.data.db.ProviderStatus
import app.freedrive.data.db.UploadEntity
import app.freedrive.data.db.UploadState
import app.freedrive.data.net.LanShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Everything the UI needs, with no provider knowledge of its own.
 *
 * Three rules hold the whole thing together:
 *   1. A provider is only used after its check returned OK.
 *   2. Nothing is written remotely before the intent is on disk in `journal`.
 *   3. An upload belongs to the process, not to a screen. Picking five files and walking
 *      away drains all five, and a killed process resumes from the `uploads` table.
 */
class FreeDriveRepo(private val context: Context, db: FreeDriveDb) {

    private val dao = db.dao()
    private val gb = 1024L * 1024L * 1024L

    /** Outlives every screen, so navigation and rotation cannot cancel a transfer. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Only one drain loop at a time, no matter how many times something is enqueued. */
    private val drainLock = Mutex()

    /**
     * Held while a deadline is set and its link minted, and by the sweep.
     *
     * A one-second lifetime would otherwise race itself: the timer can come due while the
     * round trip that mints the URL is still in flight, and the sweep would delete the file
     * before the link existed. The mint never takes this lock twice — `setExpiry` and
     * `shareLink` are called from inside it, not from outside.
     */
    private val shareLock = Mutex()

    /** Live bytes written per upload id. The socket is the only source of truth here. */
    private val _live = MutableStateFlow<Map<String, Long>>(emptyMap())
    val live: StateFlow<Map<String, Long>> = _live.asStateFlow()

    /** The Wi-Fi share that is currently open, if any. At most one can exist at a time. */
    private val _lan = MutableStateFlow<LanLink?>(null)
    val lan: StateFlow<LanLink?> = _lan.asStateFlow()
    private var lanShare: LanShare? = null

    init {
        // Finish anything the previous process left half-written, then carry on.
        scope.launch {
            runCatching {
                dao.pendingUploads()
                    .filter { it.state == UploadState.RUNNING }
                    .forEach { dao.requeueUpload(it.id) }
                drain()
            }
        }

        // Deadlines are checked on every launch — a file whose time ran out while the phone
        // was in a pocket is already overdue by the time this line runs — and then every few
        // seconds while the process lives. The alarm covers the hours when it does not.
        scope.launch {
            while (true) {
                runCatching { sweep() }
                delay(SWEEP_EVERY_MS)
            }
        }
    }

    // ------------------------------------------------------------ queries

    fun providers(): Flow<List<ProviderEntity>> = dao.providers()
    fun usedTotal(): Flow<Long> = dao.usedTotal()
    fun quotaTotal(): Flow<Long> = dao.quotaTotal()
    fun allEntries(): Flow<List<EntryEntity>> = dao.allEntries()
    fun entryFlow(id: String): Flow<EntryEntity?> = dao.entryFlow(id)
    fun uploads(): Flow<List<UploadEntity>> = dao.uploads()
    fun pendingCount(): Flow<Int> = dao.pendingCount()

    fun children(parentId: String?): Flow<List<EntryEntity>> =
        if (parentId == null) dao.rootEntries() else dao.childEntries(parentId)

    suspend fun path(folderId: String?): List<EntryEntity> = withContext(Dispatchers.IO) {
        val out = ArrayList<EntryEntity>()
        var cursor = folderId?.let { dao.entry(it) }
        var guard = 0
        while (cursor != null && guard++ < 64) {
            out.add(0, cursor)
            cursor = cursor.parentId?.let { dao.entry(it) }
        }
        out
    }

    // ------------------------------------------------------------ providers

    suspend fun addProvider(spec: ProviderSpec, values: Map<String, String>): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val secretKeys = spec.fields.filter { it.secret }.map { it.key }.toSet()
        val config = values.filterKeys { it !in secretKeys }
        val secret = values.filterKeys { it in secretKeys }

        // Accounts of the same provider are told apart by a number: "pCloud 1", "pCloud 2".
        // The lowest free number is reused, so removing account 2 and adding another gives
        // you "pCloud 2" again instead of a growing count.
        val taken = dao.providersNow()
            .filter { it.specId == spec.id }
            .mapNotNull { it.label.removePrefix("${spec.name} ").toIntOrNull() }
            .toSet()
        var number = 1
        while (number in taken) number++

        dao.upsertProvider(
            ProviderEntity(
                id = id,
                specId = spec.id,
                label = "${spec.name} $number",
                kind = spec.kind.name,
                configJson = Json.encode(config),
                secretJson = Json.encode(secret),
                quotaBytes = spec.freeGb.toLong() * gb,
                usedBytes = 0L,
                status = ProviderStatus.UNTESTED.name,
                lastError = null,
                detail = null,
                createdAt = System.currentTimeMillis(),
                lastCheckAt = 0L,
            ),
        )
        check(id)
        id
    }

    /** Runs one verification and writes the verdict back to the row. */
    suspend fun check(id: String) = withContext(Dispatchers.IO) {
        val p = dao.provider(id) ?: return@withContext
        val spec = Catalog.byId(p.specId) ?: return@withContext
        dao.setCheck(id, ProviderStatus.CHECKING.name, null, p.detail, p.quotaBytes, p.lastCheckAt)

        try {
            val config = Json.decode(p.configJson)
            val secret = Json.decode(p.secretJson)
            val adapter = Adapters.build(spec, config, secret)
            val result = adapter.verify()

            if (result.ok && spec.kind == AdapterKind.PCLOUD && adapter is PCloudAdapter) {
                // Trade the password for a token and drop the password from disk.
                val token = runCatching { adapter.exchangeToken() }.getOrNull()
                if (token != null) {
                    dao.upsertProvider(
                        p.copy(
                            configJson = Json.encode(config + ("token" to token)),
                            secretJson = Json.encode(secret.mapValues { (k, _) -> if (k == "password") "" else secret[k].orEmpty() }),
                        ),
                    )
                }
            }

            dao.setCheck(
                id = id,
                status = if (result.ok) ProviderStatus.OK.name else ProviderStatus.FAILED.name,
                error = if (result.ok) null else result.message,
                detail = result.detail ?: result.message,
                quota = result.freeBytes ?: p.quotaBytes,
                at = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            dao.setCheck(id, ProviderStatus.FAILED.name, t.readable(), null, p.quotaBytes, System.currentTimeMillis())
        }
    }

    suspend fun checkAll() = withContext(Dispatchers.IO) {
        dao.providersNow().forEach { check(it.id) }
    }

    suspend fun removeProvider(id: String) = withContext(Dispatchers.IO) {
        dao.deleteProvider(id)
    }

    // ------------------------------------------------------------ folders

    suspend fun createFolder(name: String, parentId: String?): EntryEntity = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty()) throw AdapterException("Give the folder a name")
        val item = EntryEntity(
            id = newId(),
            parentId = parentId,
            name = clean,
            isFolder = true,
            size = 0L,
            mime = null,
            width = null,
            height = null,
            providerId = null,
            providerName = null,
            remoteKey = null,
            thumbPath = null,
            sha256 = null,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertEntry(item)
        item
    }

    // ------------------------------------------------------------ upload queue

    /**
     * Accepts the picker result and returns immediately. Nothing here touches the network;
     * the queue drains on its own and the screen can go away.
     */
    suspend fun enqueue(
        uris: List<Uri>,
        parentId: String?,
        targetProviderId: String? = null,
        lifetimeMs: Long? = null,
    ): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uri in uris) {
            val picked = describe(uri) ?: continue
            dao.upsertUpload(
                UploadEntity(
                    id = newId(),
                    uri = uri.toString(),
                    parentId = parentId,
                    name = picked.name,
                    mime = picked.mime,
                    size = picked.size,
                    state = UploadState.QUEUED,
                    sentBytes = 0L,
                    providerName = null,
                    targetProviderId = targetProviderId,
                    lifetimeMs = lifetimeMs,
                    error = null,
                    attempt = 0,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            added++
        }
        if (added > 0) scope.launch { drain() }
        added
    }

    /** Drains the queue front to back, one file at a time. Safe to call repeatedly. */
    suspend fun drain() {
        if (!drainLock.tryLock()) return
        try {
            while (true) {
                val next = dao.pendingUploads().firstOrNull() ?: break
                if (next.state == UploadState.RUNNING) {
                    // Leftover from a process that died mid-transfer.
                    dao.requeueUpload(next.id)
                    continue
                }
                runOne(next)
            }
        } finally {
            drainLock.unlock()
        }
    }

    private suspend fun runOne(u: UploadEntity) {
        dao.setUploadState(u.id, UploadState.RUNNING, null)
        val seq = dao.log(
            JournalEntity(
                at = System.currentTimeMillis(),
                op = "put",
                state = "intent",
                payload = "{\"upload\":\"${u.id}\",\"size\":${u.size}}",
            ),
        )

        try {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(u.uri))?.use { it.readBytes() }
                    ?: throw IOException("Could not read that image")
            }
            if (bytes.isEmpty()) throw IOException("That image is empty")
            if (u.size != bytes.size.toLong()) dao.upsertUpload(u.copy(size = bytes.size.toLong()))

            val mime = u.mime.ifBlank { "image/jpeg" }
            val provider = pickProvider(bytes.size.toLong(), u.targetProviderId)
            dao.setUploadProgress(u.id, 0L, provider.label)

            val spec = Catalog.byId(provider.specId)
                ?: throw AdapterException("Unknown provider ${provider.specId}")
            val adapter: StorageAdapter =
                Adapters.build(spec, Json.decode(provider.configJson), Json.decode(provider.secretJson))

            val entryId = u.id
            val ext = extensionFor(mime)
            val remoteName = "${WebDavAdapter.ROOT}/${WebDavAdapter.BLOBS}/$entryId.$ext"

            _live.update { it + (u.id to 0L) }
            val remoteKey = adapter.put(remoteName, bytes, mime) { sent ->
                _live.update { it + (u.id to sent) }
            }

            val bounds = boundsOf(bytes)
            val thumb = writeThumb(entryId, bytes)
            val now = System.currentTimeMillis()
            val item = EntryEntity(
                id = entryId,
                parentId = u.parentId,
                name = u.name,
                isFolder = false,
                size = bytes.size.toLong(),
                mime = mime,
                width = bounds.first,
                height = bounds.second,
                providerId = provider.id,
                providerName = provider.label,
                remoteKey = remoteKey,
                thumbPath = thumb?.absolutePath,
                sha256 = sha256(bytes),
                createdAt = now,
                // The clock starts when the bytes have landed, not when the picker closed.
                expiresAt = u.lifetimeMs?.takeIf { it > 0 }?.let { now + it },
            )
            dao.upsertEntry(item)
            dao.addUsed(provider.id, bytes.size.toLong())
            dao.setJournal(seq, "done")
            ExpiryAlarms.sync(context, dao.nextExpiry())

            // Keep the original cached so previews are instant on a slow link.
            runCatching {
                val cache = File(context.cacheDir, "originals").apply { mkdirs() }
                File(cache, entryId).writeBytes(bytes)
            }

            _live.update { it - u.id }
            dao.setUploadState(u.id, UploadState.DONE, null)
        } catch (t: Throwable) {
            dao.setJournal(seq, "failed")
            _live.update { it - u.id }
            dao.setUploadState(u.id, UploadState.FAILED, t.readable())
        }
    }

    suspend fun retryUpload(id: String) = withContext(Dispatchers.IO) {
        dao.requeueUpload(id)
        scope.launch { drain() }
    }

    suspend fun removeUpload(id: String) = withContext(Dispatchers.IO) {
        _live.update { it - id }
        dao.deleteUpload(id)
    }

    suspend fun clearFinishedUploads() = withContext(Dispatchers.IO) {
        dao.clearFinishedUploads()
    }

    /** Fetch the untouched original — from cache if the upload already left one. */
    suspend fun originalFile(entryId: String): File = withContext(Dispatchers.IO) {
        val entry = dao.entry(entryId) ?: throw AdapterException("That file is no longer indexed")
        val cache = File(context.cacheDir, "originals").apply { mkdirs() }
        val cached = File(cache, entry.id)
        if (cached.exists() && cached.length() > 0) return@withContext cached

        val providerId = entry.providerId ?: throw AdapterException("No provider recorded for this file")
        val remoteKey = entry.remoteKey ?: throw AdapterException("No remote key recorded for this file")
        val p = dao.provider(providerId) ?: throw AdapterException("That provider was removed")
        val spec = Catalog.byId(p.specId) ?: throw AdapterException("Unknown provider")
        val adapter = Adapters.build(spec, Json.decode(p.configJson), Json.decode(p.secretJson))
        val bytes = adapter.get(remoteKey)
        cached.writeBytes(bytes)
        cached
    }

    suspend fun delete(entryId: String): Unit = withContext(Dispatchers.IO) {
        val entry = dao.entry(entryId) ?: return@withContext
        if (entry.isFolder) {
            dao.childrenOf(entry.id).forEach { delete(it.id) }
            dao.deleteEntry(entry.id)
            return@withContext
        }

        val providerId = entry.providerId
        val remoteKey = entry.remoteKey
        if (providerId != null && remoteKey != null) {
            val p = dao.provider(providerId)
            val spec = p?.let { Catalog.byId(it.specId) }
            if (p != null && spec != null) {
                val adapter = Adapters.build(spec, Json.decode(p.configJson), Json.decode(p.secretJson))
                runCatching { adapter.delete(remoteKey) }
                dao.addUsed(providerId, -entry.size)
            }
        }
        entry.thumbPath?.let { runCatching { File(it).delete() } }
        runCatching { File(File(context.cacheDir, "originals"), entry.id).delete() }
        dao.deleteEntry(entry.id)
    }

    // ------------------------------------------------------------ expiring files

    /**
     * Deletes everything whose deadline has passed.
     *
     * Called from three places on purpose: at process start, where a deadline may have passed
     * days ago; every few seconds while the app is open; and from the alarm receiver when it is
     * not. It goes through `delete`, the same call the trash button makes, so an expiry removes
     * the remote object, the index row, the thumbnail, the cached original and the provider's
     * used counter — one path, one set of bugs.
     */
    suspend fun sweep() = withContext(Dispatchers.IO) {
        shareLock.withLock {
            val due = dao.expiredEntries(System.currentTimeMillis())
            for (entry in due) {
                // A dead provider must not block the rest of the list, and nothing is retried
                // forever: delete already drops the row once the remote call has been tried.
                runCatching { delete(entry.id) }
            }
            ExpiryAlarms.sync(context, dao.nextExpiry())
        }
    }

    /**
     * The timer and the link, in that order and under one lock, so a URL is never minted for a
     * file the sweep is in the middle of deleting. Callers use this instead of the two calls
     * above; the pair is one decision, and it should not be possible to make half of it.
     */
    suspend fun armAndShare(entryId: String, lifetimeMs: Long?, windowSeconds: Long): ShareLink =
        withContext(Dispatchers.IO) {
            shareLock.withLock {
                setExpiry(entryId, lifetimeMs)
                shareLink(entryId, windowSeconds)
            }
        }

    /** Re-arms one file. Null or zero clears the deadline and lets it stay. */
    suspend fun setExpiry(entryId: String, lifetimeMs: Long?) = withContext(Dispatchers.IO) {
        dao.entry(entryId) ?: throw AdapterException("That file is no longer indexed")
        dao.setEntryExpiry(
            entryId,
            lifetimeMs?.takeIf { it > 0L }?.let { System.currentTimeMillis() + it },
        )
        ExpiryAlarms.sync(context, dao.nextExpiry())
    }

    // ------------------------------------------------------------ sharing

    /**
     * A URL for one image, when this provider can produce one.
     *
     * Three kinds of provider, three honest answers: pCloud mints a real public link and serves
     * it from its own CDN; the four S3 providers get a presigned GET, good for one object for
     * one window; Telegram and plain WebDAV get a refusal with the reason and the alternative.
     * Nothing here invents a link that would not actually open.
     */
    suspend fun shareLink(entryId: String, windowSeconds: Long): ShareLink = withContext(Dispatchers.IO) {
        val entry = dao.entry(entryId) ?: throw AdapterException("That file is no longer indexed")
        if (entry.isFolder) throw AdapterException("A folder has no link. Open it and share an image.")
        val remoteKey = entry.remoteKey ?: throw AdapterException("No remote key recorded for this file")
        val p = entry.providerId?.let { dao.provider(it) }
            ?: throw AdapterException("That provider was removed")
        val spec = Catalog.byId(p.specId) ?: throw AdapterException("Unknown provider")
        val adapter = Adapters.build(spec, Json.decode(p.configJson), Json.decode(p.secretJson))
        val seconds = windowSeconds.coerceIn(1L, Lifetime.MAX_LINK_SECONDS)
        val now = System.currentTimeMillis()

        when (adapter) {
            is PCloudAdapter -> {
                val at = (now + seconds * 1000L) / 1000L
                ShareLink(
                    url = adapter.shareUrl(remoteKey, at, entry.name),
                    kind = ShareKind.NATIVE,
                    diesAt = at * 1000L,
                    note = "pCloud serves this one itself, so this phone can be off. " +
                        "Deleting the image ends the link immediately.",
                )
            }

            is S3Adapter -> ShareLink(
                url = adapter.shareUrl(remoteKey, seconds, entry.name),
                kind = ShareKind.SIGNED,
                diesAt = now + seconds * 1000L,
                note = "Anyone holding this URL can download until it expires. It is signed, " +
                    "not revocable — deleting the image is the only way to end it sooner.",
            )

            else -> throw AdapterException(
                "${p.label} has no link API, so a public URL is not possible here. " +
                    "Send it over Wi-Fi instead — the other phone has to be on this network.",
            )
        }
    }

    /**
     * Starts a one-file HTTP server on the local network and hands back the address to send.
     *
     * Reachable only with the switch in Settings turned on. Binds an ephemeral port, guards the
     * path with 128 random bits, and closes itself after [LAN_WINDOW_MS] or when the sheet is
     * dismissed, whichever comes first.
     */
    suspend fun startLan(entryId: String): LanLink = withContext(Dispatchers.IO) {
        val entry = dao.entry(entryId) ?: throw AdapterException("That file is no longer indexed")
        if (entry.isFolder) throw AdapterException("A folder cannot be served this way. Open it and send an image.")

        // The bytes have to be on this phone — that is the point of the handoff, not a detail.
        val file = originalFile(entry.id)

        stopLan()
        val server = LanShare.open(file, entry.mime ?: "image/jpeg", entry.name) { count ->
            _lan.update { it?.copy(served = count) }
        }
        val now = System.currentTimeMillis()
        val link = LanLink(
            url = server.url,
            fileName = entry.name,
            port = server.port,
            served = 0,
            startedAt = now,
            diesAt = now + LAN_WINDOW_MS,
        )
        lanShare = server
        _lan.value = link
        server.begin(scope)
        scope.launch {
            delay(LAN_WINDOW_MS)
            if (_lan.value?.url == link.url) stopLan()
        }
        link
    }

    fun stopLan() {
        lanShare?.stop()
        lanShare = null
        _lan.value = null
    }

    // ------------------------------------------------------------ placement

    /**
     * Two modes, deliberately different.
     *
     * `targetId == null` is automatic: fill largest free space first, keep a per-provider
     * reserve so nothing lands against a hard wall, and skip providers whose per-file cap
     * is smaller than this file. That is the "spread it out" behaviour.
     *
     * `targetId != null` is an instruction: use that provider or fail loudly. The reserve
     * is ignored — it is a guess about the future, not a wall — and so is Telegram's
     * app-side reservation. Only the provider's own limits still apply.
     */
    private suspend fun pickProvider(need: Long, targetId: String?): ProviderEntity {
        val mb = 1024L * 1024L

        if (targetId != null) {
            val chosen = dao.provider(targetId)
                ?: throw AdapterException("The provider you chose was removed. Pick another one, or Any available.")
            if (chosen.status != ProviderStatus.OK.name) {
                throw AdapterException("${chosen.label} is not verified. Check it on the Providers tab, then retry.")
            }
            val spec = Catalog.byId(chosen.specId)
            val cap = spec?.maxFileMb ?: 0
            if (cap > 0 && need > cap * mb) {
                throw AdapterException(
                    "${chosen.label} caps a file at $cap MB — this one is ${need / mb} MB. Pick a different provider.",
                )
            }
            if (spec?.appReservedQuota != true && chosen.usedBytes + need > chosen.quotaBytes) {
                throw AdapterException("${chosen.label} does not have room for this file. Pick a different provider.")
            }
            return chosen
        }

        val live = dao.liveProviders()
        if (live.isEmpty()) {
            throw AdapterException("No verified provider yet — add one in Settings first.")
        }
        val fits = live.filter { p ->
            val cap = Catalog.byId(p.specId)?.maxFileMb ?: 0
            cap == 0 || need <= cap * mb
        }
        if (fits.isEmpty()) {
            throw AdapterException("Every provider caps files below ${need / mb + 1} MB. Choose one yourself, or add another provider.")
        }
        val scored = fits.mapNotNull { p ->
            val reserve = max(gb, p.quotaBytes * 5 / 100)
            val free = p.quotaBytes - p.usedBytes - reserve
            if (free > need) p to free else null
        }
        return scored.maxByOrNull { it.second }?.first
            ?: throw AdapterException("Every provider is inside its safety reserve. Add another provider, or pick one by hand.")
    }

    // ------------------------------------------------------------ helpers

    private data class Picked(val name: String, val mime: String, val size: Long)

    /** Display name, MIME type and size straight from the content provider. */
    private fun describe(uri: Uri): Picked? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val label = name?.takeIf { it.isNotBlank() } ?: "IMG_${stamp()}.${extensionFor(mime)}"
        return Picked(label, mime, if (size > 0) size else 0L)
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(24)

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun extensionFor(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("heic") || mime.contains("heif") -> "heic"
        else -> "jpg"
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    }

    /** 512px thumbnail so the grid never decodes a 12 MP original. */
    private fun writeThumb(id: String, bytes: ByteArray): File? = runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        var sample = 1
        while (max(probe.outWidth, probe.outHeight) / sample > 1024) sample *= 2

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        val longest = max(bmp.width, bmp.height).coerceAtLeast(1)
        val scale = 512f / longest
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp

        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        out
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    private companion object {
        /** Short enough that a one-minute timer still feels like one. */
        const val SWEEP_EVERY_MS = 5_000L

        /** A Wi-Fi handoff is a minute-long errand, not a service. */
        const val LAN_WINDOW_MS = 30 * 60_000L
    }
}
