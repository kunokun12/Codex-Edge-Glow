package com.codexedge.glow.fastpathv1

import android.os.SystemClock
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ObserverIpcClientV1Test {
    private val requestId = "00000000-0000-4000-8000-000000000001"
    private val executionId = "00000000-0000-4000-8000-000000000002"
    private val signer = ObserverIpcClientV1.EXPECTED_SIGNER_SHA256
    private val apkHash = "c".repeat(64)
    private val sourceCommit = "d".repeat(40)
    private val installed = ObserverInstalledIdentityV1(10566, 3, "0.3.0-schema3-evidence", signer, apkHash)

    @Test fun requestResponseReturnsValidatedCompleteSnapshotOnce() {
        var exchanges = 0
        val transport = ObserverIpcTransportV1 { request, _ ->
            exchanges++
            val json = snapshot(request.expectedPackage, request.expectedWindowId, 2L, request.maxNodes)
            val hash = JsonParser.parseString(json).asJsonObject.get("sha256").asString
            ObserverIpcTransportResultV1.Reply(ObserverIpcTransportReplyV1(
                ObserverIpcReplyV1("tessera.observer-ipc/1", "CANDIDATE_EVIDENCE", null,
                    request.requestId, request.executionId, request.challenge, request.bootTimebaseId,
                    request.expectedPackage, request.expectedWindowId, 2L, 10L, true, hash, json,
                    "org.tessera.observer", "org.tessera.observer/3/$signer", "0.3.0-schema3-evidence", 3,
                    signer, apkHash, sourceCommit, false),
                10566, setOf("org.tessera.observer")))
        }
        val client = client(transport)
        val result = client.capture(requestId, executionId, "org.example.app", 7, 0)

        assertEquals(1, exchanges)
        assertTrue(result is ObserverIpcClientV1.Result.Candidate)
        val evidence = (result as ObserverIpcClientV1.Result.Candidate).evidence
        assertEquals(10566, evidence.authenticatedObserverUid)
        assertEquals("org.example.app", evidence.snapshot["packageName"])
        assertEquals(3L, evidence.snapshot["schema"])
        assertFalse(evidence.snapshot.containsKey("text"))
    }

    @Test fun exactSnapshotPackageWindowFreshnessAndCompletenessAreMandatory() {
        val wrongPackage = fixtureReply { req -> snapshot("org.other.app", req.expectedWindowId, 2, req.maxNodes) }
        assertRefused(wrongPackage, "observer-ipc-snapshot-identity-mismatch")
        val wrongWindow = fixtureReply { req -> snapshot(req.expectedPackage, req.expectedWindowId + 1, 2, req.maxNodes) }
        assertRefused(wrongWindow, "observer-ipc-snapshot-identity-mismatch")
        val stale = fixtureReply { req -> snapshot(req.expectedPackage, req.expectedWindowId, 1100, req.maxNodes) }
        assertRefused(stale, "observer-ipc-stale-or-pre-dispatch", now = 5000)
        val incomplete = fixtureReply { req -> snapshot(req.expectedPackage, req.expectedWindowId, 2, req.maxNodes, complete = false) }
        assertRefused(incomplete, "observer-ipc-snapshot-incomplete")
    }

    @Test fun malformedTreesOversizedSnapshotAndDigestMismatchRefuse() {
        val malformed = fixtureReply { req -> snapshot(req.expectedPackage, req.expectedWindowId, 2, req.maxNodes, invalidParent = true) }
        assertRefused(malformed, "observer-ipc-node-tree-invalid")
        val oversized = ObserverIpcReplyV1("tessera.observer-ipc/1", "CANDIDATE_EVIDENCE", null,
            requestId, executionId, "A".repeat(48), "12345678-1234-1234-1234-123456789abc", "org.example.app", 7, 2,
            10, true, "a".repeat(64), " ".repeat(ObserverSnapshotV3Codec.MAX_BYTES + 1), "org.tessera.observer",
            "org.tessera.observer/3/$signer", installed.versionName, 3, signer, apkHash, sourceCommit, false)
        assertRefused(oversized, "observer-ipc-snapshot-size-invalid")
        val badDigest = fixtureReply { req -> snapshot(req.expectedPackage, req.expectedWindowId, 2, req.maxNodes, forcedDigest = "a".repeat(64)) }
        assertRefused(badDigest, "observer-ipc-snapshot-digest-mismatch")
    }

    @Test fun ipcPermissionTimeoutBindingAndInstalledSignerFailuresAreDistinct() {
        listOf("observer-permission-denied", "observer-reply-timeout", "observer-bind-rejected").forEach { reason ->
            val client = client(ObserverIpcTransportV1 { _, _ -> ObserverIpcTransportResultV1.Failure(reason) })
            assertEquals(ObserverIpcClientV1.Result.Refused(reason), client.capture(requestId, executionId, "org.example.app", 7, 0))
        }
        val badIdentity = ObserverInstalledIdentityV1(10566, 3, installed.versionName, "e".repeat(64), apkHash)
        val client = ObserverIpcClientV1(RuntimeEnvironment.getApplication(),
            ObserverIpcTransportV1 { _, _ -> error("must not bind with wrong certificate") }, { 10L }, { badIdentity })
        assertEquals(ObserverIpcClientV1.Result.Refused("observer-provenance-unavailable"),
            client.capture(requestId, executionId, "org.example.app", 7, 0))
    }

    private fun client(transport: ObserverIpcTransportV1) = ObserverIpcClientV1(
        RuntimeEnvironment.getApplication(), transport, { 10L }, { installed })

    private fun fixtureReply(make: (ObserverIpcRequestMessageV1) -> String): ObserverIpcReplyV1 {
        val challenge = "A".repeat(48)
        val boot = "12345678-1234-1234-1234-123456789abc"
        val req = ObserverIpcRequestMessageV1("tessera.observer-ipc/1", challenge, requestId, executionId,
            "org.example.app", 7, 2500, 4096, 0, boot)
        val json = make(req)
        val parsed = JsonParser.parseString(json).asJsonObject
        return ObserverIpcReplyV1("tessera.observer-ipc/1", "CANDIDATE_EVIDENCE", null, requestId, executionId,
            challenge, boot, "org.example.app", 7, parsed.get("capturedAtElapsedRealtimeMs").asLong,
            10, true, parsed.get("sha256").asString, json, "org.tessera.observer", "org.tessera.observer/3/$signer",
            installed.versionName, 3, signer, apkHash, sourceCommit, false)
    }

    private fun assertRefused(reply: ObserverIpcReplyV1, reason: String, now: Long = 10L) {
        val result = ObserverIpcClientV1(RuntimeEnvironment.getApplication(), ObserverIpcTransportV1 { request, _ ->
            ObserverIpcTransportResultV1.Reply(ObserverIpcTransportReplyV1(reply.copy(requestId = request.requestId,
                executionId = request.executionId, challenge = request.challenge, bootTimebaseId = request.bootTimebaseId),
                10566, setOf("org.tessera.observer")))
        }, { now }, { installed })
            .capture(requestId, executionId, "org.example.app", 7, 0)
        assertEquals(ObserverIpcClientV1.Result.Refused(reason), result)
    }

    private fun snapshot(pkg: String, window: Int, captured: Long, nodeLimit: Int, complete: Boolean = true,
                        invalidParent: Boolean = false, forcedDigest: String? = null): String {
        val age = (10L - captured).coerceAtLeast(0)
        val root = JsonParser.parseString("""{
          "schema":3,"snapshotId":"00000000-0000-4000-8000-000000000010","packageName":"$pkg",
          "source":"accessibility","windowId":$window,"windowIdBefore":$window,"windowIdAfter":$window,
          "capturedAtEpochMs":1700000000000,"capturedAtElapsedRealtimeMs":$captured,"ageMs":0,
          "treeComplete":$complete,"truncated":false,
          "nodes":[{"windowId":$window,"parentIndex":${if (invalidParent) 0 else -1},"depth":0,"enabled":true,
            "clickable":false,"visible":true,"focusable":false,"selected":false,"checked":false,"password":false,
            "bounds":[0,0,100,100],"resourceId":"$pkg:id/root","className":"android.view.View"}],
          "traversal":{"rootCount":1,"visitedNodeCount":1,"maxDepth":64,"nodeTraversalLimit":$nodeLimit,
            "timeBudgetMs":250,"durationMs":1,"timeBudgetExceeded":false,"truncationReason":null,"traversalError":null,
            "collectionSuccess":true,"windowStable":true,"windowIdBefore":$window,"windowIdAfter":$window},"sha256":""
        }""").asJsonObject
        root.addProperty("ageMs", age)
        root.addProperty("sha256", forcedDigest ?: ObserverSnapshotV3Codec.structuralDigest(pkg, window, root.getAsJsonArray("nodes")))
        return root.toString()
    }
}
