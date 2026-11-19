package com.todd.charlie.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Dials OUT to the Cloudflare relay and answers tool calls, the same protocol relay_client.py speaks:
 * phone -> {"type":"hello","tools":[...]}, relay -> {"type":"call","rid","name","arguments"},
 * phone -> {"type":"result","rid","result"}. "ping" every 30 s, the relay answers "pong".
 * Reconnects by itself with growing waits. Pure JVM, so it is tested against a local copy of the relay.
 */
class RelayClient(
    private val url: String,
    private val key: String,
    private val tools: ToolRegistry,
    private val listener: Listener,
    private val pingEveryMs: Long = 30_000,
    private val deadAfterMs: Long = 75_000,
    private val replacedWaitMs: Long = 60_000,
    http: OkHttpClient? = null,
) {
    interface Listener {
        fun status(connected: Boolean, text: String) {}
        fun call(name: String) {}
        fun log(text: String) {}
    }

    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay").apply { isDaemon = true } }
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "relay-call").apply { isDaemon = true } }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var lastHeard = 0L
    @Volatile var connected = false
        private set
    private var session = 0
    private var openedAt = 0L
    private var wait = 2_000L
    private var reconnectTask: ScheduledFuture<*>? = null

    private fun now() = System.currentTimeMillis()

    fun start() = sched.execute {
        if (!running) {
            running = true
            connect()
        }
    }

    fun stop() = sched.execute {
        running = false
        reconnectTask?.cancel(false)
        session++
        ws?.close(1000, "stopped")
        ws = null
        setConnected(false, "Stopped")
    }

    fun shutdown() {
        stop()
        sched.execute { sched.shutdown() }
        workers.shutdown()
    }

    /** Call when the network comes back: reconnect now instead of waiting out the backoff. */
    fun nudge() = sched.execute {
        if (running && !connected) {
            reconnectTask?.cancel(false)
            wait = 2_000L
            connect()
        }
    }

    private fun setConnected(c: Boolean, text: String) {
        connected = c
        listener.status(c, text)
    }

    private fun connect() {
        ws?.cancel()
        val gen = ++session
        setConnected(false, "Connecting")
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sched.execute {
                    if (gen != session) { webSocket.cancel(); return@execute }
                    openedAt = now()
                    lastHeard = now()
                    webSocket.send(JSONObject().put("type", "hello").put("tools", tools.listJson()).toString())
                    setConnected(true, "Connected")
                    schedulePing(gen)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != session) return
                lastHeard = now()
                handle(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                sched.execute { dropped(gen, code, reason.ifBlank { "closed" }) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sched.execute { dropped(gen, code, reason.ifBlank { "closed" }) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when (response?.code) {
                    401 -> "Relay refused the phone key (HTTP 401)"
                    null -> t.message ?: t.javaClass.simpleName
                    else -> "Relay answered HTTP ${response.code}"
                }
                sched.execute { dropped(gen, -1, why) }
            }
        })
    }

    private fun schedulePing(gen: Int) {
        sched.schedule({
            if (gen != session || !running) return@schedule
            if (now() - lastHeard > deadAfterMs) {
                ws?.cancel()
                dropped(gen, -2, "no answer from the relay")
                return@schedule
            }
            ws?.send("ping")
            schedulePing(gen)
        }, pingEveryMs, TimeUnit.MILLISECONDS)
    }

    private fun dropped(gen: Int, code: Int, reason: String) {
        if (gen != session) return
        session++
        ws = null
        val delay: Long
        if (code == 4000) {
            delay = replacedWaitMs
            listener.log("Another phone connection took over the relay (is Termux's start-relay.sh still running?). Trying again in ${delay / 1000} s.")
        } else {
            wait = if (openedAt > 0 && now() - openedAt > 60_000) 2_000L else minOf(wait * 2, 60_000L)
            delay = wait
        }
        openedAt = 0
        setConnected(false, "Disconnected: ${reason.take(100)}")
        if (!running) return
        reconnectTask = sched.schedule({ if (running) connect() }, delay + (Math.random() * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    private fun handle(webSocket: WebSocket, text: String) {
        if (text == "pong") return
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("type")) {
            "call" -> workers.execute {
                val name = m.optString("name")
                listener.call(name)
                val result = try {
                    tools.call(name, m.optJSONObject("arguments") ?: JSONObject())
                } catch (e: Exception) {
                    ToolRegistry.mcpText("tool failed: " + e.javaClass.simpleName, true)
                }
                webSocket.send(JSONObject().put("type", "result").put("rid", m.optString("rid")).put("result", result).toString())
            }
            "hello_ok" -> listener.log("Relay has ${m.optInt("tools")} tools from this phone")
        }
    }
}
