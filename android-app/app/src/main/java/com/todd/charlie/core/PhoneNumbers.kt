package com.todd.charlie.core

/** Same number matching as contacts_filter.py, so the app and the Termux bridge agree on who is a contact. */
object PhoneNumbers {
    private val cut = Regex("[,;#]|ext\\.?|x")
    private val shape = Regex("\\+?[0-9 ()./-]*[0-9][0-9 ()./-]*")

    fun norm(x: String?): String? {
        if (x == null) return null
        val s = x.trim().lowercase().replace("tel:", "").split(cut, 2)[0].trim()
        if (!shape.matches(s)) return null
        val d = s.filter { it.isDigit() }
        if (s[0] == '+') return "+$d"
        if (d.startsWith("011") && d.length > 6) return "+" + d.substring(3)
        if (d.startsWith("00") && d.length > 6) return "+" + d.substring(2)
        if (d.length == 10 && d[0] > '1') return "+1$d"
        if (d.length == 11 && d[0] == '1' && d[1] > '1') return "+$d"
        return "n:$d"
    }

    /** A dialable full number (E.164) or null. Short codes are refused on purpose. */
    fun dialable(x: String?): String? {
        val n = norm(x) ?: return null
        return if (n.startsWith("+") && n.length >= 9) n else null
    }
}
