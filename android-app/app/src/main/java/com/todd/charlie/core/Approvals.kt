package com.todd.charlie.core

import org.json.JSONObject
import java.security.SecureRandom

/** Things that wait for Todd's tap on the phone before they happen: outgoing texts and risky taps. */
class Approvals(private val now: () -> Long = { System.currentTimeMillis() }, private val ttlMs: Long = 10 * 60_000L) {
    enum class State { WAITING, APPROVED, DENIED, EXPIRED, FAILED }

    class Item(
        val id: String,
        val kind: String,
        val title: String,
        val detail: String,
        val buttonLabel: String,
        val createdAt: Long,
        internal val action: (Item) -> String,
    ) {
        @Volatile var state: State = State.WAITING
        @Volatile var result: String? = null

        fun toJson(): JSONObject = JSONObject()
            .put("approval_id", id)
            .put("kind", kind)
            .put("what", title)
            .put("state", state.name.lowercase())
            .put("result", result ?: "")
    }

    private val items = LinkedHashMap<String, Item>()
    private val rnd = SecureRandom()

    private fun newId(): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        while (true) {
            val id = (1..6).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
            if (id !in items) return id
        }
    }

    private fun expireOld() {
        val t = now()
        for (it in items.values) if (it.state == State.WAITING && t - it.createdAt > ttlMs) {
            it.state = State.EXPIRED
            it.result = "Not approved within 10 minutes, so nothing was done."
        }
        while (items.size > 50) items.remove(items.keys.first())
    }

    @Synchronized
    fun add(kind: String, title: String, detail: String, buttonLabel: String, action: (Item) -> String): Item {
        expireOld()
        val item = Item(newId(), kind, title, detail, buttonLabel, now(), action)
        items[item.id] = item
        return item
    }

    @Synchronized
    fun get(id: String): Item? {
        expireOld()
        return items[id]
    }

    @Synchronized
    fun waiting(): List<Item> {
        expireOld()
        return items.values.filter { it.state == State.WAITING }
    }

    @Synchronized
    fun recent(n: Int = 10): List<Item> {
        expireOld()
        return items.values.toList().takeLast(n)
    }

    /** Runs the action once, only if it is still waiting. */
    fun approve(id: String): Item? {
        val item = synchronized(this) {
            expireOld()
            val it = items[id] ?: return null
            if (it.state != State.WAITING) return it
            it.state = State.APPROVED
            it.result = "running"
            it
        }
        try {
            item.result = item.action(item)
        } catch (e: Exception) {
            item.state = State.FAILED
            item.result = "Failed: " + (e.message ?: e.javaClass.simpleName).take(200)
        }
        return item
    }

    @Synchronized
    fun deny(id: String): Item? {
        expireOld()
        val it = items[id] ?: return null
        if (it.state == State.WAITING) {
            it.state = State.DENIED
            it.result = "Todd said no, so nothing was done."
        }
        return it
    }

    @Synchronized
    fun update(id: String, state: State, result: String) {
        items[id]?.let { it.state = state; it.result = result }
    }
}
