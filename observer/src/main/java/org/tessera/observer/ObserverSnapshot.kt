package org.tessera.observer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.graphics.Rect
import java.util.ArrayDeque
import java.util.UUID

internal data class ObserverNode(
    val parentIndex: Int, val depth: Int, val resourceId: String?, val className: String?,
    val visible: Boolean, val enabled: Boolean, val clickable: Boolean, val focusable: Boolean,
    val selected: Boolean, val checked: Boolean, val password: Boolean, val bounds: List<Int>,
    val windowId: Int
)
internal data class ObserverSnapshot(
    val snapshotId: String, val packageName: String, val windowId: Int, val capturedAtEpochMs: Long,
    val capturedElapsedRealtimeMs: Long, val complete: Boolean, val nodes: List<ObserverNode>,
    val durationMs: Long, val nodeLimit: Int, val digest: String, val json: String
)
internal sealed interface ObserverSnapshotResult {
    data class Captured(val snapshot: ObserverSnapshot) : ObserverSnapshotResult
    data class Refused(val reasonCode: String) : ObserverSnapshotResult
}

/** Read-only hierarchy observation. It never invokes a gesture, text-entry, or navigation API. */
class ReadOnlyAccessibilityObserverService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        // Do not eagerly read or retain the foreground app. A caller must request the exact package/window.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The Observer does not cache Accessibility events or UI snapshots.
    }

    override fun onInterrupt() {
        if (activeService === this) activeService = null
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    /** Captures only after checking requested package/window, avoiding unrelated UI traversal. */
    private fun captureActiveWindow(expectedPackage: String? = null, expectedWindowId: Int? = null,
                                    newerThanElapsedMs: Long? = null, nodeLimit: Int = MAX_NODES): ObserverSnapshotResult {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: return ObserverSnapshotResult.Refused("active-window-unavailable")
        val packageName = root.packageName?.toString().orEmpty()
        val windowId = root.windowId
        if (packageName.isBlank() || windowId < 0) {
            runCatching { root.recycle() }
            return ObserverSnapshotResult.Refused("active-window-unavailable")
        }
        if (expectedPackage != null && packageName != expectedPackage) {
            runCatching { root.recycle() }
            return ObserverSnapshotResult.Refused("snapshot-package-mismatch")
        }
        if (expectedWindowId != null && windowId != expectedWindowId) {
            runCatching { root.recycle() }
            return ObserverSnapshotResult.Refused("snapshot-window-mismatch")
        }

        val startedElapsed = SystemClock.elapsedRealtime()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
        val nodes = mutableListOf<ObserverNode>()
        queue.add(Triple(root, -1, 0))
        var complete = true
        try {
            while (queue.isNotEmpty()) {
                if (nodes.size >= nodeLimit || SystemClock.elapsedRealtime() - startedElapsed > MAX_CAPTURE_MS) {
                    complete = false
                    break
                }
                val (node, parentIndex, depth) = queue.removeFirst()
                try {
                    if (depth > MAX_DEPTH || node.windowId != windowId || node.packageName?.toString() != packageName) {
                        complete = false
                        break
                    }
                    val index = nodes.size
                    val rawResourceId = node.viewIdResourceName
                    val safeResourceId = rawResourceId?.takeIf { RESOURCE_ID.matches(it) && it.startsWith("$packageName:id/") }
                        ?.take(MAX_RESOURCE_CHARS)
                    val className = node.className?.toString()?.take(MAX_CLASS_CHARS)
                        ?.takeIf { it.startsWith("android.") || it.startsWith("$packageName.") }
                    val bounds = Rect().also(node::getBoundsInScreen)
                    nodes += ObserverNode(parentIndex, depth, safeResourceId,
                        className, node.isVisibleToUser, node.isEnabled,
                        node.isClickable, node.isFocusable, node.isSelected, node.isChecked, node.isPassword,
                        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom), node.windowId)
                    if (depth == MAX_DEPTH && node.childCount > 0) {
                        complete = false
                        break
                    }
                    if (node.childCount > nodeLimit - nodes.size - queue.size) {
                        complete = false
                        break
                    }
                    for (childIndex in 0 until node.childCount) {
                        val child = node.getChild(childIndex)
                        if (child == null) {
                            complete = false
                            continue
                        }
                        queue.add(Triple(child, index, depth + 1))
                    }
                } catch (_: Exception) {
                    complete = false
                } finally {
                    runCatching { node.recycle() }
                }
                if (!complete) break
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }

        val after = try { rootInActiveWindow } catch (_: Exception) { null }
        val stable = after?.let { it.windowId == windowId && it.packageName?.toString() == packageName } == true
        runCatching { after?.recycle() }
        val capturedElapsed = SystemClock.elapsedRealtime()
        if (!complete || nodes.isEmpty() || !stable || capturedElapsed - startedElapsed > MAX_CAPTURE_MS)
            return ObserverSnapshotResult.Refused("snapshot-incomplete")
        if (newerThanElapsedMs != null && capturedElapsed <= newerThanElapsedMs)
            return ObserverSnapshotResult.Refused("snapshot-pre-dispatch")

        val durationMs = capturedElapsed - startedElapsed
        val digest = ObserverSnapshotV3.structuralDigest(packageName, windowId, nodes)
        val snapshot = ObserverSnapshot(UUID.randomUUID().toString(), packageName, windowId,
            System.currentTimeMillis(), capturedElapsed, complete = true, nodes = nodes, durationMs = durationMs,
            nodeLimit = nodeLimit, digest = digest, json = "")
        val json = runCatching { ObserverSnapshotV3.encode(snapshot) }.getOrElse {
            return ObserverSnapshotResult.Refused("snapshot-incomplete")
        }
        if (json.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES)
            return ObserverSnapshotResult.Refused("snapshot-payload-too-large")
        return ObserverSnapshotResult.Captured(snapshot.copy(json = json))
    }

    companion object {
        private const val MAX_NODES = 4096
        private const val MAX_DEPTH = 64
        private const val MAX_CAPTURE_MS = 250L
        private const val MAX_RESOURCE_CHARS = 256
        private const val MAX_CLASS_CHARS = 200
        private const val MAX_SNAPSHOT_BYTES = 256 * 1024
        private val RESOURCE_ID = Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+")
        @Volatile private var activeService: ReadOnlyAccessibilityObserverService? = null

        /** Called by ObserverIpcService on the main looper, sharing the AccessibilityService lifecycle. */
        internal fun captureForRequest(expectedPackage: String, expectedWindowId: Int,
                                      newerThanElapsedMs: Long, maxNodes: Int): ObserverSnapshotResult {
            val service = activeService ?: return ObserverSnapshotResult.Refused("accessibility-service-unavailable")
            if (maxNodes !in 1..MAX_NODES) return ObserverSnapshotResult.Refused("ipc-boundary-invalid")
            return service.captureActiveWindow(expectedPackage, expectedWindowId, newerThanElapsedMs, maxNodes)
        }
    }
}
