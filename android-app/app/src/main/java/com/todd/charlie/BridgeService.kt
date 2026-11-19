package com.todd.charlie

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.todd.charlie.core.RelayClient

/** Foreground service: keeps the relay connection open while the link is on. Its notification is always shown. */
class BridgeService : Service() {
    companion object {
        const val ACTION_STOP = "com.todd.charlie.STOP"
        const val ACTION_SCREEN_OFF = "com.todd.charlie.SCREEN_OFF"
        @Volatile var running = false
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BridgeService::class.java))
    }

    private var client: RelayClient? = null
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @android.annotation.SuppressLint("WakelockTimeout") // held only while Todd has the link switched on; released on Stop
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = App.I
        when (intent?.action) {
            ACTION_STOP -> {
                app.prefs.linkWanted = false
                app.log("Link stopped from the notification")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCREEN_OFF -> {
                app.prefs.screenControl = false
                app.log("Screen control turned off from the notification")
                Notifs.updateLink(this)
                if (client != null) return START_STICKY
                stopSelf()
                return START_NOT_STICKY
            }
        }
        running = true
        val n = Notifs.link(this, app.linkStatus)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifs.LINK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifs.LINK_ID, n)

        if (client == null) {
            val prefs = app.prefs
            if (!prefs.hasSettings) {
                app.linkStatus = "No relay settings"
                app.log("Can't start: paste the relay settings first")
                stopSelf()
                return START_NOT_STICKY
            }
            val c = RelayClient(prefs.url, prefs.key, app.tools, object : RelayClient.Listener {
                override fun status(connected: Boolean, text: String) {
                    app.linkConnected = connected
                    app.linkStatus = text
                    if (connected || text.startsWith("Disconnected")) app.log("Link: $text")
                    Notifs.updateLink(this@BridgeService)
                }
                override fun call(name: String) = app.log("Request: $name")
                override fun log(text: String) = app.log(text)
            })
            client = c
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "charlie:link").apply { setReferenceCounted(false); acquire() }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { c.nudge() }
            }
            try {
                getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
                netCb = cb
            } catch (_: Exception) {}
            c.start()
            app.log("Link starting")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        client?.shutdown()
        client = null
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        App.I.linkConnected = false
        App.I.linkStatus = "Off"
        super.onDestroy()
    }
}
