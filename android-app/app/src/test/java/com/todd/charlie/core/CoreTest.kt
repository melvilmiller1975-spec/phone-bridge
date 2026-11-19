package com.todd.charlie.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    private fun n(x: String?) = PhoneNumbers.norm(x)

    @Test fun sameRulesAsTermuxBridge() {
        // The same cases as tests/test_contacts.py
        val a = n("(304) 555-0101")
        assertEquals("+13045550101", a)
        assertEquals(a, n("1-304-555-0101"))
        assertEquals(a, n("tel:+1 304 555 0101"))
        assertEquals(a, n("304.555.0101 ext 2"))
        assertEquals(n("+44 20 7946 0101"), n("011 44 20 7946 0101"))
        assertNotEquals(n("+44 20 7946 0101"), n("207 946 0101"))
        assertNotEquals(n("+3045550101"), n("3045550101"))
        assertNotEquals(n("22395"), n("122395"))
        for (b in listOf("CAMC", "Montani", "1-800-FLOWERS", "", null, "+")) assertNull(b, n(b))
    }

    @Test fun dialableRefusesShortCodes() {
        assertEquals("+13045550101", PhoneNumbers.dialable("304-555-0101"))
        assertNull(PhoneNumbers.dialable("22395"))
        assertNull(PhoneNumbers.dialable("Mom"))
    }
}

class TextFilterTest {
    private val contacts = mapOf("+13045550101" to "Mom", "+13045550202" to "Wife")
    private fun sms(id: Long, addr: String?) = Sms(id, addr, "body $id", 1_000L * id, "inbox", false)

    @Test fun onlyContactsOldestFirst() {
        val newestFirst = listOf(sms(5, "304-555-0101"), sms(4, "22395"), sms(3, "(304) 555-0202"), sms(2, "CAMC"), sms(1, "+1 304 555 0101"))
        val out = TextFilter.filter(contacts, newestFirst, 20) { "t$it" }
        val msgs = out.getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals(1L, msgs.getJSONObject(0).getLong("_id"))
        assertEquals("Mom", msgs.getJSONObject(0).getString("contact"))
        assertEquals("Wife", msgs.getJSONObject(1).getString("contact"))
        assertEquals(5L, msgs.getJSONObject(2).getLong("_id"))
        assertEquals(2, out.getInt("left_out_not_in_contacts"))
        for (i in 0 until msgs.length()) assertFalse(msgs.getJSONObject(i).getString("body").contains("2") && msgs.getJSONObject(i).getLong("_id") == 2L)
    }

    @Test fun limitKeepsNewest() {
        val newestFirst = (10 downTo 1).map { sms(it.toLong(), "304-555-0101") }
        val msgs = TextFilter.filter(contacts, newestFirst, 3) { "" }.getJSONArray("messages")
        assertEquals(listOf(8L, 9L, 10L), (0 until 3).map { msgs.getJSONObject(it).getLong("_id") })
    }

    @Test(expected = FilterFail::class) fun failsClosedWithoutContacts() {
        TextFilter.filter(emptyMap(), listOf(sms(1, "304-555-0101")), 5) { "" }
    }

    @Test fun argsClamped() {
        assertEquals(100, TextFilter.limit(JSONObject().put("limit", 9999)))
        assertEquals(1, TextFilter.limit(JSONObject().put("limit", -3)))
        assertEquals("inbox", TextFilter.box(JSONObject().put("box", "drafts")))
        assertEquals("sent", TextFilter.box(JSONObject().put("box", "sent")))
    }
}

class SafetyTest {
    @Test fun riskyButtonsNeedApproval() {
        for (l in listOf("Send", "SEND MESSAGE", "Place order", "Pay now", "Delete", "Buy", "Call", "Confirm", "Submit")) assertTrue(l, Safety.isRisky(l))
        assertTrue(Safety.isRisky("", "com.google.android.apps.messaging:id/send_message_button_icon"))
        for (l in listOf("Search", "Back", "Settings menu", "Directions", "Callum's photos", "Sender info", null)) assertFalse(l.toString(), Safety.isRisky(l))
    }

