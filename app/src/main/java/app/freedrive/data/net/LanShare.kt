package app.freedrive.data.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Peer-to-peer without a peer-to-peer stack.
 *
 * WebRTC would need signalling, a STUN server and TURN for the half of the internet behind
 * symmetric NAT — three servers to move one image between two phones in the same room.
 * A plain HTTP server on the phone does the same job over the Wi-Fi you are both already on,
 * moves no bytes through anybody else's computer, and needs nothing to be set up.
 *
 * What it costs is honest and visible: the app has to stay open, both devices have to be on
 * the same network, and the window is short. That is why it is opt-in and why the cloud link
 * stays the default.
 */
class LanShare private constructor(
    val url: String,
    val port: Int,
    private val server: ServerSocket,
    private val token: String,
    private val file: File,
    private val mime: String,
    private val fileName: String,
    private val onServed: (Int) -> Unit,
) {

    private var acceptJob: Job? = null
    private val served = AtomicInteger(0)

    /** Accepts connections until [stop]. Every request is answered on its own coroutine. */
    fun begin(scope: CoroutineScope) {
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (t: Throwable) {
                    break
                }
                launch(Dispatchers.IO) { runCatching { serve(client) } }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server.close() }
    }

    private fun serve(client: java.net.Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val head = readHead(input)
            val requestLine = head.lineSequence().firstOrNull().orEmpty()
            val method = requestLine.substringBefore(' ').uppercase()
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()

            val out = socket.getOutputStream()

            if (path.trimEnd('/') != "/$token" || (method != "GET" && method != "HEAD")) {
                respond(out, 404, "text/plain; charset=utf-8", "Not found\n".toByteArray(), method == "HEAD")
                return
            }
            if (!file.exists() || file.length() <= 0L) {
                respond(out, 410, "text/plain; charset=utf-8", "That file is gone\n".toByteArray(), method == "HEAD")
                return
            }

            writeHead(out, 200, mime, file.length(), method == "HEAD")
            if (method == "GET") {
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            out.flush()

            if (method == "GET") {
                val n = served.incrementAndGet()
                onServed(n)
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean) {
        writeHead(out, code, type, body.size.toLong(), headOnly)
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, type: String, length: Long, headOnly: Boolean) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            410 -> "Gone"
            else -> "Error"
        }
        val safeName = fileName.replace("\"", "").replace("\n", " ").ifBlank { "image" }
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            append("Content-Disposition: attachment; filename=\"$safeName\"\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            if (headOnly) append("X-FreeDrive: head\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
    }

    /** Reads up to the blank line that ends the request head. Nothing after it is read. */
    private fun readHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream(1024)
        var tail = 0
        while (buffer.size() < 8192) {
            val b = input.read()
            if (b == -1) break
            buffer.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
        }
        return buffer.toString("ISO-8859-1")
    }

    companion object {

        /** Lowest random port is picked by the OS; the token is what guards the file. */
        fun open(file: File, mime: String, fileName: String, onServed: (Int) -> Unit): LanShare {
            val ip = lanAddress() ?: throw LanException(
                "No Wi-Fi address. Join a Wi-Fi network first — this cannot run over mobile data.",
            )
            val socket = ServerSocket(0, 8)
            val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            return LanShare(
                url = "http://$ip:${socket.localPort}/$token",
                port = socket.localPort,
                server = socket,
                token = token,
                file = file,
                mime = mime,
                fileName = fileName,
                onServed = onServed,
            )
        }

        /**
         * The private IPv4 address of the interface that is actually up. Site-local only:
         * 10/8, 172.16/12, 192.168/16 — a public address here would mean the phone is not
         * on a LAN and the link would be useless to anyone in the room.
         */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

class LanException(message: String) : Exception(message)
