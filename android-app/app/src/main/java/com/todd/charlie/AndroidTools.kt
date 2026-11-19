package com.todd.charlie

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityNodeInfo
import com.todd.charlie.core.AppEntry
import com.todd.charlie.core.AppMatch
import com.todd.charlie.core.ContactEntry
import com.todd.charlie.core.ContactMatch
import com.todd.charlie.core.FilterFail
import com.todd.charlie.core.PhoneNumbers
import com.todd.charlie.core.Props
import com.todd.charlie.core.Recipient
import com.todd.charlie.core.Safety
import com.todd.charlie.core.ScreenReader
import com.todd.charlie.core.ScreenSnapshot
import com.todd.charlie.core.Sms
import com.todd.charlie.core.TextFilter
import com.todd.charlie.core.ToolRegistry
import com.todd.charlie.core.ToolRegistry.Companion.err
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every tool Sesame can call on the phone. Each one checks its own permission and Todd's settings first. */
class AndroidTools(private val app: App) {
    private val prefs get() = app.prefs
    @Volatile private var last: ScreenSnapshot? = null

    fun registry(): ToolRegistry = ToolRegistry().apply {
        add("battery", "Phone battery level and charging state.") { battery() }
        add(
            "read_texts",
            "Read recent SMS texts from saved contacts only (read-only). RCS chats are not in Android's SMS store; use new_messages for those.",
            Props.of("limit" to Props.int("1-100, default 20"), "box" to Props.choice("default inbox", "inbox", "sent", "all"))
        ) { readTexts(it) }
        add(
            "new_messages",
            "New incoming messages from saved contacts, read from Messages notifications, so RCS chats are included. Only messages that arrived since Charlie's notification access was turned on.",
            Props.of("limit" to Props.int("1-50, default 20"))
        ) { newMessages(it) }
        add("list_apps", "Apps Todd approved for Charlie to open and control.") { listApps() }
        add("open_app", "Open an approved app by its name, for example Maps.", Props.of("name" to Props.str("App name as shown on the phone")), listOf("name")) { openApp(it) }
        add(
            "send_text",
            "Ask to send an SMS to a saved contact or a phone number. Nothing is sent until Todd taps Send on Charlie's notification on the phone. Then check with check_approval.",
            Props.of("to" to Props.str("Contact name or phone number"), "message" to Props.str("Exact text to send")),
            listOf("to", "message")
        ) { sendText(it) }
        add(
            "check_approval",
            "Status of something waiting for Todd's OK on the phone (a text or a tap). Leave id empty to list recent ones.",
            Props.of("id" to Props.str("approval_id from send_text or tap"))
        ) { checkApproval(it) }
        add("read_screen", "List what is on the phone's screen now (text, buttons, fields), numbered. Only in approved apps, with screen control turned on in the Charlie app.") { readScreen() }
        add(
            "tap",
            "Tap a numbered item from the last read_screen. Buttons like Send, Pay, Buy, Delete or Call wait for Todd's OK on the phone.",
            Props.of("item" to Props.int("Item number from read_screen")), listOf("item")
        ) { tap(it) }
        add(
            "type_text",
            "Type into a numbered field from the last read_screen. Replaces what is in the field. Never passwords. Does not press Send or Enter.",
            Props.of("item" to Props.int("Field number from read_screen"), "text" to Props.str("Text to type")), listOf("item", "text")
        ) { typeText(it) }
        add(
            "scroll",
            "Scroll the screen up or down, or a numbered list from read_screen.",
            Props.of("direction" to Props.choice("default down", "down", "up"), "item" to Props.int("Optional list number from read_screen"))
        ) { scroll(it) }
        add("press_key", "Press the phone's back, home or recent apps button.", Props.of("key" to Props.choice("Which button", "back", "home", "recents")), listOf("key")) { pressKey(it) }
        add("link_status", "Which Charlie features and permissions are turned on, and the app version.") { status() }
    }

