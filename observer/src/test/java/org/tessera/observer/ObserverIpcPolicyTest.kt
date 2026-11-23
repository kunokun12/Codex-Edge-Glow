package org.tessera.observer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class ObserverIpcPolicyTest {
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val request = ObserverIpcRequestV1("tessera.observer-ipc/1", "c".repeat(48),
        "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
        "org.example.app", 9, 2500, 4096, 1000, boot)
    private val artifact = ObserverArtifactV1("org.tessera.observer/3/" + "b".repeat(64),
        "0.3.0-schema3-evidence", 3, "b".repeat(64), "c".repeat(64), "d".repeat(40))

    private fun node(parent: Int = -1, depth: Int = 0, window: Int = 9) = ObserverNode(parent, depth,
        "org.example.app:id/root", "android.view.View", true, true, false, false, false, false, false,
        listOf(0, 0, 100, 100), window)
    private fun snapshot(packageName: String = "org.example.app", window: Int = 9, captured: Long = 1100,
                         nodes: List<ObserverNode> = listOf(node()), complete: Boolean = true,
                         json: String = "{\"schema\":3}") = ObserverSnapshot("00000000-0000-4000-8000-000000000010",
        packageName, window, 1700000000000, captured, complete, nodes, 3, 4096, "a".repeat(64), json)
    private fun respond(policy: ObserverIpcPolicy = ObserverIpcPolicy(), request: ObserverIpcRequestV1 = this.request,
                        callerUid: Int = 10606, callerPackages: Set<String> = setOf("com.codexedge.glow"),
                        result: ObserverSnapshotResult = ObserverSnapshotResult.Captured(snapshot()),
                        now: Long = 1200) = policy.respond(request, callerUid, callerPackages, result, now, boot, artifact)

    @Test fun returnsFreshBoundedStructuralSnapshotAndNeverVerified() {
        val response = respond()
        assertEquals("CANDIDATE_EVIDENCE", response.outcome)
        assertEquals(request.requestId, response.requestId)
        assertEquals(request.executionId, response.executionId)
        assertEquals(request.challenge, response.challenge)
        assertEquals("org.example.app", response.packageName)
        assertEquals(9, response.windowId)
        assertEquals("a".repeat(64), response.evidenceDigest)
        assertEquals("{\"schema\":3}", response.snapshotJson)
        assertEquals(3, response.observerVersionCode)
        assertFalse(response.verified)
    }

    @Test fun refusesWrongPackageWindowAndPreDispatchOrStaleSnapshots() {
        assertEquals("snapshot-package-mismatch", respond(result = ObserverSnapshotResult.Captured(snapshot(packageName = "org.other.app"))).reasonCode)
        assertEquals("snapshot-window-mismatch", respond(result = ObserverSnapshotResult.Captured(snapshot(window = 10))).reasonCode)
        assertEquals("snapshot-pre-dispatch", respond(result = ObserverSnapshotResult.Captured(snapshot(captured = 1000))).reasonCode)
        assertEquals("snapshot-stale", respond(result = ObserverSnapshotResult.Captured(snapshot(captured = 1100)), now = 4000).reasonCode)
    }

    @Test fun refusesIncompleteTreeAndOversizedPayload() {
        assertEquals("snapshot-incomplete", respond(result = ObserverSnapshotResult.Captured(snapshot(complete = false))).reasonCode)
        assertEquals("snapshot-payload-too-large", respond(result = ObserverSnapshotResult.Captured(snapshot(json = "x".repeat(262145)))).reasonCode)
        assertEquals("snapshot-incomplete", respond(result = ObserverSnapshotResult.Captured(snapshot(nodes = emptyList()))).reasonCode)
    }

    @Test fun refusesWrongCallerPermissionIdentityAndReplay() {
        assertEquals("ipc-caller-invalid", respond(callerPackages = setOf("other.app")).reasonCode)
        assertEquals("ipc-caller-invalid", respond(callerUid = -1).reasonCode)
        val policy = ObserverIpcPolicy()
        assertEquals("CANDIDATE_EVIDENCE", respond(policy = policy).outcome)
        assertEquals("ipc-challenge-replayed", respond(policy = policy).reasonCode)
    }

    @Test fun refusesInvalidBoundsAndTimebase() {
        assertEquals("ipc-boundary-invalid", respond(request = request.copy(maxNodes = 5000)).reasonCode)
        assertEquals("ipc-boundary-invalid", respond(request = request.copy(expectedWindowId = -1)).reasonCode)
        assertEquals("ipc-timebase-mismatch", respond(request = request.copy(bootTimebaseId = "f".repeat(36))).reasonCode)
    }

    @Test fun schemaThreeEncoderContainsNoTextOrDescriptionAndUsesStructuralDigest() {
        val item = node()
        val provisional = snapshot()
        val digest = ObserverSnapshotV3.structuralDigest(provisional.packageName, provisional.windowId, listOf(item))
        val encoded = ObserverSnapshotV3.encode(provisional.copy(digest = digest))
        assertTrue(encoded.contains("\"schema\":3"))
        assertTrue(encoded.contains("\"nodes\""))
        assertFalse(encoded.contains("\"text\""))
        assertFalse(encoded.contains("\"description\""))
        assertTrue(encoded.contains(digest))
    }

    @Test fun actionCapabilityIsAbsentFromObserverResponse() {
        assertEquals("CANDIDATE_EVIDENCE", respond().outcome)
        assertFalse(respond().verified)
    }
}
