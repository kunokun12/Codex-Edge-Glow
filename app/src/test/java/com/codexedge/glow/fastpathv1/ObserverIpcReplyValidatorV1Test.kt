package com.codexedge.glow.fastpathv1

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ObserverIpcReplyValidatorV1Test {
    private val request = "00000000-0000-4000-8000-000000000001"
    private val execution = "00000000-0000-4000-8000-000000000002"
    private val challenge = "A".repeat(48)
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val signer = ObserverIpcClientV1.EXPECTED_SIGNER_SHA256
    private val apkHash = "c".repeat(64)
    private val sourceCommit = "d".repeat(40)
    private val expected = ObserverIpcExpectedV1(request, execution, challenge, boot, "org.example.app", 9, 1000,
        2500, 4096, 10566, 3, "0.3.0-schema3-evidence", signer, apkHash)
    private val snapshotJson = makeSnapshot()
    private val digest = JsonParser.parseString(snapshotJson).asJsonObject.get("sha256").asString
    private val good = ObserverIpcReplyV1("tessera.observer-ipc/1", "CANDIDATE_EVIDENCE", null,
        request, execution, challenge, boot, "org.example.app", 9, 1100, 1200, true, digest, snapshotJson,
        "org.tessera.observer", "org.tessera.observer/3/$signer", "0.3.0-schema3-evidence", 3,
        signer, apkHash, sourceCommit, false)

    private fun validate(reply: ObserverIpcReplyV1 = good, uid: Int = 10566,
                         packages: Set<String> = setOf("org.tessera.observer"), now: Long = 1200) =
        ObserverIpcReplyValidatorV1.validate(expected, reply, uid, packages, now)

    @Test fun acceptsFreshFullStructuralSnapshotAndAuthenticatedProducer() {
        val result = validate()
        assertTrue(result is ObserverIpcValidatedResponseV1.Candidate)
        val evidence = (result as ObserverIpcValidatedResponseV1.Candidate).evidence
        assertEquals(10566, evidence.observerUid)
        assertEquals(digest, evidence.digest)
        assertEquals(3L, evidence.snapshot["schema"])
        assertEquals("org.example.app", evidence.snapshot["packageName"])
        assertEquals("0.3.0-schema3-evidence", evidence.observerVersionName)
    }

    @Test fun preservesCorrelatedRefusalWithoutEvidence() {
        val refusal = good.copy(outcome = "REFUSED", reasonCode = "snapshot-package-mismatch",
            packageName = null, windowId = null, capturedElapsedMs = null, complete = false, digest = null, snapshotJson = null)
        assertEquals(ObserverIpcValidatedResponseV1.Refused("snapshot-package-mismatch"), validate(refusal))
    }

    @Test fun rejectsWrongOrSharedCallerUidAndSignerOrArtifactProvenance() {
        assertThrows(IllegalArgumentException::class.java) { validate(uid = 0) }
        assertThrows(IllegalArgumentException::class.java) { validate(packages = setOf("org.tessera.observer", "other.app")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(observerSignerSha256 = "e".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(observerArtifactSha256 = "e".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(observerSourceCommit = "unknown")) }
    }

    @Test fun rejectsCorrelationTimebaseAndWindowIdentityDrift() {
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(protocol = "1")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(outcome = "VERIFIED")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(requestId = execution)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(challenge = "B".repeat(48))) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(bootTimebaseId = "0".repeat(36))) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(packageName = "org.other.app")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(windowId = 10)) }
    }

    @Test fun rejectsStalePreDispatchMalformedAndIncompleteSnapshots() {
        assertThrows(IllegalArgumentException::class.java) { validate(now = 5000) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(capturedElapsedMs = 1000)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(observerElapsedMs = 1050)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(complete = false)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(snapshotJson = "{")) }
        val malformedTree = snapshotJson.replace("\"parentIndex\":-1", "\"parentIndex\":0")
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(snapshotJson = malformedTree)) }
        val leakedText = snapshotJson.replace("\"visible\":true", "\"visible\":true,\"text\":\"secret\"")
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(snapshotJson = leakedText)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(snapshotJson = snapshotJson.dropLast(2) + ",\"sha256\":\"bad\"}")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(snapshotJson = "x".repeat(ObserverSnapshotV3Codec.MAX_BYTES + 1))) }
    }

    @Test fun refusesUncorrelatedOrEvidenceBearingRemoteErrors() {
        val base = good.copy(outcome = "REFUSED", reasonCode = "snapshot-stale", packageName = null,
            windowId = null, capturedElapsedMs = null, complete = false, digest = null, snapshotJson = null)
        assertThrows(IllegalArgumentException::class.java) { validate(base.copy(challenge = "other")) }
        assertThrows(IllegalArgumentException::class.java) { validate(base.copy(reasonCode = "Invalid Reason")) }
        assertThrows(IllegalArgumentException::class.java) { validate(base.copy(snapshotJson = snapshotJson)) }
    }

    private fun makeSnapshot(): String {
        val root = JsonParser.parseString("""{
          "schema":3,"snapshotId":"00000000-0000-4000-8000-000000000010","packageName":"org.example.app",
          "source":"accessibility","windowId":9,"windowIdBefore":9,"windowIdAfter":9,
          "capturedAtEpochMs":1700000000000,"capturedAtElapsedRealtimeMs":1100,"ageMs":0,
          "treeComplete":true,"truncated":false,
          "nodes":[{"windowId":9,"parentIndex":-1,"depth":0,"enabled":true,"clickable":false,"visible":true,
            "focusable":false,"selected":false,"checked":false,"password":false,"bounds":[0,0,100,100],
            "resourceId":"org.example.app:id/root","className":"android.view.View"}],
          "traversal":{"rootCount":1,"visitedNodeCount":1,"maxDepth":64,"nodeTraversalLimit":4096,"timeBudgetMs":250,
            "durationMs":3,"timeBudgetExceeded":false,"truncationReason":null,"traversalError":null,"collectionSuccess":true,
            "windowStable":true,"windowIdBefore":9,"windowIdAfter":9},"sha256":""
        }""").asJsonObject
        root.addProperty("sha256", ObserverSnapshotV3Codec.structuralDigest("org.example.app", 9, root.getAsJsonArray("nodes")))
        return root.toString()
    }
}
