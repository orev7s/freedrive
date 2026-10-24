package app.freedrive.data.adapters

import app.freedrive.data.net.Http
import app.freedrive.data.net.ProgressBody
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV: Koofr, Nextcloud, Box, HiDrive, anything that speaks RFC 4918.
 *
 * Only three verbs are needed for a working drive — PROPFIND to check, MKCOL to
 * make the two folders FreeDrive owns, PUT/GET/DELETE for objects.
 */
class WebDavAdapter(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val declaredQuota: Long,
) : StorageAdapter {

    private val auth: String = Credentials.basic(username, password)

    private fun urlFor(path: String?): HttpUrl {
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw AdapterException("WebDAV URL is not valid: $baseUrl")
        val b = base.newBuilder()
        if (!path.isNullOrEmpty()) path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    override suspend fun verify(): Check {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:quota-available-bytes/><d:quota-used-bytes/><d:resourcetype/>
            </d:prop></d:propfind>
        """.trimIndent()

        val req = Request.Builder()
            .url(urlFor(null))
            .header("Authorization", auth)
            .header("Depth", "0")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
            .build()

        val res = Http.exec(req)
        if (res.code !in 200..299) {
            val hint = when (res.code) {
                401 -> "401 — the username or app password is wrong"
                403 -> "403 — this account is not allowed to use WebDAV"
                404 -> "404 — the WebDAV path is wrong"
                405 -> "405 — that URL is not a WebDAV endpoint"
                else -> "HTTP ${res.code}"
            }
            return Check(false, hint, detail = res.body.take(300))
        }

        val free = Regex("quota-available-bytes[^>]*>\\s*(\\d+)").find(res.body)?.groupValues?.get(1)?.toLongOrNull()
        val used = Regex("quota-used-bytes[^>]*>\\s*(\\d+)").find(res.body)?.groupValues?.get(1)?.toLongOrNull()

        return Check(
            ok = true,
            message = if (free != null) "WebDAV reachable, ${fmt(free)} free" else "WebDAV reachable (quota not reported)",
            freeBytes = free ?: declaredQuota.takeIf { it > 0 },
            usedBytes = used,
            detail = if (free == null) "This server does not expose quota-available-bytes; using the configured size." else null,
        )
    }

    private suspend fun mkcol(path: String) {
        val res = Http.empty("MKCOL", urlFor(path).toString(), mapOf("Authorization" to auth))
        // 405 = already exists. 301/302 happen on servers that normalise trailing slashes.
        if (res.code !in listOf(200, 201, 204, 301, 302, 405)) {
            throw AdapterException("Could not create $path (HTTP ${res.code})")
        }
    }

    override suspend fun put(
        name: String,
        bytes: ByteArray,
        mime: String,
        onProgress: (Long) -> Unit,
    ): String {
        mkcol(ROOT)
        mkcol("$ROOT/$BLOBS")

        val req = Request.Builder()
            .url(urlFor(name))
            .header("Authorization", auth)
            .put(ProgressBody(bytes, mime.toMediaTypeOrNull(), onProgress))
            .build()

        val res = Http.exec(req)
        if (!res.ok) throw AdapterException("Upload failed: HTTP ${res.code} ${res.body.take(120)}")
        return name
    }

    override suspend fun get(remoteKey: String): ByteArray {
        val req = Request.Builder().url(urlFor(remoteKey)).header("Authorization", auth).get().build()
        return Http.bytes(req)
    }

    override suspend fun delete(remoteKey: String) {
        val res = Http.empty("DELETE", urlFor(remoteKey).toString(), mapOf("Authorization" to auth))
        if (!res.ok && res.code != 404) throw AdapterException("Delete failed: HTTP ${res.code}")
    }

    private fun fmt(b: Long): String = when {
        b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L -> "%.0f MB".format(b / 1_048_576.0)
        else -> "$b B"
    }

    companion object {
        const val ROOT = "freedrive"
        const val BLOBS = "blobs"
    }
}
