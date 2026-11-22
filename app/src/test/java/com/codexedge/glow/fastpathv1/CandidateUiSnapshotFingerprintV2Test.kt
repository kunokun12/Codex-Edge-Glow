package com.codexedge.glow.fastpathv1

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateUiSnapshotFingerprintV2Test {
    private fun snapshot(
        nodes: List<CandidateUiSnapshotFingerprintV2.Node> = listOf(root(), title(), button()),
        capturedAt: Long = 10_000,
        packageName: String = "com.example.reader",
        windowId: Int = 41,
        before: Int = windowId,
        after: Int = windowId,
        complete: Boolean = true,
        truncated: Boolean = false,
        success: Boolean = true,
        timeExceeded: Boolean = false,
        truncationReason: String? = null,
        traversalError: String? = null
    ) = CandidateUiSnapshotFingerprintV2.Snapshot(packageName, windowId, before, after,
        capturedAt, complete, truncated, success, timeExceeded, truncationReason, traversalError, nodes)

    private fun root(windowId: Int = 41) = CandidateUiSnapshotFingerprintV2.Node(
        0, -1, 0, windowId, null, null, "com.example.reader:id/root", "android.view.ViewGroup",
        true, true, false, false, false, false, false)

    private fun title(index: Int = 1, parent: Int = 0, depth: Int = 1, text: String? = "Café", windowId: Int = 41) =
        CandidateUiSnapshotFingerprintV2.Node(index, parent, depth, windowId, text, null,
            "com.example.reader:id/title", "android.widget.TextView", true, true, false, false,
            false, false, false)

    private fun button(index: Int = 2, parent: Int = 0, depth: Int = 1, windowId: Int = 41) =
        CandidateUiSnapshotFingerprintV2.Node(index, parent, depth, windowId, null, "Continue",
            "com.example.reader:id/continue", "android.widget.Button", true, true, true, true,
            false, false, false)

    private fun fingerprint(value: CandidateUiSnapshotFingerprintV2.Snapshot = snapshot(),
                            pkg: String = value.packageName, window: Int = value.windowId, now: Long = 10_100) =
        CandidateUiSnapshotFingerprintV2.fingerprint(value, pkg, window, now)

    @Test fun agreesWithSharedHostVector() {
        val vector = javaClass.getResourceAsStream("/fastpath-v1/ui-state-candidate-v2-vectors.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            .getAsJsonArray("vectors")[0].asJsonObject
        val v = vector.getAsJsonObject("snapshot")
        val parsedNodes = v.getAsJsonArray("nodes").map { item ->
            val n = item.asJsonObject
            fun nullable(key: String): String? = if (n.get(key).isJsonNull) null else n.get(key).asString
            CandidateUiSnapshotFingerprintV2.Node(
                n.get("index").asInt, n.get("parentIndex").asInt, n.get("depth").asInt,
                n.get("windowId").asInt, nullable("text"), nullable("description"), nullable("resourceId"),
                nullable("className"), n.get("visible").asBoolean, n.get("enabled").asBoolean,
                n.get("clickable").asBoolean, n.get("focusable").asBoolean,
                n.get("selected").asBoolean, n.get("checked").asBoolean, n.get("password").asBoolean)
        }
        val s = CandidateUiSnapshotFingerprintV2.Snapshot(v.get("packageName").asString,
            v.get("windowId").asInt, v.get("windowIdBefore").asInt, v.get("windowIdAfter").asInt,
            v.get("capturedAtElapsedRealtimeMs").asLong, v.get("treeComplete").asBoolean,
            v.get("truncated").asBoolean, v.get("collectionSuccess").asBoolean,
            v.get("timeBudgetExceeded").asBoolean,
            if (v.get("truncationReason").isJsonNull) null else v.get("truncationReason").asString,
            if (v.get("traversalError").isJsonNull) null else v.get("traversalError").asString, parsedNodes)
        assertEquals(vector.get("expectedFingerprint").asString,
            CandidateUiSnapshotFingerprintV2.fingerprint(s, vector.get("expectedPackage").asString,
                vector.get("expectedWindowId").asInt, vector.get("nowElapsedRealtimeMs").asLong,
                vector.get("maxAgeMs").asLong))
    }

    @Test fun equivalentUnicodeAndVolatileCaptureMetadataHaveStableFingerprint() {
        val nfd = "Cafe\u0301"
        val composed = "Caf\u00e9"
        val a = snapshot(nodes = listOf(root(), title(text = nfd)))
        val b = snapshot(nodes = listOf(root(windowId = 99), title(text = composed, windowId = 99)),
            capturedAt = 10_050, windowId = 99)
        assertEquals(fingerprint(a), fingerprint(b, window = 99, now = 10_100))
    }

    @Test fun hierarchyAndTraversalOrderArePartOfFingerprint() {
        val base = fingerprint(snapshot())
        val reversed = snapshot(nodes = listOf(root(), button(index = 1), title(index = 2)))
        val changedParent = snapshot(nodes = listOf(root(), title(), button(index = 2, parent = 1, depth = 2)))
        assertNotEquals(base, fingerprint(reversed))
        assertNotEquals(base, fingerprint(changedParent))
    }

    @Test fun differentVisibleStateChangesDigestAndPasswordTextIsExcluded() {
        val base = fingerprint()
        val changed = snapshot(nodes = listOf(root(), title(text = "Privacy"), button()))
        assertNotEquals(base, fingerprint(changed))
        val passwordA = title(text = "secret").copy(password = true, description = "secret description")
        val passwordB = title(text = "different").copy(password = true, description = "different description")
        assertEquals(fingerprint(snapshot(nodes = listOf(root(), passwordA))),
            fingerprint(snapshot(nodes = listOf(root(), passwordB))))
    }

    @Test fun rejectsMissingOrUnstableEvidenceAndIdentityDrift() {
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(nodes = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(packageName = "com.other.app"), pkg = "com.example.reader")
        }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(windowId = 42), window = 41) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(after = 42)) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(complete = false)) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(truncated = true)) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(success = false)) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(timeExceeded = true)) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(traversalError = "node-failure")) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(capturedAt = 0), now = 2501) }
        assertThrows(IllegalArgumentException::class.java) { fingerprint(snapshot(capturedAt = 101), now = 100) }
    }

    @Test fun rejectsDuplicateIndicesAndInvalidParentHierarchy() {
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(nodes = listOf(root(), title(index = 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(nodes = listOf(root(), title(parent = 1))))
        }
        // Identical labels remain distinct by ordered node position and parentage.
        assertNotEquals(fingerprint(snapshot(nodes = listOf(root(), title(), title(index = 2)))),
            fingerprint(snapshot(nodes = listOf(root(), title()))))
    }

    @Test fun rejectsNonBreadthFirstTraversalOrder() {
        val depthRegression = listOf(root(), title(), button(index = 2, parent = 1, depth = 2),
            button(index = 3))
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(nodes = depthRegression))
        }
        val parentRegression = listOf(root(), title(), button(index = 2),
            title(index = 3, parent = 2, depth = 2), button(index = 4, parent = 1, depth = 2))
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(nodes = parentRegression))
        }
    }

    @Test fun respectsNodeAndCanonicalByteBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(nodes = List(CandidateUiSnapshotFingerprintV2.MAX_NODES + 1) { i ->
                if (i == 0) root() else title(index = i, parent = 0)
            }))
        }
        val longResource = "r".repeat(1000)
        val largeNodes = (0..1500).map { i ->
            if (i == 0) root().copy(resourceId = longResource)
            else title(index = i).copy(resourceId = longResource)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(nodes = largeNodes))
        }
    }

    @Test fun supportsOnlyFreshExpectedSingleWindowSnapshotAtExactAgeBoundary() {
        assertTrue(fingerprint(snapshot(capturedAt = 7600), now = 10_100).matches(Regex("[0-9a-f]{64}")))
        assertThrows(IllegalArgumentException::class.java) {
            fingerprint(snapshot(capturedAt = 7599), now = 10_100)
        }
    }
}
