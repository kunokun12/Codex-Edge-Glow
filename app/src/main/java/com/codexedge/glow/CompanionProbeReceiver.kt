package com.codexedge.glow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * Read-only accessibility tree diagnostics.
 * Does not perform any accessibility action, input gesture, or click.
 * Only ADB-shell callers holding DUMP may invoke this receiver.
 */
class CompanionProbeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.codexedge.glow.NATIVE_PROBE"
        private const val MAX_NODES = 650
        private val ID = Regex("^[A-Za-z0-9_-]{12,100}$")

        private fun scan(root: AccessibilityNodeInfo, value: String): JSONObject {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var traversed = 0
            var visible = 0
            var text = 0
            var description = 0
            var clickable = 0
            var omittedChildren = 0
            while (queue.isNotEmpty() && traversed < MAX_NODES) {
                val node = queue.removeFirst()
                traversed++
                if (node.isVisibleToUser) {
                    visible++
                    if (node.text?.toString() == value) text++
                    if (node.contentDescription?.toString() == value) description++
                    if (node.isClickable && node.isEnabled) clickable++
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) omittedChildren++ else queue.add(child)
                }
            }
            return JSONObject()
                .put("windowId", root.windowId)
                .put("rootPackagePresent", root.packageName?.toString() != null)
                .put("rootClass", root.className?.toString() ?: "")
                .put("rootChildCount", root.childCount)
                .put("rootVisible", root.isVisibleToUser)
                .put("rootRefreshSuccessful", root.refresh())
                .put("visitedNodes", traversed)
                .put("visibleNodes", visible)
                .put("clickableNodes", clickable)
                .put("exactTextMatches", text)
                .put("exactDescriptionMatches", description)
                .put("missingChildRefs", omittedChildren)
                .put("truncated", queue.isNotEmpty())
        }

        private fun write(context: Context, data: JSONObject) {
            val file = AtomicFile(File(context.filesDir, "native-probe-result.json"))
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(data.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (_: Exception) {
                if (stream != null) file.failWrite(stream)
            }
        }
    }

    override fun onReceive(context: Context, request: Intent) {
        if (request.action != ACTION) return
        val id = request.getStringExtra("request_id") ?: ""
        val pkg = request.getStringExtra("target_package") ?: ""
        val value = request.getStringExtra("selector_value") ?: ""
        val output = JSONObject().put("schema", 1).put("operation", "NATIVE_PROBE")
            .put("requestId", id).put("targetPackage", pkg)
            .put("actionPerformed", false).put("coordinateTapUsed", false)
        try {
            if (!ID.matches(id) || pkg.isBlank() || value.isBlank() || value.length > 180) {
                output.put("status", "REFUSED").put("reason", "invalid-request")
            } else if (!SessionLease.isActive(context) ||
                System.currentTimeMillis() < SessionLease.lastHeartbeat(context) ||
                System.currentTimeMillis() - SessionLease.lastHeartbeat(context) >= SessionLease.TIMEOUT_MS) {
                output.put("status", "REFUSED").put("reason", "session-not-active")
            } else {
                val allowlist = context.getSharedPreferences("automation_settings", Context.MODE_PRIVATE)
                    .getString("allowed_packages", "dev.androidroute.benchmark")
                    ?.split(',')?.map { it.trim() } ?: emptyList()
                if (!allowlist.contains(pkg) || pkg == context.packageName || pkg.startsWith("com.android.")) {
                    output.put("status", "REFUSED").put("reason", "package-not-allowed")
                } else {
                    val service = ScreenAccessService.current
                    if (service == null) {
                        output.put("status", "REFUSED").put("reason", "service-not-connected")
                    } else {
                        val active = service.rootInActiveWindow
                        val summary = JSONObject()
                        if (active != null) {
                            summary.put("packageMatchesRequested", active.packageName?.toString() == pkg)
                            summary.put("tree", scan(active, value))
                        } else summary.put("error", "no-active-root")
                        output.put("activeRoot", summary)
                        val windows = JSONArray()
                        for (window in service.windows.take(15)) {
                            val root = window.root ?: continue
                            val item = JSONObject()
                                .put("windowId", window.id)
                                .put("windowType", window.type)
                                .put("active", window.isActive)
                                .put("focused", window.isFocused)
                                .put("targetPackage", root.packageName?.toString() == pkg)
                            if (root.packageName?.toString() == pkg)
                                item.put("tree", scan(root, value))
                            windows.put(item)
                        }
                        output.put("interactiveWindows", windows)
                            .put("status", "OBSERVED")
                    }
                }
            }
        } catch (e: Exception) {
            output.put("status", "ERROR").put("reason", e.javaClass.simpleName)
        }
        write(context, output)
    }
}
