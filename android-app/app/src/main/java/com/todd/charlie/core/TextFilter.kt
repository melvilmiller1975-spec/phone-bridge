package com.todd.charlie.core

import org.json.JSONArray
import org.json.JSONObject

data class Sms(val id: Long, val address: String?, val body: String?, val date: Long, val box: String, val read: Boolean)

class FilterFail(message: String) : Exception(message)

/** Contacts-only filter for read_texts. Fails closed: no readable contacts means no texts. */
object TextFilter {
    const val MAX_SCAN = 500

    fun limit(args: JSONObject): Int = args.optInt("limit", 20).coerceIn(1, 100)

    fun box(args: JSONObject): String {
        val b = args.optString("box", "inbox")
        return if (b in setOf("inbox", "sent", "all")) b else "inbox"
    }

    /**
     * contacts: normalized number -> contact name. newestFirst: texts newest first.
     * Returns the newest [want] contact texts, oldest first, like the Termux bridge.
     */
    fun filter(contacts: Map<String, String>, newestFirst: Iterable<Sms>, want: Int, fmt: (Long) -> String): JSONObject {
        if (contacts.isEmpty()) throw FilterFail("No contact numbers readable, so no texts shown. Allow Contacts for Charlie.")
        val found = ArrayList<Pair<Sms, String>>()
        var hidden = 0
        var checked = 0
        for (m in newestFirst) {
            if (checked >= MAX_SCAN || found.size >= want) break
            checked++
            val name = PhoneNumbers.norm(m.address)?.let { contacts[it] }
            if (name != null) found.add(m to name) else hidden++
        }
        val arr = JSONArray()
        for ((m, name) in found.asReversed()) {
            arr.put(
                JSONObject()
                    .put("_id", m.id)
                    .put("contact", name)
                    .put("number", m.address ?: "")
                    .put("body", m.body ?: "")
                    .put("received", fmt(m.date))
                    .put("type", m.box)
                    .put("read", m.read)
            )
        }
        return JSONObject()
            .put("filter", "contacts only")
            .put("messages", arr)
            .put("checked", checked)
            .put("left_out_not_in_contacts", hidden)
    }
}
