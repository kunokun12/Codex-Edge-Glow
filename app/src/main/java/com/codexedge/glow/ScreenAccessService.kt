package com.codexedge.glow

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-only accessibility snapshot. Read with:
 * adb shell run-as com.codexedge.glow cat files/ui-snapshot.json
 * Requires the debug build and explicit Accessibility permission.
 */
class ScreenAccessService : AccessibilityService() {
    companion object {
        @Volatile var current: ScreenAccessService? = null
            private set
    }
    private var lastWrite = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRequest by lazy { File(filesDir, "ui-refresh-request") }

    private val unicodeRequest by lazy { File(filesDir, "unicode-input-request.json") }
    private val unicodeResponse by lazy { File(filesDir, "unicode-input-response.json") }
    private fun handleUnicodeRequest() {
        if (!unicodeRequest.exists()) return
        var id = ""
        try {
            val req = JSONObject(unicodeRequest.readText())
            unicodeRequest.delete()
            id = req.getString("id")
            val pkg = req.getString("package")
            val resourceId = req.getString("resourceId")
            val value = req.getString("text")
            val root = rootInActiveWindow
            var reason = "no-active-window"
            var success = false
            if (root != null) {
                if (root.packageName?.toString() != pkg) {
                    reason = "package-mismatch"
                } else {
                    val matches = mutableListOf<AccessibilityNodeInfo>()
                    val queue = ArrayDeque<AccessibilityNodeInfo>()
                    queue.add(root)
                    var visited = 0
                    while (queue.isNotEmpty() && visited++ < 500) {
                        val node = queue.removeFirst()
                        if (node.viewIdResourceName == resourceId && node.isVisibleToUser &&
                            node.isEnabled && node.isEditable && !node.isPassword && node.isFocused) {
                            matches.add(node)
                        }
                        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    }
                    if (matches.size != 1) {
                        reason = "expected-one-focused-editable-nonpassword-field"
                    } else {
                        val bundle = android.os.Bundle()
                        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                        success = matches[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                        reason = if (success) "action-set-text-accepted" else "action-set-text-rejected"
                    }
                }
            }
            unicodeResponse.writeText(JSONObject().put("id", id).put("success", success)
                .put("reason", reason).toString())
        } catch (_: Exception) {
            unicodeRequest.delete()
            unicodeResponse.writeText(JSONObject().put("id", id).put("success", false)
                .put("reason", "bridge-error").toString())
        }
    }
    private val refreshPoll = object : Runnable {
        override fun run() {
            try {
                handleUnicodeRequest()
                if (refreshRequest.exists()) {
                    refreshRequest.delete()
                    captureSnapshot()
                }
            } catch (_: Exception) { }
            handler.postDelayed(this, 350)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        handler.removeCallbacks(refreshPoll)
        handler.post(refreshPoll)
        captureSnapshot()
    }
    override fun onDestroy() {
        if (current === this) current = null
        handler.removeCallbacks(refreshPoll)
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.uptimeMillis()
        val windowChanged = event != null && (event.eventType == TYPE_WINDOW_STATE_CHANGED || event.eventType == TYPE_WINDOWS_CHANGED)
        if (!windowChanged && now - lastWrite < 200) return
        captureSnapshot()
    }
    private fun captureSnapshot() {
        val now = SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: return
        lastWrite = now
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
                .put("capturedAtUptimeMs", now)
                .put("windowId", root.windowId)
                .put("package", root.packageName?.toString() ?: "")
                .put("nodes", list)
            val tmp = File(filesDir, "ui-snapshot.tmp")
            tmp.writeText(data.toString())
            val target = File(filesDir, "ui-snapshot.json")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }
    override fun onInterrupt() {}
}
