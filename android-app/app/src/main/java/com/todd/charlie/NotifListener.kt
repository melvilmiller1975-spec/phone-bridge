package com.todd.charlie

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Catches new incoming messages from messaging-app notifications (this is how RCS chats get seen).
 * Only messages that arrive while it is on are kept, in memory only, at most 200.
 */
class NotifListener : NotificationListenerService() {
    data class Msg(val from: String, val text: String, val time: Long, val pkg: String, val chat: String?)

    companion object {
        private val store = ArrayDeque<Msg>()
        private val seen = LinkedHashSet<String>()

        fun recent(): List<Msg> = synchronized(store) { store.toList() }

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotifListener::class.java)
            return flat.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
        }

        internal fun add(m: Msg) = synchronized(store) {
            val k = "${m.pkg}|${m.from}|${m.time}|${m.text.hashCode()}"
            if (!seen.add(k)) return@synchronized
            while (seen.size > 600) seen.remove(seen.first())
            store.addLast(m)
            while (store.size > 200) store.removeFirst()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.category != Notification.CATEGORY_MESSAGE && n.extras?.containsKey(Notification.EXTRA_MESSAGES) != true) return
        val ex = n.extras ?: return
        val chat = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (Build.VERSION.SDK_INT >= 30 && arr != null && arr.isNotEmpty()) {
            for (m in Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)) {
                val who = m.senderPerson?.name?.toString() ?: m.sender?.toString() ?: continue // null sender = Todd's own message
                val text = m.text?.toString() ?: continue
                add(Msg(who, text, m.timestamp, sbn.packageName, chat))
            }
        } else {
            val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            add(Msg(who, text, sbn.postTime, sbn.packageName, chat))
        }
    }
}
