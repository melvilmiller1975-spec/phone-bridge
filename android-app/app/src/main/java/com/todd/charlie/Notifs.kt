package com.todd.charlie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.todd.charlie.core.Approvals

object Notifs {
    const val LINK_ID = 1
    private const val CH_LINK = "link"
    private const val CH_ASK = "approvals"
    private const val CH_INFO = "info"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LINK, "Link status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Waiting for your OK", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Opened apps and sent texts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openMain(ctx: Context) =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags())

    /** The always-on notification: the sign that the link is running, with Stop buttons. */
    fun link(ctx: Context, status: String): Notification {
        val prefs = App.I.prefs
        val screen = if (prefs.screenControl && ScreenService.instance != null) "Screen control ON" else "Screen control off"
        val stop = PendingIntent.getService(ctx, 1, Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_STOP), flags())
        val b = Notification.Builder(ctx, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Charlie link: $status")
            .setContentText(screen)
            .setOngoing(true)
            .setContentIntent(openMain(ctx))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Stop link", stop).build())
        if (prefs.screenControl) {
            val off = PendingIntent.getService(ctx, 2, Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_SCREEN_OFF), flags())
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Stop screen control", off).build())
        }
        return b.build()
    }

    fun updateLink(ctx: Context) {
        if (!BridgeService.running) return
        ctx.getSystemService(NotificationManager::class.java).notify(LINK_ID, link(ctx, App.I.linkStatus))
    }

    private fun askId(id: String) = 1000 + (id.hashCode() and 0xffff)

    fun approval(ctx: Context, item: Approvals.Item) {
        fun act(action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, askId(item.id) * 4 + code,
            Intent(ctx, ApprovalReceiver::class.java).setAction(action).putExtra("id", item.id), flags()
        )
        val yes = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), item.buttonLabel, act(ApprovalReceiver.APPROVE, 1))
        val no = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Don't", act(ApprovalReceiver.DENY, 2))
        if (Build.VERSION.SDK_INT >= 31) yes.setAuthenticationRequired(true) // phone must be unlocked to approve
        val n = Notification.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(item.title)
            .setContentText(item.detail)
            .setStyle(Notification.BigTextStyle().bigText(item.detail))
            .setContentIntent(openMain(ctx))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setTimeoutAfter(10 * 60_000L)
            .addAction(yes.build())
            .addAction(no.build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(askId(item.id), n)
    }

    fun cancelApproval(ctx: Context, id: String) =
        ctx.getSystemService(NotificationManager::class.java).cancel(askId(id))

    fun info(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent? = null) {
        val n = Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap ?: openMain(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** Fallback when Android blocks opening an app from the background: one tap opens it. */
    fun tapToOpen(ctx: Context, label: String, launch: Intent) {
        val pi = PendingIntent.getActivity(ctx, 500 + (label.hashCode() and 0xfff), launch, flags())
        info(ctx, 500, "Tap to open $label", "Charlie couldn't open it from the background.", pi)
    }
}
