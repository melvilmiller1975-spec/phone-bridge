package com.todd.charlie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns the link back on after a restart or an app update, if Todd left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = App.I.prefs
        if (p.autoStart && p.linkWanted && p.hasSettings) {
            try { BridgeService.start(context) } catch (e: Exception) { App.I.log("Couldn't restart the link after boot: ${e.javaClass.simpleName}") }
        }
    }
}
