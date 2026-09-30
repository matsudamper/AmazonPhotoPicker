package net.matsudamper.amazonphotopicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class DownloadedImage(
    val file: File,
    val mimeType: String,
)

class UnsupportedMimeTypeException(val mimeType: String) : IOException("Unsupported mime type: $mimeType")

/**
 * GeckoView のログイン状態で画像をダウンロードし、キャッシュディレクトリに保存する。
 */
class ImageDownloader(context: Context, private val webExecutor: GeckoWebExecutor) {
    private val dir = File(context.cacheDir, DIR_NAME)

    /** 候補URLを順に試し、最初に画像が取得できたものを返す */
    suspend fun download(
        candidates: List<String>,
        referer: String?,
        acceptedMimeTypes: List<String> = emptyList(),
    ): DownloadedImage = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                val image = downloadSingle(url, referer)
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
    suspend fun loadPreview(url: String, referer: String?, maxSize: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tempFile = File(dir, "preview-${UUID.randomUUID()}.tmp")
            try {
                fetchToFile(url, referer, tempFile)
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

    private fun downloadSingle(url: String, referer: String?): DownloadedImage {
        return storeAsImage { tempFile -> fetchToFile(url, referer, tempFile) }
    }

    /** ページのダウンロード操作で GeckoView から渡された内容を保存する */
    suspend fun saveResponse(
        body: InputStream,
        contentType: String?,
        acceptedMimeTypes: List<String>,
    ): DownloadedImage = withContext(Dispatchers.IO) {
        val image = body.use { input ->
            storeAsImage { tempFile ->
                tempFile.outputStream().use { input.copyTo(it) }
                contentType
            }
        }
        if (!MimeTypeMatcher.matches(image.mimeType, acceptedMimeTypes)) {
            image.file.delete()
            throw UnsupportedMimeTypeException(image.mimeType)
        }
        image
    }

    /** 元画像は大きいため、メモリに載せずファイルへ直接書き込んでから形式を判定する */
    private fun storeAsImage(writeTo: (File) -> String?): DownloadedImage {
        dir.mkdirs()
        val tempFile = File(dir, "${UUID.randomUUID()}.tmp")
        try {
            val contentType = writeTo(tempFile)
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
    private fun fetchToFile(url: String, referer: String?, file: File): String? {
        if (url.startsWith("file:")) {
            // ページの blob を書き出したファイル。キャッシュディレクトリ内のもののみ受け付ける
            val uri = URI(url)
            val source = File(uri.path).canonicalFile
            if (source.parentFile != dir.canonicalFile) throw IOException("Unsupported file: $url")
            source.copyTo(file, overwrite = true)
            // フラグメントにblobのMIMEタイプが入っている
            return uri.fragment
        }
        if (url.startsWith("data:")) {
            return file.outputStream().buffered().use { writeDataUrl(url, it) }
        }
        return request(url, referer) { input, contentType ->
            file.outputStream().use { input.copyTo(it) }
            contentType
        }
    }

    /** GeckoView の Cookie を使うため、GeckoView のネットワーク層で取得する */
    private fun <T> request(
        url: String,
        referer: String?,
        consumer: (InputStream, String?) -> T,
    ): T {
        val request = WebRequest.Builder(url)
            .header("Accept", "image/*,*/*;q=0.8")
            .apply { if (referer != null) referrer(referer) }
            .build()
        val response = webExecutor.fetch(request).poll(FETCH_TIMEOUT_MILLIS)
            ?: throw IOException("No response: $url")
        val body = response.body ?: throw IOException("Empty body: $url")
        return body.use {
            if (response.statusCode !in 200..299) {
                throw IOException("HTTP ${response.statusCode}: $url")
            }
            consumer(it, response.contentType())
        }
    }

    /** ページの blob を書き出した一時ファイルを削除する */
    fun deleteLocalSource(url: String) {
        if (!url.startsWith("file:")) return
        val source = runCatching { File(URI(url).path).canonicalFile }.getOrNull() ?: return
        if (source.parentFile == dir.canonicalFile) source.delete()
    }

    fun cleanupOldFiles(maxAgeMillis: Long = 24L * 60 * 60 * 1000) {
        val threshold = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.filter { it.lastModified() < threshold }?.forEach { it.delete() }
    }

    companion object {
        const val DIR_NAME = "picked"
        private const val FETCH_TIMEOUT_MILLIS = 60_000L
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

/** GeckoView はヘッダー名の大文字小文字を保持したまま渡すため、大小を区別せずに探す */
fun WebResponse.contentType(): String? {
    return headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
}
