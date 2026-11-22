package com.codexedge.glow.fastpathv1

import android.content.Context
import android.util.AtomicFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class FastPathResultStoreV1Test {
    private lateinit var context: Context
    private lateinit var request: Map<String, Any?>
    private lateinit var directory: File
    private lateinit var resultFile: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val uuid = "00000000-0000-4000-8000-000000000123"
        request = mapOf(
            "protocolVersion" to StrictEnvelopeV1.VERSION,
            "messageType" to "route.status",
            "requestId" to uuid,
            "taskId" to "00000000-0000-4000-8000-000000000124",
            "sessionId" to "00000000-0000-4000-8000-000000000125",
            "ownerId" to "00000000-0000-4000-8000-000000000126",
            "deviceBinding" to mapOf("deviceSerial" to "fixture-device",
                "companionPackage" to "com.codexedge.glow", "companionVersionCode" to 18L,
                "signerCertificateSha256" to "a".repeat(64)),
            "expiresAtEpochMs" to 1_790_000_000_000L,
            "payload" to mapOf("afterSequence" to 0L))
        directory = File(context.filesDir, "fastpath-v1-results")
        resultFile = File(directory, "${sha256(uuid)}.json")
        AtomicFile(resultFile).delete()
    }

    @After fun tearDown() { AtomicFile(resultFile).delete() }

    private fun response(request: Map<String, Any?> = this.request) = mapOf(
        "protocolVersion" to StrictEnvelopeV1.VERSION,
        "messageType" to "route.status.result",
        "requestId" to request.getValue("requestId"),
        "taskId" to request.getValue("taskId"),
        "sessionId" to request.getValue("sessionId"),
        "ownerId" to request.getValue("ownerId"),
        "deviceBinding" to request.getValue("deviceBinding"),
        "status" to "RUNNING", "reasonCode" to "status-read",
        "observedAtEpochMs" to 1_790_000_000_000L,
        "payload" to mapOf("taskState" to "RUNNING"))

    @Test fun atomicResultIsCanonicalTupleBoundAndIdempotent() {
        val store = FastPathResultStoreV1(context)
        val expected = CanonicalRouteV1.encode(response())
        assertEquals(expected, store.save(request, response()))
        assertEquals(expected, store.existingResponse(request))
        assertEquals(expected, store.read(request.getValue("requestId") as String,
            request.getValue("taskId") as String, request.getValue("sessionId") as String,
            request.getValue("ownerId") as String))
        assertNull(store.read(request.getValue("requestId") as String,
            "00000000-0000-4000-8000-000000000999", request.getValue("sessionId") as String,
            request.getValue("ownerId") as String))
        assertThrows(IllegalStateException::class.java) {
            store.existingResponse(request + ("payload" to mapOf("afterSequence" to 1L)))
        }
    }

    @Test fun responseTupleMismatchAndCorruptAtomicFileFailClosed() {
        val store = FastPathResultStoreV1(context)
        val wrong = response().toMutableMap().apply { put("ownerId", "00000000-0000-4000-8000-000000000127") }
        assertThrows(RegistryStorageException::class.java) { store.save(request, wrong) }
        store.save(request, response())
        val bytes = resultFile.readBytes()
        bytes[0] = 'f'.code.toByte()
        resultFile.writeBytes(bytes)
        assertThrows(RegistryStorageException::class.java) { store.existingResponse(request) }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
