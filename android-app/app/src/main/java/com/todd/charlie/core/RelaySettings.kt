package com.todd.charlie.core

data class RelaySettings(val url: String, val key: String)

object RelaySettingsParser {
    const val DEFAULT_URL = "wss://phone-bridge-relay.melvilmiller1975.workers.dev/phone"

    /** Reads what Todd pastes: the two lines of ~/.phone-bridge-relay (address, then phone key), or just the key. */
    fun parse(text: String): RelaySettings? {
        var url: String? = null
        var key: String? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val low = l.lowercase()
            if (url == null && (low.startsWith("wss://") || low.startsWith("ws://") || low.startsWith("https://") || low.startsWith("http://"))) {
                url = l
            } else if (key == null && l.length >= 20 && !l.contains(' ') && !l.contains("://")) {
                key = l
            }
        }
        if (key == null) return null
        return RelaySettings(normalizeUrl(url ?: DEFAULT_URL), key)
    }

    fun normalizeUrl(u: String): String {
        var s = u.trim().substringBefore('?').substringBefore('#')
        s = when {
            s.startsWith("https://", true) -> "wss://" + s.substring(8)
            s.startsWith("http://", true) -> "ws://" + s.substring(7)
            else -> s
        }
        val schemeEnd = s.indexOf("://") + 3
        val slash = s.indexOf('/', schemeEnd)
        val host = if (slash < 0) s else s.substring(0, slash)
        return "$host/phone"
    }
}
