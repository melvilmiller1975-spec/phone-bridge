package com.todd.charlie.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Runs the app's real RelayClient against a local copy of the Cloudflare relay (wrangler dev, same index.js).
 * Set RELAY_TEST_URL=http://127.0.0.1:8787 with PHONE_KEY / SESAME_KEY test values from relay/.dev.vars.test.
 */
class RelayClientTest {
    private val base = System.getenv("RELAY_TEST_URL") ?: ""
    private val phoneKey = "test-phone-key-0123456789abcdef"
    private val sesameKey = "test-sesame-key-0123456789abcdef"
    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()

    private fun mcp(method: String, params: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()
        val req = Request.Builder().url("$base/mcp?k=$sesameKey").post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { return JSONObject(it.body!!.string()) }
    }

    private fun text(r: JSONObject) = r.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")

    private fun waitFor(what: String, f: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { if (f()) return; Thread.sleep(100) }
        throw AssertionError("timed out waiting for $what")
    }

    private fun registry(tag: String) = ToolRegistry().apply {
        add("battery", "Phone battery") { JSONObject().put("percentage", 79).put("from", tag) }
        add("open_app", "Open app", Props.of("name" to Props.str("n")), listOf("name")) { JSONObject().put("opened", it.optString("name")) }
        add("send_text", "Send text") { JSONObject().put("status", "waiting_for_approval") }
    }

    class L : RelayClient.Listener {
        @Volatile var connected = false
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun status(connected: Boolean, text: String) { this.connected = connected }
        override fun call(name: String) { calls.add(name) }
    }

    @Test fun endToEndThroughRelay() {
        assumeTrue("RELAY_TEST_URL not set", base.isNotEmpty())
        val ws = base.replace("http://", "ws://") + "/phone"

        // Wrong key is refused and never connects.
        val badL = L()
        val bad = RelayClient(ws, "wrong-key-0123456789abcdefgh", registry("bad"), badL)
        bad.start(); Thread.sleep(1500); bad.shutdown()
        assertTrue(!badL.connected)

        val l1 = L()
        val c1 = RelayClient(ws, phoneKey, registry("first"), l1, replacedWaitMs = 600_000)
        c1.start()
        waitFor("first connection") { l1.connected }
        Thread.sleep(500)

        val names = mcp("tools/list").getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(listOf("battery", "open_app", "send_text"), names)

        val r1 = JSONObject(text(mcp("tools/call", JSONObject().put("name", "battery").put("arguments", JSONObject()))))
        assertEquals(79, r1.getInt("percentage"))
        assertEquals("first", r1.getString("from"))
        val r2 = JSONObject(text(mcp("tools/call", JSONObject().put("name", "open_app").put("arguments", JSONObject().put("name", "Maps")))))
        assertEquals("Maps", r2.getString("opened"))

        // A newer connection replaces the old one, and calls go to the new one (the bug fixed today).
        val l2 = L()
        val c2 = RelayClient(ws, phoneKey, registry("second"), l2)
        c2.start()
        waitFor("second connection") { l2.connected }
        waitFor("first connection told it was replaced") { !l1.connected }
        Thread.sleep(500)
        val r3 = JSONObject(text(mcp("tools/call", JSONObject().put("name", "battery").put("arguments", JSONObject()))))
        assertEquals("second", r3.getString("from"))

        // Phone stops: the relay says offline instead of hanging.
        c1.shutdown(); c2.shutdown()
        Thread.sleep(1500)
        val off = mcp("tools/call", JSONObject().put("name", "battery").put("arguments", JSONObject()))
        assertTrue(text(off), text(off).contains("offline") || text(off).contains("dropped"))
        assertEquals(listOf("battery", "open_app"), l1.calls.toList())
        assertEquals(listOf("battery"), l2.calls.toList())
    }

    @Test fun reconnectsAfterRelayDropsIt() {
        assumeTrue("RELAY_TEST_URL not set", base.isNotEmpty())
        val ws = base.replace("http://", "ws://") + "/phone"
        val l = L()
        // Treat 30 s of silence as dead with fast pings, so a quiet connection is noticed.
        val c = RelayClient(ws, phoneKey, registry("solo"), l, pingEveryMs = 500, deadAfterMs = 3_000, replacedWaitMs = 1_000)
        c.start()
        waitFor("connect") { l.connected }
        // Kick it with another connection that then goes away; the client must come back by itself.
        val kickL = L()
        val kicker = RelayClient(ws, phoneKey, registry("kicker"), kickL)
        kicker.start()
        waitFor("kicker") { kickL.connected }
        waitFor("solo dropped") { !l.connected }
        kicker.shutdown()
        waitFor("solo back") { l.connected }
        Thread.sleep(800)
        val r = JSONObject(text(mcp("tools/call", JSONObject().put("name", "battery").put("arguments", JSONObject()))))
        assertEquals("solo", r.getString("from"))
        // Pings keep it alive past the dead-connection limit.
        Thread.sleep(4_000)
        assertTrue(l.connected)
        c.shutdown()
    }
}
