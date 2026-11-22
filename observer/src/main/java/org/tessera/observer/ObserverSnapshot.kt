package org.tessera.observer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

internal object ObserverSnapshotStore {
    private val current = AtomicReference<ObserverSnapshot?>(null)
    fun get(): ObserverSnapshot? = current.get()
    fun put(value: ObserverSnapshot) { current.set(value) }
}

/** Read-only hierarchy observation. No gesture, text-entry, or navigation operation is invoked. */
class ReadOnlyAccessibilityObserverService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        val windowId = root.windowId
        val startedElapsed = android.os.SystemClock.elapsedRealtime()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
        val nodes = mutableListOf<ObserverNode>()
        queue.add(Triple(root, -1, 0))
        var complete = true
        while (queue.isNotEmpty()) {
            if (nodes.size >= MAX_NODES || android.os.SystemClock.elapsedRealtime() - startedElapsed > MAX_CAPTURE_MS) { complete = false; break }
            val (node, parent, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH || node.windowId != windowId) { complete = false; break }
            val index = nodes.size
            nodes += ObserverNode(parent, depth, node.viewIdResourceName?.take(MAX_RESOURCE_CHARS),
                node.className?.toString()?.take(MAX_CLASS_CHARS), node.isVisibleToUser, node.isEnabled,
                node.isClickable, node.isFocusable, node.isSelected, node.isChecked, node.isPassword)
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex)
                if (child == null) { complete = false; continue }
                queue.add(Triple(child, index, depth + 1))
            }
        }
        if (rootInActiveWindow?.windowId != windowId) complete = false
        val timestamp = android.os.SystemClock.elapsedRealtime()
        val canonical = buildString {
            fun field(value: String?) {
                if (value == null) append("-1:") else {
                    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                    val bytes = normalized.toByteArray(Charsets.UTF_8)
                    append(bytes.size).append(':').append(normalized)
                }
            }
            field(packageName); append(windowId).append(':').append(timestamp).append(':')
            nodes.forEach { n ->
                append(n.parentIndex).append(':').append(n.depth).append(':')
                field(n.resourceId); field(n.className)
                append(if (n.visible) '1' else '0').append(if (n.enabled) '1' else '0')
                    .append(if (n.clickable) '1' else '0').append(if (n.focusable) '1' else '0')
                    .append(if (n.selected) '1' else '0').append(if (n.checked) '1' else '0')
                    .append(if (n.password) '1' else '0')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        ObserverSnapshotStore.put(ObserverSnapshot(packageName, windowId, timestamp, complete, nodes, digest))
    }
    override fun onInterrupt() { ObserverSnapshotStore.put(nullSnapshot()) }
    private fun nullSnapshot(): ObserverSnapshot = ObserverSnapshot("", -1, 0L, false, emptyList(), "0".repeat(64))
    override fun onServiceConnected() { super.onServiceConnected() }
    companion object { private const val MAX_NODES = 4096; private const val MAX_DEPTH = 64; private const val MAX_CAPTURE_MS = 250L; private const val MAX_RESOURCE_CHARS = 256; private const val MAX_CLASS_CHARS = 200 }
}
