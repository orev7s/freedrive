package app.freedrive.data

import app.freedrive.data.adapters.AdapterException
import app.freedrive.data.net.Http
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Anonymous file hosts, used only when the provider holding a file cannot mint a link.
 *
 * The honest part is [keeps]: these are other people's servers, running on donations, with no
 * contract and no way for this app to extend, refresh or protect anything it sends them. The
 * number is what the site itself publishes, checked against the live pages, and it is shown to
 * the user before the upload starts rather than buried in a help link.
 */
enum class TempHost(
    val label: String,
    /** Exactly what the site promises, in the site's own terms. */
    val keeps: String,
    val keepsMs: Long,
    val limitMb: Int,
) {
    LITTERBOX("Litterbox", "3 days", 72L * 60 * 60 * 1000, 1024),
    TMPFILES("tmpfiles.org", "1 hour", 60L * 60 * 1000, 100),
    UGUU("uguu.se", "3 hours", 3L * 60 * 60 * 1000, 128),
    ;

    companion object {
        /**
         * Sends the bytes and returns the URL the host handed back. Blocking; call on IO.
         *
         * Each host speaks a slightly different multipart dialect, which is the whole reason
         * this is a `when` and not a loop.
         */
        suspend fun upload(host: TempHost, bytes: ByteArray, name: String, mime: String): String {
            if (bytes.size > host.limitMb.toLong() * 1024 * 1024) {
                throw AdapterException(
                    "${host.label} takes up to ${host.limitMb} MB — this file is ${bytes.size / (1024 * 1024)} MB.",
                )
            }
            val part = bytes.toRequestBody(mime.toMediaTypeOrNull())
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)

            val url = when (host) {
                LITTERBOX -> {
                    form.addFormDataPart("reqtype", "fileupload")
                        .addFormDataPart("time", "72h")
                        .addFormDataPart("fileToUpload", name, part)
                    "https://litterbox.catbox.moe/resources/internals/api.php"
                }

                TMPFILES -> {
                    form.addFormDataPart("file", name, part)
                    "https://tmpfiles.org/api/v1/upload"
                }

                UGUU -> {
                    form.addFormDataPart("files[]", name, part)
                    "https://uguu.se/upload?output=text"
                }
            }

            val res = Http.exec(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .post(form.build())
                    .build(),
            )
            if (!res.ok) throw AdapterException("${host.label} refused the upload (HTTP ${res.code}).")

            val raw = res.body.trim()
            val link = when (host) {
                LITTERBOX, UGUU -> raw.lineSequence().firstOrNull { it.startsWith("http") }
                TMPFILES -> runCatching {
                    JSONObject(raw).getJSONObject("data").getString("url")
                }.getOrNull()
            } ?: throw AdapterException("${host.label} answered, but not with a link.")

            // tmpfiles returns a viewer page; the file itself lives under /dl/.
            return if (host == TMPFILES) link.replace("https://tmpfiles.org/", "https://tmpfiles.org/dl/") else link
        }

        /** Litterbox turns away obvious bots; a browser-shaped UA is the polite minimum. */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
