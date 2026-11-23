package org.tessera.observer

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
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
        val nodeJson = JsonArray()
        snapshot.nodes.forEach { node ->
            val item = JsonObject().apply {
                addProperty("windowId", node.windowId); addProperty("parentIndex", node.parentIndex); addProperty("depth", node.depth)
                addProperty("enabled", node.enabled); addProperty("clickable", node.clickable); addProperty("visible", node.visible)
                addProperty("focusable", node.focusable); addProperty("selected", node.selected); addProperty("checked", node.checked)
                addProperty("password", node.password)
                add("bounds", JsonArray().apply { node.bounds.forEach(::add) })
                node.resourceId?.let { addProperty("resourceId", it) }
                node.className?.let { addProperty("className", it) }
            }
            nodeJson.add(item)
        }
        val traversal = JsonObject().apply {
            addProperty("rootCount", 1); addProperty("visitedNodeCount", snapshot.nodes.size)
            addProperty("maxDepth", MAX_DEPTH); addProperty("nodeTraversalLimit", snapshot.nodeLimit)
            addProperty("timeBudgetMs", TIME_BUDGET_MS); addProperty("durationMs", snapshot.durationMs)
            addProperty("timeBudgetExceeded", false); add("truncationReason", JsonNull.INSTANCE)
            add("traversalError", JsonNull.INSTANCE); addProperty("collectionSuccess", true)
            addProperty("windowStable", true); addProperty("windowIdBefore", snapshot.windowId); addProperty("windowIdAfter", snapshot.windowId)
        }
        return JsonObject().apply {
            addProperty("schema", 3); addProperty("snapshotId", snapshot.snapshotId); addProperty("packageName", snapshot.packageName)
            addProperty("source", "accessibility"); addProperty("windowId", snapshot.windowId)
            addProperty("windowIdBefore", snapshot.windowId); addProperty("windowIdAfter", snapshot.windowId)
            addProperty("capturedAtEpochMs", snapshot.capturedAtEpochMs)
            addProperty("capturedAtElapsedRealtimeMs", snapshot.capturedElapsedRealtimeMs); addProperty("ageMs", 0)
            addProperty("treeComplete", true); addProperty("truncated", false); add("nodes", nodeJson)
            add("traversal", traversal); addProperty("sha256", snapshot.digest)
        }.toString()
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
