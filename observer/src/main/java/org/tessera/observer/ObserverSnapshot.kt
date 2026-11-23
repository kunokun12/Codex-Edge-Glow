package org.tessera.observer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import java.security.MessageDigest
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

internal data class ObserverNode(
    val parentIndex: Int, val depth: Int, val resourceId: String?, val className: String?,
    val visible: Boolean, val enabled: Boolean, val clickable: Boolean, val focusable: Boolean,
    val selected: Boolean, val checked: Boolean, val password: Boolean
)
internal data class ObserverSnapshot(
    val packageName: String, val windowId: Int, val capturedElapsedRealtimeMs: Long,
    val complete: Boolean, val nodes: List<ObserverNode>, val digest: String
)
internal sealed interface ObserverSnapshotResult {
    data class Captured(val snapshot: ObserverSnapshot) : ObserverSnapshotResult
    data class Refused(val reasonCode: String) : ObserverSnapshotResult
}

internal object ObserverSnapshotStore {
    private val current = AtomicReference<ObserverSnapshot?>(null)
    fun get(): ObserverSnapshot? = current.get()
    fun put(value: ObserverSnapshot?) { current.set(value) }
    fun clear() = current.set(null)
}

/** Read-only hierarchy observation. It never invokes a gesture, text-entry, or navigation API. */
class ReadOnlyAccessibilityObserverService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        // Do not eagerly read the foreground app. A caller must request the exact package/window.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            ObserverSnapshotStore.clear()
            return
        }
        when (val result = captureActiveWindow()) {
            is ObserverSnapshotResult.Captured -> ObserverSnapshotStore.put(result.snapshot)
            is ObserverSnapshotResult.Refused -> ObserverSnapshotStore.clear()
        }
    }

    override fun onInterrupt() { ObserverSnapshotStore.clear() }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        ObserverSnapshotStore.clear()
        super.onDestroy()
    }

    /** Captures only after checking requested package/window, avoiding unrelated UI traversal. */
    private fun captureActiveWindow(expectedPackage: String? = null, expectedWindowId: Int? = null,
                                    newerThanElapsedMs: Long? = null): ObserverSnapshotResult {
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
                if (nodes.size >= MAX_NODES || SystemClock.elapsedRealtime() - startedElapsed > MAX_CAPTURE_MS) {
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
                    nodes += ObserverNode(parentIndex, depth, node.viewIdResourceName?.take(MAX_RESOURCE_CHARS),
                        node.className?.toString()?.take(MAX_CLASS_CHARS), node.isVisibleToUser, node.isEnabled,
                        node.isClickable, node.isFocusable, node.isSelected, node.isChecked, node.isPassword)
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

        val canonical = buildString {
            fun field(value: String?) {
                if (value == null) append("-1:") else {
                    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                    val bytes = normalized.toByteArray(Charsets.UTF_8)
                    append(bytes.size).append(':').append(normalized)
                }
            }
            field(packageName); append(windowId).append(':').append(capturedElapsed).append(':')
            nodes.forEach { node ->
                append(node.parentIndex).append(':').append(node.depth).append(':')
                field(node.resourceId); field(node.className)
                append(if (node.visible) '1' else '0').append(if (node.enabled) '1' else '0')
                    .append(if (node.clickable) '1' else '0').append(if (node.focusable) '1' else '0')
                    .append(if (node.selected) '1' else '0').append(if (node.checked) '1' else '0')
                    .append(if (node.password) '1' else '0')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ObserverSnapshotResult.Captured(ObserverSnapshot(packageName, windowId, capturedElapsed,
            complete = true, nodes = nodes, digest = digest))
    }

    companion object {
        private const val MAX_NODES = 4096
        private const val MAX_DEPTH = 64
        private const val MAX_CAPTURE_MS = 250L
        private const val MAX_RESOURCE_CHARS = 256
        private const val MAX_CLASS_CHARS = 200
        @Volatile private var activeService: ReadOnlyAccessibilityObserverService? = null

        /** Called by ObserverIpcService on the main looper, sharing the AccessibilityService lifecycle. */
        internal fun captureForRequest(expectedPackage: String, expectedWindowId: Int,
                                      newerThanElapsedMs: Long): ObserverSnapshotResult {
            val service = activeService ?: return ObserverSnapshotResult.Refused("accessibility-service-unavailable")
            return service.captureActiveWindow(expectedPackage, expectedWindowId, newerThanElapsedMs).also {
                if (it is ObserverSnapshotResult.Captured) ObserverSnapshotStore.put(it.snapshot)
                else ObserverSnapshotStore.clear()
            }
        }
    }
}
