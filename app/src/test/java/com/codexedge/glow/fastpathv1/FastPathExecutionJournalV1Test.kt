package com.codexedge.glow.fastpathv1

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class FastPathExecutionJournalV1Test {
    private lateinit var context: Context
    private val databaseName = "journal-test-${System.nanoTime()}.db"
    private val markerName get() = "$databaseName.present"
    private val routeFingerprint = "a".repeat(64)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
    }

    @After fun tearDown() {
        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
    }

    private fun journal() = FastPathExecutionJournalV1(context, databaseName)
    private fun tuple(seed: Int, device: String = "fixture-device") = ExecutionTupleV1(
        "00000000-0000-4000-8000-${seed.toString().padStart(12, '0')}",
        "00000000-0000-4000-8000-${(seed + 10).toString().padStart(12, '0')}",
        "00000000-0000-4000-8000-${(seed + 20).toString().padStart(12, '0')}",
        "00000000-0000-4000-8000-${(seed + 30).toString().padStart(12, '0')}", device)

    private fun acquire(store: FastPathExecutionJournalV1, value: ExecutionTupleV1, at: Long = 10_000L) =
        store.acquire(value, "benchmark.open_guide", 1, routeFingerprint, at, "boot-a")

    @Test fun duplicateRequestAndConcurrentOwnersCannotAcquireOrDispatchTwice() {
        val first = tuple(1)
        val second = tuple(2)
        val stores = listOf(journal(), journal())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf(
                Callable { acquire(stores[0], first).acquired },
                Callable { acquire(stores[1], second).acquired }
            )).map { it.get() }
            assertEquals(1, results.count { it })
            val winner = if (results[0]) first else second
            val winnerStore = stores[results.indexOf(true)]
            assertFalse(acquire(winnerStore, winner).acquired)
            val current = winnerStore.record(winner)!!
            assertFalse(winnerStore.commitDispatchPossible(winner, current.fencingToken + 1,
                "step-one", current.journalSequence, 10_001, "boot-a"))
            val raced = if (results[0]) stores[1] else stores[0]
            assertFalse(acquire(raced, if (results[0]) second else first).acquired)
        } finally {
            stores.forEach { it.close() }
            pool.shutdownNow()
        }
    }

    @Test fun leaseExpiryFencingAndRenewalAreCheckedTransactionally() {
        val first = tuple(3)
        val second = tuple(4)
        journal().use { store ->
            val acquired = acquire(store, first)
            assertTrue(acquired.acquired)
            val fence1 = acquired.record!!.fencingToken
            assertTrue(store.renew(first, fence1, 11_000, "boot-a"))
            assertFalse(store.renew(first, fence1, 41_000, "boot-a"))
            val next = acquire(store, second, 42_000)
            assertTrue(next.acquired)
            assertTrue(next.record!!.fencingToken > fence1)
            assertFalse(store.commitDispatchPossible(first, fence1, "old", next.record.journalSequence,
                42_001, "boot-a"))
            assertTrue(store.commitDispatchPossible(second, next.record.fencingToken, "new",
                next.record.journalSequence, 42_001, "boot-a"))
        }
    }

    @Test fun recoveryBeforeDispatchIsSafeAndRecoveryAfterIntentNeverReplays() {
        val before = tuple(5)
        val first = journal()
        val pre = acquire(first, before)
        assertTrue(pre.acquired)
        first.close()
        journal().use { afterRestart ->
            val recovered = afterRestart.recoverExpired(50_001, "boot-a").single()
            assertEquals("CANCELLED", recovered.taskState)
            assertFalse(recovered.dispatchPossible)
            assertFalse(acquire(afterRestart, before, 50_002).acquired)
        }

        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
        val after = tuple(6)
        val second = journal()
        val run = acquire(second, after)
        assertTrue(run.acquired)
        val record = run.record!!
        assertTrue(second.commitDispatchPossible(after, record.fencingToken, "step-one",
            record.journalSequence, 10_001, "boot-a"))
        second.close()
        journal().use { afterRestart ->
            val recovered = afterRestart.recoverExpired(50_001, "boot-a").single()
            assertEquals("RECOVERY_REQUIRED", recovered.taskState)
            assertTrue(recovered.dispatchPossible)
            assertEquals(10_001L, recovered.dispatchPossibleElapsedMs)
            assertFalse(acquire(afterRestart, after, 50_002).acquired)
        }
    }

    @Test fun unresolvedPossibleDispatchFencesDifferentRequestsUntilRecovery() {
        journal().use { store ->
            val first = tuple(70)
            val acquired = acquire(store, first)
            assertTrue(acquired.acquired)
            val record = requireNotNull(acquired.record)
            assertTrue(store.commitDispatchPossible(first, record.fencingToken, "open-guide",
                record.journalSequence, 10_001L, "boot-a"))
            assertTrue(store.finishRecoveryRequired(first, record.fencingToken))

            val competing = acquire(store, tuple(71), at = 10_002L)
            assertFalse(competing.acquired)
            assertEquals("prior-operation-uncertain", competing.reasonCode)
            assertEquals(0, competing.record?.completedStepCount)
        }
    }

    @Test fun cancellationBeforeDispatchCancelsAndAfterBoundaryRemainsUncertain() {
        val before = tuple(7)
        journal().use { store ->
            assertTrue(acquire(store, before).acquired)
            val cancelRequest = before.copy(requestId = "00000000-0000-4000-8000-000000000777")
            val cancelled = store.cancel(cancelRequest)
            assertTrue(cancelled.accepted)
            assertEquals("CANCELLED", cancelled.taskState)
            assertFalse(cancelled.dispatchPossible)
            assertFalse(store.cancel(cancelRequest.copy(ownerId = tuple(99).ownerId)).accepted)
        }

        context.deleteDatabase(databaseName)
        context.filesDir.resolve(markerName).delete()
        val after = tuple(8)
        journal().use { store ->
            val acquired = acquire(store, after)
            val record = acquired.record!!
            assertTrue(store.commitDispatchPossible(after, record.fencingToken, "step-one",
                record.journalSequence, 10_001, "boot-a"))
            val cancelRequest = after.copy(requestId = "00000000-0000-4000-8000-000000000888")
            val cancelled = store.cancel(cancelRequest)
            assertTrue(cancelled.accepted)
            assertTrue(cancelled.stopAfterCurrentStep)
            assertTrue(cancelled.dispatchPossible)
            assertEquals("RECOVERY_REQUIRED", cancelled.taskState)
        }
    }

    @Test fun cancellationRacingWithAcceptedDispatchCannotRestoreRunningOrVerified() {
        val value = tuple(18)
        journal().use { store ->
            val record = acquire(store, value).record!!
            assertTrue(store.commitDispatchPossible(value, record.fencingToken, "step-one",
                record.journalSequence, 10_001, "boot-a"))
            val cancellation = store.cancel(value.copy(
                requestId = "00000000-0000-4000-8000-000000000818"))
            assertTrue(cancellation.accepted)
            assertEquals("RECOVERY_REQUIRED", cancellation.taskState)
            assertTrue(store.recordActionOutcome(value, record.fencingToken, "step-one", "ACCEPTED"))
            assertEquals("RECOVERY_REQUIRED", store.record(value)?.taskState)
            assertFalse(store.recordStepObserved(value, record.fencingToken, "step-one"))
            assertFalse(store.finishVerified(value, record.fencingToken, 1, 10_002, "boot-a"))
        }
    }

    @Test fun crashAfterAcceptedActionButBeforeObservationRemainsRecoveryRequired() {
        val value = tuple(11)
        val beforeCrash = journal()
        val acquired = acquire(beforeCrash, value)
        val record = acquired.record!!
        assertTrue(beforeCrash.commitDispatchPossible(value, record.fencingToken, "step-one",
            record.journalSequence, 10_001, "boot-a"))
        assertTrue(beforeCrash.recordActionOutcome(value, record.fencingToken, "step-one", "ACCEPTED"))
        beforeCrash.close()
        journal().use { afterRestart ->
            val recovered = afterRestart.recoverExpired(50_001, "boot-a").single()
            assertEquals("RECOVERY_REQUIRED", recovered.taskState)
            assertTrue(recovered.dispatchPossible)
            val events = afterRestart.status(value, 0, "0".repeat(64), 64, 50_002, "boot-a")!!.events
            assertTrue(events.any { it.eventType == "ACTION_ACCEPTED" })
            assertFalse(events.any { it.eventType == "TASK_VERIFIED" })
            assertFalse(acquire(afterRestart, value, 50_002).acquired)
        }
    }

    @Test fun journalCursorPagesAreContiguousAndRecoverAfterRestart() {
        val value = tuple(9)
        val statusRequest = value.copy(requestId = "00000000-0000-4000-8000-000000000999")
        journal().use { store ->
            val record = acquire(store, value).record!!
            val first = store.status(statusRequest, 0, "0".repeat(64), 1, 10_001, "boot-a")!!
            assertEquals(1L, first.fromSequence)
            assertEquals(1L, first.throughSequence)
            assertTrue(first.hasMore)
            val second = store.status(statusRequest, 1, first.events.single().eventDigest, 64, 10_001, "boot-a")!!
            assertEquals(2L, second.throughSequence)
            assertFalse(second.hasMore)
            assertEquals(record.journalHeadDigest, second.journalHeadDigest)
            assertEquals(value.requestId, second.record.tuple.requestId)
            assertNull(store.status(statusRequest.copy(deviceSerial = "wrong-device"), 0,
                "0".repeat(64), 64, 10_001, "boot-a"))
            assertThrows(RegistryStorageException::class.java) {
                store.status(value, 1, "f".repeat(64), 64, 10_001, "boot-a")
            }
        }
        journal().use { reopened ->
            val page = reopened.status(statusRequest, 0, "0".repeat(64), 64, 10_002, "boot-a")!!
            assertEquals(2, page.events.size)
            assertEquals(page.record.journalHeadDigest, page.events.last().eventDigest)
        }
    }

    @Test fun corruptionInJournalDigestFailsClosedOnReopen() {
        val value = tuple(10)
        journal().use { acquire(it, value) }
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.execSQL("UPDATE journal_events SET event_digest=? WHERE sequence=1", arrayOf("e".repeat(64)))
        db.close()
        assertThrows(RegistryStorageException::class.java) {
            journal().use { it.status(value, 0, "0".repeat(64), 64, 10_001, "boot-a") }
        }
    }

    @Test fun preexistingDatabaseWithoutIntegrityMarkerIsNotAdopted() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).close()
        context.filesDir.resolve(markerName).delete()

        assertThrows(RegistryStorageException::class.java) {
            journal().use { it.record(tuple(30)) }
        }
        assertFalse(context.filesDir.resolve(markerName).exists())
    }

    @Test fun v1MigrationPreservesPossibleDispatchButDoesNotInventItsBoundaryTime() {
        val value = tuple(31)
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE journal_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1),schema_version INTEGER NOT NULL,fencing_counter INTEGER NOT NULL)")
        db.execSQL("INSERT INTO journal_meta VALUES(1,1,1)")
        db.execSQL("CREATE TABLE execution_requests(request_id TEXT PRIMARY KEY,task_id TEXT NOT NULL UNIQUE,session_id TEXT NOT NULL,owner_id TEXT NOT NULL,device_serial TEXT NOT NULL,route_id TEXT NOT NULL,route_version INTEGER NOT NULL,route_fingerprint TEXT NOT NULL,task_state TEXT NOT NULL,current_step_id TEXT,completed_step_count INTEGER NOT NULL,journal_sequence INTEGER NOT NULL,journal_head_digest TEXT NOT NULL,dispatch_possible INTEGER NOT NULL,current_step_possible INTEGER NOT NULL,cancel_requested INTEGER NOT NULL,fencing_token INTEGER NOT NULL,started_elapsed_ms INTEGER NOT NULL,lease_expires_elapsed_ms INTEGER NOT NULL,boot_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE execution_lease(singleton INTEGER PRIMARY KEY CHECK(singleton=1),request_id TEXT NOT NULL,task_id TEXT NOT NULL,session_id TEXT NOT NULL,owner_id TEXT NOT NULL,device_serial TEXT NOT NULL,expires_elapsed_ms INTEGER NOT NULL,fencing_token INTEGER NOT NULL,boot_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE journal_events(request_id TEXT NOT NULL,sequence INTEGER NOT NULL,task_id TEXT NOT NULL,session_id TEXT NOT NULL,owner_id TEXT NOT NULL,device_serial TEXT NOT NULL,event_type TEXT NOT NULL,step_id TEXT,task_state TEXT NOT NULL,dispatch_possible INTEGER NOT NULL,previous_digest TEXT NOT NULL,event_digest TEXT NOT NULL,PRIMARY KEY(request_id,sequence),FOREIGN KEY(request_id) REFERENCES execution_requests(request_id))")
        db.execSQL("CREATE INDEX journal_task_idx ON execution_requests(task_id)")
        db.version = 1

        val events = mutableListOf<JournalEventV1>()
        var previous = "0".repeat(64)
        listOf(
            Triple("TASK_PREPARED", "PREPARED", null),
            Triple("LEASE_ACQUIRED", "RUNNING", null),
            Triple("DISPATCH_POSSIBLE", "RUNNING", "step-one")
        ).forEachIndexed { index, (type, state, step) ->
            val unsigned = JournalEventV1(index + 1L, value.taskId, value.requestId,
                value.sessionId, value.ownerId, value.deviceSerial, type, step, state,
                type == "DISPATCH_POSSIBLE", previous, "")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(CanonicalRouteV1.encode(unsigned.digestInput()).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val event = unsigned.copy(eventDigest = digest)
            events.add(event)
            previous = digest
        }
        val request = ContentValues().apply {
            put("request_id", value.requestId); put("task_id", value.taskId)
            put("session_id", value.sessionId); put("owner_id", value.ownerId)
            put("device_serial", value.deviceSerial); put("route_id", "benchmark.open_guide")
            put("route_version", 1); put("route_fingerprint", routeFingerprint)
            put("task_state", "RUNNING"); put("current_step_id", "step-one")
            put("completed_step_count", 0); put("journal_sequence", events.size)
            put("journal_head_digest", previous); put("dispatch_possible", 1)
            put("current_step_possible", 1); put("cancel_requested", 0)
            put("fencing_token", 1); put("started_elapsed_ms", 10_000)
            put("lease_expires_elapsed_ms", 40_000); put("boot_id", "boot-a")
        }
        db.insertOrThrow("execution_requests", null, request)
        events.forEach { event ->
            val row = ContentValues().apply {
                put("request_id", event.requestId); put("sequence", event.sequence)
                put("task_id", event.taskId); put("session_id", event.sessionId)
                put("owner_id", event.ownerId); put("device_serial", event.deviceSerial)
                put("event_type", event.eventType); put("step_id", event.stepId)
                put("task_state", event.taskState); put("dispatch_possible", if (event.dispatchPossible) 1 else 0)
                put("previous_digest", event.previousDigest); put("event_digest", event.eventDigest)
            }
            db.insertOrThrow("journal_events", null, row)
        }
        db.close()
        context.filesDir.resolve(markerName).writeText("tessera.fast-path.execution-v1\n")

        journal().use { upgraded ->
            val preserved = upgraded.record(value)!!
            assertTrue(preserved.dispatchPossible)
            assertNull(preserved.dispatchPossibleElapsedMs)
            assertEquals("RUNNING", preserved.taskState)
        }
        val upgradedDb = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        val boundaryColumnExists = upgradedDb.rawQuery("PRAGMA table_info(execution_requests)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "dispatch_possible_elapsed_ms") found = true
            found
        }
        upgradedDb.close()
        assertTrue(boundaryColumnExists)
    }
}