    @Test fun neverControlsCharlieSettingsOrLockScreen() {
        val ok = setOf("com.google.android.apps.maps")
        assertNull(Safety.controlRefusal("com.google.android.apps.maps", ok, false))
        assertTrue(Safety.controlRefusal("com.facebook.katana", ok, false)!!.contains("approved list"))
        for (p in listOf("com.todd.charlie", "com.android.settings", "com.android.systemui", "com.google.android.permissioncontroller", "com.google.android.packageinstaller"))
            assertTrue(p, Safety.controlRefusal(p, ok + p, true) != null)
        assertNull(Safety.controlRefusal("com.facebook.katana", ok, true))
        assertTrue(Safety.controlRefusal(null, ok, true) != null)
    }

    @Test fun appMatching() {
        val apps = listOf(AppEntry("Maps", "com.google.android.apps.maps"), AppEntry("Messages", "com.google.android.apps.messaging"), AppEntry("Sesame", "ai.sesame.app"), AppEntry("Google Maps Go", "x.go"))
        assertEquals("com.google.android.apps.maps", AppMatch.find(apps, "maps")!!.pkg)
        assertEquals("ai.sesame.app", AppMatch.find(apps, "ses")!!.pkg)
        assertEquals("com.google.android.apps.messaging", AppMatch.find(apps, "com.google.android.apps.messaging")!!.pkg)
        assertNull(AppMatch.find(apps, "booker"))
        assertNull(AppMatch.find(apps, "  "))
    }

    @Test fun recipients() {
        val c = listOf(ContactEntry("Mom", "(304) 555-0101", "Mobile"), ContactEntry("Chris Work", "304-555-0303"), ContactEntry("Chris Smith", "304-555-0404"), ContactEntry("Pharmacy", "22395"))
        assertEquals(Recipient.Found("Mom", "+13045550101"), ContactMatch.resolve(c, "mom"))
        assertEquals(Recipient.Found("Mom", "+13045550101"), ContactMatch.resolve(c, "1 304 555 0101"))
        assertEquals(Recipient.Found(null, "+13045559999"), ContactMatch.resolve(c, "304-555-9999"))
        val two = ContactMatch.resolve(c, "chris")
        assertTrue(two is Recipient.Problem && two.message.contains("More than one"))
        assertTrue(ContactMatch.resolve(c, "22395") is Recipient.Problem)
        assertTrue(ContactMatch.resolve(c, "Pharmacy") is Recipient.Problem)
        assertTrue(ContactMatch.resolve(c, "nobody") is Recipient.Problem)
        assertEquals(Recipient.Found("Chris Smith", "+13045550404"), ContactMatch.resolve(c, "chris smith"))
    }
}

class ApprovalsTest {
    @Test fun runsOnlyOnceAndOnlyWhenApproved() {
        val a = Approvals()
        var runs = 0
        val item = a.add("text", "t", "d", "Send") { runs++; "sent" }
        assertEquals(Approvals.State.WAITING, a.get(item.id)!!.state)
        assertEquals(0, runs)
        a.approve(item.id)
        a.approve(item.id)
        assertEquals(1, runs)
        assertEquals("sent", a.get(item.id)!!.result)
        assertEquals(6, item.id.length)
    }

    @Test fun deniedNeverRuns() {
        val a = Approvals()
        var runs = 0
        val item = a.add("tap", "t", "d", "Tap it") { runs++; "x" }
        a.deny(item.id)
        a.approve(item.id)
        assertEquals(0, runs)
        assertEquals(Approvals.State.DENIED, item.state)
    }

    @Test fun expiresAfterTenMinutes() {
        var t = 0L
        val a = Approvals(now = { t })
        var runs = 0
        val item = a.add("text", "t", "d", "Send") { runs++; "x" }
        t = 10 * 60_000L + 1
        a.approve(item.id)
        assertEquals(0, runs)
        assertEquals(Approvals.State.EXPIRED, item.state)
        assertTrue(a.waiting().isEmpty())
    }

    @Test fun failureIsReported() {
        val a = Approvals()
        val item = a.add("tap", "t", "d", "Tap it") { throw IllegalStateException("gone") }
        a.approve(item.id)
        assertEquals(Approvals.State.FAILED, item.state)
        assertTrue(item.result!!.contains("gone"))
    }
}

