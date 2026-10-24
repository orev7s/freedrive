package app.freedrive.data.adapters

/**
 * Four methods. That is the entire surface a storage backend has to implement,
 * and it is why seven providers fit in one build.
 */
interface StorageAdapter {

    /** Cheap, side-effect free proof that the credentials work. */
    suspend fun verify(): Check

    /**
     * Writes one object. Returns the locator to persist in the index — usually the
     * key you passed in, but an adapter is free to return its own file id.
     *
     * `onProgress` receives cumulative bytes handed to the network, so the queue can
     * show one number per file that is true for every backend.
     */
    suspend fun put(
        name: String,
        bytes: ByteArray,
        mime: String,
        onProgress: (Long) -> Unit = {},
    ): String

    suspend fun get(remoteKey: String): ByteArray

    suspend fun delete(remoteKey: String)
}

data class Check(
    val ok: Boolean,
    val message: String,
    val freeBytes: Long? = null,
    val usedBytes: Long? = null,
    val detail: String? = null,
)

class AdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Human-readable failure text. The UI shows this verbatim, so keep it short. */
fun Throwable.readable(): String = when (this) {
    is AdapterException -> message ?: "Unknown error"
    is java.net.UnknownHostException -> "No connection to ${message ?: "the host"}"
    is java.net.SocketTimeoutException -> "Timed out — the provider did not answer"
    is javax.net.ssl.SSLException -> "TLS failed: ${message ?: "certificate rejected"}"
    else -> message?.take(180) ?: this::class.java.simpleName
}
