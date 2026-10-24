package app.freedrive.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thin OkHttp wrapper. Everything is a suspend function on IO. */
object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    data class Res(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        override fun toString(): String = "HTTP $code"
    }

    suspend fun exec(req: Request): Res = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r: Response ->
            Res(r.code, r.body?.string().orEmpty())
        }
    }

    suspend fun bytes(req: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message}")
            r.body?.bytes() ?: throw IOException("empty body")
        }
    }

    suspend fun empty(method: String, url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null): Res =
        exec(build(method, url, headers, body))

    fun build(method: String, url: String, headers: Map<String, String>, body: RequestBody?): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.method(method, body).build()
    }
}
