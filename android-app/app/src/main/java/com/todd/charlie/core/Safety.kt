package com.todd.charlie.core

/** Rules that keep screen control and app opening inside what Todd approved. */
object Safety {
    /** Never read or tapped: Charlie itself (its approval buttons), the notification shade and lock screen,
     *  Settings, the installer and the permission screens. That stops Charlie from approving its own requests
     *  or granting itself anything. */
    val NEVER_CONTROL = setOf(
        "com.todd.charlie",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    private val risky = Regex(
        "\\b(send|pay|payment|buy|purchase|order|checkout|check out|delete|remove|erase|reset|wipe|post|publish|submit|confirm|transfer|withdraw|call|dial|book|reserve|subscribe|uninstall|install|accept|share|sign out|log out|unsubscribe|block|report|archive|forward|reply all)\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when a tap on something with this label or view id could send, pay, buy, delete and so on. */
    fun isRisky(vararg labels: String?): Boolean =
        labels.any { it != null && risky.containsMatchIn(it.replace('_', ' ').replace('.', ' ').replace('/', ' ')) }

    /** Null when Charlie may read and tap this app's screen, else the reason it may not. */
    fun controlRefusal(pkg: String?, approved: Set<String>, allowAll: Boolean): String? = when {
        pkg.isNullOrBlank() -> "Can't tell which app is on screen."
        pkg == "com.android.systemui" -> "That's the lock screen, notification shade or another system screen. Charlie doesn't read or tap those."
        pkg in NEVER_CONTROL -> "Charlie never reads or taps Charlie itself, Settings, the installer or permission screens."
        allowAll || pkg in approved -> null
        else -> "That app isn't on the approved list. Todd can add it in the Charlie app under Approved apps."
    }
}

data class AppEntry(val label: String, val pkg: String)

object AppMatch {
    fun find(apps: List<AppEntry>, query: String): AppEntry? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val ql = q.lowercase()
        return apps.firstOrNull { it.pkg == q }
            ?: apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: apps.filter { it.label.lowercase().startsWith(ql) }.minByOrNull { it.label.length }
            ?: apps.filter { it.label.lowercase().contains(ql) }.minByOrNull { it.label.length }
    }
}

data class ContactEntry(val name: String, val number: String, val label: String = "")

sealed class Recipient {
    data class Found(val name: String?, val number: String) : Recipient()
    data class Problem(val message: String) : Recipient()
}

object ContactMatch {
    /** Turns "Mom" or "304-555-0101" into one number, or says why it can't. */
    fun resolve(contacts: List<ContactEntry>, to: String): Recipient {
        val q = to.trim()
        if (q.isEmpty()) return Recipient.Problem("Say who the text goes to.")
        val asNumber = PhoneNumbers.norm(q)
        if (asNumber != null) {
            val full = PhoneNumbers.dialable(q)
                ?: return Recipient.Problem("Charlie only texts full phone numbers, not short codes.")
            val name = contacts.firstOrNull { PhoneNumbers.norm(it.number) == full }?.name
            return Recipient.Found(name, full)
        }
        val ql = q.lowercase()
        var hits = contacts.filter { it.name.trim().lowercase() == ql }
        if (hits.isEmpty()) hits = contacts.filter { c -> c.name.lowercase().split(' ', '-', '.').any { it == ql } }
        if (hits.isEmpty()) hits = contacts.filter { it.name.lowercase().contains(ql) }
        val byNumber = hits.filter { PhoneNumbers.dialable(it.number) != null }
            .distinctBy { PhoneNumbers.norm(it.number) }
        return when {
            byNumber.isEmpty() -> Recipient.Problem("No saved contact with a phone number matches \"$q\".")
            byNumber.size == 1 -> Recipient.Found(byNumber[0].name, PhoneNumbers.dialable(byNumber[0].number)!!)
            else -> Recipient.Problem(
                "More than one match for \"$q\": " + byNumber.take(8).joinToString("; ") {
                    it.name + (if (it.label.isNotBlank()) " (${it.label})" else "") + " " + PhoneNumbers.dialable(it.number)
                } + ". Say which one, or give the number."
            )
        }
    }
}
