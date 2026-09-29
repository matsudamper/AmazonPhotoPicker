package net.matsudamper.amazonphotopicker

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class DownloadedImage(
    val file: File,
    val mimeType: String,
)

/**
 * WebViewのCookieを使って画像をダウンロードし、キャッシュディレクトリに保存する。
 */
class ImageDownloader(context: Context) {
    private val dir = File(context.cacheDir, "picked")

    /** 候補URLを順に試し、最初に画像が取得できたものを返す */
    suspend fun download(
        candidates: List<String>,
        userAgent: String,
        referer: String?,
    ): DownloadedImage = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                return@withContext downloadSingle(url, userAgent, referer)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No candidate")
    }

    suspend fun fetchBytes(url: String, userAgent: String, referer: String?): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            if (url.startsWith("data:")) {
                return@withContext decodeDataUrl(url)
            }
            request(url, userAgent, referer)
        }

    suspend fun saveBytes(bytes: ByteArray, contentType: String?): DownloadedImage = withContext(Dispatchers.IO) {
        val mimeType = resolveImageMimeType(bytes, contentType)
            ?: throw IOException("画像ではありません (${contentType ?: "unknown"})")
        dir.mkdirs()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        DownloadedImage(file = file, mimeType = mimeType)
    }

    private suspend fun downloadSingle(url: String, userAgent: String, referer: String?): DownloadedImage {
        val (bytes, contentType) = fetchBytes(url, userAgent, referer)
        return saveBytes(bytes, contentType)
    }

    private fun request(startUrl: String, userAgent: String, referer: String?): Pair<ByteArray, String?> {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
                if (referer != null) setRequestProperty("Referer", referer)
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without location")
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IOException("HTTP $code: $url")
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                return bytes to connection.contentType
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    fun cleanupOldFiles(maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        val threshold = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.filter { it.lastModified() < threshold }?.forEach { it.delete() }
    }

    companion object {
        private const val MAX_REDIRECTS = 10

        fun decodeDataUrl(url: String): Pair<ByteArray, String?> {
            val header = url.substringAfter("data:").substringBefore(",")
            val body = url.substringAfter(",")
            val mime = header.substringBefore(";").ifEmpty { null }
            val bytes = if (header.endsWith(";base64")) {
                Base64.decode(body, Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
            }
            return bytes to mime
        }

        fun resolveImageMimeType(bytes: ByteArray, contentType: String?): String? {
            val sniffed = sniffImageMimeType(bytes)
            if (sniffed != null) return sniffed
            val type = contentType?.substringBefore(";")?.trim()?.lowercase()
            return type?.takeIf { it.startsWith("image/") }
        }

        private fun sniffImageMimeType(b: ByteArray): String? {
            fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
            fun ascii(start: Int, s: String) = s.indices.all { at(start + it) == s[it].code }
            return when {
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
                at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
                ascii(0, "GIF8") -> "image/gif"
                ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
                ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1")) -> "image/heic"
                ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> "image/avif"
                ascii(0, "BM") -> "image/bmp"
                else -> null
            }
        }
    }
}
