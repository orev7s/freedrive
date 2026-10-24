package app.freedrive.data.net

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Byte array that reports how much of itself has left the phone.
 *
 * Upload progress has to come from the socket, not from a timer that pretends — this is
 * why the queue can show real numbers for every provider.
 */
class ProgressBody(
    private val bytes: ByteArray,
    private val type: MediaType?,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - offset)
            sink.write(bytes, offset, n)
            offset += n
            onProgress(offset.toLong())
        }
    }
}
