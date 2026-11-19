package com.todd.charlie

import android.app.Application
import com.todd.charlie.core.Approvals
import com.todd.charlie.core.ToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    companion object {
        lateinit var I: App
            private set
    }

    val approvals = Approvals()
    lateinit var prefs: Prefs
        private set
    val tools: ToolRegistry by lazy { AndroidTools(this).registry() }

    @Volatile var linkConnected = false
    @Volatile var linkStatus = "Off"
    private val logLines = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        I = this
        prefs = Prefs(this)
        Notifs.channels(this)
    }

    /** On-phone activity log, so Todd can see every request. Kept in memory only. */
    fun log(text: String) {
        val line = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date()) + "  " + text
        synchronized(logLines) {
            logLines.addFirst(line)
            while (logLines.size > 60) logLines.removeLast()
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }
}
