package com.todd.charlie

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.todd.charlie.core.Approvals
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Sends a text only after Todd tapped Send, and reports back whether the carrier took it. */
object SmsSender {
    const val SENT = "com.todd.charlie.SMS_SENT"
    private val parts = ConcurrentHashMap<String, AtomicInteger>()
    private val failed = ConcurrentHashMap<String, Int>()

    fun send(ctx: Context, approvalId: String, number: String, message: String) {
        val sms = ctx.getSystemService(SmsManager::class.java)
        val pieces = sms.divideMessage(message)
        parts[approvalId] = AtomicInteger(pieces.size)
        val intents = ArrayList<PendingIntent>()
        for (i in pieces.indices) {
            val it = Intent(ctx, SentReceiver::class.java).setAction(SENT).putExtra("id", approvalId)
            intents.add(PendingIntent.getBroadcast(ctx, (approvalId.hashCode() and 0xffff) * 16 + i, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (pieces.size == 1) sms.sendTextMessage(number, null, message, intents[0], null)
        else sms.sendMultipartTextMessage(number, null, pieces, intents, null)
        App.I.log("Sending text $approvalId")
    }

    internal fun partDone(ctx: Context, id: String, code: Int) {
        if (code != Activity.RESULT_OK) failed[id] = code
        val left = parts[id]?.decrementAndGet() ?: return
        if (left > 0) return
        parts.remove(id)
        val bad = failed.remove(id)
        val app = App.I
        if (bad == null) {
            app.approvals.update(id, Approvals.State.APPROVED, "sent (the carrier accepted it)")
            app.log("Text $id sent")
            Notifs.info(ctx, 600, "Text sent", "Charlie's text went out.")
        } else {
            app.approvals.update(id, Approvals.State.FAILED, "not sent (Android error $bad, maybe no signal)")
            app.log("Text $id failed ($bad)")
            Notifs.info(ctx, 600, "Text NOT sent", "Charlie's text didn't go out (no signal?).")
        }
    }
}

class SentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        SmsSender.partDone(context, id, resultCode)
    }
}

/** The Send / Tap it / Don't buttons on approval notifications. */
class ApprovalReceiver : BroadcastReceiver() {
    companion object {
        const val APPROVE = "com.todd.charlie.APPROVE"
        const val DENY = "com.todd.charlie.DENY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val app = App.I
        Notifs.cancelApproval(context, id)
        when (intent.action) {
            APPROVE -> {
                val pending = goAsync()
                Thread {
                    try {
                        val item = app.approvals.approve(id)
                        app.log("You approved ${item?.title ?: id}: ${item?.result ?: "expired"}")
                    } finally { pending.finish() }
                }.start()
            }
            DENY -> {
                app.approvals.deny(id)
                app.log("You said no to $id")
            }
        }
    }
}
