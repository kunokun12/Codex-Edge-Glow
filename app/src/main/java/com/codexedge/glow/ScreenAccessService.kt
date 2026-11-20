package com.codexedge.glow

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-only accessibility snapshot. Read with:
 * adb shell run-as com.codexedge.glow cat files/ui-snapshot.json
 * Requires the debug build and explicit Accessibility permission.
 */
class ScreenAccessService : AccessibilityService() {
    private var lastWrite = 0L
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastWrite < 200) return
        lastWrite = now
        val root = rootInActiveWindow ?: return
        try {
            val list = JSONArray()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 350) {
                val n = queue.removeFirst()
                count++
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                if (n.isVisibleToUser) {
                    val o = JSONObject()
                    o.put("text", n.text?.toString() ?: "")
                    o.put("description", n.contentDescription?.toString() ?: "")
                    o.put("resourceId", n.viewIdResourceName ?: "")
                    o.put("className", n.className?.toString() ?: "")
                    o.put("clickable", n.isClickable)
                    o.put("enabled", n.isEnabled)
                    o.put("bounds", JSONArray(listOf(r.left,r.top,r.right,r.bottom)))
                    list.put(o)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
            val data = JSONObject().put("timestamp", System.currentTimeMillis())
                .put("package", root.packageName?.toString() ?: "")
                .put("nodes", list)
            val tmp = File(filesDir, "ui-snapshot.tmp")
            tmp.writeText(data.toString())
            tmp.renameTo(File(filesDir, "ui-snapshot.json"))
        } catch (_: Exception) { }
    }
    override fun onInterrupt() {}
}
