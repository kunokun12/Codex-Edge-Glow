package com.codexedge.glow.fastpathv1

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class UiStateFingerprintV1ExperimentalTest {
    private fun fixture(): CandidateUiSnapshotFingerprintV2.Snapshot {
        val input = javaClass.classLoader!!.getResourceAsStream("fastpathv1/ui-state-v1-vectors.json")!!
            .bufferedReader().use { it.readText() }
        val vector = JsonParser.parseString(input).asJsonObject.getAsJsonArray("vectors")[0].asJsonObject
        val raw = vector.getAsJsonObject("snapshot")
        val nodes = raw.getAsJsonArray("nodes").map { el ->
            val n = el.asJsonObject
            fun nullable(key: String): String? = if (n.get(key).isJsonNull) null else n.get(key).asString
            CandidateUiSnapshotFingerprintV2.Node(n.get("index").asInt, n.get("parentIndex").asInt,
                n.get("depth").asInt, n.get("windowId").asInt, nullable("text"), nullable("description"),
                nullable("resourceId"), nullable("className"), n.get("visible").asBoolean, n.get("enabled").asBoolean,
                n.get("clickable").asBoolean, n.get("focusable").asBoolean, n.get("selected").asBoolean,
                n.get("checked").asBoolean, n.get("password").asBoolean)
        }
        fun nullable(key: String): String? = if (raw.get(key).isJsonNull) null else raw.get(key).asString
        return CandidateUiSnapshotFingerprintV2.Snapshot(raw.get("packageName").asString, raw.get("windowId").asInt,
            raw.get("windowIdBefore").asInt, raw.get("windowIdAfter").asInt,
            raw.get("capturedAtElapsedRealtimeMs").asLong, raw.get("treeComplete").asBoolean,
            raw.get("truncated").asBoolean, raw.get("collectionSuccess").asBoolean,
            raw.get("timeBudgetExceeded").asBoolean, nullable("truncationReason"), nullable("traversalError"), nodes)
    }

    private fun fingerprint(s: CandidateUiSnapshotFingerprintV2.Snapshot = fixture(), now: Long = 10100) =
        UiStateFingerprintV1Experimental.fingerprint(s, "org.example.reader", 41, now)

    @Test fun crossLanguageGoldenVectorMatches() {
        val vector = fingerprint()
        assertEquals("0f1c046965f5b150d1b708d1fda32b91c1f6cd0a7a9a847e0b68cabbc267b96d", vector.uiStateFingerprint)
        assertEquals("bee4e7d515a262273402392bf79e97124473bd05fd74c38789818efcc6cc376b", vector.evidenceSnapshotDigest)
        assertEquals(setOf("screen.home"), vector.markerIds)
    }

    @Test fun unlistedRawTextDescriptionAndPasswordTextAreExcluded() {
        val original = fixture()
        val changedNodes = original.nodes.toMutableList()
        changedNodes[2] = changedNodes[2].copy(text = "private account 987654321")
        changedNodes[3] = changedNodes[3].copy(text = "private password value")
        changedNodes[1] = changedNodes[1].copy(description = "private description")
        val changed = original.copy(nodes = changedNodes)
        assertEquals(fingerprint(original), fingerprint(changed))
    }

    @Test fun markerPolicyAndAmbiguityFailClosed() {
        UiStateFingerprintV1Experimental.validateMarkerIds(listOf("screen.home", "screen.done"))
        assertThrows(IllegalArgumentException::class.java) {
            UiStateFingerprintV1Experimental.validateMarkerIds(listOf("user.supplied.marker"))
        }
        val original = fixture(); val nodes = original.nodes.toMutableList()
        nodes[2] = nodes[2].copy(text = "Home")
        assertThrows(IllegalArgumentException::class.java) { fingerprint(original.copy(nodes = nodes)) }
    }

    @Test fun staleWindowDriftAndIncompleteSnapshotsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { fingerprint(now = 12301) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(fixture().copy(windowIdAfter = 42)) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(fixture().copy(treeComplete = false)) }
    }
}
