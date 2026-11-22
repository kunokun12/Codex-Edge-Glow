package com.codexedge.glow.fastpathv1

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
            assertFalse(acquire(afterRestart, after, 50_002).acquired)
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
}
