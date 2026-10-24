package app.freedrive.data.adapters

import app.freedrive.data.net.Http
import app.freedrive.data.net.ProgressBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Telegram through the Bot API: every file becomes a document in one chat.
 *
 * Two hard limits come with bots and both are enforced here rather than discovered later:
 * uploads are capped by Telegram's bot download limit, because a file the app cannot read
 * back is a file it should not have written. The locator keeps the message id as well as
 * the file id so a delete removes the message instead of orphaning the bytes in the chat.
 */
class TelegramAdapter(
    private val token: String,
    private val chatId: String,
    private val declaredQuota: Long,
) : StorageAdapter {

    private val base = "https://api.telegram.org/bot$token"

    // ---------------------------------------------------------------- transport

    /**
     * Methods with no arguments still have to send *something*: OkHttp refuses to build a
     * multipart body with no parts, which is why getMe used to fail before the token was
     * ever looked at. An empty body is what Telegram expects there anyway.
     */
    private suspend fun post(method: String, parts: Map<String, String>): JSONObject {
        val body = if (parts.isEmpty()) {
            ByteArray(0).toRequestBody(null)
        } else {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            parts.forEach { (k, v) -> form.addFormDataPart(k, v) }
            form.build()
        }
        val res = Http.exec(Request.Builder().url("$base/$method").post(body).build())
        return unwrap(res.code, res.body)
    }

    private fun unwrap(code: Int, body: String): JSONObject {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AdapterException("Telegram returned HTTP $code")
        if (!json.optBoolean("ok")) throw AdapterException(explain(code, json.optString("description")))
        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun explain(code: Int, description: String): String {
        val d = description.lowercase()
        return when {
            d.contains("chat not found") || d.contains("chat_id") ->
                "Telegram cannot see that chat — open your bot and press Start first"
            d.contains("unauthorized") || code == 401 -> "Telegram rejected the bot token"
            d.contains("too many requests") -> "Telegram is rate limiting — retry in a minute"
            d.contains("file is too big") || d.contains("file too large") -> "Telegram's bot API rejects this file size"
            d.contains("wrong file identifier") -> "Telegram no longer has this file"
            d.contains("message to delete not found") -> "The message is already gone"
            description.isNotBlank() -> "Telegram: $description"
            else -> "Telegram returned HTTP $code"
        }
    }

    // ---------------------------------------------------------------- adapter

    override suspend fun verify(): Check {
        val me = post("getMe", emptyMap())
        val username = me.optString("username").ifEmpty { "bot" }

        val chat = post("getChat", mapOf("chat_id" to chatId))
        val name = chat.optString("title")
            .ifEmpty { chat.optString("username") }
            .ifEmpty { listOf(chat.optString("first_name"), chat.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ") }
            .ifEmpty { "chat $chatId" }

        return Check(
            ok = true,
            message = "@$username → $name",
            freeBytes = null,
            detail = "No published quota · ${MAX_FILE_MB} MB per file",
        )
    }

    override suspend fun put(
        name: String,
        bytes: ByteArray,
        mime: String,
        onProgress: (Long) -> Unit,
    ): String {
        if (bytes.size > MAX_FILE_BYTES) {
            throw AdapterException(
                "Telegram accepts ${MAX_FILE_MB} MB per file in this app; ${name} is " +
                    "${bytes.size / (1024 * 1024)} MB",
            )
        }

        val document = ProgressBody(bytes, (mime.ifBlank { "application/octet-stream" }).toMediaTypeOrNull(), onProgress)
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", name.take(200))
            .addFormDataPart("document", name.substringAfterLast('/'), document)
            .build()

        val res = Http.exec(Request.Builder().url("$base/sendDocument").post(form).build())
        val result = unwrap(res.code, res.body)

        val documentId = result.optJSONObject("document")?.optString("file_id").orEmpty()
        if (documentId.isEmpty()) throw AdapterException("Telegram stored nothing for $name")

        return "$documentId|${result.optLong("message_id")}"
    }

    override suspend fun get(remoteKey: String): ByteArray {
        val parts = remoteKey.split('|')
        val documentId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: throw AdapterException("Malformed Telegram locator")

        val file = post("getFile", mapOf("file_id" to documentId))
        val path = file.optString("file_path")
        if (path.isEmpty()) throw AdapterException("Telegram gave no download path for this file")

        return Http.bytes(Request.Builder().url("https://api.telegram.org/file/bot$token/$path").build())
    }

    override suspend fun delete(remoteKey: String) {
        val messageId = remoteKey.split('|').getOrNull(1)?.toLongOrNull() ?: return
        runCatching { post("deleteMessage", mapOf("chat_id" to chatId, "message_id" to messageId.toString())) }
    }

    private companion object {
        /** Bot downloads stop at 20 MB, so uploads stop there too. */
        const val MAX_FILE_MB = 20
        const val MAX_FILE_BYTES = MAX_FILE_MB * 1024L * 1024L
    }
}
