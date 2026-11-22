package com.codexedge.glow.fastpathv1

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/**
 * M7 candidate read-only observer. Android hosts this component in
 * `:fastpath-observer`, separate from the receiver/executor process. The code
 * only reads AccessibilityNodeInfo and exposes one ephemeral in-memory snapshot
 * through a non-exported provider. It is not enabled by this implementation,
 * not independently approved, and not connected to route.verify.
 */
internal class CandidateObserverAccessibilityServiceV1 : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        captureCandidateSnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> captureCandidateSnapshot()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        CandidateObserverSnapshotStoreV1.clear()
        super.onDestroy()
    }

    private fun captureCandidateSnapshot() {
        CandidateObserverSnapshotStoreV1.clear()
        val startedElapsed = SystemClock.elapsedRealtime()
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: run {
            CandidateObserverSnapshotStoreV1.clear()
            return
        }
        val rootWindowId = root.windowId
        val packageName = root.packageName?.toString().orEmpty()
        if (rootWindowId < 0 || packageName.isBlank()) {
            root.recycle()
            CandidateObserverSnapshotStoreV1.clear()
            return
        }

        val nodes = JSONArray()
        val queue = ArrayDeque<QueuedNode>()
        queue.add(QueuedNode(root, -1, 0))
        var visited = 0
        var truncationReason: String? = null
        var traversalError: String? = null
        try {
            while (queue.isNotEmpty()) {
                if (visited >= MAX_NODES) {
                    truncationReason = "node-limit"
                    break
                }
                if (SystemClock.elapsedRealtime() - startedElapsed >= MAX_TRAVERSAL_MS) {
                    truncationReason = "time-limit"
                    break
                }
                val queued = queue.removeFirst()
                val node = queued.node
                val index = visited++
                if (queued.depth > MAX_DEPTH || node.windowId != rootWindowId ||
                    node.packageName?.toString() != packageName) {
                    traversalError = "node-identity-or-depth-mismatch"
                    node.recycle()
                    break
                }
                if (queued.depth > 0 && queued.parentIndex !in 0 until index) {
                    traversalError = "parent-index-invalid"
                    node.recycle()
                    break
                }
                try {
                    val encodedNode = JSONObject()
                        .put("windowId", node.windowId)
                        .put("parentIndex", queued.parentIndex)
                        .put("depth", queued.depth)
                        .put("enabled", node.isEnabled)
                        .put("clickable", node.isClickable)
                        .put("visible", node.isVisibleToUser)
                        .put("focusable", node.isFocusable)
                        .put("selected", node.isSelected)
                        .put("checked", node.isChecked)
                        .put("password", node.isPassword)
                    val text = if (node.isPassword) null else node.text?.toString()
                    val description = if (node.isPassword) null else node.contentDescription?.toString()
                    val resourceId = node.viewIdResourceName
                    val className = node.className?.toString()
                    if (!addBoundedString(encodedNode, "text", text, MAX_TEXT_CHARS) ||
                        !addBoundedString(encodedNode, "description", description, MAX_TEXT_CHARS) ||
                        !addBoundedString(encodedNode, "resourceId", resourceId, MAX_TEXT_CHARS) ||
                        !addBoundedString(encodedNode, "className", className, MAX_CLASS_CHARS)) {
                        traversalError = "node-field-limit"
                    }
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    encodedNode.put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    nodes.put(encodedNode)
                } catch (_: Exception) {
                    traversalError = "node-serialization-error"
                }

                if (traversalError != null) {
                    node.recycle()
                    break
                }
                if (queued.depth == MAX_DEPTH && node.childCount > 0) {
                    truncationReason = "depth-limit"
                    node.recycle()
                    break
                }
                try {
                    for (childIndex in 0 until node.childCount) {
                        val child = node.getChild(childIndex)
                        if (child == null) {
                            traversalError = "inaccessible-child"
                            break
                        }
                        queue.add(QueuedNode(child, index, queued.depth + 1))
                    }
                } catch (_: Exception) {
                    traversalError = "child-traversal-error"
                } finally {
                    node.recycle()
                }
                if (traversalError != null) break
            }
        } catch (_: Exception) {
            traversalError = "traversal-exception"
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().node.recycle() }
        }

        var afterWindowId = -1
        var afterPackage = ""
        try {
            val after = rootInActiveWindow
            if (after != null) {
                afterWindowId = after.windowId
                afterPackage = after.packageName?.toString().orEmpty()
                after.recycle()
            }
        } catch (_: Exception) { }
        val durationMs = SystemClock.elapsedRealtime() - startedElapsed
        val windowStable = afterWindowId == rootWindowId && afterPackage == packageName
        val complete = traversalError == null && truncationReason == null && queue.isEmpty() &&
            windowStable && durationMs <= MAX_TRAVERSAL_MS && nodes.length() == visited &&
            visited in 1..MAX_NODES
        if (!complete) return
        val snapshot = JSONObject()
            .put("schema", 3)
            .put("snapshotId", UUID.randomUUID().toString())
            .put("packageName", packageName)
            .put("source", "accessibility")
            .put("windowId", rootWindowId)
            .put("windowIdBefore", rootWindowId)
            .put("windowIdAfter", afterWindowId)
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("capturedAtElapsedRealtimeMs", startedElapsed)
            .put("ageMs", 0)
            .put("treeComplete", complete)
            .put("truncated", truncationReason != null)
            .put("nodes", nodes)
            .put("traversal", JSONObject()
                .put("rootCount", 1)
                .put("visitedNodeCount", visited)
                .put("maxDepth", MAX_DEPTH)
                .put("nodeTraversalLimit", MAX_NODES)
                .put("timeBudgetMs", MAX_TRAVERSAL_MS)
                .put("durationMs", durationMs.coerceAtMost(MAX_TRAVERSAL_MS))
                .put("timeBudgetExceeded", durationMs > MAX_TRAVERSAL_MS || truncationReason == "time-limit")
                .put("truncationReason", truncationReason ?: JSONObject.NULL)
                .put("traversalError", traversalError ?: JSONObject.NULL)
                .put("collectionSuccess", traversalError == null)
                .put("windowStable", windowStable)
                .put("windowIdBefore", rootWindowId)
                .put("windowIdAfter", afterWindowId))
        // This is only a candidate content digest. M1 does not define the meaning
        // or canonical input of snapshot.sha256; the proposal does.
        val serialized = try {
            snapshot.put("sha256", CandidateObserverSnapshotV1.candidateDigest(snapshot))
            snapshot.toString()
        } catch (_: Exception) {
            CandidateObserverSnapshotStoreV1.clear()
            return
        }
        val processingMs = SystemClock.elapsedRealtime() - startedElapsed
        if (serialized.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES &&
            processingMs <= MAX_PROCESSING_MS) CandidateObserverSnapshotStoreV1.publish(serialized)
        else CandidateObserverSnapshotStoreV1.clear()
    }

    private fun addBoundedString(target: JSONObject, key: String, value: String?, maxLength: Int): Boolean {
        if (value == null) return true
        if (value.length > maxLength) return false
        target.put(key, value)
        return true
    }

    private data class QueuedNode(val node: AccessibilityNodeInfo, val parentIndex: Int, val depth: Int)

    private companion object {
        const val MAX_NODES = 4096
        const val MAX_DEPTH = 64
        const val MAX_TRAVERSAL_MS = 250L
        const val MAX_PROCESSING_MS = 500L
        const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
        const val MAX_TEXT_CHARS = 1000
        const val MAX_CLASS_CHARS = 200
    }
}

/** One-shot, same-UID, in-memory handoff. Raw UI evidence is never written to disk. */
internal object CandidateObserverSnapshotStoreV1 {
    @Volatile private var snapshotJson: String? = null

    @Synchronized fun publish(snapshot: String) { snapshotJson = snapshot }
    @Synchronized fun take(): String? = snapshotJson.also { snapshotJson = null }
    @Synchronized fun clear() { snapshotJson = null }
}

internal object CandidateObserverSnapshotV1 {
    fun candidateDigest(snapshotWithoutDigest: JSONObject): String {
        val canonical = CanonicalRouteV1.encode(jsonValue(com.google.gson.JsonParser.parseString(snapshotWithoutDigest.toString())))
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun jsonValue(value: com.google.gson.JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to jsonValue(it.value) }
        value.isJsonArray -> value.asJsonArray.map(::jsonValue)
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> value.asLong
        else -> value.asString
    }
}