class ScreenReaderTest {
    class F(
        override val text: String? = null, override val desc: String? = null, override val hint: String? = null,
        override val cls: String? = "android.view.View", override val viewId: String? = null,
        override val clickable: Boolean = false, override val editable: Boolean = false, override val scrollable: Boolean = false,
        override val checkable: Boolean = false, override val checked: Boolean = false, override val password: Boolean = false,
        override val visible: Boolean = true, val kids: List<UiNode> = emptyList(),
    ) : UiNode {
        override val bounds = intArrayOf(0, 0, 10, 10)
        override fun children() = kids
    }

    @Test fun numbersItemsAndHidesPasswords() {
        val root = F(scrollable = true, kids = listOf(
            F(text = "Inbox"),
            F(clickable = true, kids = listOf(F(text = "Mom"), F(text = "See you at 5"))),
            F(cls = "android.widget.EditText", editable = true, hint = "Search"),
            F(cls = "android.widget.EditText", editable = true, password = true, text = "hunter2secret"),
            F(text = "hidden", visible = false),
            F(cls = "android.widget.Switch", checkable = true, checked = true, clickable = true, text = "Wi-Fi"),
        ))
        val s = ScreenReader.snapshot("pkg", root, 0)
        val all = s.lines().joinToString("\n")
        assertTrue(all, s.lines()[0].startsWith("[1] List"))
        assertTrue(all, all.contains("[2] Text: Inbox"))
        assertTrue(all, all.contains("[3] Button: Mom / See you at 5 [can tap]"))
        assertFalse("child texts of a button are not repeated", all.contains("Text: Mom"))
        assertTrue(all, all.contains("Field: empty (Search) [can type]"))
        assertTrue(all, all.contains("Password field (Todd types this himself)"))
        assertFalse(all, all.contains("hunter2"))
        assertFalse(all, all.contains("hidden"))
        assertTrue(all, all.contains("Switch: Wi-Fi [on] [can tap]"))
        assertEquals("Mom / See you at 5", s.item(3)!!.label)
    }

    @Test fun capsHugeScreens() {
        val root = F(kids = (1..400).map { F(text = "row $it") })
        val s = ScreenReader.snapshot("pkg", root, 0)
        assertEquals(150, s.items.size)
        assertTrue(s.truncated)
    }
}

class RelaySettingsTest {
    @Test fun parsesTermuxFile() {
        val s = RelaySettingsParser.parse("https://phone-bridge-relay.example.workers.dev\nAbCdEfGhIjKlMnOpQrStUvWxYz0123456789\n")!!
        assertEquals("wss://phone-bridge-relay.example.workers.dev/phone", s.url)
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789", s.key)
        assertEquals("wss://x.dev/phone", RelaySettingsParser.parse("wss://x.dev/phone?k=1\nAbCdEfGhIjKlMnOpQrStUvWxYz0123456789")!!.url)
        assertEquals(RelaySettingsParser.DEFAULT_URL, RelaySettingsParser.parse("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")!!.url)
        assertNull(RelaySettingsParser.parse("https://x.dev"))
        assertNull(RelaySettingsParser.parse("hello there"))
    }
}

class ToolRegistryTest {
    @Test fun callsAndErrors() {
        val r = ToolRegistry()
        r.add("battery", "b") { JSONObject().put("percentage", 79) }
        r.add("boom", "x") { throw RuntimeException("bad") }
        r.add("no", "x") { ToolRegistry.err("nope") }
        val ok = r.call("battery", null)
        assertFalse(ok.has("isError"))
        assertEquals(79, JSONObject(ok.getJSONArray("content").getJSONObject(0).getString("text")).getInt("percentage"))
        assertTrue(r.call("boom", JSONObject()).getBoolean("isError"))
        assertTrue(r.call("no", JSONObject()).getBoolean("isError"))
        assertTrue(r.call("shell", JSONObject()).getBoolean("isError"))
        assertEquals("battery", r.listJson().getJSONObject(0).getString("name"))
    }
}
