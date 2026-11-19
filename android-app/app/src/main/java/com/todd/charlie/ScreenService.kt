package com.todd.charlie

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.todd.charlie.core.UiNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Charlie's accessibility service. Todd turns it on in Settings > Accessibility. It reacts to nothing on its
 * own: it only reads or taps when a tool asks, and only while the screen control switch is on.
 */
class ScreenService : AccessibilityService() {
    companion object {
        @Volatile var instance: ScreenService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        App.I.log("Screen control service on")
        Notifs.updateLink(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        App.I.log("Screen control service off")
        Notifs.updateLink(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun gesture(path: Path, ms: Long): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, null)
        if (!sent) return false
        done.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun tapAt(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)

    fun swipe(x: Float, fromY: Float, toY: Float): Boolean = gesture(Path().apply { moveTo(x, fromY); lineTo(x, toY) }, 350)
}

/** Wraps an Android screen node for the shared ScreenReader logic. */
class A11yNode(val info: AccessibilityNodeInfo) : UiNode {
    override val text get() = info.text?.toString()
    override val desc get() = info.contentDescription?.toString()
    override val hint get() = info.hintText?.toString()
    override val cls get() = info.className?.toString()
    override val viewId get() = info.viewIdResourceName
    override val clickable get() = info.isClickable
    override val editable get() = info.isEditable
    override val scrollable get() = info.isScrollable
    override val checkable get() = info.isCheckable
    override val checked get() = info.isChecked
    override val password get() = info.isPassword
    override val visible get() = info.isVisibleToUser
    override val bounds: IntArray get() = Rect().also { info.getBoundsInScreen(it) }.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
    override fun children(): List<UiNode> = (0 until info.childCount).mapNotNull { info.getChild(it)?.let(::A11yNode) }
}
