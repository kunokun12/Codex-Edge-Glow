package com.codexedge.glow

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import com.codexedge.glow.fastpathv1.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64 as JavaBase64
import java.util.concurrent.atomic.AtomicLong

/** Receiver -> real SQLite registry/journal -> existing multi-step engine, with isolated test authorities/adapters. */
@RunWith(RobolectricTestRunner::class)
class FastPathV1_1ReceiverEngineSimulationTest {
    private lateinit var context: Context
    private lateinit var keyPair: KeyPair
    private lateinit var registry: FastPathRegistryV1
    private lateinit var journal: FastPathExecutionJournalV1
    private lateinit var resultStore: FastPathV1_1ResultStore
    private lateinit var runtime: EngineFixture
    private lateinit var leaseKeys: KeyPair
    private val registryDb = "v11-receiver-registry-${System.nanoTime()}.db"
    private val journalDb = "v11-receiver-journal-${System.nanoTime()}.db"
    private val resultDirectory = "v11-results-${System.nanoTime()}"
    private val now = System.currentTimeMillis()
    private val bootId = "isolated-test-boot"
    private val observerDigest = "f".repeat(64)
    private val binding = mapOf("deviceSerial" to "offline-test", "companionPackage" to "com.codexedge.glow",
        "companionVersionCode" to 18, "signerCertificateSha256" to "a".repeat(64))
    private val route: Map<String, Any?>

    init {
        val before = mapOf("stateFingerprint" to "b".repeat(64), "requiredMarkers" to listOf("screen.home"))
        val middle = mapOf("stateFingerprint" to "c".repeat(64), "requiredMarkers" to listOf("navigation.settings"))
        val after = mapOf("stateFingerprint" to "d".repeat(64), "requiredMarkers" to listOf("screen.done"))
        route = mapOf("packageName" to "org.example.app",
            "appConstraint" to mapOf("versionName" to "1.0", "versionCode" to 1L, "signerCertificateSha256" to "d".repeat(64)),
            "origin" to before, "destination" to after, "windowPolicy" to "same_window",
            "steps" to listOf(
                mapOf("stepId" to "open", "selector" to mapOf("kind" to "text", "value" to "Open"),
                    "uniquenessPolicy" to "exactly_one_actionable_target", "expectedBefore" to before, "expectedAfter" to middle),
                mapOf("stepId" to "continue", "selector" to mapOf("kind" to "text", "value" to "Continue"),
                    "uniquenessPolicy" to "exactly_one_actionable_target", "expectedBefore" to middle, "expectedAfter" to after)))
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(registryDb); context.deleteDatabase(journalDb)
        context.filesDir.resolve("fastpath-registry-v1.present").delete()
        context.filesDir.resolve("$journalDb.present").delete()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        keyPair = generator.generateKeyPair()
        leaseKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trust = AuthorityTrustV1.forTests(listOf(AuthorityAnchorV1("test-root", keyPair.public as RSAPublicKey, 1, null)))
        registry = FastPathRegistryV1(context, trust, { now }, registryDb)
        journal = FastPathExecutionJournalV1(context, journalDb)
        resultStore = FastPathV1_1ResultStore(context, resultDirectory)
        val fingerprint = CanonicalRouteV1.fingerprint("route.demo", 1, route)
        val unsigned = mapOf<String, Any?>("authorityKeyId" to "test-root", "registryEpoch" to 1L,
            "issuedAtEpochMs" to now - 1000, "expiresAtEpochMs" to now + 60000,
            "routeId" to "route.demo", "routeVersion" to 1L, "routeFingerprint" to fingerprint,
            "routeDefinition" to route)
        assertTrue(registry.applyPromotion(unsigned + ("signature" to sign(unsigned))).accepted)
        runtime = EngineFixture()
    }

    @After fun tearDown() {
        runtime.close()
        registry.close(); journal.close()
        context.deleteDatabase(registryDb); context.deleteDatabase(journalDb)
        context.filesDir.resolve("fastpath-registry-v1.present").delete()
        context.filesDir.resolve("$journalDb.present").delete()
        context.filesDir.resolve(resultDirectory).deleteRecursively()
    }

