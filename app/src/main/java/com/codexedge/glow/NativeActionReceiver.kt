package com.codexedge.glow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * Exact-selector, one-shot AccessibilityNodeInfo.ACTION_CLICK.
 *
 * Receiver is exported only to callers holding android.permission.DUMP
 * (Android shell / privileged debugging). Never accepts shell commands,
 * coordinates, wildcard selectors, or gestures.
 *
 * At-most-once journal is committed BEFORE performing the action.
 * Any uncertain result must not be retried by MCP.
 */
class NativeActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.codexedge.glow.NATIVE_CLICK"
        const val MAX_NODES = 600
        private val PACKAGE_FORMAT = Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private val ID_FORMAT = Regex("^[A-Za-z0-9_-]{12,100}$")

        private fun save(context: Context, result: JSONObject) {
            val atomic = AtomicFile(File(context.filesDir, "native-action-result.json"))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(result.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (e: Exception) {
                if (output != null) atomic.failWrite(output)
            }
        }

        private fun allowed(context: Context, pkg: String): Boolean {
            if (pkg == context.packageName || pkg.startsWith("com.android.") ||
                pkg.startsWith("android.") || pkg.startsWith("com.google.android.permissioncontroller"))
                return false
            val prefs = context.getSharedPreferences("automation_settings", Context.MODE_PRIVATE)
            val allowlist = prefs.getString("allowed_packages", "dev.androidroute.benchmark")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            return allowlist.contains(pkg)
        }

        private fun matches(node: AccessibilityNodeInfo, selector: String, value: String): Boolean {
            if (!node.isVisibleToUser || !node.isEnabled || node.isPassword) return false
            return when (selector) {
                "text" -> node.text?.toString() == value
                "description" -> node.contentDescription?.toString() == value
                "resourceId" -> node.viewIdResourceName == value
                "label" -> node.text?.toString() == value || node.contentDescription?.toString() == value
                else -> false
            }
        }

        private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps++ < 12) {
                if (current.isVisibleToUser && current.isEnabled && current.isClickable)
                    return current
                current = current.parent
            }
            return null
        }

        private fun execute(context: Context, request: Intent): JSONObject {
            val id = request.getStringExtra("request_id") ?: ""
            val pkg = request.getStringExtra("target_package") ?: ""
            val selector = request.getStringExtra("selector_type") ?: ""
            val value = request.getStringExtra("selector_value") ?: ""
            val result = JSONObject()
                .put("schema", 2).put("requestId", id).put("operation", "NATIVE_CLICK")
                .put("targetPackage", pkg).put("selectorType", selector)
                .put("coordinateTapUsed", false).put("destinationVerified", false)
            fun refuse(reason: String): JSONObject = result.put("status", "REFUSED").put("reason", reason)

            if (!ID_FORMAT.matches(id)) return refuse("invalid-request-id")
            if (!PACKAGE_FORMAT.matches(pkg) || !allowed(context, pkg)) return refuse("package-not-allowed")
            if (selector !in setOf("text", "description", "resourceId", "label") ||
                value.isBlank() || value.length > 180 ||
                value.any { it == '\n' || it == '\r' || it.code < 32 })
                return refuse("invalid-exact-selector")
            val now = System.currentTimeMillis()
            val last = SessionLease.lastHeartbeat(context)
            if (!SessionLease.isActive(context) || last <= 0L || now < last ||
                now - last >= SessionLease.TIMEOUT_MS)
                return refuse("automation-session-not-active")

            val service = ScreenAccessService.current ?: return refuse("accessibility-service-not-connected")
            val root = service.rootInActiveWindow ?: return refuse("no-active-window")
            if (root.packageName?.toString() != pkg) return refuse("foreground-package-mismatch")
            if (root.windowId < 0) return refuse("invalid-accessibility-window")

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            var count = 0
            var textMatches = 0
            var descriptionMatches = 0
            var resourceIdMatches = 0
            while (queue.isNotEmpty() && count < MAX_NODES) {
                val node = queue.removeFirst()
                count++
                if (node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                    if (node.text?.toString() == value) textMatches++
                    if (node.contentDescription?.toString() == value) descriptionMatches++
                    if (node.viewIdResourceName == value) resourceIdMatches++
                }
                if (matches(node, selector, value)) candidates.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            result.put("visitedNodeCount", count)
                .put("exactTextMatchCount", textMatches)
                .put("exactDescriptionMatchCount", descriptionMatches)
                .put("exactResourceIdMatchCount", resourceIdMatches)
                .put("exactSelectorMatchCount", candidates.size)
                .put("rootWindowId", root.windowId)
            if (queue.isNotEmpty()) return refuse("hierarchy-too-large")
            // Labels are allowed on a child or its clickable ancestor, so a
            // single control can expose the same label on multiple nodes.
            // Collapse those to distinct clickable targets before deciding.
            val controls = mutableListOf<AccessibilityNodeInfo>()
            for (candidate in candidates) {
                val actionable = clickable(candidate) ?: continue
                if (actionable.windowId != root.windowId) continue
                if (controls.none { it == actionable }) controls.add(actionable)
            }
            result.put("uniqueClickableTargetCount", controls.size)
            if (controls.size != 1) return refuse("expected-one-exact-visible-target-found-${controls.size}")
            val button = controls[0]
            val bounds = Rect()
            button.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return refuse("target-has-empty-bounds")
            // Refresh the live node rather than trusting the earlier tree.
            if (!button.refresh() || !button.isVisibleToUser || !button.isEnabled ||
                !button.isClickable || button.windowId != root.windowId)
                return refuse("target-stale-or-disabled")
            if (!SessionLease.isActive(context)) return refuse("automation-session-stopped")

            // Commit an at-most-once outcome before dispatch. The broadcast receiver
            // serializes main-thread requests; the journal survives process death.
            val journal = context.getSharedPreferences("native_action_journal", Context.MODE_PRIVATE)
            val key = "seen_" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if (journal.contains(key)) return refuse("duplicate-request-id")
            if (!journal.edit().putBoolean(key, true).commit())
                return refuse("journal-write-failed")
            result.put("status", "DISPATCH_UNCERTAIN")
                .put("reason", "journal-committed-before-action")
            save(context, result)
            val accepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return if (accepted) {
                result.put("status", "DISPATCH_ACCEPTED_UNVERIFIED").put("reason", "action-click-accepted")
            } else {
                result.put("status", "DISPATCH_REJECTED").put("reason", "action-click-rejected")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val result = try { execute(context, intent) }
            catch (_: Exception) {
                JSONObject().put("schema", 2)
                    .put("requestId", intent.getStringExtra("request_id") ?: "")
                    .put("status", "DISPATCH_UNCERTAIN")
                    .put("reason", "native-exception")
                    .put("destinationVerified", false)
                    .put("coordinateTapUsed", false)
            }
        save(context, result)
    }
}
