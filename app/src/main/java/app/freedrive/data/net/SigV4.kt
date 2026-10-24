package app.freedrive.data.net

import okhttp3.HttpUrl
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4, the ~90 lines that replace an SDK.
 *
 * B2, R2, IDrive e2 and OCI all speak this, so one signer covers four providers and
 * a few gigabytes of the total.
 */
object SigV4 {

    private val amzFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dayFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef".get(v shr 4)).append("0123456789abcdef".get(v and 0x0F))
        }
        return sb.toString()
    }

    fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    /** RFC 3986 encoding. AWS rejects '+', '*' and friends in canonical components. */
    fun encode(value: String, keepSlash: Boolean = false): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val unreserved = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~' || (keepSlash && ch == '/')
            if (unreserved) sb.append(ch) else sb.append('%').append("0123456789ABCDEF".get(c shr 4)).append("0123456789ABCDEF".get(c and 0x0F))
        }
        return sb.toString()
    }

    /**
     * Returns every header the request needs, including Authorization. Caller applies them.
     */
    fun sign(
        method: String,
        url: HttpUrl,
        payload: ByteArray,
        region: String,
        service: String,
        accessKey: String,
        secretKey: String,
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val now = Instant.ofEpochMilli(nowMillis)
        val amzDate = amzFmt.format(now)
        val day = dayFmt.format(now)
        val payloadHash = sha256Hex(payload)

        val host = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"

        val canonicalUri = url.encodedPath.ifEmpty { "/" }

        val query = StringBuilder()
        val names = url.queryParameterNames.sorted()
        names.forEachIndexed { ni, name ->
            val values = url.queryParameterValues(name).map { it ?: "" }.sorted()
            values.forEachIndexed { vi, v ->
                if (ni > 0 || vi > 0) query.append('&')
                query.append(encode(name)).append('=').append(encode(v))
            }
        }

        val signed = sortedMapOf<String, String>()
        signed["host"] = host
        signed["x-amz-content-sha256"] = payloadHash
        signed["x-amz-date"] = amzDate
        if (contentType != null) signed["content-type"] = contentType
        extraHeaders.forEach { (k, v) -> signed[k.lowercase()] = v.trim() }

        val canonicalHeaders = buildString {
            signed.forEach { (k, v) -> append(k).append(':').append(v).append('\n') }
        }
        val signedHeaders = signed.keys.joinToString(";")

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            query.toString(),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$day/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)),
        ).joinToString("\n")

        var key = "AWS4$secretKey".toByteArray(StandardCharsets.UTF_8)
        key = hmac(key, day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, stringToSign))

        val auth = "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        val out = LinkedHashMap<String, String>()
        out["Host"] = host
        out["x-amz-date"] = amzDate
        out["x-amz-content-sha256"] = payloadHash
        if (contentType != null) out["Content-Type"] = contentType
        extraHeaders.forEach { (k, v) -> out[k] = v }
        out["Authorization"] = auth
        return out
    }

    /**
     * A GET URL that anyone can open for the next `expiresSeconds`, with no key in it.
     *
     * This is the query-string flavour of SigV4: the signature moves out of a header and
     * into the URL, the payload hash becomes the literal UNSIGNED-PAYLOAD, and only the
     * host is signed. One link, one object, one deadline — and no way to revoke it early,
     * because nothing on the server records that it exists.
     *
     * Returns the finished string rather than an HttpUrl on purpose: every component is
     * already RFC 3986 encoded here, and handing it back through HttpUrl would encode it
     * a second time.
     */
    fun presignGet(
        url: HttpUrl,
        region: String,
        service: String,
        accessKey: String,
        secretKey: String,
        expiresSeconds: Long,
        extraQuery: Map<String, String> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val now = Instant.ofEpochMilli(nowMillis)
        val amzDate = amzFmt.format(now)
        val day = dayFmt.format(now)
        val scope = "$day/$region/$service/aws4_request"
        val host = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"

        val params = sortedMapOf<String, String>()
        url.queryParameterNames.forEach { n -> params[n] = url.queryParameterValues(n).firstOrNull() ?: "" }
        extraQuery.forEach { (k, v) -> params[k] = v }
        params["X-Amz-Algorithm"] = "AWS4-HMAC-SHA256"
        params["X-Amz-Credential"] = "$accessKey/$scope"
        params["X-Amz-Date"] = amzDate
        params["X-Amz-Expires"] = expiresSeconds.toString()
        params["X-Amz-SignedHeaders"] = "host"

        val canonicalQuery = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val canonicalHeaders = "host:$host\n"
        val canonicalRequest = listOf(
            "GET",
            url.encodedPath.ifEmpty { "/" },
            canonicalQuery,
            canonicalHeaders,
            "host",
            "UNSIGNED-PAYLOAD",
        ).joinToString("\n")

        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)),
        ).joinToString("\n")

        var key = "AWS4$secretKey".toByteArray(StandardCharsets.UTF_8)
        key = hmac(key, day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, stringToSign))

        return "${url.scheme}://$host${url.encodedPath.ifEmpty { "/" }}?$canonicalQuery&X-Amz-Signature=$signature"
    }
}
