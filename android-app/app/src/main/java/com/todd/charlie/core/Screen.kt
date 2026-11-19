package com.todd.charlie.core

/** What Charlie needs from one thing on screen. The Android side wraps AccessibilityNodeInfo in this. */
interface UiNode {
    val text: String?
    val desc: String?
    val hint: String?
    val cls: String?
    val viewId: String?
    val clickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val password: Boolean
    val visible: Boolean
    val bounds: IntArray // left, top, right, bottom
    fun children(): List<UiNode>
}

class ScreenItem(val index: Int, val node: UiNode, val label: String, val line: String)

class ScreenSnapshot(val pkg: String, val items: List<ScreenItem>, val truncated: Boolean, val takenAt: Long) {
    fun item(index: Int): ScreenItem? = items.firstOrNull { it.index == index }
    fun lines(): List<String> = items.map { it.line }
}

object ScreenReader {
    private val ws = Regex("\\s+")

    fun clean(s: String?, max: Int = 160): String? {
        val t = s?.replace(ws, " ")?.trim()
        if (t.isNullOrEmpty()) return null
        return if (t.length > max) t.take(max) + "..." else t
    }

    private fun own(n: UiNode): String? = if (n.password) null else clean(n.text) ?: clean(n.desc)

    /** Label for a node: its own text, else the first few texts inside it. Password text is never used. */
    fun labelOf(n: UiNode): String? {
        own(n)?.let { return it }
        val parts = ArrayList<String>()
        fun walk(x: UiNode, depth: Int) {
            if (parts.size >= 3 || depth > 4) return
            for (c in x.children()) {
                if (!c.visible) continue
                own(c)?.let { if (it !in parts) parts.add(it) }
                if (parts.size >= 3) return
                walk(c, depth + 1)
            }
        }
        walk(n, 0)
        return if (parts.isEmpty()) null else clean(parts.joinToString(" / "))
    }

    private fun kind(n: UiNode): String {
        val c = n.cls?.substringAfterLast('.') ?: ""
        return when {
            n.password -> "Password field"
            n.editable || c.contains("EditText") -> "Field"
            c.contains("Switch") -> "Switch"
            c.contains("CheckBox") -> "Checkbox"
            c.contains("RadioButton") -> "Option"
            c.contains("Button") -> "Button"
            n.clickable -> "Button"
            n.scrollable -> "List"
            else -> "Text"
        }
    }

    fun snapshot(pkg: String, root: UiNode, now: Long, maxItems: Int = 150): ScreenSnapshot {
        val items = ArrayList<ScreenItem>()
        var visited = 0
        var truncated = false

        fun walk(n: UiNode, depth: Int, parentLabel: String?) {
            if (truncated) return
            if (++visited > 2500 || depth > 60) { truncated = true; return }
            if (!n.visible) return
            val interactive = n.clickable || n.editable || n.scrollable || n.checkable
            val ownText = own(n)
            var labelForKids = parentLabel
            val skipAsDuplicate = !interactive && ownText != null && parentLabel != null && parentLabel.contains(ownText)
            if ((interactive || ownText != null) && !skipAsDuplicate) {
                if (items.size >= maxItems) { truncated = true; return }
                val idx = items.size + 1
                val label = when {
                    n.password -> ""
                    n.editable -> clean(n.text) ?: ""
                    else -> labelOf(n) ?: ""
                }
                val sb = StringBuilder("[$idx] ").append(kind(n))
                when {
                    n.password -> sb.append(" (Todd types this himself)")
                    n.editable -> {
                        val hint = clean(n.hint) ?: clean(n.desc)
                        if (label.isNotEmpty()) sb.append(": \"").append(label).append('"') else sb.append(": empty")
                        if (hint != null && hint != label) sb.append(" (").append(hint).append(')')
                        sb.append(" [can type]")
                    }
                    else -> {
                        if (label.isNotEmpty()) sb.append(": ").append(label)
                        if (n.checkable) sb.append(if (n.checked) " [on]" else " [off]")
                        if (n.clickable) sb.append(" [can tap]")
                        if (n.scrollable) sb.append(" [scrolls]")
                    }
                }
                items.add(ScreenItem(idx, n, label, sb.toString()))
                if (n.clickable && label.isNotEmpty()) labelForKids = label
            }
            for (c in n.children()) walk(c, depth + 1, labelForKids)
        }
        walk(root, 0, null)
        return ScreenSnapshot(pkg, items, truncated, now)
    }
}
