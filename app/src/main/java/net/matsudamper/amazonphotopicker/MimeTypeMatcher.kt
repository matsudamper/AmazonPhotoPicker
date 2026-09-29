package net.matsudamper.amazonphotopicker

/**
 * 呼び出し元が要求したMIMEタイプ(例: image/png やワイルドカード)に一致するかを判定する。
 */
object MimeTypeMatcher {
    fun matches(mimeType: String, accepted: List<String>): Boolean {
        if (accepted.isEmpty()) return true
        val (type, subtype) = split(mimeType) ?: return false
        return accepted.any { pattern ->
            val (acceptedType, acceptedSubtype) = split(pattern) ?: return@any false
            (acceptedType == "*" || acceptedType == type) &&
                (acceptedSubtype == "*" || acceptedSubtype == subtype)
        }
    }

    private fun split(mimeType: String): Pair<String, String>? {
        val normalized = mimeType.substringBefore(";").trim().lowercase()
        val parts = normalized.split("/")
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }
}
