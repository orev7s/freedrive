package app.freedrive.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.freedrive.data.AdapterKind
import app.freedrive.data.Catalog
import app.freedrive.data.FreeDriveRepo
import app.freedrive.data.LanLink
import app.freedrive.data.Lifetime
import app.freedrive.data.ProviderSpec
import app.freedrive.data.ShareLink
import app.freedrive.data.TempHost
import app.freedrive.data.adapters.readable
import app.freedrive.data.db.EntryEntity
import app.freedrive.data.db.ProviderEntity
import app.freedrive.data.db.UploadEntity
import app.freedrive.data.db.UploadState
import app.freedrive.ui.theme.ThemeMode
import app.freedrive.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

enum class Tab { FILES, PROVIDERS, SETTINGS }

sealed interface Route {
    data object Root : Route
    data object Catalog : Route
    data class AddProvider(val spec: ProviderSpec) : Route
    data class Tutorial(val spec: ProviderSpec) : Route
}

/**
 * One holder for the whole app. Three observed streams (folder contents, providers, queue)
 * plus a folder cursor. The upload queue is read straight from the repository, so this
 * class never owns a transfer and nothing here can cancel one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppState(
    val repo: FreeDriveRepo,
    val theme: ThemeStore,
    private val targets: UploadTargetStore,
    private val sharing: SharePrefs,
    private val scope: CoroutineScope,
) {

    var tab by mutableStateOf(Tab.FILES)
        private set
    var route by mutableStateOf<Route>(Route.Root)
        private set

    var folderId by mutableStateOf<String?>(null)
        private set
    var path by mutableStateOf<List<EntryEntity>>(emptyList())
        private set
    var items by mutableStateOf<List<EntryEntity>>(emptyList())
        private set

    var providers by mutableStateOf<List<ProviderEntity>>(emptyList())
        private set
    var used by mutableStateOf(0L)
        private set
    var quota by mutableStateOf(0L)
        private set

    /** Whole queue, oldest first. DONE and FAILED rows stay until dismissed. */
    var uploads by mutableStateOf<List<UploadEntity>>(emptyList())
        private set
    var live by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    var previewId by mutableStateOf<String?>(null)
        private set
    var preview by mutableStateOf<EntryEntity?>(null)
        private set

    /** null means "any available": the repository picks by free space. */
    var targetProviderId by mutableStateOf<String?>(null)
        private set
    var rememberTarget by mutableStateOf(false)
        private set

    var newFolderOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var confirmDelete by mutableStateOf<EntryEntity?>(null)

    // ---- sharing and self-destruct

    /** The image the share sheet is open for. Null means the sheet is closed. */
    var shareFor by mutableStateOf<EntryEntity?>(null)
        private set
    var shareLifetime by mutableStateOf(Lifetime.KEEP)
        private set
    var shareLink by mutableStateOf<ShareLink?>(null)
        private set
    var shareBusy by mutableStateOf(false)
        private set
    var lanLink by mutableStateOf<LanLink?>(null)
        private set

    /** Failures while the sheet is open are shown inside it — a snackbar would be behind it. */
    var shareError by mutableStateOf<String?>(null)
        private set

    /** The Wi-Fi handoff switch. Off until someone turns it on, and off again after a rebuild. */
    var wifiSending by mutableStateOf(false)
        private set

    /** Timer applied to the next upload only; existing files keep whatever they already have. */
    var defaultLifetime by mutableStateOf(Lifetime.KEEP)
        private set

    /** Only provider work blocks the UI now. Uploads never do. */
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    init {
        // Restore the selector only when the box was ticked last time. A one-off choice is
        // deliberately not remembered: every fresh start begins at "Any available".
        rememberTarget = targets.remembering
        if (rememberTarget) {
            targetProviderId = targets.rememberedTarget?.takeIf { it.isNotBlank() }
        }

        wifiSending = sharing.wifiSending
        defaultLifetime = Lifetime.nearest(sharing.defaultLifetimeMs.takeIf { it > 0L })

        scope.launch {
            repo.providers().collect { list ->
                providers = list
                val chosen = targetProviderId
                if (chosen != null && list.none { it.id == chosen }) {
                    // The provider was removed while selected. Fall back instead of failing
                    // every upload with "that provider was removed".
                    targetProviderId = null
                    if (rememberTarget) targets.save(null, true)
                }
            }
        }
        scope.launch {
            combine(repo.usedTotal(), repo.quotaTotal()) { u, q -> u to q }.collect {
                used = it.first
                quota = it.second
            }
        }
        scope.launch {
            snapshotFlow { folderId }
                .flatMapLatest { id -> repo.children(id) }
                .collect { items = it }
        }
        scope.launch {
            snapshotFlow { folderId }.collect { path = repo.path(it) }
        }
        scope.launch { repo.uploads().collect { uploads = it } }
        scope.launch { repo.live.collect { live = it } }
        scope.launch { repo.lan.collect { lanLink = it } }
        scope.launch {
            snapshotFlow { previewId }
                .flatMapLatest { id -> if (id == null) flowOf<EntryEntity?>(null) else repo.entryFlow(id) }
                .collect {
                    preview = it
                    if (it == null) previewId = null
                }
        }
    }

    // ------------------------------------------------------------ derived

    val verifiedCount: Int get() = providers.count { it.status == "OK" }
    val freeBytes: Long get() = (quota - used).coerceAtLeast(0L)

    val pending: List<UploadEntity>
        get() = uploads.filter { it.state == UploadState.QUEUED || it.state == UploadState.RUNNING }

    val pendingCount: Int get() = pending.size

    /** Whole-queue progress, summing only what is still moving. */
    val queueFraction: Float
        get() {
            val rows = pending
            if (rows.isEmpty()) return 1f
            val total = rows.sumOf { it.size }
            val sent = rows.sumOf { bytesOf(it).coerceAtMost(it.size) }
            return if (total <= 0L) 0f else (sent.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }

    fun bytesOf(upload: UploadEntity): Long = live[upload.id] ?: upload.sentBytes

    val folderTitle: String get() = path.lastOrNull()?.name ?: "FreeDrive"

    fun specOf(provider: ProviderEntity): ProviderSpec? = Catalog.byId(provider.specId)

    /** How many accounts of this provider are connected. Any number is allowed. */
    fun countOf(spec: ProviderSpec): Int = providers.count { it.specId == spec.id }

    // ------------------------------------------------------------ navigation

    fun tab(next: Tab) {
        tab = next
        route = Route.Root
    }

    fun openCatalog() {
        route = Route.Catalog
    }

    fun openAdd(spec: ProviderSpec) {
        route = Route.AddProvider(spec)
    }

    fun openTutorial(spec: ProviderSpec) {
        route = Route.Tutorial(spec)
    }

    fun back() {
        route = when (val current = route) {
            is Route.Tutorial -> Route.AddProvider(current.spec)
            else -> Route.Root
        }
    }

    fun open(entry: EntryEntity) {
        if (entry.isFolder) folderId = entry.id else previewId = entry.id
    }

    fun goUp() {
        folderId = path.dropLast(1).lastOrNull()?.id
    }

    fun closePreview() {
        previewId = null
    }

    fun setTheme(mode: ThemeMode) = theme.set(mode)

    // ------------------------------------------------------------ upload target

    /** Choosing is always instant; only a ticked Remember box writes it to disk. */
    fun setTarget(providerId: String?) {
        targetProviderId = providerId
        if (rememberTarget) targets.save(providerId, true)
    }

    fun setRemember(on: Boolean) {
        rememberTarget = on
        targets.save(if (on) targetProviderId else null, on)
    }

    fun labelFor(providerId: String?): String =
        providers.firstOrNull { it.id == providerId }?.label ?: "any provider"

    // ------------------------------------------------------------ actions

    /** Instant: the repository owns the transfer from here. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val lifetime = defaultLifetime.ms.takeIf { it > 0L }
        scope.launch {
            val added = runCatching { repo.enqueue(uris, folderId, targetProviderId, lifetime) }
                .onFailure { error = it.readable() }
                .getOrDefault(0)
            if (added > 0) queueOpen = true
        }
    }

    /** Returns an error message, or null when the folder was created. */
    suspend fun createFolder(name: String): String? =
        runCatching { repo.createFolder(name, folderId) }
            .exceptionOrNull()
            ?.readable()

    fun delete(entry: EntryEntity) {
        confirmDelete = null
        if (previewId == entry.id) previewId = null
        scope.launch {
            runCatching { repo.delete(entry.id) }.onFailure { error = it.readable() }
        }
    }

    fun addProvider(spec: ProviderSpec, values: Map<String, String>) {
        if (busy != null) return
        scope.launch {
            busy = "Checking ${spec.name}"
            val failure = runCatching { repo.addProvider(spec, values) }.exceptionOrNull()
            busy = null
            if (failure == null) {
                route = Route.Root
                tab = Tab.PROVIDERS
            } else {
                error = failure.readable()
            }
        }
    }

    fun checkAll() {
        if (busy != null) return
        scope.launch {
            busy = "Checking providers"
            runCatching { repo.checkAll() }.onFailure { error = it.readable() }
            busy = null
        }
    }

    fun recheck(providerId: String) {
        scope.launch {
            runCatching { repo.check(providerId) }.onFailure { error = it.readable() }
        }
    }

    fun removeProvider(providerId: String) {
        scope.launch {
            runCatching { repo.removeProvider(providerId) }.onFailure { error = it.readable() }
        }
    }

    fun retry(uploadId: String) {
        scope.launch { runCatching { repo.retryUpload(uploadId) }.onFailure { error = it.readable() } }
    }

    fun dismissUpload(uploadId: String) {
        scope.launch { runCatching { repo.removeUpload(uploadId) }.onFailure { error = it.readable() } }
    }

    fun clearFinished() {
        scope.launch { runCatching { repo.clearFinishedUploads() }.onFailure { error = it.readable() } }
    }

    // ------------------------------------------------------------ sharing

    /**
     * Opens the share sheet on a timer that already makes sense for this file: whatever it has
     * left, or a day when it has no deadline at all. The chips stay a visible choice — nothing
     * is committed until the button is pressed.
     */
    fun openShare(entry: EntryEntity) {
        shareFor = entry
        shareLink = null
        shareError = null
        shareLifetime = entry.expiresAt
            ?.let { Lifetime.nearest(it - System.currentTimeMillis()) }
            ?: Lifetime.ONE_DAY
    }

    fun closeShare() {
        shareFor = null
        shareLink = null
        shareError = null
        repo.stopLan()
    }

    fun chooseShareLifetime(option: Lifetime) {
        shareLifetime = option
    }

    /**
     * One button, two effects, on purpose: it arms the timer on the file and then mints a link
     * that cannot outlive it. The clipboard never gets a URL to something already deleted.
     */
    fun createLink() {
        val entry = shareFor ?: return
        if (shareBusy) return
        val lifetime = shareLifetime.ms
        scope.launch {
            shareBusy = true
            shareLink = null
            shareError = null
            val seconds = if (lifetime > 0L) lifetime / 1000L else Lifetime.MAX_LINK_SECONDS
            runCatching { repo.armAndShare(entry.id, lifetime.takeIf { it > 0L }, seconds) }
                .onSuccess { shareLink = it }
                .onFailure { shareError = it.readable() }
            shareBusy = false
        }
    }

    // ------------------------------------------------------------ when the provider cannot link

    /** True for pCloud and the S3 family; false for Telegram and plain WebDAV. */
    val shareProviderCanLink: Boolean
        get() {
            val provider = shareFor?.providerId
                ?.let { id -> providers.firstOrNull { it.id == id } }
                ?: return true
            val kind = specOf(provider)?.kind ?: return true
            return kind == AdapterKind.PCLOUD || kind == AdapterKind.S3
        }

    /** Other providers of yours that can actually mint a link, for a mirror to land on. */
    val shareTargets: List<ProviderEntity>
        get() = providers.filter { p ->
            p.id != shareFor?.providerId &&
                p.status == "OK" &&
                (specOf(p)?.kind == AdapterKind.PCLOUD || specOf(p)?.kind == AdapterKind.S3)
        }

    /**
     * Copies the file to an anonymous host. Slow — the bytes are read back from the provider and
     * sent again — and the result is on somebody else's server, which is why the retention is
     * printed before the tap as well as after it.
     */
    fun shareToTemp(host: TempHost) {
        val entry = shareFor ?: return
        if (shareBusy) return
        scope.launch {
            shareBusy = true
            shareLink = null
            shareError = null
            runCatching { repo.shareViaTemp(entry.id, host) }
                .onSuccess { shareLink = it }
                .onFailure { shareError = it.readable() }
            shareBusy = false
        }
    }

    /** Copies the file to a provider that can link, then links from there. */
    fun shareToMirror(target: ProviderEntity) {
        val entry = shareFor ?: return
        if (shareBusy) return
        val lifetime = shareLifetime.ms
        val seconds = if (lifetime > 0L) lifetime / 1000L else Lifetime.MAX_LINK_SECONDS
        scope.launch {
            shareBusy = true
            shareLink = null
            shareError = null
            runCatching { repo.shareViaMirror(entry.id, target.id, seconds) }
                .onSuccess { shareLink = it }
                .onFailure { shareError = it.readable() }
            shareBusy = false
        }
    }

    /**
     * The peer-to-peer path. No relay, no server of anybody's — this phone answers on the
     * local network for as long as the sheet is open.
     */
    fun sendOverWifi() {        val entry = shareFor ?: return
        if (shareBusy) return
        scope.launch {
            shareBusy = true
            shareError = null
            runCatching { repo.startLan(entry.id) }.onFailure { shareError = it.readable() }
            shareBusy = false
        }
    }

    fun stopWifi() = repo.stopLan()

    fun enableWifiSending(on: Boolean) {
        wifiSending = on
        sharing.wifiSending = on
        if (!on) repo.stopLan()
    }

    fun chooseDefaultLifetime(option: Lifetime) {
        defaultLifetime = option
        sharing.defaultLifetimeMs = option.ms
    }

    fun dismissError() {
        error = null
    }
}
