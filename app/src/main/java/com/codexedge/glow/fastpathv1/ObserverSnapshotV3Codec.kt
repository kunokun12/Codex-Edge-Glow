package com.codexedge.glow.fastpathv1

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID

/** Closed schema-3 validator for the separately signed Observer payload. */
internal object ObserverSnapshotV3Codec {
    private val snapshotKeys = setOf("schema", "snapshotId", "packageName", "source", "windowId", "windowIdBefore",
        "windowIdAfter", "capturedAtEpochMs", "capturedAtElapsedRealtimeMs", "ageMs", "treeComplete", "truncated",
        "nodes", "traversal", "sha256")
    private val nodeRequired = setOf("windowId", "parentIndex", "depth", "enabled", "clickable", "visible", "focusable",
        "selected", "checked", "password", "bounds")
    private val nodeAllowed = nodeRequired + setOf("resourceId", "className")
    private val traversalKeys = setOf("rootCount", "visitedNodeCount", "maxDepth", "nodeTraversalLimit", "timeBudgetMs",
        "durationMs", "timeBudgetExceeded", "truncationReason", "traversalError", "collectionSuccess", "windowStable",
        "windowIdBefore", "windowIdAfter")

    fun parseAndValidate(json: String, expectedDigest: String, packageName: String, windowId: Int,
                         capturedElapsedMs: Long, nowElapsedMs: Long, maxAgeMs: Long, maxNodes: Int): Map<String, Any?> {
        require(json.toByteArray(Charsets.UTF_8).size in 2..MAX_BYTES) { "observer-ipc-snapshot-size-invalid" }
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "observer-ipc-snapshot-invalid" }
        val snapshot = root.asJsonObject
        require(snapshot.keySet() == snapshotKeys) { "observer-ipc-snapshot-fields-invalid" }
        require(snapshot.long("schema") == 3L && snapshot.string("source") == "accessibility") { "observer-ipc-snapshot-profile-invalid" }
        val id = snapshot.string("snapshotId")
        require(runCatching { UUID.fromString(id).version() == 4 }.getOrDefault(false)) { "observer-ipc-snapshot-id-invalid" }
        require(snapshot.string("packageName") == packageName && snapshot.long("windowId") == windowId.toLong() &&
            snapshot.long("windowIdBefore") == windowId.toLong() && snapshot.long("windowIdAfter") == windowId.toLong()) {
            "observer-ipc-snapshot-identity-mismatch"
        }
        require(snapshot.long("capturedAtElapsedRealtimeMs") == capturedElapsedMs && snapshot.long("capturedAtEpochMs") > 0L) {
            "observer-ipc-snapshot-time-invalid"
        }
        require(nowElapsedMs >= capturedElapsedMs && nowElapsedMs - capturedElapsedMs <= maxAgeMs) {
            "observer-ipc-snapshot-stale"
        }
        require(snapshot.boolean("treeComplete") && !snapshot.boolean("truncated")) { "observer-ipc-snapshot-incomplete" }
        val nodes = snapshot.getAsJsonArray("nodes")
        require(maxNodes in 1..MAX_NODES && nodes.size() in 1..maxNodes) { "observer-ipc-snapshot-node-count-invalid" }
        nodes.forEachIndexed { index, element ->
            require(element.isJsonObject) { "observer-ipc-node-invalid" }
            val node = element.asJsonObject
            require(node.keySet().containsAll(nodeRequired) && node.keySet().all { it in nodeAllowed }) {
                "observer-ipc-node-fields-invalid"
            }
            val nodeWindow = node.long("windowId")
            val parent = node.long("parentIndex")
            val depth = node.long("depth")
            require(nodeWindow == windowId.toLong() && depth in 0..MAX_DEPTH.toLong() &&
                (if (index == 0) parent == -1L && depth == 0L else parent in 0 until index.toLong() &&
                    depth == nodes[parent.toInt()].asJsonObject.long("depth") + 1)) { "observer-ipc-node-tree-invalid" }
            for (key in listOf("enabled", "clickable", "visible", "focusable", "selected", "checked", "password"))
                require(node[key].isJsonPrimitive && node[key].asJsonPrimitive.isBoolean) { "observer-ipc-node-flag-invalid" }
            val bounds = node.getAsJsonArray("bounds")
            require(bounds.size() == 4 && bounds.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber &&
                runCatching { it.asLong.toInt().toLong() == it.asLong }.getOrDefault(false) }) { "observer-ipc-node-bounds-invalid" }
            node.get("resourceId")?.let {
                require(it.isJsonPrimitive && it.asString.length <= 256 && it.asString.matches(RESOURCE_ID) &&
                    it.asString.startsWith("$packageName:id/")) { "observer-ipc-node-resource-invalid" }
            }
            node.get("className")?.let {
                require(it.isJsonPrimitive && it.asString.length <= 200 &&
                    (it.asString.startsWith("android.") || it.asString.startsWith("$packageName."))) {
                    "observer-ipc-node-class-invalid"
                }
            }
        }
        val traversal = snapshot.getAsJsonObject("traversal")
        require(traversal.keySet() == traversalKeys && traversal.long("rootCount") == 1L &&
            traversal.long("visitedNodeCount") == nodes.size().toLong() && traversal.long("maxDepth") == MAX_DEPTH.toLong() &&
            traversal.long("nodeTraversalLimit") in nodes.size().toLong()..maxNodes.toLong() &&
            traversal.long("timeBudgetMs") == TIME_BUDGET_MS.toLong() &&
            traversal.long("durationMs") in 0..TIME_BUDGET_MS.toLong() &&
            !traversal.boolean("timeBudgetExceeded") && traversal["truncationReason"].isJsonNull &&
            traversal["traversalError"].isJsonNull && traversal.boolean("collectionSuccess") &&
            traversal.boolean("windowStable") && traversal.long("windowIdBefore") == windowId.toLong() &&
            traversal.long("windowIdAfter") == windowId.toLong()) { "observer-ipc-traversal-invalid" }
        val digest = snapshot.string("sha256")
        require(digest.matches(HEX64) && digest == expectedDigest && structuralDigest(packageName, windowId, nodes) == digest) {
            "observer-ipc-snapshot-digest-mismatch"
        }
        val age = nowElapsedMs - capturedElapsedMs
        val map = toValue(snapshot) as Map<String, Any?>
        return map + ("ageMs" to age)
    }

    fun structuralDigest(packageName: String, windowId: Int, nodes: com.google.gson.JsonArray): String {
        val canonical = buildString {
            field(packageName); append(windowId).append(':')
            nodes.forEach { element ->
                val n = element.asJsonObject
                append(n.long("parentIndex")).append(':').append(n.long("depth")).append(':').append(n.long("windowId")).append(':')
                field(n.get("resourceId")?.takeUnless { it.isJsonNull }?.asString)
                field(n.get("className")?.takeUnless { it.isJsonNull }?.asString)
                for (key in listOf("visible", "enabled", "clickable", "focusable", "selected", "checked", "password"))
                    append(if (n.boolean(key)) '1' else '0')
                n.getAsJsonArray("bounds").forEach { append(it.asLong).append(':') }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.field(value: String?) {
        if (value == null) append("-1:") else append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
    }
    private fun JsonObject.string(key: String) = get(key).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: error("observer-ipc-snapshot-field-invalid")
    private fun JsonObject.long(key: String) = get(key).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        ?: error("observer-ipc-snapshot-field-invalid")
    private fun JsonObject.boolean(key: String) = get(key).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: error("observer-ipc-snapshot-field-invalid")
    private fun toValue(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to toValue(it.value) }
        element.isJsonArray -> element.asJsonArray.map(::toValue)
        element.asJsonPrimitive.isBoolean -> element.asBoolean
        element.asJsonPrimitive.isNumber -> element.asBigDecimal.let { n -> if (n.stripTrailingZeros().scale() <= 0) n.toLong() else n.toDouble() }
        else -> element.asString
    }

    const val MAX_BYTES = 256 * 1024
    private const val MAX_NODES = 4096
    private const val MAX_DEPTH = 64
    private const val TIME_BUDGET_MS = 250
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val RESOURCE_ID = Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+")
}
