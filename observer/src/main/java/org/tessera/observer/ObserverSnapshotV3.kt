package org.tessera.observer

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Schema-3 structural evidence. UI text and descriptions are intentionally never serialized. */
internal object ObserverSnapshotV3 {
    const val MAX_NODES = 4096
    const val MAX_DEPTH = 64
    const val TIME_BUDGET_MS = 250

    fun structuralDigest(packageName: String, windowId: Int, nodes: List<ObserverNode>): String {
        val canonical = buildString {
            field(packageName); append(windowId).append(':')
            nodes.forEach { n ->
                append(n.parentIndex).append(':').append(n.depth).append(':').append(n.windowId).append(':')
                field(n.resourceId); field(n.className)
                append(if (n.visible) '1' else '0').append(if (n.enabled) '1' else '0')
                    .append(if (n.clickable) '1' else '0').append(if (n.focusable) '1' else '0')
                    .append(if (n.selected) '1' else '0').append(if (n.checked) '1' else '0')
                    .append(if (n.password) '1' else '0')
                n.bounds.forEach { append(it).append(':') }
            }
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun encode(snapshot: ObserverSnapshot): String {
        require(snapshot.complete && snapshot.nodes.isNotEmpty() && snapshot.nodes.size <= snapshot.nodeLimit &&
            snapshot.nodeLimit in 1..MAX_NODES && snapshot.durationMs in 0..TIME_BUDGET_MS.toLong() &&
            snapshot.nodes.first().parentIndex == -1 && snapshot.nodes.first().depth == 0)
        snapshot.nodes.forEachIndexed { index, node ->
            require(node.windowId == snapshot.windowId && node.depth in 0..MAX_DEPTH && node.bounds.size == 4 &&
                (if (index == 0) node.parentIndex == -1 else node.parentIndex in 0 until index &&
                    node.depth == snapshot.nodes[node.parentIndex].depth + 1) &&
                (node.resourceId == null || node.resourceId.length <= 256) &&
                (node.className == null || node.className.length <= 200))
        }
        val nodeJson = JSONArray()
        snapshot.nodes.forEach { node ->
            val item = JSONObject()
                .put("windowId", node.windowId).put("parentIndex", node.parentIndex).put("depth", node.depth)
                .put("enabled", node.enabled).put("clickable", node.clickable).put("visible", node.visible)
                .put("focusable", node.focusable).put("selected", node.selected).put("checked", node.checked)
                .put("password", node.password).put("bounds", JSONArray(node.bounds))
            node.resourceId?.let { item.put("resourceId", it) }
            node.className?.let { item.put("className", it) }
            nodeJson.put(item)
        }
        val traversal = JSONObject()
            .put("rootCount", 1).put("visitedNodeCount", snapshot.nodes.size)
            .put("maxDepth", MAX_DEPTH).put("nodeTraversalLimit", snapshot.nodeLimit)
            .put("timeBudgetMs", TIME_BUDGET_MS).put("durationMs", snapshot.durationMs)
            .put("timeBudgetExceeded", false).put("truncationReason", JSONObject.NULL)
            .put("traversalError", JSONObject.NULL).put("collectionSuccess", true)
            .put("windowStable", true).put("windowIdBefore", snapshot.windowId).put("windowIdAfter", snapshot.windowId)
        return JSONObject()
            .put("schema", 3).put("snapshotId", snapshot.snapshotId).put("packageName", snapshot.packageName)
            .put("source", "accessibility").put("windowId", snapshot.windowId)
            .put("windowIdBefore", snapshot.windowId).put("windowIdAfter", snapshot.windowId)
            .put("capturedAtEpochMs", snapshot.capturedAtEpochMs)
            .put("capturedAtElapsedRealtimeMs", snapshot.capturedElapsedRealtimeMs).put("ageMs", 0)
            .put("treeComplete", true).put("truncated", false).put("nodes", nodeJson)
            .put("traversal", traversal).put("sha256", snapshot.digest).toString()
    }

    private fun StringBuilder.field(value: String?) {
        if (value == null) append("-1:") else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            append(bytes.size).append(':').append(value)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