    // ---------- helpers ----------

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private val fmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun contacts(): List<ContactEntry> {
        if (!granted(Manifest.permission.READ_CONTACTS)) throw FilterFail("Charlie can't read contacts. Open Charlie and tap Allow texts and contacts.")
        val out = ArrayList<ContactEntry>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(app.resources, c.getInt(2), c.getString(3) ?: "").toString()
                out.add(ContactEntry(name, num, label))
            }
        }
        return out
    }

    private fun contactMap(): Map<String, String> {
        val m = HashMap<String, String>()
        for (c in contacts()) PhoneNumbers.norm(c.number)?.let { m.putIfAbsent(it, c.name) }
        return m
    }

    private fun appLabel(pkg: String): String = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun launchables(): List<AppEntry> {
        val pm = app.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun allApps(): List<AppEntry> = launchables().filter { it.pkg != app.packageName }

    // ---------- battery and texts ----------

    private fun battery(): JSONObject {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = app.getSystemService(BatteryManager::class.java)
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
            else -> "UNPLUGGED"
        }
        val o = JSONObject().put("percentage", pct).put("status", status).put("plugged", plugged)
        i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { o.put("temperature", it / 10.0) }
        return o
    }

    private fun readTexts(args: JSONObject): JSONObject {
        return try {
            if (!granted(Manifest.permission.READ_SMS)) throw FilterFail("Charlie can't read texts. Open Charlie and tap Allow texts and contacts.")
            val keys = contactMap()
            val box = TextFilter.box(args)
            val sel = when (box) {
                "inbox" -> "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}"
                "sent" -> "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}"
                else -> null
            }
            val rows = ArrayList<Sms>()
            val proj = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
            val c = app.contentResolver.query(Telephony.Sms.CONTENT_URI, proj, sel, null, "${Telephony.Sms.DATE} DESC")
                ?: throw FilterFail("Android returned no SMS list (permission?)")
            c.use {
                while (it.moveToNext() && rows.size < TextFilter.MAX_SCAN) {
                    val type = it.getInt(4)
                    rows.add(Sms(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3),
                        if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "other",
                        it.getInt(5) == 1))
                }
            }
            TextFilter.filter(keys, rows, TextFilter.limit(args)) { fmt.format(Date(it)) }
        } catch (e: FilterFail) {
            app.log("read_texts blocked: ${e.message}")
            JSONObject().put("error", e.message).put("messages", JSONArray())
        } catch (e: SecurityException) {
            JSONObject().put("error", "Android blocked reading texts or contacts: allow them in the Charlie app.").put("messages", JSONArray())
        }
    }

    private fun newMessages(args: JSONObject): JSONObject {
        if (!NotifListener.enabled(app)) return err("New message alerts are off. In the Charlie app tap New message alerts and turn Charlie on.")
        val contacts = try { contacts() } catch (e: FilterFail) { return err(e.message ?: "contacts unreadable") }
        if (contacts.isEmpty()) return err("No contacts readable, so no messages shown.")
        val names = contacts.map { it.name.trim().lowercase() }.toSet()
        val nums = contacts.mapNotNull { PhoneNumbers.norm(it.number) }.toSet()
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        var hidden = 0
        val keep = NotifListener.recent().filter { m ->
            val ok = m.from.trim().lowercase() in names || PhoneNumbers.norm(m.from)?.let { it in nums } == true
            if (!ok) hidden++
            ok
        }.takeLast(limit)
        val arr = JSONArray()
        for (m in keep) arr.put(JSONObject().put("from", m.from).put("text", m.text).put("time", fmt.format(Date(m.time))).put("app", appLabel(m.pkg)).apply { m.chat?.let { put("chat", it) } })
        return JSONObject().put("filter", "contacts only").put("messages", arr).put("left_out_not_in_contacts", hidden)
            .put("note", "Only messages that arrived since alerts were turned on and the app last started. Android hides one-time codes from apps like this.")
    }

    // ---------- apps ----------

    private fun listApps(): JSONObject {
        val apps = allApps()
        val ok = if (prefs.allowAll) apps else apps.filter { it.pkg in prefs.approved }
        val arr = JSONArray()
        for (a in ok) arr.put(JSONObject().put("name", a.label).put("package", a.pkg))
        return JSONObject().put("all_apps_approved", prefs.allowAll).put("approved", arr)
    }

    private fun openApp(args: JSONObject): JSONObject {
        val q = args.optString("name").trim()
        if (q.isEmpty()) return err("Say which app to open.")
        val a = AppMatch.find(allApps(), q) ?: return err("No app called \"$q\" on the phone.")
        if (!prefs.allowAll && a.pkg !in prefs.approved) return err("${a.label} isn't on Todd's approved list. He can add it in the Charlie app under Approved apps.")
        val launch = app.packageManager.getLaunchIntentForPackage(a.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return err("${a.label} has no screen to open.")
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val svc = ScreenService.instance
        val how: String
        try {
            when {
                svc != null -> { svc.startActivity(launch); how = "opened" }
                Settings.canDrawOverlays(app) -> { app.startActivity(launch); how = "opened" }
                else -> {
                    Notifs.tapToOpen(app, a.label, launch)
                    app.log("Asked Todd to tap to open ${a.label}")
                    return JSONObject().put("app", a.label).put("result", "notification")
                        .put("note", "Android blocks opening apps from the background without Display over other apps or screen control, so the phone shows 'Tap to open ${a.label}'.")
                }
            }
        } catch (e: Exception) {
            Notifs.tapToOpen(app, a.label, launch)
            return JSONObject().put("app", a.label).put("result", "notification").put("note", "Android refused (${e.javaClass.simpleName}), so the phone shows 'Tap to open ${a.label}'.")
        }
        app.log("Opened ${a.label}")
        val o = JSONObject().put("app", a.label).put("package", a.pkg).put("result", how).put("phone_locked", locked)
        if (locked) o.put("note", "The phone is locked, so the app shows after Todd unlocks it.")
        if (svc != null) {
            var seen = false
            for (t in 0 until 10) {
                if (svc.rootInActiveWindow?.packageName?.toString() == a.pkg) { seen = true; break }
                Thread.sleep(250)
            }
            o.put("on_screen_confirmed", seen)
        }
        return o
    }

    // ---------- outgoing texts (always wait for Todd's tap) ----------

    private fun sendText(args: JSONObject): JSONObject {
        if (!granted(Manifest.permission.SEND_SMS)) return err("Sending texts is off. In the Charlie app tap Allow sending texts.")
        val msg = args.optString("message")
        if (msg.isBlank()) return err("The message is empty.")
        if (msg.length > 1600) return err("That message is too long for a text (over 1600 characters).")
        val list = try { contacts() } catch (e: FilterFail) { emptyList() }
        val r = when (val x = ContactMatch.resolve(list, args.optString("to"))) {
            is Recipient.Problem -> return err(x.message)
            is Recipient.Found -> x
        }
        val who = r.name ?: r.number
        val detail = "To: " + (if (r.name != null) "${r.name} (${r.number})" else "${r.number}, not in your contacts") + "\nMessage: $msg"
        val item = app.approvals.add("text", "Charlie wants to text $who", detail, "Send") { it ->
            SmsSender.send(app, it.id, r.number, msg)
            "sending"
        }
        Notifs.approval(app, item)
        app.log("Waiting for your OK: text to $who")
        return JSONObject().put("status", "waiting_for_approval").put("approval_id", item.id)
            .put("to", r.name ?: JSONObject.NULL).put("number", r.number).put("message", msg)
            .put("note", "Nothing has been sent. Todd has to tap Send on Charlie's notification on the phone within 10 minutes (the phone must be unlocked). Check with check_approval.")
    }

    private fun checkApproval(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        if (id.isEmpty()) {
            val arr = JSONArray()
            for (i in app.approvals.recent()) arr.put(i.toJson())
            return JSONObject().put("recent", arr)
        }
        return app.approvals.get(id)?.toJson() ?: err("No approval with id $id (they are kept 10 minutes, in memory only).")
    }

    // ---------- screen control ----------

    private class Gate(val svc: ScreenService, val root: AccessibilityNodeInfo, val pkg: String)

    private fun gate(): Any {
        if (!prefs.screenControl) return err("Screen control is turned off in the Charlie app.")
        val svc = ScreenService.instance ?: return err("Charlie's screen control service is off. Turn it on in Settings, Accessibility, Charlie.")
        val root = svc.rootInActiveWindow ?: return err("Nothing readable on screen right now (the phone may be locked, or the app hides its screen).")
        val pkg = root.packageName?.toString() ?: ""
        Safety.controlRefusal(pkg, prefs.approved, prefs.allowAll)?.let { return err(it) }
        return Gate(svc, root, pkg)
    }

    private fun readScreen(): JSONObject {
        val gr = gate()
        val g = gr as? Gate ?: return gr as JSONObject
        val snap = ScreenReader.snapshot(g.pkg, A11yNode(g.root), System.currentTimeMillis())
        last = snap
        val label = appLabel(g.pkg)
        app.log("Read the screen in $label")
        val o = JSONObject().put("app", label).put("package", g.pkg).put("items", JSONArray(snap.lines()))
        if (snap.truncated) o.put("truncated", true)
        if (snap.items.isEmpty()) o.put("note", "Nothing readable. Some apps hide their screens from accessibility services.")
        return o
    }

    private class Target(val g: Gate, val snap: ScreenSnapshot, val node: AccessibilityNodeInfo, val label: String)

    private fun target(args: JSONObject): Any {
        val gr = gate()
        val g = gr as? Gate ?: return gr
        val snap = last ?: return err("Call read_screen first.")
        if (snap.pkg != g.pkg) return err("The screen changed to another app. Call read_screen again.")
        if (System.currentTimeMillis() - snap.takenAt > 5 * 60_000) return err("The screen list is over 5 minutes old. Call read_screen again.")
        val item = snap.item(args.optInt("item", -1)) ?: return err("No item with that number. Call read_screen again.")
        val node = (item.node as A11yNode).info
        if (!node.refresh() || !node.isVisibleToUser) return err("That item is gone from the screen. Call read_screen again.")
        return Target(g, snap, node, item.label)
    }

    private fun clickNode(svc: ScreenService, node: AccessibilityNodeInfo): String {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && !n.isClickable && hops < 6) { n = n.parent; hops++ }
        if (n != null && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped"
        val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return "could not tap"
        return if (svc.tapAt(r.exactCenterX(), r.exactCenterY())) "tapped by position" else "could not tap"
    }

    private fun tap(args: JSONObject): JSONObject {
        val tr = target(args)
        val t = tr as? Target ?: return tr as JSONObject
        val appName = appLabel(t.g.pkg)
        val shown = t.label.ifBlank { "item ${args.optInt("item")}" }
        if (Safety.isRisky(t.label, t.node.viewIdResourceName, t.node.contentDescription?.toString())) {
            val node = t.node
            val svc = t.g.svc
            val item = app.approvals.add("tap", "Charlie wants to tap \"$shown\" in $appName", "Tap \"$shown\" in $appName. This could send, pay, buy, call or delete something.", "Tap it") {
                if (!node.refresh() || !node.isVisibleToUser) throw IllegalStateException("the button is no longer on screen")
                val res = clickNode(svc, node)
                app.log("Approved tap on $shown: $res")
                res
            }
            Notifs.approval(app, item)
            app.log("Waiting for your OK: tap $shown in $appName")
            return JSONObject().put("status", "waiting_for_approval").put("approval_id", item.id)
                .put("note", "\"$shown\" could send, pay, buy, call or delete something, so nothing was tapped. Todd has to tap 'Tap it' on Charlie's notification within 10 minutes.")
        }
        val res = clickNode(t.g.svc, t.node)
        app.log("Tapped $shown in $appName ($res)")
        Notifs.updateLink(app)
        return if (res.startsWith("tapped")) JSONObject().put("result", res).put("item", shown) else err("Couldn't tap $shown.")
    }

    private fun typeText(args: JSONObject): JSONObject {
        val tr = target(args)
        val t = tr as? Target ?: return tr as JSONObject
        if (t.node.isPassword) return err("That's a password field. Todd types passwords himself.")
        if (!t.node.isEditable) return err("That item isn't a text field.")
        val text = args.optString("text")
        if (text.length > 2000) return err("Too much text (over 2000 characters).")
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        t.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = t.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        app.log("Typed into a field in ${appLabel(t.g.pkg)}")
        return if (ok) JSONObject().put("result", "typed").put("note", "Nothing was sent or submitted.") else err("The app didn't accept typed text in that field.")
    }

    private fun scroll(args: JSONObject): JSONObject {
        val gr = gate()
        val g = gr as? Gate ?: return gr as JSONObject
        val up = args.optString("direction") == "up"
        var node: AccessibilityNodeInfo? = null
        if (args.has("item")) {
            val tr = target(args)
        val t = tr as? Target ?: return tr as JSONObject
            node = t.node
        } else {
            val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(g.root) }
            var seen = 0
            while (q.isNotEmpty() && seen < 1500) {
                val n = q.removeFirst(); seen++
                if (n.isScrollable && n.isVisibleToUser) { node = n; break }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
        }
        val action = if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (node != null && node.performAction(action)) { last = null; return JSONObject().put("result", "scrolled " + (if (up) "up" else "down")).put("note", "Call read_screen to see the new items.") }
        val dm = app.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (a, b) = if (up) dm.heightPixels * 0.3f to dm.heightPixels * 0.7f else dm.heightPixels * 0.7f to dm.heightPixels * 0.3f
        return if (g.svc.swipe(x, a, b)) { last = null; JSONObject().put("result", "swiped " + (if (up) "up" else "down")) } else err("Couldn't scroll here.")
    }

    private fun pressKey(args: JSONObject): JSONObject {
        if (!prefs.screenControl) return err("Screen control is turned off in the Charlie app.")
        val svc = ScreenService.instance ?: return err("Charlie's screen control service is off.")
        val k = args.optString("key")
        val action = when (k) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return err("key must be back, home or recents")
        }
        last = null
        app.log("Pressed $k")
        return if (svc.performGlobalAction(action)) JSONObject().put("result", "pressed $k") else err("Android refused to press $k.")
    }

    private fun status(): JSONObject {
        val v = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
        return JSONObject()
            .put("app_version", v)
            .put("read_texts_permission", granted(Manifest.permission.READ_SMS))
            .put("contacts_permission", granted(Manifest.permission.READ_CONTACTS))
            .put("send_texts_permission", granted(Manifest.permission.SEND_SMS))
            .put("display_over_other_apps", Settings.canDrawOverlays(app))
            .put("screen_control_switch", prefs.screenControl)
            .put("screen_control_service", ScreenService.instance != null)
            .put("new_message_alerts", NotifListener.enabled(app))
            .put("approved_apps", if (prefs.allowAll) "all" else prefs.approved.size.toString())
            .put("waiting_for_ok", app.approvals.waiting().size)
    }
}
