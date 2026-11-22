package com.codexedge.glow.fastpathv1

import android.content.Context
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
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FastPathExecutionEngineV1Test {
    private val now = 1_790_000_000_000L
    private var wallNow = now
    private val elapsed = 20_000L
    private lateinit var context: Context
    private lateinit var pair: KeyPair
    private lateinit var trust: AuthorityTrustV1
    private lateinit var registry: FastPathRegistryV1
    private lateinit var journal: FastPathExecutionJournalV1
    private val registryDb = "engine-registry-${System.nanoTime()}.db"
    private val journalDb = "engine-journal-${System.nanoTime()}.db"
    private lateinit var requestTuple: ExecutionTupleV1
    private var latestRoute: PromotedRouteV1? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        pair = generator.generateKeyPair()
        trust = AuthorityTrustV1.forTests(listOf(AuthorityAnchorV1("engine-root",
            pair.public as java.security.interfaces.RSAPublicKey, 1, null)))
        wallNow = now
        registry = FastPathRegistryV1(context, trust, { wallNow }, registryDb)
        journal = FastPathExecutionJournalV1(context, journalDb)
        requestTuple = tuple(1)
    }

    @After fun tearDown() {
        registry.close(); journal.close()
        context.deleteDatabase(registryDb); context.deleteDatabase(journalDb)
        context.filesDir.resolve("fastpath-registry-v1.present").delete()
        context.filesDir.resolve("$journalDb.present").delete()
    }

    private fun tuple(seed: Int) = ExecutionTupleV1(
        "00000000-0000-4000-8000-${seed.toString().padStart(12, '0')}",
        "00000000-0000-4000-8000-${(seed + 10).toString().padStart(12, '0')}",
        "00000000-0000-4000-8000-${(seed + 20).toString().padStart(12, '0')}",
        "00000000-0000-4000-8000-${(seed + 30).toString().padStart(12, '0')}", "fixture-device")

    private fun definition(twoSteps: Boolean = false): Map<String, Any?> {
        val home = "b".repeat(64)
        val middle = "c".repeat(64)
        val destination = if (twoSteps) "d".repeat(64) else middle
        val first = mapOf("stepId" to "open-guide", "selector" to mapOf("kind" to "text", "value" to "Open Guide"),
            "uniquenessPolicy" to "exactly_one_actionable_target",
            "expectedBefore" to mapOf("stateFingerprint" to home, "requiredMarkers" to listOf("Home")),
            "expectedAfter" to mapOf("stateFingerprint" to middle, "requiredMarkers" to listOf("Guide")))
        val second = mapOf("stepId" to "open-details", "selector" to mapOf("kind" to "contentDescription", "value" to "Open Details"),
            "uniquenessPolicy" to "exactly_one_actionable_target",
            "expectedBefore" to mapOf("stateFingerprint" to middle, "requiredMarkers" to listOf("Guide")),
            "expectedAfter" to mapOf("stateFingerprint" to destination, "requiredMarkers" to listOf("Details")))
        return linkedMapOf("packageName" to "dev.androidroute.benchmark",
            "appConstraint" to mapOf("versionName" to "1.0", "versionCode" to 1L,
                "signerCertificateSha256" to "a".repeat(64)),
            "origin" to mapOf("stateFingerprint" to home, "requiredMarkers" to listOf("Home")),
            "destination" to mapOf("stateFingerprint" to destination,
                "requiredMarkers" to listOf(if (twoSteps) "Details" else "Guide")),
            "windowPolicy" to "same_window", "steps" to if (twoSteps) listOf(first, second) else listOf(first))
    }

    private fun signedPromotion(definition: Map<String, Any?> = definition(), epoch: Long = 1,
                                issued: Long = now - 1_000, expires: Long = now + 60_000): Map<String, Any?> {
        val unsigned = linkedMapOf<String, Any?>("authorityKeyId" to "engine-root",
            "registryEpoch" to epoch, "issuedAtEpochMs" to issued, "expiresAtEpochMs" to expires,
            "routeId" to "benchmark.open_guide", "routeVersion" to 1L,
            "routeFingerprint" to CanonicalRouteV1.fingerprint("benchmark.open_guide", 1, definition),
            "routeDefinition" to definition)
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(pair.private)
        signer.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        return unsigned + ("signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()))
    }

    private fun signedRevocation(route: PromotedRouteV1): Map<String, Any?> {
        val unsigned = linkedMapOf<String, Any?>("authorityKeyId" to "engine-root",
            "registryEpoch" to 2L, "issuedAtEpochMs" to now - 1_000,
            "routeId" to route.routeId, "routeVersion" to route.routeVersion,
            "routeFingerprint" to route.routeFingerprint, "reasonCode" to "route-retired")
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(pair.private)
        signer.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        return unsigned + ("signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()))
    }

    private fun promote(def: Map<String, Any?> = definition(), issued: Long = now - 1_000,
                        expires: Long = now + 60_000): PromotedRouteV1 {
        val record = signedPromotion(def, issued = issued, expires = expires)
        assertTrue(registry.applyPromotion(record).accepted)
        return registry.findPromoted("benchmark.open_guide", 1, record.getValue("routeFingerprint") as String)!!
            .also { latestRoute = it }
    }

    private fun target(window: Int = 42, label: String = "Open Guide", description: String? = null,
                       enabled: Boolean = true, visible: Boolean = true, actionable: Boolean = true,
                       opaqueHandle: String = "target-$label-$window") =
        VisibleTargetV1(opaqueHandle, "dev.androidroute.benchmark", window,
            visible, enabled, actionable, label, description, null)

    private fun evidence(state: String, marker: String, window: Int = 42,
                         targets: List<VisibleTargetV1> = emptyList(), captured: Long = elapsed) =
        UiEvidenceV1("dev.androidroute.benchmark", window, window, window, captured,
            complete = true, truncated = false, stateFingerprint = state,
            targets = listOf(VisibleTargetV1("marker-$marker", "dev.androidroute.benchmark", window,
                true, true, false, marker, null, null)) + targets)

    private open class SimulatedPort(
        private val states: MutableList<UiEvidenceV1>,
        var identity: AppIdentityV1 = AppIdentityV1("dev.androidroute.benchmark", "1.0", 1, "a".repeat(64)),
        var verification: VerificationEvidenceV1? = null
    ) : FastPathActionPortV1 {
        var dispatchCount = 0
        var captureCount = 0
        var verifyCount = 0
        override fun installedApp(packageName: String) = identity
        override fun captureFresh(packageName: String, expectedWindowId: Int?, maxNodes: Int, maxAgeMs: Int): UiEvidenceV1? {
            captureCount++
            return if (states.isEmpty()) null else states.removeAt(0)
        }
        override fun dispatchClick(target: VisibleTargetV1, expectedPackage: String, expectedWindowId: Int): Boolean {
            dispatchCount++
            return true
        }
        override fun independentlyVerify(route: ExecutableRouteV1, expectedWindowId: Int, maxAgeMs: Int): VerificationEvidenceV1? {
            verifyCount++
            return verification
        }
    }

    private fun engine(port: FastPathActionPortV1, enabled: Boolean = true, clock: () -> Long = { elapsed }) =
        FastPathExecutionEngineV1(registry, journal, port, { enabled }, clock, { "boot-a" }, "e".repeat(64))

    private fun request(route: PromotedRouteV1) = RequestedRouteV1(route.routeId,
        route.routeVersion, route.routeFingerprint, route.routeDefinition)

    private fun validVerifier(window: Int = 42, fingerprint: String = "c".repeat(64), at: Long = elapsed,
                              markers: Set<String> = setOf("Guide")) =
        VerificationEvidenceV1(true, "dev.androidroute.benchmark", window, fingerprint, at,
            0, "separate-observer-process", "e".repeat(64), markers)

    @Test fun authorizedRouteExecutesMultipleStepsLocallyAndRequiresIndependentVerification() {
        val route = promote(definition(twoSteps = true))
        val home = "b".repeat(64); val middle = "c".repeat(64); val destination = "d".repeat(64)
        val states = mutableListOf(
            evidence(home, "Home", targets = listOf(target())),
            evidence(middle, "Guide", targets = listOf(target(description = "Open Details"))),
            evidence(middle, "Guide", targets = listOf(target(description = "Open Details"))),
            evidence(destination, "Details"))
        val port = SimulatedPort(states, verification = validVerifier(fingerprint = destination,
            markers = setOf("Details")))
        val result = engine(port).execute(requestTuple, request(route))
        assertEquals("VERIFIED_SUCCESS", result.taskState)
        assertEquals("VERIFIED", result.verificationStatus)
        assertEquals(2, result.completedStepCount)
        assertEquals(2, port.dispatchCount)
        assertEquals(1, port.verifyCount)
    }

    @Test fun disabledRuntimeCannotReachAccessibilityDispatch() {
        val route = promote()
        val port = SimulatedPort(mutableListOf())
        val result = engine(port, enabled = false).execute(requestTuple, request(route))
        assertEquals("REFUSED", result.taskState)
        assertEquals("execution-disabled", result.reasonCode)
        assertEquals(0, port.dispatchCount)
    }

    @Test fun concurrentSameRequestFaultInjectionProducesZeroDuplicateDispatches() {
        val route = promote()
        val home = "b".repeat(64)
        val destination = "c".repeat(64)
        val bothAtPreflight = CountDownLatch(2)
        val port = object : SimulatedPort(mutableListOf(
            evidence(home, "Home", targets = listOf(target())), evidence(destination, "Guide")),
            verification = validVerifier(fingerprint = destination)) {
            override fun installedApp(packageName: String): AppIdentityV1 {
                bothAtPreflight.countDown()
                check(bothAtPreflight.await(5, TimeUnit.SECONDS))
                return super.installedApp(packageName)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll(listOf(
                Callable { engine(port).execute(requestTuple, request(route)) },
                Callable { engine(port).execute(requestTuple, request(route)) }
            )).map { it.get() }
            assertEquals(1, port.dispatchCount)
            assertEquals(1, outcomes.count { it.taskState == "VERIFIED_SUCCESS" })
        } finally { pool.shutdownNow() }
    }

    @Test fun unknownRouteAndAppVersionOrSignerDriftAreRejected() {
        val route = promote()
        val port = SimulatedPort(mutableListOf())
        val unknown = request(route).copy(routeId = "benchmark.unknown")
        assertEquals("route-not-authorized", engine(port).execute(tuple(2), unknown).reasonCode)
        port.identity = port.identity.copy(versionCode = 2)
        assertEquals("target-app-identity-mismatch", engine(port).execute(tuple(3), request(route)).reasonCode)
        port.identity = port.identity.copy(versionCode = 1, signerCertificateSha256 = "f".repeat(64))
        assertEquals("target-app-identity-mismatch", engine(port).execute(tuple(4), request(route)).reasonCode)
        assertEquals(0, port.dispatchCount)
    }

    @Test fun exactDefinitionExpiryAndRevocationAreRecheckedAgainstTheRegistry() {
        val route = promote()
        val port = SimulatedPort(mutableListOf())
        val changedDefinition = definition().toMutableMap().apply { put("packageName", "other.app") }
        assertEquals("route-definition-mismatch", engine(port).execute(requestTuple,
            request(route).copy(routeDefinition = changedDefinition)).reasonCode)
        wallNow = now + 60_000
        assertEquals("route-not-authorized", engine(port).execute(tuple(21), request(route)).reasonCode)
        wallNow = now
        assertTrue(registry.applyRevocation(signedRevocation(route)).accepted)
        assertEquals("route-not-authorized", engine(port).execute(tuple(22), request(route)).reasonCode)
        assertEquals(0, port.dispatchCount)
    }

    @Test fun staleWrongPackageWindowAndUnexpectedOriginNeverDispatch() {
        val route = promote()
        val home = "b".repeat(64)
        fun run(port: SimulatedPort, seed: Int) = engine(port, clock = { elapsed + 3_000 })
            .execute(tuple(seed), request(route))
        val stale = SimulatedPort(mutableListOf(evidence(home, "Home", targets = listOf(target()), captured = 0)))
        assertEquals("stale-or-ambiguous-evidence", run(stale, 5).reasonCode)
        val wrongPackageEvidence = evidence(home, "Home", targets = listOf(target())).copy(packageName = "other.app")
        val packagePort = SimulatedPort(mutableListOf(wrongPackageEvidence))
        assertEquals("stale-or-ambiguous-evidence", run(packagePort, 6).reasonCode)
        val wrongWindow = evidence(home, "Home", targets = listOf(target())).copy(windowIdAfter = 43)
        val windowPort = SimulatedPort(mutableListOf(wrongWindow))
        val resultWindow = engine(windowPort).execute(tuple(7), request(route))
        assertEquals("stale-or-ambiguous-evidence", resultWindow.reasonCode)
        val unexpected = evidence("f".repeat(64), "Other", targets = listOf(target()))
        val originPort = SimulatedPort(mutableListOf(unexpected))
        assertEquals("origin-state-mismatch", engine(originPort).execute(tuple(8), request(route)).reasonCode)
        assertEquals(0, stale.dispatchCount + packagePort.dispatchCount + windowPort.dispatchCount + originPort.dispatchCount)
    }

    @Test fun zeroMultipleDisabledAndInvisibleTargetsAreRefusedWithoutGuessing() {
        val route = promote()
        val home = "b".repeat(64)
        val zero = SimulatedPort(mutableListOf(evidence(home, "Home")))
        assertEquals("selector-no-match", engine(zero).execute(tuple(9), request(route)).reasonCode)
        val multiple = SimulatedPort(mutableListOf(evidence(home, "Home", targets = listOf(
            target(opaqueHandle = "target-a"), target(opaqueHandle = "target-b")))))
        assertEquals("selector-ambiguous", engine(multiple).execute(tuple(10), request(route)).reasonCode)
        val disabled = SimulatedPort(mutableListOf(evidence(home, "Home", targets = listOf(target(enabled = false)))))
        assertEquals("selector-no-match", engine(disabled).execute(tuple(11), request(route)).reasonCode)
        val invisible = SimulatedPort(mutableListOf(evidence(home, "Home", targets = listOf(target(visible = false)))))
        assertEquals("selector-no-match", engine(invisible).execute(tuple(12), request(route)).reasonCode)
        assertEquals(0, zero.dispatchCount + multiple.dispatchCount + disabled.dispatchCount + invisible.dispatchCount)
    }

    @Test fun uncertainDispatchAndFailedAfterStateAreNeverRetried() {
        val route = promote()
        val home = "b".repeat(64)
        val target = target()
        val port = object : SimulatedPort(mutableListOf(
            evidence(home, "Home", targets = listOf(target)), evidence("f".repeat(64), "Wrong"))) {
            override fun dispatchClick(target: VisibleTargetV1, expectedPackage: String, expectedWindowId: Int): Boolean {
                dispatchCount++
                throw IllegalStateException("fault after possible dispatch")
            }
        }
        val result = engine(port).execute(tuple(13), request(route))
        assertEquals("RECOVERY_REQUIRED", result.taskState)
        assertTrue(result.dispatchPossible)
        assertEquals(1, port.dispatchCount)
        assertEquals(0, port.verifyCount)
    }

    @Test fun dispatchJournalCommitFaultPreventsAccessibilityCall() {
        val route = promote()
        assertNull(journal.record(tuple(24)))
        val db = context.openOrCreateDatabase(journalDb, Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TRIGGER reject_dispatch_intent BEFORE INSERT ON journal_events WHEN NEW.event_type='DISPATCH_POSSIBLE' BEGIN SELECT RAISE(ABORT,'fault-injected'); END")
        db.close()
        val port = SimulatedPort(mutableListOf(evidence("b".repeat(64), "Home",
            targets = listOf(target()))))
        val result = engine(port).execute(tuple(24), request(route))
        assertEquals("REFUSED", result.taskState)
        assertEquals("dispatch-intent-not-committed", result.reasonCode)
        assertFalse(result.dispatchPossible)
        assertEquals(0, port.dispatchCount)
    }

    @Test fun unexpectedIntermediateStateStopsBeforeTheNextStep() {
        val route = promote(definition(twoSteps = true))
        val port = SimulatedPort(mutableListOf(
            evidence("b".repeat(64), "Home", targets = listOf(target())),
            evidence("f".repeat(64), "Unexpected"),
            evidence("c".repeat(64), "Guide", targets = listOf(target(description = "Open Details")))))
        val result = engine(port).execute(tuple(23), request(route))
        assertEquals("RECOVERY_REQUIRED", result.taskState)
        assertEquals("expected-after-unverified", result.reasonCode)
        assertEquals(1, port.dispatchCount)
        assertEquals(2, port.captureCount)
    }

    @Test fun missingStaleOrWrongDestinationVerificationNeverClaimsSuccess() {
        val route = promote()
        val home = "b".repeat(64)
        val destination = "c".repeat(64)
        fun run(seed: Int, verification: VerificationEvidenceV1?) : ExecutionOutcomeV1 {
            val port = SimulatedPort(mutableListOf(
                evidence(home, "Home", targets = listOf(target())),
                evidence(destination, "Guide")), verification = verification)
            val result = engine(port).execute(tuple(seed), request(route))
            assertEquals(1, port.dispatchCount)
            assertEquals("RECOVERY_REQUIRED", result.taskState)
            assertTrue(result.dispatchPossible)
            assertNotEquals("VERIFIED", result.verificationStatus)
            return result
        }
        assertEquals("destination-not-independently-verified", run(14, null).reasonCode)
        assertEquals("destination-not-independently-verified", run(15,
            validVerifier(fingerprint = "f".repeat(64))).reasonCode)
        assertEquals("destination-not-independently-verified", run(16,
            validVerifier().copy(packageName = "other.app")).reasonCode)
        assertEquals("destination-not-independently-verified", run(17,
            validVerifier().copy(windowId = 99)).reasonCode)
        assertEquals("destination-not-independently-verified", run(18,
            validVerifier().copy(ageMs = 2_501, observedElapsedMs = elapsed - 2_501)).reasonCode)
        assertEquals("destination-not-independently-verified", run(19,
            validVerifier().copy(observedMarkers = emptySet())).reasonCode)
        assertEquals("destination-not-independently-verified", run(20,
            validVerifier().copy(observerBuildSha256 = "f".repeat(64))).reasonCode)
    }
}
