package com.todd.charlie

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.todd.charlie.core.RelaySettingsParser

/** One plain screen: link on/off, permissions with a button each, approved apps, waiting approvals, activity log. */
class MainActivity : Activity() {
    private val app get() = App.I
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private val refresher = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun head(t: String) = box.addView(TextView(this).apply { text = t; textSize = 19f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(18), 0, dp(6)) })
    private fun line(t: String, color: Int? = null) = box.addView(TextView(this).apply { text = t; textSize = 15f; color?.let { setTextColor(it) }; setPadding(0, dp(2), 0, dp(2)) })
    private fun button(t: String, f: () -> Unit) = box.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } })
    private val green = Color.rgb(20, 140, 60)
    private val red = Color.rgb(190, 40, 40)
    private fun row(ok: Boolean, label: String, fixLabel: String, fix: () -> Unit) {
        line((if (ok) "ON   " else "OFF  ") + label, if (ok) green else red)
        if (!ok) button(fixLabel, fix)
    }

    private fun render() {
        if (currentFocus is EditText) return
        box.removeAllViews()
        val p = app.prefs

        head("Charlie link")
        line(if (BridgeService.running) "Status: ${app.linkStatus}" else "Status: off", if (app.linkConnected) green else null)
        if (!p.hasSettings) {
            line("First, paste the relay settings. In Termux run:  cat ~/.phone-bridge-relay  then copy both lines.")
            button("Paste relay settings") { pasteSettings() }
        } else {
            box.addView(Switch(this).apply {
                text = "Link on (shows a notification while on)"
                textSize = 16f
                isChecked = BridgeService.running
                setOnCheckedChangeListener { _, on ->
                    p.linkWanted = on
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                        BridgeService.start(this@MainActivity)
                        app.log("Link turned on")
                    } else {
                        BridgeService.stop(this@MainActivity)
                        app.log("Link turned off")
                    }
                    ui.postDelayed({ render() }, 400)
                }
            })
            line("Turn off start-relay.sh in Termux while this is on. Only one can hold the relay.")
        }

        head("Permissions")
        row(has(Manifest.permission.READ_SMS) && has(Manifest.permission.READ_CONTACTS), "Read texts, contacts only", "Allow texts and contacts") {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 1)
        }
        row(has(Manifest.permission.SEND_SMS), "Send texts (each one waits for your Send tap)", "Allow sending texts") {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 3)
        }
        if (Build.VERSION.SDK_INT >= 33) row(has(Manifest.permission.POST_NOTIFICATIONS), "Notifications (needed for approvals)", "Allow notifications") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val pm = getSystemService(PowerManager::class.java)
        row(pm.isIgnoringBatteryOptimizations(packageName), "Battery: allowed to run in background", "Let Charlie run in background") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row(Settings.canDrawOverlays(this), "Display over other apps (to open apps)", "Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row(NotifListener.enabled(this), "New message alerts (catches RCS chats)", "Turn on new message alerts") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        line("If Android says \"Restricted setting\": open Charlie's App info, tap the 3 dots at the top right, tap Allow restricted settings, then try again.")
        button("Open Charlie's App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }

        head("Screen control")
        val svcOn = ScreenService.instance != null
        row(svcOn, "Accessibility service", "Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        box.addView(Switch(this).apply {
            text = "Let Charlie read and tap the screen in approved apps"
            textSize = 16f
            isChecked = p.screenControl
            setOnCheckedChangeListener { _, on -> p.screenControl = on; app.log("Screen control switch " + (if (on) "on" else "off")); Notifs.updateLink(this@MainActivity) }
        })
        line("Never in Charlie, Settings, the lock screen or permission screens. Password fields are skipped. Send, Pay, Buy, Delete and Call buttons wait for your OK.")

        head("Approved apps")
        line(if (p.allowAll) "All apps approved" else "${p.approved.size} approved")
        button("Choose apps") { chooseApps() }

        val waiting = app.approvals.waiting()
        if (waiting.isNotEmpty()) {
            head("Waiting for your OK")
            for (w in waiting) {
                line(w.title + "\n" + w.detail)
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(Button(this).apply { text = w.buttonLabel; isAllCaps = false; setOnClickListener { askThenApprove(w.id, w.title) } })
                r.addView(Button(this).apply { text = "Don't"; isAllCaps = false; setOnClickListener { app.approvals.deny(w.id); Notifs.cancelApproval(this@MainActivity, w.id); app.log("You said no to ${w.id}"); render() } })
                box.addView(r)
            }
        }

        head("Activity")
        line(app.logText().ifBlank { "Nothing yet." })
        if (p.hasSettings) {
            head("Settings")
            box.addView(CheckBox(this).apply { text = "Turn the link back on after a restart"; isChecked = p.autoStart; setOnCheckedChangeListener { _, b -> p.autoStart = b } })
            button("Replace relay settings") { pasteSettings() }
        }
    }

    private fun askThenApprove(id: String, title: String) {
        AlertDialog.Builder(this).setMessage("$title?").setPositiveButton("Yes") { _, _ ->
            Notifs.cancelApproval(this, id)
            Thread { val r = app.approvals.approve(id); app.log("You approved ${r?.title ?: id}: ${r?.result}") }.start()
            ui.postDelayed({ render() }, 500)
        }.setNegativeButton("No", null).show()
    }

    private fun pasteSettings() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val s = RelaySettingsParser.parse(clip)
        val field = EditText(this).apply { setText(if (s != null) clip else ""); hint = "Paste both lines here"; minLines = 3 }
        AlertDialog.Builder(this).setTitle("Relay settings").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val parsed = RelaySettingsParser.parse(field.text.toString())
                if (parsed == null) Toast.makeText(this, "That doesn't have the phone key in it.", Toast.LENGTH_LONG).show()
                else {
                    app.prefs.url = parsed.url; app.prefs.key = parsed.key
                    app.log("Relay settings saved")
                    if (BridgeService.running) { BridgeService.stop(this); ui.postDelayed({ BridgeService.start(this) }, 500) }
                }
                currentFocus?.clearFocus()
                render()
            }.setNegativeButton("Cancel") { _, _ -> currentFocus?.clearFocus() }.show()
    }

    private fun chooseApps() {
        val apps = AndroidTools(app).allApps()
        val chosen = app.prefs.approved.toMutableSet()
        val labels = (listOf("ALL APPS") + apps.map { it.label }).toTypedArray()
        val checks = BooleanArray(labels.size) { i -> if (i == 0) app.prefs.allowAll else apps[i - 1].pkg in chosen }
        var all = app.prefs.allowAll
        AlertDialog.Builder(this).setTitle("Apps Charlie may open and control")
            .setMultiChoiceItems(labels, checks) { _, i, on ->
                if (i == 0) all = on else if (on) chosen.add(apps[i - 1].pkg) else chosen.remove(apps[i - 1].pkg)
            }
            .setPositiveButton("Save") { _, _ ->
                app.prefs.approved = chosen; app.prefs.allowAll = all
                app.log("Approved apps: " + if (all) "all" else chosen.size.toString())
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    @Suppress("unused")
    private fun View.gone() { (parent as? ViewGroup)?.removeView(this) }
}
