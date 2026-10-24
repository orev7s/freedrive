package app.freedrive.data.adapters

import app.freedrive.data.net.Http
import app.freedrive.data.net.ProgressBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * pCloud: the one pure login-token API in this build.
 *
 * E-mail + password are traded for a token once; the token is what the index keeps.
 * Two folder levels are created on first use, then every object is a file id.
 */
class PCloudAdapter(
    private val host: String,
    private val username: String,
    private val password: String,
    private val preSharedToken: String? = null,
    private val declaredQuota: Long,
) : StorageAdapter {

    private var token: String? = preSharedToken
    private var blobsFolderId: String? = null

    private fun api(path: String, params: List<Pair<String, String>>): HttpUrl {
        val b = "https://${host.trim().removePrefix("https://").removeSuffix("/")}/$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun auth(): String {
        token?.let { return it }
        val url = api("login", listOf("username" to username, "password" to password, "getauth" to "1", "logout" to "1"))
        val res = Http.exec(Request.Builder().url(url).get().build())
        val json = runCatching { JSONObject(res.body) }.getOrElse {
            throw AdapterException("pCloud returned something that is not JSON (HTTP ${res.code})")
        }
        if (json.optInt("result", -1) != 0) {
            throw AdapterException("pCloud login failed: ${json.optString("error", "result ${json.optInt("result")}")}")
        }
        val t = json.optString("auth").ifEmpty { throw AdapterException("pCloud login returned no token") }
        token = t
        return t
    }

    /** The token, for the caller to persist so the password need not be kept. */
    suspend fun exchangeToken(): String = auth()

    private suspend fun call(path: String, params: List<Pair<String, String>> = emptyList()): JSONObject {
        val all = params + ("auth" to auth())
        val res = Http.exec(Request.Builder().url(api(path, all)).get().build())
        val json = runCatching { JSONObject(res.body) }.getOrElse {
            throw AdapterException("pCloud: unreadable response (HTTP ${res.code})")
        }
        val r = json.optInt("result", -1)
        if (r != 0) throw AdapterException("pCloud: ${json.optString("error", "result $r")}")
        return json
    }

    override suspend fun verify(): Check {
        val info = call("userinfo")
        val quota = info.optLong("quota", 0L)
        val used = info.optLong("usedquota", 0L)
        return Check(
            ok = true,
            message = "Signed in as ${info.optString("email", username)}",
            freeBytes = if (quota > 0) quota else declaredQuota,
            usedBytes = used,
            detail = if (quota > 0) "pCloud reports ${fmt(quota - used)} free" else null,
        )
    }

    private suspend fun blobsFolder(): String {
        blobsFolderId?.let { return it }
        val root = call("createfolderifnotexists", listOf("folderid" to "0", "name" to "FreeDrive"))
            .optJSONObject("metadata")?.optString("folderid")
            ?: throw AdapterException("pCloud did not return a folder id")
        val blobs = call("createfolderifnotexists", listOf("folderid" to root, "name" to "blobs"))
            .optJSONObject("metadata")?.optString("folderid")
            ?: throw AdapterException("pCloud did not return a folder id")
        blobsFolderId = blobs
        return blobs
    }

    override suspend fun put(
        name: String,
        bytes: ByteArray,
        mime: String,
        onProgress: (Long) -> Unit,
    ): String {
        val folder = blobsFolder()
        val fileName = name.substringAfterLast('/')
        val url = api(
            "uploadfile",
            listOf(
                "auth" to auth(),
                "folderid" to folder,
                "filename" to fileName,
                "nopartial" to "1",
                "mtime" to (System.currentTimeMillis() / 1000).toString(),
            ),
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, ProgressBody(bytes, mime.toMediaTypeOrNull(), onProgress))
            .build()
        val res = Http.exec(Request.Builder().url(url).post(body).build())
        val json = runCatching { JSONObject(res.body) }.getOrElse {
            throw AdapterException("pCloud upload: unreadable response (HTTP ${res.code})")
        }
        val r = json.optInt("result", -1)
        if (r != 0) throw AdapterException("pCloud upload failed: ${json.optString("error", "result $r")}")
        val fileId = json.optJSONArray("metadata")?.optJSONObject(0)?.optString("fileid")
            ?: json.optJSONObject("metadata")?.optString("fileid")
            ?: throw AdapterException("pCloud upload returned no file id")
        return "$folder|$fileId|$fileName"
    }

    override suspend fun get(remoteKey: String): ByteArray {
        val fileId = remoteKey.split('|').getOrNull(1)
            ?: throw AdapterException("Malformed pCloud locator")
        val json = call("getfilelink", listOf("fileid" to fileId))
        val h = json.optJSONArray("hosts")?.optString(0)?.ifEmpty { null }
            ?: throw AdapterException("pCloud returned no host for that file")
        val path = json.optString("path")
        // pCloud hands back a bare hostname (c123.pcloud.com); some responses include the scheme.
        val base = if (h.startsWith("http")) h else "https://$h"
        return Http.bytes(Request.Builder().url(base + path).get().build())
    }

    override suspend fun delete(remoteKey: String) {
        val fileId = remoteKey.split('|').getOrNull(1) ?: return
        call("deletefile", listOf("fileid" to fileId))
    }

    /**
     * pCloud makes its own public link — the bytes come from pCloud's CDN, not from the
     * phone, and `expire` is a hard server-side deadline in unix seconds.
     *
     * The response shape has moved around between API versions, so this reads `link` at
     * both levels and falls back to rebuilding the URL from `code`. If neither is there,
     * it fails loudly with what the server actually said instead of handing back a
     * plausible-looking dead URL.
     */
    suspend fun shareUrl(remoteKey: String, expireAtSeconds: Long, downloadName: String?): String {
        val fileId = remoteKey.split('|').getOrNull(1)
            ?: throw AdapterException("Malformed pCloud locator")
        val params = buildList {
            add("fileid" to fileId)
            add("expire" to expireAtSeconds.toString())
            if (!downloadName.isNullOrBlank()) add("name" to downloadName)
        }
        val json = call("getfilepublink", params)
        val body = json.optJSONObject("publink") ?: json
        val link = body.optString("link").ifEmpty { "" }
        val code = body.optString("code").ifEmpty { "" }

        return when {
            link.contains("code=") -> link
            link.isNotEmpty() && code.isNotEmpty() -> "$link?code=$code"
            code.isNotEmpty() -> "https://u.pcloud.link/publink/show?code=$code"
            else -> throw AdapterException(
                "pCloud accepted the request but returned no link: ${json.toString().take(160)}",
            )
        }
    }

    private fun fmt(b: Long): String = when {
        b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L -> "%.0f MB".format(b / 1_048_576.0)
        else -> "$b B"
    }
}
