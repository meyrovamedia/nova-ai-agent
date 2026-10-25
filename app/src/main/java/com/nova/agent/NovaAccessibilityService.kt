package com.nova.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UiNode(
    val id: Int,
    val cls: String,
    val text: String,
    val desc: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val info: AccessibilityNodeInfo
) {
    fun label(): String = if (text.isNotEmpty()) text else desc
}

class Snapshot(val pkg: String, val nodes: List<UiNode>) {
    fun describe(): String {
        if (nodes.isEmpty()) return "(screen not readable or empty; app may block accessibility)"
        val sb = StringBuilder("APP: $pkg\n")
        for (n in nodes) {
            sb.append("[${n.id}] ${n.cls}")
            if (n.text.isNotEmpty()) sb.append(" text=\"${n.text}\"")
            if (n.desc.isNotEmpty()) sb.append(" desc=\"${n.desc}\"")
            if (n.clickable) sb.append(" clickable")
            if (n.editable) sb.append(" editable")
            if (n.scrollable) sb.append(" scrollable")
            if (n.password) sb.append(" password(hidden)")
            sb.append('\n')
        }
        return sb.toString()
    }
}

class NovaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: NovaAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Reads only visible nodes with a label or an interaction; passwords are never read. */
    fun snapshot(): Snapshot {
        val root = rootInActiveWindow ?: return Snapshot("", emptyList())
        val out = ArrayList<UiNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || out.size >= 80 || !n.isVisibleToUser) return
            val pw = n.isPassword
            val text = if (pw) "" else (n.text?.toString()?.take(60) ?: "")
            val desc = n.contentDescription?.toString()?.take(60) ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable) {
                out.add(UiNode(out.size + 1, n.className?.toString()?.substringAfterLast('.') ?: "View",
                    text, desc, n.isClickable, n.isEditable, n.isScrollable, pw, n))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return Snapshot(root.packageName?.toString() ?: "", out)
    }

    fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 6) {
            if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
            d++
        }
        return false
    }

    fun setText(n: AccessibilityNodeInfo, t: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun pressEnter(n: AccessibilityNodeInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else false
    }

    fun scroll(snap: Snapshot, down: Boolean): Boolean {
        val n = snap.nodes.firstOrNull { it.scrollable } ?: return false
        val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return n.info.performAction(a)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
