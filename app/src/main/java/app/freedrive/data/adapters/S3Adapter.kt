package app.freedrive.data.adapters

import app.freedrive.data.net.Http
import app.freedrive.data.net.ProgressBody
import app.freedrive.data.net.SigV4
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One adapter, four providers: Backblaze B2, IDrive e2, Cloudflare R2, Oracle OCI.
 *
 * Path-style addressing is used everywhere (endpoint/bucket/key) because it is the
 * only form all four accept without a DNS wildcard.
 */
class S3Adapter(
    private val endpoint: String,
    private val region: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val declaredQuota: Long,
) : StorageAdapter {

    private fun objectUrl(key: String?): HttpUrl {
        val base = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw AdapterException("Endpoint is not a valid URL: $endpoint")
        val b = base.newBuilder()
        b.addPathSegment(bucket)
        if (!key.isNullOrEmpty()) {
            key.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        }
        return b.build()
    }

    private fun signed(
        method: String,
        url: HttpUrl,
        payload: ByteArray,
        contentType: String? = null,
        onProgress: (Long) -> Unit = {},
    ): Request {
        val headers = SigV4.sign(
            method = method,
            url = url,
            payload = payload,
            region = region,
            service = "s3",
            accessKey = accessKey,
            secretKey = secretKey,
            contentType = contentType,
        )
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        val body = if (method == "PUT") {
            ProgressBody(payload, contentType?.toMediaTypeOrNull(), onProgress)
        } else null
        return b.method(method, body).build()
    }

    override suspend fun verify(): Check {
        val url = objectUrl(null).newBuilder()
            .addQueryParameter("list-type", "2")
            .addQueryParameter("max-keys", "1")
            .addQueryParameter("prefix", ROOT + "/")
            .build()
        val res = Http.exec(signed("GET", url, ByteArray(0)))
        if (!res.ok) {
            return Check(false, describe(res.code, res.body), detail = res.body.take(300))
        }
        return Check(
            ok = true,
            message = "Bucket reachable on $region",
            freeBytes = declaredQuota,
            detail = "Path-style GET ?list-type=2 answered 200.",
        )
    }

    override suspend fun put(
        name: String,
        bytes: ByteArray,
        mime: String,
        onProgress: (Long) -> Unit,
    ): String {
        val res = Http.exec(signed("PUT", objectUrl(name), bytes, mime, onProgress))
        if (!res.ok) throw AdapterException("PUT $name failed: ${describe(res.code, res.body)}")
        return name
    }

    override suspend fun get(remoteKey: String): ByteArray =
        Http.bytes(signed("GET", objectUrl(remoteKey), ByteArray(0)))

    override suspend fun delete(remoteKey: String) {
        val res = Http.exec(signed("DELETE", objectUrl(remoteKey), ByteArray(0)))
        if (!res.ok && res.code != 404) throw AdapterException("DELETE failed: ${describe(res.code, res.body)}")
    }

    /**
     * A link the recipient can open in any browser, for `expiresSeconds`, with no
     * credentials in it. The signature is scoped to this one object and this one window.
     *
     * `expiresSeconds` is clamped to 604800 because that is the ceiling SigV4 itself
     * enforces — asking for a month would just come back as 403.
     */
    fun shareUrl(remoteKey: String, expiresSeconds: Long, downloadName: String?): String {
        val extra = if (downloadName.isNullOrBlank()) emptyMap() else mapOf(
            "response-content-disposition" to
                "attachment; filename=\"${downloadName.replace("\"", "").replace("\n", "")}\"",
        )
        return SigV4.presignGet(
            url = objectUrl(remoteKey),
            region = region,
            service = "s3",
            accessKey = accessKey,
            secretKey = secretKey,
            expiresSeconds = expiresSeconds.coerceIn(1L, 604_800L),
            extraQuery = extra,
        )
    }

    companion object {
        const val ROOT = "freedrive"

        /** S3 error bodies are XML; pull <Code> and <Message> out without pulling in a parser. */
        fun describe(code: Int, body: String): String {
            val c = Regex("<Code>(.*?)</Code>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val m = Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val tail = when {
                c != null && m != null -> "$c: $m"
                c != null -> c
                else -> body.trim().take(120).ifEmpty { "HTTP $code" }
            }
            return when {
                code == 403 -> "403 Forbidden — $tail (check the key, the secret and the bucket policy)"
                code == 404 -> "404 Not Found — $tail (check the bucket name and region)"
                code == 400 && body.contains("AuthorizationHeaderMalformed") ->
                    "Wrong region — the endpoint and the region must match"
                else -> "HTTP $code — $tail"
            }
        }
    }
}
