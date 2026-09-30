package net.matsudamper.amazonphotopicker

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 長押しで取得した画像URLから、ダウンロードを試みるURLの候補を高画質順に返す。
 */
object ImageUrlResolver {
    private val thumbnailPathRegex = Regex("""/v1/thumbnail/([^/?#]+)""")

    fun candidates(url: String, pageUrl: String?): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val host = uri.host.orEmpty()
        val nodeId = thumbnailPathRegex.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
        if (!host.startsWith("thumbnails-photos.") || nodeId == null) {
            return listOf(url)
        }
        val query = parseQuery(uri.rawQuery)
        val ownerId = query["ownerId"]
        val amazonHost = amazonHost(pageUrl) ?: amazonHostFromThumbnail(host)

        val result = mutableListOf<String>()
        if (amazonHost != null) {
            result += buildString {
                append("https://").append(amazonHost)
                append("/drive/v1/nodes/").append(nodeId).append("/contentRedirection")
                append("?querySuffix=").append(encode("?download=true"))
                if (ownerId != null) append("&ownerId=").append(encode(ownerId))
            }
        }
        val largeQuery = query.toMutableMap().apply { put("viewBox", "4096,4096") }
        result += buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(uri.rawPath)
            append("?").append(largeQuery.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" })
        }
        result += url
        return result.distinct()
    }

    private fun amazonHost(pageUrl: String?): String? {
        val host = pageUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: return null
        return host.takeIf { it.startsWith("www.amazon.") }
    }

    /** thumbnails-photos.amazon.co.jp -> www.amazon.co.jp */
    private fun amazonHostFromThumbnail(host: String): String? {
        val suffix = host.substringAfter("thumbnails-photos.", "")
        return if (suffix.startsWith("amazon.")) "www.$suffix" else null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split("&").filter { it.isNotEmpty() }.associate { part ->
            val key = part.substringBefore("=")
            val value = part.substringAfter("=", "")
            decode(key) to decode(value)
        }.let { LinkedHashMap(it) }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