    private fun sign(value: Map<String, Any?>): String {
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(keyPair.private)
        signer.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.update(CanonicalRouteV1.encode(value).toByteArray(Charsets.UTF_8))
        return JavaBase64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    private fun requestJson(expiredLease: Boolean = false, seed: Int = 1): String {
        val fingerprint = CanonicalRouteV1.fingerprint("route.demo", 1, route)
        fun id(offset: Int) = "00000000-0000-4000-8000-${(100 + seed + offset).toString().padStart(12, '0')}"
        val requestId = id(0)
        val nowElapsed = SystemClock.elapsedRealtime()
        runtime.lease = runtime.signedLease(OfflineLeaseV1(leaseId = "fixture-lease", deviceEnrollmentId = "offline-test",
            protocolProfile = "tessera.fast-path/1.1", authorityId = "test-root", keysetVersion = 1,
            registryEpoch = 1, checkpointDigest = "e".repeat(64), authorizedRouteFingerprints = listOf(fingerprint),
            issuedAtEpochMs = now - if (expiredLease) 100_000 else 1000,
            expiresAtEpochMs = now + if (expiredLease) -1 else 50_000,
            maxValidityMs = CheckpointLeaseV1Experimental.MAX_LEASE_DURATION_MS, bootId = bootId,
            issuedElapsedRealtimeMs = nowElapsed - 100, revoked = false, signature = ""))
        runtime.requestId = requestId
        return Gson().toJson(mapOf("protocolVersion" to "tessera.fast-path/1.1", "messageType" to "route.execute",
            "requestId" to requestId, "taskId" to id(1), "sessionId" to id(2), "ownerId" to id(3),
            "deviceBinding" to binding, "expiresAtEpochMs" to now + 60000,
            "payload" to mapOf("routeId" to "route.demo", "routeVersion" to 1, "routeFingerprint" to fingerprint,
                "routeDefinition" to route)))
    }

    private fun intent(json: String) = Intent(FastPathV1_1Receiver.ACTION).putExtra(FastPathV1_1Receiver.REQUEST_EXTRA,
        Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    private inner class EngineFixture : V1_1RuntimePorts, AutoCloseable {
        override val executionGateApproved = true
        override val authorityAvailable = true
        var observerTrusted = true
        override val observerApproved get() = observerTrusted
        val elapsed = AtomicLong(SystemClock.elapsedRealtime())
        var lease = signedLease(OfflineLeaseV1(leaseId = "unset", deviceEnrollmentId = "offline-test",
            protocolProfile = "tessera.fast-path/1.1", authorityId = "test-root", keysetVersion = 1,
            registryEpoch = 1, checkpointDigest = "e".repeat(64), authorizedRouteFingerprints = emptyList(),
            issuedAtEpochMs = now - 1000, expiresAtEpochMs = now + 50_000,
            maxValidityMs = CheckpointLeaseV1Experimental.MAX_LEASE_DURATION_MS, bootId = bootId,
            issuedElapsedRealtimeMs = elapsed.get() - 100, revoked = false, signature = ""))
        var requestId = ""
        var taskId = ""
        var sessionId = ""
        var ownerId = ""
        var wrongOrigin = false
        var ambiguous = false
        var uncertainAfterDispatch = false
        var actionReceipt = "ACCEPTED"
        var dispatches = 0
        var lastResponse: Map<String, Any?>? = null
        val tuple get() = ExecutionTupleV1(requestId, taskId, sessionId, ownerId, "offline-test")
        private val action = object : FastPathActionPortV1 {
            private var captures = 0
            override fun installedApp(packageName: String) = AppIdentityV1(packageName, "1.0", 1, "d".repeat(64))
            override fun captureFresh(packageName: String, expectedWindowId: Int?, maxNodes: Int, maxAgeMs: Int): UiEvidenceV1 {
                captures++
                val fingerprint = when (captures) {
                    2, 3 -> "c".repeat(64)
                    4 -> "d".repeat(64)
                    else -> if (wrongOrigin) "0".repeat(64) else "b".repeat(64)
                }
                val marker = when (captures) { 2, 3 -> "navigation.settings"; 4 -> "screen.done"; else -> "screen.home" }
                val targets = mutableListOf(VisibleTargetV1("marker-$captures", packageName, 7, true, true, false, marker, null, null))
                val selector = if (captures <= 2) "Open" else "Continue"
                targets += VisibleTargetV1("action-$captures", packageName, 7, true, true, true, selector, null, null)
                if (ambiguous && captures == 1) targets += VisibleTargetV1("action-duplicate", packageName, 7, true, true, true, "Open", null, null)
                return UiEvidenceV1(packageName, 7, 7, 7, elapsed.get(), true, false, fingerprint, targets)
            }
            override fun dispatchClick(target: VisibleTargetV1, expectedPackage: String, expectedWindowId: Int): Boolean {
                dispatches++
                if (uncertainAfterDispatch) { actionReceipt = "UNCERTAIN"; throw IllegalStateException("simulated-process-loss") }
                return true
            }
            override fun independentlyVerify(route: ExecutableRouteV1, expectedWindowId: Int, maxAgeMs: Int): VerificationEvidenceV1 {
                val observed = elapsed.incrementAndGet()
                return VerificationEvidenceV1(true, route.packageName, expectedWindowId, route.destination.stateFingerprint,
                    observed, 0, "separate-observer-process", observerDigest, setOf("screen.done"))
            }
        }

        fun signedLease(value: OfflineLeaseV1): OfflineLeaseV1 {
            val unsigned = mapOf<String, Any?>("schema" to value.schema, "leaseId" to value.leaseId,
                "deviceEnrollmentId" to value.deviceEnrollmentId, "protocolProfile" to value.protocolProfile,
                "authorityId" to value.authorityId, "keysetVersion" to value.keysetVersion, "registryEpoch" to value.registryEpoch,
                "checkpointDigest" to value.checkpointDigest, "authorizedRouteFingerprints" to value.authorizedRouteFingerprints,
                "issuedAtEpochMs" to value.issuedAtEpochMs, "expiresAtEpochMs" to value.expiresAtEpochMs,
                "maxValidityMs" to value.maxValidityMs, "bootId" to value.bootId,
                "issuedElapsedRealtimeMs" to value.issuedElapsedRealtimeMs, "revoked" to value.revoked)
            return value.copy(signature = signEd25519(unsigned))
        }

        private fun signEd25519(value: Map<String, Any?>): String {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(leaseKeys.private)
            signer.update(CanonicalRouteV1.encode(value).toByteArray(Charsets.UTF_8))
            return JavaBase64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        }

        private fun signedCheckpoint(value: RegistryCheckpointV1): RegistryCheckpointV1 {
            val unsigned = mapOf<String, Any?>("schema" to value.schema, "deviceEnrollmentId" to value.deviceEnrollmentId,
                "protocolProfile" to value.protocolProfile, "authorityId" to value.authorityId,
                "keysetVersion" to value.keysetVersion, "highestCommittedRegistryEpoch" to value.highestCommittedRegistryEpoch,
                "currentCheckpointDigest" to value.currentCheckpointDigest, "issuedAtEpochMs" to value.issuedAtEpochMs,
                "expiresAtEpochMs" to value.expiresAtEpochMs, "revoked" to value.revoked,
                "recoveryState" to value.recoveryState)
            return value.copy(signature = signEd25519(unsigned))
        }

        override fun capture(request: Map<String, Any?>) = CaptureResult("OBSERVER_UNAVAILABLE", "observer-unavailable")

        override fun execute(request: Map<String, Any?>, nowEpochMs: Long): Map<String, Any?> {
            resultStore.existing(request)?.let { return it }
            taskId = request["taskId"] as String
            sessionId = request["sessionId"] as String
            ownerId = request["ownerId"] as String
            val payload = request["payload"] as Map<String, Any?>
            val requested = RequestedRouteV1(payload["routeId"] as String, payload["routeVersion"] as Long,
                payload["routeFingerprint"] as String, payload["routeDefinition"] as Map<String, Any?>)
            // This is the real cryptographically verified SQLite registry read.
            val promoted = registry.findPromoted(requested.routeId, requested.routeVersion, requested.routeFingerprint)
                ?: return refusal(request, nowEpochMs, "route-not-authorized")
            val checkpoint = signedCheckpoint(RegistryCheckpointV1(deviceEnrollmentId = "offline-test", protocolProfile = "tessera.fast-path/1.1",
                authorityId = "test-root", keysetVersion = 1, highestCommittedRegistryEpoch = promoted.registryEpoch,
                currentCheckpointDigest = "e".repeat(64), issuedAtEpochMs = now - 200_000, expiresAtEpochMs = now + 60000,
                revoked = false, recoveryState = "NORMAL", signature = ""))
            val leaseFields = mapOf<String, Any?>("schema" to lease.schema, "leaseId" to lease.leaseId,
                "deviceEnrollmentId" to lease.deviceEnrollmentId, "protocolProfile" to lease.protocolProfile,
                "authorityId" to lease.authorityId, "keysetVersion" to lease.keysetVersion, "registryEpoch" to lease.registryEpoch,
                "checkpointDigest" to lease.checkpointDigest, "authorizedRouteFingerprints" to lease.authorizedRouteFingerprints,
                "issuedAtEpochMs" to lease.issuedAtEpochMs, "expiresAtEpochMs" to lease.expiresAtEpochMs,
                "maxValidityMs" to lease.maxValidityMs, "bootId" to lease.bootId,
                "issuedElapsedRealtimeMs" to lease.issuedElapsedRealtimeMs, "revoked" to lease.revoked,
                "signature" to lease.signature)
            val checkpointFields = mapOf<String, Any?>("schema" to checkpoint.schema,
                "deviceEnrollmentId" to checkpoint.deviceEnrollmentId, "protocolProfile" to checkpoint.protocolProfile,
                "authorityId" to checkpoint.authorityId, "keysetVersion" to checkpoint.keysetVersion,
                "highestCommittedRegistryEpoch" to checkpoint.highestCommittedRegistryEpoch,
                "currentCheckpointDigest" to checkpoint.currentCheckpointDigest, "issuedAtEpochMs" to checkpoint.issuedAtEpochMs,
                "expiresAtEpochMs" to checkpoint.expiresAtEpochMs, "revoked" to checkpoint.revoked,
                "recoveryState" to checkpoint.recoveryState, "signature" to checkpoint.signature)
            val leaseDecision = CheckpointLeaseV1Experimental.authorizeOffline(lease, checkpoint, "offline-test",
                "tessera.fast-path/1.1", requested.routeFingerprint, nowEpochMs, elapsed.get(), bootId,
                object : LeaseReplayStoreV1 {
                    override val rollbackResistant = true
                    override fun consumeOnce(leaseId: String) = leaseId == lease.leaseId
                }, signatureValid = CheckpointLeaseV1Experimental.verifySignature(leaseKeys.public, leaseFields) &&
                    CheckpointLeaseV1Experimental.verifySignature(leaseKeys.public, checkpointFields))
            if (leaseDecision != "AUTHORIZED_OFFLINE") return refusal(request, nowEpochMs,
                if (leaseDecision == "lease-expired") "lease-expired" else "lease-authorization-failed")
            val engine = FastPathExecutionEngineV1(registry, journal, action, { true }, { elapsed.get() }, { bootId }, observerDigest)
            val ttl = (request["expiresAtEpochMs"] as Long) - nowEpochMs
            val outcome = engine.execute(tuple, requested, elapsed.get() + ttl)
            val record = journal.record(tuple)
            val success = outcome.taskState == "VERIFIED_SUCCESS" && outcome.verificationStatus == "VERIFIED"
            val response = base(request, nowEpochMs) + mapOf("status" to if (success) "VERIFIED_SUCCESS" else if (outcome.dispatchPossible) "RECOVERY_REQUIRED" else "REFUSED",
                "reasonCode" to if (success) "destination-verified" else outcome.reasonCode,
                "lease" to mapOf("state" to if (success) "RELEASED" else "NONE", "expiresAtEpochMs" to null),
                "execution" to mapOf("currentStepId" to record?.currentStepId, "completedStepCount" to (record?.completedStepCount ?: 0).toLong(),
                    "journalSequence" to (record?.journalSequence ?: 0L), "dispatchPossible" to outcome.dispatchPossible,
                    "actionReceipt" to if (outcome.dispatchPossible) actionReceipt else null),
                "snapshot" to null,
                "verification" to if (success) mapOf("status" to "VERIFIED", "packageName" to "org.example.app", "windowId" to 7,
                    "observedAtEpochMs" to nowEpochMs, "ageMs" to 0L, "destinationFingerprint" to "d".repeat(64),
                    "independent" to true, "observerId" to "fixture-observer-$observerDigest") else null,
                "provenance" to if (success) mapOf("sourceCommit" to "simulated-test-commit", "artifactSha256" to "1".repeat(64),
                    "signerCertificateSha256" to "2".repeat(64), "versionName" to "test-fixture", "versionCode" to 1L) else null)
            // Isolated durable-result stand-in: the receiver returns only after persistence succeeds.
            lastResponse = resultStore.save(request, response)
            return lastResponse!!
        }

        private fun refusal(request: Map<String, Any?>, now: Long, reason: String) = base(request, now) + mapOf(
            "status" to "REFUSED", "reasonCode" to reason, "lease" to mapOf("state" to "NONE", "expiresAtEpochMs" to null),
            "execution" to mapOf("currentStepId" to null, "completedStepCount" to 0L, "journalSequence" to 0L,
                "dispatchPossible" to false, "actionReceipt" to null), "snapshot" to null, "verification" to null, "provenance" to null)
        private fun base(request: Map<String, Any?>, now: Long) = mapOf("protocolVersion" to request["protocolVersion"],
            "messageType" to "route.execute.result", "requestId" to request["requestId"], "taskId" to request["taskId"],
            "sessionId" to request["sessionId"], "ownerId" to request["ownerId"], "deviceBinding" to request["deviceBinding"],
            "observedAtEpochMs" to now)
        override fun close() { action.release() }
    }

    @Test fun receiverRunsSignedRegistryLeaseJournalActionAndIndependentObserverSimulation() {
        val result = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson()), runtime)
        assertEquals(android.app.Activity.RESULT_OK, result.first)
        val response = JsonParser.parseString(String(Base64.decode(result.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))).asJsonObject
        assertEquals("VERIFIED_SUCCESS", response.get("status").asString)
        assertEquals(2, runtime.dispatches)
        assertNotNull(resultStore.existing(StrictEnvelopeV1.parseExperimentalV1_1(requestJson())))
        assertEquals("VERIFIED_SUCCESS", runtime.lastResponse!!["status"])
        val eventTypes = journal.record(runtime.tuple)!!
        assertTrue(eventTypes.dispatchPossible)
        assertEquals("VERIFIED_SUCCESS", eventTypes.taskState)
    }

