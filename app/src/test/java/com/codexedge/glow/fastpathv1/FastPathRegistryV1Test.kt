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
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class FastPathRegistryV1Test {
    private val now = 1_790_000_000_000L
    private lateinit var context: Context
    private lateinit var pair: KeyPair
    private lateinit var trust: AuthorityTrustV1
    private val databaseName = "registry-test-${System.nanoTime()}.db"
    private val markerName = "fastpath-registry-v1.present"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        pair = generator.generateKeyPair()
        trust = AuthorityTrustV1.forTests(listOf(AuthorityAnchorV1("root-a",
            pair.public as java.security.interfaces.RSAPublicKey, 1L, null)))
    }

    @After fun tearDown() {
        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
    }

    private fun registry(clock: () -> Long = { now }) =
        FastPathRegistryV1(context, trust, clock, databaseName)

    private fun routeDefinition(label: String = "Open Guide"): Map<String, Any?> {
        val hash = "a".repeat(64)
        return linkedMapOf(
            "packageName" to "dev.androidroute.benchmark",
            "appConstraint" to mapOf("versionName" to "1.0", "versionCode" to 1L,
                "signerCertificateSha256" to hash),
            "origin" to mapOf("stateFingerprint" to "b".repeat(64), "requiredMarkers" to listOf("Home")),
            "destination" to mapOf("stateFingerprint" to "c".repeat(64), "requiredMarkers" to listOf("Guide")),
            "windowPolicy" to "same_window",
            "steps" to listOf(mapOf("stepId" to "open-guide",
                "selector" to mapOf("kind" to "text", "value" to label),
                "uniquenessPolicy" to "exactly_one_actionable_target",
                "expectedBefore" to mapOf("stateFingerprint" to "b".repeat(64), "requiredMarkers" to listOf("Home")),
                "expectedAfter" to mapOf("stateFingerprint" to "c".repeat(64), "requiredMarkers" to listOf("Guide")))))
    }

    private fun signedPromotion(
        epoch: Long = 1,
        id: String = "benchmark.open_guide",
        version: Long = 1,
        issued: Long = now - 1_000,
        expires: Long = now + 1_000,
        definition: Map<String, Any?> = routeDefinition(),
        authority: String = "root-a"
    ): Map<String, Any?> {
        val unsigned = linkedMapOf<String, Any?>(
            "authorityKeyId" to authority, "registryEpoch" to epoch,
            "issuedAtEpochMs" to issued, "expiresAtEpochMs" to expires,
            "routeId" to id, "routeVersion" to version,
            "routeFingerprint" to CanonicalRouteV1.fingerprint(id, version, definition),
            "routeDefinition" to definition)
        return unsigned + ("signature" to sign(unsigned))
    }

    private fun signedRevocation(epoch: Long, promotion: Map<String, Any?>,
                                authority: String = "root-a"): Map<String, Any?> {
        val unsigned = linkedMapOf<String, Any?>(
            "authorityKeyId" to authority, "registryEpoch" to epoch,
            "issuedAtEpochMs" to now - 1_000,
            "routeId" to promotion.getValue("routeId"),
            "routeVersion" to promotion.getValue("routeVersion"),
            "routeFingerprint" to promotion.getValue("routeFingerprint"),
            "reasonCode" to "route-retired")
        return unsigned + ("signature" to sign(unsigned))
    }

    private fun operationResponse(requestId: String, messageType: String, status: String,
                                  reason: String, epoch: Long = 1) = CanonicalRouteV1.encode(mapOf(
        "protocolVersion" to StrictEnvelopeV1.VERSION,
        "messageType" to messageType,
        "requestId" to requestId,
        "taskId" to "00000000-0000-4000-8000-000000000090",
        "sessionId" to "00000000-0000-4000-8000-000000000091",
        "ownerId" to "00000000-0000-4000-8000-000000000092",
        "deviceBinding" to mapOf("deviceSerial" to "fixture-device",
            "companionPackage" to "com.codexedge.glow", "companionVersionCode" to 18L,
            "signerCertificateSha256" to "a".repeat(64)),
        "status" to status, "reasonCode" to reason,
        "observedAtEpochMs" to now,
        "payload" to mapOf("lifecycle" to if (status == "REFUSED") "REFUSED" else status,
            "registryEpoch" to epoch)))

    private fun sign(unsigned: Map<String, Any?>): String {
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(pair.private)
        signer.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    @Test fun commitsCryptographicallyAuthorizedPromotionAndFindsItAfterRestart() {
        val record = signedPromotion()
        registry().use { assertTrue(it.applyPromotion(record).accepted) }
        registry().use { store ->
            val route = store.findPromoted("benchmark.open_guide", 1, record.getValue("routeFingerprint") as String)
            assertNotNull(route)
            assertEquals(record["routeFingerprint"], route?.routeFingerprint)
            assertEquals(1L, store.highestEpoch())
            assertFalse(store.applyPromotion(record).accepted)
        }
    }

    @Test fun rejectsTamperingWrongAuthorityAndMissingAnchors() {
        val valid = signedPromotion()
        val badDefinition = routeDefinition().toMutableMap().apply { put("windowPolicy", "allow-new-window") }
        val validRevocation = signedRevocation(1, valid)
        val tamperedRevocation = validRevocation.toMutableMap().apply {
            val signature = getValue("signature") as String
            put("signature", (if (signature[0] == 'A') "B" else "A") + signature.drop(1))
        }
        registry().use { store ->
            assertFalse(store.applyPromotion(valid + ("routeId" to "other.route")).accepted)
            assertFalse(store.applyPromotion(valid + ("signature" to "A".repeat(512))).accepted)
            assertFalse(store.applyPromotion(signedPromotion(epoch = 2, authority = "wrong-root")).accepted)
            assertFalse(store.applyPromotion(signedPromotion(definition = badDefinition)).accepted)
            assertFalse(store.applyRevocation(tamperedRevocation).accepted)
            assertFalse(store.applyRevocation(signedRevocation(1, valid, authority = "wrong-root")).accepted)
        }
        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
        FastPathRegistryV1(context, AuthorityTrustV1.unconfigured(), { now }, databaseName).use { store ->
            assertEquals("no-trusted-authority", store.applyPromotion(valid).reasonCode)
        }
    }

    @Test fun rejectsFingerprintMismatchAndMalformedFingerprintOnRestart() {
        val record = signedPromotion().toMutableMap()
        record["routeFingerprint"] = "0".repeat(64)
        // The signature still covers the original digest.
        registry().use { assertFalse(it.applyPromotion(record).accepted) }
    }

    @Test fun promotionReceiptCommitsAtomicallyAndReplaysExactResponseAfterRestart() {
        val record = signedPromotion()
        val requestId = "00000000-0000-4000-8000-000000000099"
        val receipt = RegistryOperationReceiptV1(requestId, "route.promote", "a".repeat(64),
            operationResponse(requestId, "route.promote.result", "PROMOTED", "promoted"))
        registry().use { assertTrue(it.applyPromotion(record, receipt).accepted) }
        registry().use { store ->
            val replay = store.applyPromotion(record, receipt)
            assertTrue(replay.accepted)
            assertTrue(replay.replayed)
            assertEquals(receipt.responseJson, replay.responseJson)
            assertEquals(1L, store.highestEpoch())
            val conflict = store.applyPromotion(record, receipt.copy(requestDigest = "b".repeat(64)))
            assertFalse(conflict.accepted)
            assertEquals("request-id-body-mismatch", conflict.reasonCode)
            assertEquals(1L, store.highestEpoch())
        }
    }

    @Test fun durableRefusalReceiptCannotBecomeAcceptedAfterClockAdvances() {
        val future = signedPromotion(issued = now + 200, expires = now + 60_000)
        val requestId = "00000000-0000-4000-8000-000000000098"
        val receipt = RegistryOperationReceiptV1(requestId, "route.promote", "c".repeat(64),
            operationResponse(requestId, "route.promote.result", "PROMOTED", "promoted"))
        val refusalFactory: (String, Long) -> String = { reason, epoch ->
            operationResponse(requestId, "route.promote.result", "REFUSED", reason, epoch)
        }
        registry().use { store ->
            val refused = store.applyPromotion(future, receipt, refusalFactory)
            assertFalse(refused.accepted)
            assertEquals("authorization-failed", refused.reasonCode)
            assertEquals(refusalFactory("authorization-failed", 0), refused.responseJson)
        }
        val later = FastPathRegistryV1(context, trust, { now + 500 }, databaseName)
        later.use { store ->
            val replay = store.applyPromotion(future, receipt, refusalFactory)
            assertFalse(replay.accepted)
            assertTrue(replay.replayed)
            assertEquals(refusalFactory("authorization-failed", 0), replay.responseJson)
            assertEquals(0L, store.highestEpoch())
        }
    }

    @Test fun apkAuthorityAssetIsExplicitlyUnconfiguredAtThisBaseline() {
        val packaged = AuthorityTrustV1.fromApkAsset(context)
        assertFalse(packaged.isConfigured)
        assertTrue(packaged.keysForEpoch(1).isEmpty())
    }

    @Test fun expirationFutureIssueAndValidityBoundariesAreEnforced() {
        registry().use { store ->
            assertFalse(store.applyPromotion(signedPromotion(issued = now - 100, expires = now)).accepted)
            assertFalse(store.applyPromotion(signedPromotion(issued = now + 1, expires = now + 2_000)).accepted)
            assertFalse(store.applyPromotion(signedPromotion(issued = now - 86_400_001, expires = now + 1)).accepted)
        }
        val boundary = signedPromotion(issued = now - 86_399_000, expires = now + 1_000)
        registry().use { assertTrue(it.applyPromotion(boundary).accepted) }
        val maxLifetime = signedPromotion(epoch = 2, id = "benchmark.max_lifetime",
            issued = now - 1, expires = now + 86_399_999)
        registry().use { assertTrue(it.applyPromotion(maxLifetime).accepted) }
        val expiredExact = FastPathRegistryV1(context, trust, { now + 1_000 }, databaseName)
        expiredExact.use { assertNull(it.findPromoted("benchmark.open_guide", 1,
            boundary.getValue("routeFingerprint") as String)) }
    }

    @Test fun rejectsEqualOlderEpochReplayAndPersistsRevocation() {
        val promotion = signedPromotion()
        val revocation = signedRevocation(2, promotion)
        registry().use { store ->
            assertTrue(store.applyPromotion(promotion).accepted)
            assertFalse(store.applyPromotion(promotion).accepted)
            assertFalse(store.applyPromotion(signedPromotion(epoch = 1, id = "benchmark.other")).accepted)
            assertTrue(store.applyRevocation(revocation).accepted)
            assertNull(store.findPromoted("benchmark.open_guide", 1,
                promotion.getValue("routeFingerprint") as String))
        }
        registry().use { store ->
            assertEquals(2L, store.highestEpoch())
            assertNull(store.findPromoted("benchmark.open_guide", 1,
                promotion.getValue("routeFingerprint") as String))
            assertEquals("REVOCATION", store.registryEvents().last()["operation"])
        }
    }

    @Test fun sameEpochConcurrentWritersCannotBothCommit() {
        val first = signedPromotion(id = "benchmark.route_a")
        val second = signedPromotion(id = "benchmark.route_b")
        val stores = listOf(registry(), registry())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf(
                Callable { stores[0].applyPromotion(first).accepted },
                Callable { stores[1].applyPromotion(second).accepted }
            )).map { it.get() }
            assertEquals(1, results.count { it })
            registry().use { assertEquals(1L, it.highestEpoch()) }
        } finally {
            stores.forEach { it.close() }
            pool.shutdownNow()
        }
    }

    @Test fun transactionRollbackLeavesEpochAndRouteUnchanged() {
        val record = signedPromotion()
        registry().use { it.highestEpoch() }
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TRIGGER fail_registry_event BEFORE INSERT ON registry_events BEGIN SELECT RAISE(ABORT,'test-fault'); END")
        db.close()
        registry().use { store -> assertThrows(RegistryStorageException::class.java) { store.applyPromotion(record) } }
        val cleanDb = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        cleanDb.execSQL("DROP TRIGGER fail_registry_event")
        cleanDb.close()
        registry().use { store ->
            assertEquals(0L, store.highestEpoch())
            assertNull(store.findPromoted("benchmark.open_guide", 1, record["routeFingerprint"] as String))
            assertTrue(store.applyPromotion(record).accepted)
        }
    }

    @Test fun damagedDatabaseAndMissingPreviouslyInitializedDatabaseFailClosed() {
        registry().use { assertTrue(it.applyPromotion(signedPromotion()).accepted) }
        context.deleteDatabase(databaseName)
        assertThrows(RegistryStorageException::class.java) { registry().use { it.highestEpoch() } }
        context.getDatabasePath(databaseName).writeText("not-a-sqlite-database")
        assertThrows(RegistryStorageException::class.java) { registry().use { it.highestEpoch() } }
    }

    @Test fun alteredRouteProjectionAndTruncatedEpochLogFailClosed() {
        val promotion = signedPromotion()
        registry().use { assertTrue(it.applyPromotion(promotion).accepted) }
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.execSQL("UPDATE routes SET authorization_status='REVOKED'")
        db.close()
        assertThrows(RegistryStorageException::class.java) { registry().use { it.highestEpoch() } }

        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
        registry().use { assertTrue(it.applyPromotion(promotion).accepted) }
        val truncated = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        truncated.execSQL("DELETE FROM registry_events")
        truncated.close()
        assertThrows(RegistryStorageException::class.java) { registry().use { it.highestEpoch() } }
    }
}
