package net.matsudamper.amazonphotopicker

import java.net.URLDecoder

/**
 * ブラウザ内の遷移先をどう扱うかを決める。
 * Amazonのページはアプリ誘導のため `intent://` などへ遷移することがあり、ブラウザ内では読み込めない。
 */
object NavigationPolicy {
    sealed interface Decision {
        /** ブラウザでそのまま読み込む */
        data object Allow : Decision

        /** 代わりに指定URLをブラウザで読み込む */
        data class Redirect(val url: String) : Decision

        /** 読み込まずに無視する */
        data object Block : Decision
    }

    fun decide(url: String): Decision {
        val scheme = url.substringBefore(":", "").lowercase()
        return when (scheme) {
            "http", "https", "about", "data", "blob", "javascript" -> Decision.Allow
            "intent" -> fallbackUrl(url)?.let { Decision.Redirect(it) } ?: Decision.Block
            else -> Decision.Block
        }
    }

    /** `intent://...#Intent;...;S.browser_fallback_url=...;end` からhttp(s)のフォールバックURLを取り出す */
    private fun fallbackUrl(url: String): String? {
        val fragment = url.substringAfter("#Intent;", "")
        if (fragment.isEmpty()) return null
        val encoded = fragment.split(";")
            .firstOrNull { it.startsWith("S.browser_fallback_url=") }
            ?.substringAfter("=")
            ?: return null
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        val scheme = decoded.substringBefore(":", "").lowercase()
        return decoded.takeIf { scheme == "http" || scheme == "https" }
    }
}
