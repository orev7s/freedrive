package app.freedrive.data.adapters

import app.freedrive.data.AdapterKind
import app.freedrive.data.ProviderSpec
import org.json.JSONObject

object Adapters {

    const val GB: Long = 1024L * 1024L * 1024L

    fun build(spec: ProviderSpec, config: Map<String, String>, secret: Map<String, String>): StorageAdapter {
        val all = config + secret

        fun need(key: String, label: String): String {
            val v = all[key]?.trim().orEmpty()
            if (v.isEmpty()) throw AdapterException("$label is missing")
            return v
        }

        return when (spec.kind) {
            AdapterKind.S3 -> S3Adapter(
                endpoint = need("endpoint", "Endpoint"),
                region = all["region"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "us-east-1",
                bucket = need("bucket", "Bucket name"),
                accessKey = need("accessKey", "Access key ID"),
                secretKey = need("secretKey", "Secret access key"),
                declaredQuota = spec.freeGb.toLong() * GB,
            )

            AdapterKind.WEBDAV -> WebDavAdapter(
                baseUrl = need("baseUrl", "WebDAV URL"),
                username = need("username", "Username"),
                password = need("password", "Password"),
                declaredQuota = spec.freeGb.toLong() * GB,
            )

            AdapterKind.PCLOUD -> PCloudAdapter(
                host = all["host"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "api.pcloud.com",
                username = all["username"]?.trim().orEmpty(),
                password = all["password"].orEmpty(),
                preSharedToken = all["token"]?.takeUnless { it.isNullOrEmpty() },
                declaredQuota = spec.freeGb.toLong() * GB,
            )

            AdapterKind.TELEGRAM -> TelegramAdapter(
                token = need("token", "Bot token"),
                chatId = need("chat", "Chat ID"),
                declaredQuota = spec.freeGb.toLong() * GB,
            )
        }
    }
}

/** Tiny JSON map codec — avoids a serialization dependency for two flat objects. */
object Json {
    fun encode(map: Map<String, String>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k, "") }
        return out
    }
}
