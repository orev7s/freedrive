package app.freedrive.data.adapters

import app.freedrive.data.net.Http
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Koofr is configured in this app as plain WebDAV, which is enough to move bytes but says
 * nothing about sharing — so a Koofr account used to look like any other WebDAV box and was
 * refused a link. It is not: Koofr has a real links API next to its WebDAV endpoint, and the
 * same app password opens both.
 *
 * Everything here goes to the REST API while the files themselves stay on WebDAV. The only
 * thing that has to be worked out at runtime is which mount the WebDAV path belongs to.
 */
object KoofrLinks {

    fun applies(baseUrl: String): Boolean =
        baseUrl.contains("koofr.net", ignoreCase = true) ||
            baseUrl.contains("koofr.eu", ignoreCase = true)

    private fun apiRoot(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        val scheme = trimmed.substringBefore("://", "https")
        val host = trimmed.substringAfter("://").substringBefore('/')
        return "$scheme://$host"
    }

    /** `https://app.koofr.net/dav/Koofr` → `Koofr`, the mount name the REST API asks for. */
    private fun mountName(baseUrl: String): String? {
        val afterDav = baseUrl.substringAfter("/dav/", "")
        return afterDav.substringBefore('/').takeIf { it.isNotBlank() }?.let(::decode)
    }

    private fun decode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    private fun headers(username: String, password: String): Headers =
        Headers.Builder()
            .add("Authorization", Credentials.basic(username, password))
            .add("X-Koofr-Version", "2.1")
            .build()

    /**
     * Creates (or finds) a public link for [path] — a WebDAV-relative path such as
     * `freedrive/blobs/abc.jpg`. Optional password and expiry are applied with a follow-up
     * call, because Koofr treats them as properties of an existing link.
     */
    suspend fun create(
        baseUrl: String,
        username: String,
        password: String,
        path: String,
        passwordForLink: String? = null,
        expiresAtSeconds: Long? = null,
    ): String {
        if (username.isBlank() || password.isBlank()) {
            throw AdapterException("Koofr needs the app password to make a link. Remove and re-add the account.")
        }
        val root = apiRoot(baseUrl)
        val auth = headers(username, password)

        val mountsRes = Http.exec(
            Request.Builder().url("$root/api/v2.1/mounts").headers(auth)
                .get()
                .build(),
        )
        if (!mountsRes.ok) throw AdapterException("Koofr refused the link request (HTTP ${mountsRes.code}).")

        val mountId = pickMount(mountsRes.body, mountName(baseUrl))
            ?: throw AdapterException("Could not find the Koofr mount that holds this file.")

        val cleanPath = "/" + path.trimStart('/')
        val body = JSONObject().put("path", cleanPath).toString()
        val created = Http.exec(
            Request.Builder()
                .url("$root/api/v2.1/mounts/$mountId/links")
                .headers(auth)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build(),
        )
        if (created.code !in 200..299) {
            throw AdapterException("Koofr did not create the link (HTTP ${created.code}). ${created.body.take(120)}")
        }

        val link = unwrapLink(created.body)
        val linkId = link.optString("id").takeIf { it.isNotBlank() }
        val url = link.optString("shortUrl").takeIf { it.isNotBlank() }
            ?: link.optString("url").takeIf { it.isNotBlank() }
            ?: throw AdapterException("Koofr created a link but returned no URL.")

        if (linkId != null && !passwordForLink.isNullOrBlank()) {
            val payload = JSONObject().put("password", passwordForLink).toString()
            Http.exec(
                Request.Builder()
                    .url("$root/api/v2.1/mounts/$mountId/links/$linkId/password/set")
                    .headers(auth)
                    .put(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build(),
            )
        }

        if (linkId != null && expiresAtSeconds != null) {
            val payload = JSONObject()
                .put("validFrom", System.currentTimeMillis() / 1000L)
                .put("validTo", expiresAtSeconds)
                .toString()
            Http.exec(
                Request.Builder()
                    .url("$root/api/v2.1/mounts/$mountId/links/$linkId/validity")
                    .headers(auth)
                    .put(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build(),
            )
        }

        return url
    }

    /** The API answers `{"mounts":[…]}` or a bare array depending on version. */
    private fun pickMount(body: String, preferred: String?): String? {
        val text = body.trim()
        val array: JSONArray = when {
            text.startsWith("[") -> JSONArray(text)
            else -> runCatching { JSONObject(text).optJSONArray("mounts") }.getOrNull() ?: JSONArray()
        }
        var fallback: String? = null
        var primary: String? = null
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = m.optString("name")
            if (preferred != null && name.equals(preferred, ignoreCase = true)) return id
            if (m.optBoolean("isPrimary")) primary = id
            if (fallback == null) fallback = id
        }
        return primary ?: fallback
    }

    private fun unwrapLink(body: String): JSONObject {
        val obj = JSONObject(body)
        return obj.optJSONObject("link") ?: obj
    }
}
