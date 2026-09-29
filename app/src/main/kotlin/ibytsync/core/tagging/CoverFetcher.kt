package ibytsync.core.tagging

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object CoverFetcher {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val client = ibytsync.core.network.SharedHttpClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun sniffMime(data: ByteArray): String? = when {
        data.size >= 3 && data.sliceArray(0..2).contentEquals(JPEG_MAGIC) -> "image/jpeg"
        data.size >= 8 && data.sliceArray(0..7).contentEquals(PNG_MAGIC) -> "image/png"
        else -> null
    }

    fun fetch(
        url: String,
        streamOpener: ((String) -> java.io.InputStream?)? = null
    ): ByteArray? {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val opener = streamOpener ?: return null
            return try {
                val ins = opener(url) ?: return null
                readStream(ins)
            } catch (_: Exception) {
                null
            }
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                readStream(body.byteStream())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readStream(ins: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var total = 0
        ins.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return null
                out.write(buf, 0, n)
            }
        }
        val data = out.toByteArray()
        return if (sniffMime(data) == null) null else data
    }
}
