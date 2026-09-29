package net.matsudamper.amazonphotopicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class DownloadedImage(
    val file: File,
    val mimeType: String,
)

/**
 * WebViewのCookieを使って画像をダウンロードし、キャッシュディレクトリに保存する。
 */
class UnsupportedMimeTypeException(val mimeType: String) : IOException("Unsupported mime type: $mimeType")

class ImageDownloader(context: Context) {
    private val dir = File(context.cacheDir, DIR_NAME)

    /** 候補URLを順に試し、最初に画像が取得できたものを返す */
    suspend fun download(
        candidates: List<String>,
        userAgent: String,
        referer: String?,
        acceptedMimeTypes: List<String> = emptyList(),
    ): DownloadedImage = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                val image = downloadSingle(url, userAgent, referer)
                if (!MimeTypeMatcher.matches(image.mimeType, acceptedMimeTypes)) {
                    // 元画像がHEICでもサムネイルならJPEGの場合があるため、次の候補を試す
                    image.file.delete()
                    lastError = UnsupportedMimeTypeException(image.mimeType)
                    continue
                }
                return@withContext image
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No candidate")
    }

    /** プレビュー用に縮小したBitmapを取得する。大きな画像でもメモリに全体を載せないようファイル経由でデコードする */
    suspend fun loadPreview(url: String, userAgent: String, referer: String?, maxSize: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tempFile = File(dir, "preview-${UUID.randomUUID()}.tmp")
            try {
                fetchToFile(url, userAgent, referer, tempFile)
                decodeSampled(maxSize) { BitmapFactory.decodeFile(tempFile.path, it) }
            } finally {
                tempFile.delete()
            }
        }

    private fun decodeSampled(maxSize: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun downloadSingle(url: String, userAgent: String, referer: String?): DownloadedImage {
        // 元画像は大きいため、メモリに載せずファイルへ直接書き込む
        dir.mkdirs()
        val tempFile = File(dir, "${UUID.randomUUID()}.tmp")
        try {
            val contentType = fetchToFile(url, userAgent, referer, tempFile)
            val header = tempFile.inputStream().use { input ->
                val buffer = ByteArray(HEADER_SIZE)
                val read = input.read(buffer).coerceAtLeast(0)
                buffer.copyOf(read)
            }
            val mimeType = resolveImageMimeType(header, contentType)
                ?: throw IOException("画像ではありません (${contentType ?: "unknown"})")
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
            val file = File(dir, tempFile.nameWithoutExtension + ".$ext")
            if (!tempFile.renameTo(file)) throw IOException("Failed to rename file")
            return DownloadedImage(file = file, mimeType = mimeType)
        } finally {
            tempFile.delete()
        }
    }

    /** URL(http(s)またはdata:)の内容をファイルへ書き込み、Content-Typeを返す */
    private fun fetchToFile(url: String, userAgent: String, referer: String?, file: File): String? {
        if (url.startsWith("file:")) {
            // WebViewのblobを書き出したファイル。キャッシュディレクトリ内のもののみ受け付ける
            val source = File(URI(url)).canonicalFile
            if (source.parentFile != dir.canonicalFile) throw IOException("Unsupported file: $url")
            source.copyTo(file, overwrite = true)
            return null
        }
        if (url.startsWith("data:")) {
            return file.outputStream().buffered().use { writeDataUrl(url, it) }
        }
        return request(url, userAgent, referer) { input, contentType ->
            file.outputStream().use { input.copyTo(it) }
            contentType
        }
    }

    private fun <T> request(
        startUrl: String,
        userAgent: String,
        referer: String?,
        consumer: (InputStream, String?) -> T,
    ): T {
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
                return connection.inputStream.use { consumer(it, connection.contentType) }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /** WebViewのblobを書き出した一時ファイルを削除する */
    fun deleteLocalSource(url: String) {
        if (!url.startsWith("file:")) return
        val source = runCatching { File(URI(url)).canonicalFile }.getOrNull() ?: return
        if (source.parentFile == dir.canonicalFile) source.delete()
    }

    fun cleanupOldFiles(maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        val threshold = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.filter { it.lastModified() < threshold }?.forEach { it.delete() }
    }

    companion object {
        const val DIR_NAME = "picked"
        private const val MAX_REDIRECTS = 10
        private const val HEADER_SIZE = 32
        private const val BASE64_CHUNK_SIZE = 4 * 16 * 1024

        /** data: URLを少しずつデコードして書き込み、MIMEタイプを返す */
        fun writeDataUrl(url: String, out: OutputStream): String? {
            val commaIndex = url.indexOf(',')
            if (commaIndex < 0) throw IOException("Invalid data URL")
            val header = url.substring("data:".length, commaIndex)
            val mime = header.substringBefore(";").ifEmpty { null }
            if (header.endsWith(";base64")) {
                writeBase64(url, commaIndex + 1, out)
            } else {
                writePercentDecoded(url, commaIndex + 1, out)
            }
            return mime
        }

        private fun writeBase64(value: String, start: Int, out: OutputStream) {
            val chunk = StringBuilder(BASE64_CHUNK_SIZE)
            for (i in start until value.length) {
                val c = value[i]
                if (c.isWhitespace()) continue
                chunk.append(c)
                // 4文字単位で区切ればbase64は独立してデコードできる
                if (chunk.length == BASE64_CHUNK_SIZE) {
                    out.write(Base64.decode(chunk.toString(), Base64.DEFAULT))
                    chunk.setLength(0)
                }
            }
            if (chunk.isNotEmpty()) {
                out.write(Base64.decode(chunk.toString(), Base64.DEFAULT))
            }
        }

        /** `+`を保持したまま、%XXをバイト列として直接デコードする */
        private fun writePercentDecoded(value: String, start: Int, out: OutputStream) {
            var i = start
            while (i < value.length) {
                val c = value[i]
                val hex = if (c == '%' && i + 2 < value.length) {
                    value.substring(i + 1, i + 3).toIntOrNull(16)
                } else {
                    null
                }
                if (hex != null) {
                    out.write(hex)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
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
