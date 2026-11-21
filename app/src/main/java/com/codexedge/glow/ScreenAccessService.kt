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
    /**
     * Bounded snapshot with explicit one-window traversal completeness metadata.
     * Existing legacy snapshot remains local to the debug app. This metadata
     * does not authenticate the producer or establish task/session ownership.
     */
    private fun captureSnapshot() {
        val started = SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: return
        lastWrite = started
        val originalWindowId = root.windowId
        val originalPackage = root.packageName?.toString() ?: ""
        val maxNodes = 350
        val maxDepth = 32
        val maxDurationMs = 500L
        val nodes = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))
        var visited = 0
        var observedDepth = 0
        var truncationReason = ""
        var collectionError = ""
        try {
            while (queue.isNotEmpty()) {
                if (visited >= maxNodes) {
                    truncationReason = "node-limit"
                    break
                }
                if (SystemClock.uptimeMillis() - started >= maxDurationMs) {
                    truncationReason = "time-limit"
                    break
                }
                val (node, depth) = queue.removeFirst()
                visited++
                observedDepth = maxOf(observedDepth, depth)
                if (node.isVisibleToUser) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    nodes.put(JSONObject()
                        .put("text", node.text?.toString() ?: "")
                        .put("description", node.contentDescription?.toString() ?: "")
                        .put("resourceId", node.viewIdResourceName ?: "")
                        .put("className", node.className?.toString() ?: "")
                        .put("clickable", node.isClickable)
                        .put("enabled", node.isEnabled)
                        .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
                }
                if (node.childCount > 0 && depth >= maxDepth) {
                    truncationReason = "depth-limit"
                    break
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child == null) {
                        truncationReason = "inaccessible-child"
                        break
                    }
                    queue.add(Pair(child, depth + 1))
                }
                if (truncationReason.isNotEmpty()) break
            }
        } catch (_: Exception) {
            collectionError = "traversal-exception"
        }
        try {
            val after = rootInActiveWindow
            val stableWindow = after != null && after.windowId == originalWindowId &&
                after.packageName?.toString() == originalPackage
            val durationMs = SystemClock.uptimeMillis() - started
            val complete = collectionError.isEmpty() && truncationReason.isEmpty() &&
                queue.isEmpty() && stableWindow && durationMs < maxDurationMs
            val data = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("capturedAtUptimeMs", started)
                .put("windowId", originalWindowId)
                .put("windowIdAfter", after?.windowId ?: -1)
                .put("windowStable", stableWindow)
                .put("package", originalPackage)
                .put("nodes", nodes)
                .put("snapshotId", java.util.UUID.randomUUID().toString())
                .put("traversalVisitedNodeCount", visited)
                .put("returnedNodeCount", nodes.length())
                .put("traversalMaxObservedDepth", observedDepth)
                .put("traversalNodeLimit", maxNodes)
                .put("traversalDepthLimit", maxDepth)
                .put("traversalTimeBudgetMs", maxDurationMs)
                .put("traversalDurationMs", durationMs)
                .put("truncationReason", truncationReason)
                .put("collectionError", collectionError)
                .put("collectionSucceeded", collectionError.isEmpty())
                .put("treeComplete", complete)
                .put("completenessScope", "one-accessible-active-window")
            val tmp = File(filesDir, "ui-snapshot.tmp")
            tmp.writeText(data.toString())
            val target = File(filesDir, "ui-snapshot.json")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }
    /**
     * Diagnostic-only refresh used by the DUMP-protected, read-only probe.
     * Writes the same snapshot format consumed by Android MCP, making it
     * possible to compare a service snapshot and a live tree within one call.
     * Never dispatches an accessibility action.
     */
    internal fun captureSnapshotForProbe() {
        captureSnapshot()
    }
    override fun onInterrupt() {}
}