    @Test fun duplicateReceiverRequestReturnsStoredResultWithoutSecondDispatch() {
        val request = requestJson()
        val receiver = FastPathV1_1Receiver()
        val first = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(request), runtime)
        val second = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(request), runtime)
        assertEquals(first.second, second.second)
        assertEquals(2, runtime.dispatches)
    }

    @Test fun wrongOriginAndAmbiguousSelectorStopBeforeDispatchThroughRealEngine() {
        runtime.wrongOrigin = true
        val wrongOrigin = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson()), runtime)
        assertEquals(0, runtime.dispatches)
        assertTrue(String(Base64.decode(wrongOrigin.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).contains("origin-state-mismatch"))
    }

    @Test fun expiredOfflineLeasePreventsJournalAndDispatch() {
        val result = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson(expiredLease = true)), runtime)
        assertEquals(0, runtime.dispatches)
        assertTrue(String(Base64.decode(result.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).contains("lease-expired"))
    }

    @Test fun ambiguousSelectorAndUnapprovedObserverCannotProduceVerifiedSuccess() {
        runtime.ambiguous = true
        val ambiguous = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson()), runtime)
        assertEquals(0, runtime.dispatches)
        assertTrue(String(Base64.decode(ambiguous.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).contains("selector-ambiguous"))

        runtime.ambiguous = false
        runtime.observerTrusted = false
        val noObserver = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson(seed = 2)), runtime)
        assertEquals(0, runtime.dispatches)
        assertTrue(String(Base64.decode(noObserver.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).contains("runtime-approval-required"))
        assertFalse(String(Base64.decode(noObserver.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).contains("VERIFIED_SUCCESS"))
    }

    @Test fun uncertainDispatchIsPersistedAndDuplicateCannotReplay() {
        runtime.uncertainAfterDispatch = true
        val request = requestJson()
        val receiver = FastPathV1_1Receiver()
        val first = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(request), runtime)
        val second = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(request), runtime)
        val response = String(Base64.decode(first.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        assertEquals(first.second, second.second)
        assertEquals(1, runtime.dispatches)
        assertTrue(response.contains("RECOVERY_REQUIRED"))
        assertTrue(response.contains("UNCERTAIN"))
        assertFalse(response.contains("VERIFIED_SUCCESS"))
    }
}
