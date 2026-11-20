package com.codexedge.glow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * Read-only accessibility correlation diagnostic. No clicks, gestures,
 * global actions or text injection. Exported with Android DUMP permission.
 *
 * Node labels are never saved. For the dedicated benchmark package only,
 * small metadata summaries expose class, label-present flags and exact
 * match booleans (not text). Other allowlisted packages yield only counts.
 */
class CompanionProbeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.codexedge.glow.NATIVE_PROBE"
        private const val MAX_NODES = 650
        private val ID = Regex("^[A-Za-z0-9_-]{12,100}$")

        private fun scan(root: AccessibilityNodeInfo, value: String, metadata: Boolean): JSONObject {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var traversed = 0
            var visible = 0
            var text = 0
            var description = 0
            var clickable = 0
            var omitted = 0
            val sample = JSONArray()
            while (queue.isNotEmpty() && traversed < MAX_NODES) {
                val node = queue.removeFirst()
                traversed++
                val show = node.isVisibleToUser
                val nodeText = node.text?.toString()
                val nodeDescription = node.contentDescription?.toString()
                val nodeId = node.viewIdResourceName
                if (show) {
                    visible++
                    if (nodeText == value) text++
                    if (nodeDescription == value) description++
                    if (node.isClickable && node.isEnabled) clickable++
                }
                if (metadata && traversed <= 12) {
                    sample.put(JSONObject()
                        .put("class", node.className?.toString() ?: "")
                        .put("visible", show)
                        .put("enabled", node.isEnabled)
                        .put("clickable", node.isClickable)
                        .put("password", node.isPassword)
                        .put("textPresent", !nodeText.isNullOrEmpty())
                        .put("descriptionPresent", !nodeDescription.isNullOrEmpty())
                        .put("resourceIdPresent", !nodeId.isNullOrEmpty())
                        .put("textExact", show && nodeText == value)
                        .put("descriptionExact", show && nodeDescription == value))
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) omitted++ else queue.add(child)
                }
            }
            val findByTextCount = try {
                root.findAccessibilityNodeInfosByText(value)?.count { it.isVisibleToUser } ?: 0
            } catch (_: Exception) { -1 }
            return JSONObject()
                .put("windowId", root.windowId)
                .put("rootClass", root.className?.toString() ?: "")
                .put("rootChildCount", root.childCount)
                .put("rootVisible", root.isVisibleToUser)
                .put("visitedNodes", traversed)
                .put("visibleNodes", visible)
                .put("clickableNodes", clickable)
                .put("exactTextMatches", text)
                .put("exactDescriptionMatches", description)
                .put("findByTextMatches", findByTextCount)
                .put("missingChildRefs", omitted)
                .put("truncated", queue.isNotEmpty())
                .put("nodeMetadata", sample)
        }

        private fun snapshotSummary(context: Context, pkg: String, value: String): JSONObject {
            val file = File(context.filesDir, "ui-snapshot.json")
            if (!file.exists()) return JSONObject().put("status", "missing")
            return try {
                val data = JSONObject(file.readText())
                val nodes = data.optJSONArray("nodes") ?: JSONArray()
                var text = 0
                var description = 0
                var clickable = 0
                val now = System.currentTimeMillis()
                for (index in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(index) ?: continue
                    if (node.optString("text") == value) text++
                    if (node.optString("description") == value) description++
                    if (node.optBoolean("clickable")) clickable++
                }
                JSONObject()
                    .put("status", "available")
                    .put("windowId", data.optInt("windowId", -1))
                    .put("packageMatchesRequested", data.optString("package") == pkg)
                    .put("nodeCount", nodes.length())
                    .put("clickableNodes", clickable)
                    .put("exactTextMatches", text)
                    .put("exactDescriptionMatches", description)
                    .put("capturedAtEpochMs", data.optLong("timestamp", 0L))
                    .put("snapshotAgeMs", now - data.optLong("timestamp", now))
            } catch (e: Exception) {
                JSONObject().put("status", "unreadable")
                    .put("reason", e.javaClass.simpleName)
            }
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
        val output = JSONObject().put("schema", 2).put("operation", "NATIVE_PROBE")
            .put("requestId", id).put("targetPackage", pkg)
            .put("actionPerformed", false).put("coordinateTapUsed", false)
            .put("observedAtEpochMs", System.currentTimeMillis())
            .put("observedAtUptimeMs", SystemClock.uptimeMillis())
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
                        val isBenchmark = pkg == "dev.androidroute.benchmark"
                        output.put("storedSnapshotBefore", snapshotSummary(context, pkg, value))
                        service.captureSnapshotForProbe()
                        output.put("freshServiceSnapshot", snapshotSummary(context, pkg, value))
                        val active = service.rootInActiveWindow
                        val activeSummary = JSONObject()
                        if (active != null) {
                            activeSummary.put("packageMatchesRequested", active.packageName?.toString() == pkg)
                            activeSummary.put("tree", scan(active, value, isBenchmark))
                            // Refresh before reading a second time, not after computing the first counters.
                            activeSummary.put("refreshSuccessful", active.refresh())
                            activeSummary.put("treeAfterRefresh", scan(active, value, isBenchmark))
                        } else activeSummary.put("error", "no-active-root")
                        output.put("activeRoot", activeSummary)
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
                                item.put("tree", scan(root, value, isBenchmark))
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
