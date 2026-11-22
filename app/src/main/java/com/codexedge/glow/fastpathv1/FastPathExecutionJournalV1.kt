package com.codexedge.glow.fastpathv1

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class ExecutionTupleV1(
    val requestId: String,
    val taskId: String,
    val sessionId: String,
    val ownerId: String,
    val deviceSerial: String
)

internal data class JournalEventV1(
    val sequence: Long,
    val taskId: String,
    val requestId: String,
    val sessionId: String,
    val ownerId: String,
    val deviceSerial: String,
    val eventType: String,
    val stepId: String?,
    val taskState: String,
    val dispatchPossible: Boolean,
    val previousDigest: String,
    val eventDigest: String
) {
    fun wire(): Map<String, Any?> = mapOf(
        "sequence" to sequence, "taskId" to taskId, "requestId" to requestId,
        "sessionId" to sessionId, "ownerId" to ownerId, "deviceSerial" to deviceSerial,
        "eventType" to eventType, "stepId" to stepId, "taskState" to taskState,
        "dispatchPossible" to dispatchPossible, "previousDigest" to previousDigest,
        "eventDigest" to eventDigest
    )

    fun digestInput(): Map<String, Any?> = wire().filterKeys { it != "eventDigest" }
}

internal data class ExecutionRecordV1(
    val tuple: ExecutionTupleV1,
    val routeId: String,
    val routeVersion: Long,
    val routeFingerprint: String,
    val taskState: String,
    val currentStepId: String?,
    val completedStepCount: Int,
    val journalSequence: Long,
    val journalHeadDigest: String,
    val dispatchPossible: Boolean,
    val currentStepPossible: Boolean,
    val cancelRequested: Boolean,
    val fencingToken: Long,
    val startedElapsedMs: Long,
    val leaseExpiresElapsedMs: Long,
    val bootId: String
)

internal data class LeaseAcquireV1(val acquired: Boolean, val reasonCode: String, val record: ExecutionRecordV1?)
internal data class JournalPageV1(
    val record: ExecutionRecordV1,
    val leaseState: String,
    val leaseExpiresElapsedMs: Long?,
    val observedElapsedMs: Long,
    val fromSequence: Long,
    val throughSequence: Long,
    val journalHeadSequence: Long,
    val journalHeadDigest: String,
    val events: List<JournalEventV1>,
    val hasMore: Boolean
)
internal data class CancellationResultV1(
    val accepted: Boolean, val stopAfterCurrentStep: Boolean,
    val taskState: String, val dispatchPossible: Boolean, val journalSequence: Long
)

/**
 * Durable lease and hash-chained execution journal. All mutation methods use a
 * FULL-synchronous SQLite transaction. The monotonically increasing fencing
 * token is checked in the same transaction as DISPATCH_POSSIBLE.
 */
internal class FastPathExecutionJournalV1(
    context: Context,
    databaseName: String = "fastpath-execution-v1.db"
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val markerFile = File(appContext.filesDir, "$databaseName.present")
    private val marker = AtomicFile(markerFile)
    private val corruptionObserved = AtomicBoolean(false)
    private val helper = JournalDb(appContext, databaseName, markerFile, corruptionObserved)

    private fun db(write: Boolean = false): SQLiteDatabase {
        try {
            val database = if (write) helper.writableDatabase else helper.readableDatabase
            if (!markerFile.exists()) {
                val output = marker.startWrite()
                try {
                    output.write(MARKER_BYTES)
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
            } else if (!markerFile.readBytes().contentEquals(MARKER_BYTES)) {
                throw RegistryStorageException("execution-journal-marker-corrupt")
            }
            return database
        } catch (error: RegistryStorageException) {
            throw error
        } catch (error: Throwable) {
            throw RegistryStorageException("execution-journal-unavailable", error)
        }
    }

    @Synchronized
    fun acquire(
        tuple: ExecutionTupleV1,
        routeId: String,
        routeVersion: Long,
        routeFingerprint: String,
        nowElapsedMs: Long,
        bootId: String
    ): LeaseAcquireV1 {
        require(nowElapsedMs >= 0 && bootId.isNotBlank())
        val database = db(true)
        database.beginTransaction()
        try {
            val duplicate = readRecordByRequest(database, tuple.requestId)
            if (duplicate != null) return LeaseAcquireV1(false, "duplicate-request-id", duplicate)
            val unresolved = database.rawQuery("SELECT request_id FROM execution_requests WHERE dispatch_possible=1 AND task_state='RECOVERY_REQUIRED' ORDER BY started_elapsed_ms LIMIT 1", null)
                .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (unresolved != null) {
                val owner = readRecordByRequest(database, unresolved)
                return LeaseAcquireV1(false, "prior-operation-uncertain", owner)
            }
            val existingLease = readLease(database)
            if (existingLease != null) {
                val owner = readRecordByRequest(database, existingLease.requestId)
                    ?: throw RegistryStorageException("execution-lease-orphaned")
                val sameBoot = existingLease.bootId == bootId
                val active = sameBoot && existingLease.expiresAt > nowElapsedMs
                if (active) return LeaseAcquireV1(false, "lease-held", owner)
                if (owner != null && !isTerminal(owner.taskState)) {
                    appendEvent(database, owner.tuple, "LEASE_EXPIRED", owner.currentStepId,
                        owner.taskState, owner.dispatchPossible)
                    if (owner.dispatchPossible) {
                        transition(database, owner, "RECOVERY_REQUIRED", "RECOVERY_REQUIRED", owner.currentStepId)
                        database.setTransactionSuccessful()
                        return LeaseAcquireV1(false, "prior-operation-uncertain", readRecordByRequest(database, owner.tuple.requestId))
                    }
                    transition(database, owner, "CANCELLED", "TASK_CANCELLED", owner.currentStepId)
                }
            }

            val fence = database.rawQuery("SELECT fencing_counter FROM journal_meta WHERE singleton=1", null)
                .use { c -> if (!c.moveToFirst()) throw RegistryStorageException("journal-meta-missing") else c.getLong(0) + 1L }
            database.execSQL("UPDATE journal_meta SET fencing_counter=? WHERE singleton=1", arrayOf(fence))
            val expires = safeAdd(nowElapsedMs, IDLE_LEASE_MS)
            val request = ContentValues().apply {
                put("request_id", tuple.requestId); put("task_id", tuple.taskId)
                put("session_id", tuple.sessionId); put("owner_id", tuple.ownerId)
                put("device_serial", tuple.deviceSerial); put("route_id", routeId)
                put("route_version", routeVersion); put("route_fingerprint", routeFingerprint)
                put("task_state", "PREPARED"); putNull("current_step_id")
                put("completed_step_count", 0); put("journal_sequence", 0)
                put("journal_head_digest", ZERO_DIGEST); put("dispatch_possible", 0)
                put("current_step_possible", 0); put("cancel_requested", 0)
                put("fencing_token", fence); put("started_elapsed_ms", nowElapsedMs)
                put("lease_expires_elapsed_ms", expires); put("boot_id", bootId)
            }
            database.insertOrThrow("execution_requests", null, request)
            val lease = ContentValues().apply {
                put("singleton", 1); put("request_id", tuple.requestId)
                put("task_id", tuple.taskId); put("session_id", tuple.sessionId)
                put("owner_id", tuple.ownerId); put("device_serial", tuple.deviceSerial)
                put("expires_elapsed_ms", expires); put("fencing_token", fence); put("boot_id", bootId)
            }
            database.insertWithOnConflict("execution_lease", null, lease, SQLiteDatabase.CONFLICT_REPLACE)
            appendEvent(database, tuple, "TASK_PREPARED", null, "PREPARED", false)
            database.execSQL("UPDATE execution_requests SET task_state='RUNNING' WHERE request_id=?", arrayOf(tuple.requestId))
            appendEvent(database, tuple, "LEASE_ACQUIRED", null, "RUNNING", false)
            database.setTransactionSuccessful()
            return LeaseAcquireV1(true, "acquired", readRecordByRequest(database, tuple.requestId))
        } catch (error: RegistryStorageException) {
            throw error
        } catch (error: Throwable) {
            throw RegistryStorageException("execution-acquire-transaction-failed", error)
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun renew(tuple: ExecutionTupleV1, fencingToken: Long, nowElapsedMs: Long, bootId: String): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            val lease = readLease(database) ?: return false
            if (!matches(record, tuple) || !matches(lease, tuple) || record.fencingToken != fencingToken ||
                lease.fencingToken != fencingToken || record.bootId != bootId || lease.bootId != bootId ||
                lease.expiresAt <= nowElapsedMs || record.cancelRequested || isTerminal(record.taskState) ||
                nowElapsedMs - record.startedElapsedMs >= MAX_EXECUTION_MS) return false
            val expires = safeAdd(nowElapsedMs, IDLE_LEASE_MS)
            database.execSQL("UPDATE execution_lease SET expires_elapsed_ms=? WHERE singleton=1 AND fencing_token=?",
                arrayOf(expires, fencingToken))
            database.execSQL("UPDATE execution_requests SET lease_expires_elapsed_ms=? WHERE request_id=? AND fencing_token=?",
                arrayOf(expires, tuple.requestId, fencingToken))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun commitDispatchPossible(
        tuple: ExecutionTupleV1,
        fencingToken: Long,
        stepId: String,
        expectedSequence: Long,
        nowElapsedMs: Long,
        bootId: String
    ): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            val lease = readLease(database) ?: return false
            if (!matches(record, tuple) || !matches(lease, tuple) || record.fencingToken != fencingToken ||
                lease.fencingToken != fencingToken || record.bootId != bootId || lease.bootId != bootId ||
                lease.expiresAt <= nowElapsedMs || record.cancelRequested || isTerminal(record.taskState) ||
                record.journalSequence != expectedSequence || record.currentStepPossible ||
                nowElapsedMs - record.startedElapsedMs >= MAX_EXECUTION_MS) return false
            database.execSQL("UPDATE execution_requests SET task_state='RUNNING',current_step_id=?,dispatch_possible=1,current_step_possible=1 WHERE request_id=? AND fencing_token=?",
                arrayOf(stepId, tuple.requestId, fencingToken))
            appendEvent(database, tuple, "DISPATCH_POSSIBLE", stepId, "RUNNING", true)
            database.setTransactionSuccessful()
            return true
        } catch (error: Throwable) {
            throw RegistryStorageException("dispatch-intent-commit-failed", error)
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun recordActionOutcome(tuple: ExecutionTupleV1, fencingToken: Long, stepId: String, outcome: String): Boolean {
        require(outcome in setOf("ACCEPTED", "REJECTED", "UNCERTAIN"))
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            if (!matches(record, tuple) || record.fencingToken != fencingToken ||
                !record.currentStepPossible || record.currentStepId != stepId) return false
            val eventType = when (outcome) {
                "ACCEPTED" -> "ACTION_ACCEPTED"
                "REJECTED" -> "ACTION_REJECTED"
                else -> "ACTION_UNCERTAIN"
            }
            // A cancellation after DISPATCH_POSSIBLE is irreversible. Persist
            // the dispatch result for audit, but never restore RUNNING or permit
            // later steps after the cancellation transaction won the race.
            val state = if (outcome == "ACCEPTED" && !record.cancelRequested) "RUNNING" else "RECOVERY_REQUIRED"
            database.execSQL("UPDATE execution_requests SET task_state=? WHERE request_id=?", arrayOf(state, tuple.requestId))
            appendEvent(database, tuple, eventType, stepId, state, true)
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun recordStepObserved(tuple: ExecutionTupleV1, fencingToken: Long, stepId: String): Boolean = appendNonTerminalEvent(
        tuple, fencingToken, "STEP_OBSERVED", stepId, "RUNNING")

    @Synchronized
    fun recordStepMatched(tuple: ExecutionTupleV1, fencingToken: Long, stepId: String): Boolean = appendNonTerminalEvent(
        tuple, fencingToken, "STEP_MATCHED", stepId, "RUNNING")

    private fun appendNonTerminalEvent(tuple: ExecutionTupleV1, fencingToken: Long, type: String, stepId: String, expectedState: String): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            if (!matches(record, tuple) || record.fencingToken != fencingToken ||
                record.taskState != expectedState || isTerminal(record.taskState)) return false
            appendEvent(database, tuple, type, stepId, record.taskState, record.dispatchPossible)
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun recordStepVerified(tuple: ExecutionTupleV1, fencingToken: Long, stepId: String,
                           finalStep: Boolean = false): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            if (!matches(record, tuple) || record.fencingToken != fencingToken ||
                !record.currentStepPossible || record.currentStepId != stepId ||
                record.taskState != "RUNNING") return false
            val resultingState = if (finalStep) "VERIFYING" else "RUNNING"
            database.execSQL("UPDATE execution_requests SET task_state=?,current_step_possible=0,completed_step_count=completed_step_count+1 WHERE request_id=?",
                arrayOf(resultingState, tuple.requestId))
            appendEvent(database, tuple, "STEP_VERIFIED", stepId, resultingState, true)
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun refuseBeforeDispatch(tuple: ExecutionTupleV1, reason: String): Boolean = finishBeforeDispatch(tuple, "REFUSED", "TASK_REFUSED", reason)

    @Synchronized
    fun finishVerified(tuple: ExecutionTupleV1, fencingToken: Long, expectedStepCount: Int,
                       nowElapsedMs: Long, bootId: String): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            val lease = readLease(database) ?: return false
            if (!matches(record, tuple) || !matches(lease, tuple) ||
                record.fencingToken != fencingToken || lease.fencingToken != fencingToken ||
                record.bootId != bootId || lease.bootId != bootId || lease.expiresAt <= nowElapsedMs ||
                nowElapsedMs - record.startedElapsedMs >= MAX_EXECUTION_MS ||
                record.taskState != "VERIFYING" || record.completedStepCount != expectedStepCount ||
                record.currentStepPossible || !record.dispatchPossible || record.cancelRequested)
                return false
            database.execSQL("UPDATE execution_requests SET task_state='VERIFIED_SUCCESS' WHERE request_id=?",
                arrayOf(tuple.requestId))
            appendEvent(database, tuple, "TASK_VERIFIED", record.currentStepId, "VERIFIED_SUCCESS", true)
            database.delete("execution_lease", "singleton=1 AND fencing_token=?", arrayOf(fencingToken.toString()))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun finishRecoveryRequired(tuple: ExecutionTupleV1, fencingToken: Long): Boolean = finish(tuple, fencingToken, "RECOVERY_REQUIRED", "RECOVERY_REQUIRED")

    private fun finish(tuple: ExecutionTupleV1, fencingToken: Long, state: String, eventType: String): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            if (!matches(record, tuple) || record.fencingToken != fencingToken || isTerminal(record.taskState)) return false
            database.execSQL("UPDATE execution_requests SET task_state=?,current_step_possible=0 WHERE request_id=?", arrayOf(state, tuple.requestId))
            appendEvent(database, tuple, eventType, record.currentStepId, state, record.dispatchPossible)
            database.delete("execution_lease", "singleton=1 AND fencing_token=?", arrayOf(fencingToken.toString()))
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    private fun finishBeforeDispatch(tuple: ExecutionTupleV1, state: String, eventType: String, @Suppress("UNUSED_PARAMETER") reason: String): Boolean {
        val database = db(true)
        database.beginTransaction()
        try {
            val record = readRecordByRequest(database, tuple.requestId) ?: return false
            if (!matches(record, tuple) || record.dispatchPossible || isTerminal(record.taskState)) return false
            database.execSQL("UPDATE execution_requests SET task_state=?,current_step_possible=0 WHERE request_id=?", arrayOf(state, tuple.requestId))
            appendEvent(database, tuple, eventType, record.currentStepId, state, false)
            database.delete("execution_lease", "singleton=1", null)
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun cancel(tuple: ExecutionTupleV1): CancellationResultV1 {
        val database = db(true)
        database.beginTransaction()
        try {
            // route.cancel has its own requestId. Correlate it to the original
            // execution using the owner-scoped task tuple; never require callers
            // to reuse a mutating request ID.
            val record = readRecordByTask(database, tuple.taskId)
                ?: return CancellationResultV1(false, false, "REFUSED", false, 0)
            if (!matchesOwner(record.tuple, tuple) || isTerminal(record.taskState))
                return CancellationResultV1(false, false, record.taskState, record.dispatchPossible, record.journalSequence)
            val executionTuple = record.tuple
            val state = if (record.currentStepPossible) "RECOVERY_REQUIRED" else "CANCELLED"
            val type = if (record.currentStepPossible) "RECOVERY_REQUIRED" else "TASK_CANCELLED"
            database.execSQL("UPDATE execution_requests SET cancel_requested=1,task_state=? WHERE request_id=?", arrayOf(state, executionTuple.requestId))
            appendEvent(database, executionTuple, type, record.currentStepId, state, record.dispatchPossible)
            if (state == "CANCELLED") database.delete("execution_lease", "singleton=1 AND request_id=?", arrayOf(executionTuple.requestId))
            database.setTransactionSuccessful()
            val updated = readRecordByRequest(database, executionTuple.requestId)!!
            return CancellationResultV1(true, record.currentStepPossible, updated.taskState,
                updated.dispatchPossible, updated.journalSequence)
        } finally { database.endTransaction() }
    }

    /** Called after process start. No dispatch intent is ever replayed. */
    @Synchronized
    fun recoverExpired(nowElapsedMs: Long, bootId: String): List<ExecutionRecordV1> {
        val database = db(true)
        database.beginTransaction()
        try {
            val lease = readLease(database) ?: return emptyList()
            if (lease.bootId == bootId && lease.expiresAt > nowElapsedMs) return emptyList()
            val owner = readRecordByRequest(database, lease.requestId) ?: throw RegistryStorageException("execution-lease-orphaned")
            if (!isTerminal(owner.taskState)) {
                appendEvent(database, owner.tuple, "LEASE_EXPIRED", owner.currentStepId,
                    owner.taskState, owner.dispatchPossible)
                val state = if (owner.dispatchPossible) "RECOVERY_REQUIRED" else "CANCELLED"
                val type = if (owner.dispatchPossible) "RECOVERY_REQUIRED" else "TASK_CANCELLED"
                database.execSQL("UPDATE execution_requests SET task_state=?,current_step_possible=0 WHERE request_id=?", arrayOf(state, owner.tuple.requestId))
                appendEvent(database, owner.tuple, type, owner.currentStepId, state, owner.dispatchPossible)
            }
            database.delete("execution_lease", "singleton=1", null)
            database.setTransactionSuccessful()
            return listOfNotNull(readRecordByRequest(database, owner.tuple.requestId))
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun status(
        tuple: ExecutionTupleV1,
        afterSequence: Long,
        afterDigest: String,
        maxEvents: Int,
        nowElapsedMs: Long,
        bootId: String
    ): JournalPageV1? {
        require(afterSequence >= 0 && afterDigest.matches(Regex("[0-9a-f]{64}")) && maxEvents in 1..256)
        val database = db()
        // route.status is read-only and has a fresh requestId of its own. Resolve
        // the durable execution by taskId, then require the exact owner/session/
        // device binding before exposing journal pages.
        val record = readRecordByTask(database, tuple.taskId) ?: return null
        if (!matchesOwner(record.tuple, tuple)) return null
        val executionRequestId = record.tuple.requestId
        val headSequence = record.journalSequence
        val expectedDigest = if (afterSequence == 0L) ZERO_DIGEST else readEvent(database, executionRequestId, afterSequence)?.eventDigest
        if (afterSequence > headSequence || expectedDigest == null || expectedDigest != afterDigest)
            throw RegistryStorageException("journal-cursor-mismatch")
        val events = mutableListOf<JournalEventV1>()
        database.rawQuery("SELECT sequence,task_id,request_id,session_id,owner_id,device_serial,event_type,step_id,task_state,dispatch_possible,previous_digest,event_digest FROM journal_events WHERE request_id=? AND sequence>? ORDER BY sequence LIMIT ?",
            arrayOf(executionRequestId, afterSequence.toString(), maxEvents.toString())).use { c ->
            while (c.moveToNext()) events.add(c.toEvent())
        }
        val lease = readLease(database)?.takeIf { matchesOwner(it.tuple, tuple) }
        val leaseLive = lease != null && lease.bootId == bootId && lease.expiresAt > nowElapsedMs
        return JournalPageV1(record,
            if (lease == null) "RELEASED" else if (leaseLive) "ACTIVE" else "EXPIRED",
            lease?.expiresAt, nowElapsedMs, afterSequence + 1,
            events.lastOrNull()?.sequence ?: afterSequence, headSequence, record.journalHeadDigest,
            events, (events.lastOrNull()?.sequence ?: afterSequence) < headSequence)
    }

    @Synchronized
    fun record(tuple: ExecutionTupleV1): ExecutionRecordV1? = readRecordByRequest(db(), tuple.requestId)?.takeIf { matches(it, tuple) }

    @Synchronized
    fun recordForTask(tuple: ExecutionTupleV1): ExecutionRecordV1? =
        readRecordByTask(db(), tuple.taskId)?.takeIf { matchesOwner(it.tuple, tuple) }

    private fun appendEvent(db: SQLiteDatabase, tuple: ExecutionTupleV1, type: String, stepId: String?, state: String, dispatchPossible: Boolean) {
        val record = readRecordByRequest(db, tuple.requestId) ?: throw RegistryStorageException("journal-operation-missing")
        val sequence = record.journalSequence + 1
        val event = JournalEventV1(sequence, tuple.taskId, tuple.requestId, tuple.sessionId,
            tuple.ownerId, tuple.deviceSerial, type, stepId, state, dispatchPossible,
            record.journalHeadDigest, "")
        val digest = sha256(CanonicalRouteV1.encode(event.digestInput()))
        val values = ContentValues().apply {
            put("request_id", tuple.requestId); put("sequence", sequence)
            put("task_id", tuple.taskId); put("session_id", tuple.sessionId)
            put("owner_id", tuple.ownerId); put("device_serial", tuple.deviceSerial)
            put("event_type", type); if (stepId == null) putNull("step_id") else put("step_id", stepId)
            put("task_state", state); put("dispatch_possible", if (dispatchPossible) 1 else 0)
            put("previous_digest", record.journalHeadDigest); put("event_digest", digest)
        }
        db.insertOrThrow("journal_events", null, values)
        db.execSQL("UPDATE execution_requests SET journal_sequence=?,journal_head_digest=? WHERE request_id=?",
            arrayOf(sequence, digest, tuple.requestId))
    }

    private fun transition(db: SQLiteDatabase, record: ExecutionRecordV1, state: String, event: String, step: String?) {
        db.execSQL("UPDATE execution_requests SET task_state=?,current_step_possible=0 WHERE request_id=?",
            arrayOf(state, record.tuple.requestId))
        appendEvent(db, record.tuple, event, step, state, record.dispatchPossible)
    }

    private data class LeaseRow(val tuple: ExecutionTupleV1, val requestId: String,
                                val expiresAt: Long, val fencingToken: Long, val bootId: String)

    private fun readLease(db: SQLiteDatabase): LeaseRow? = db.rawQuery(
        "SELECT request_id,task_id,session_id,owner_id,device_serial,expires_elapsed_ms,fencing_token,boot_id FROM execution_lease WHERE singleton=1", null).use { c ->
        if (!c.moveToFirst()) null else {
            val tuple = ExecutionTupleV1(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            LeaseRow(tuple, tuple.requestId, c.getLong(5), c.getLong(6), c.getString(7))
        }
    }

    private fun readRecordByRequest(db: SQLiteDatabase, requestId: String): ExecutionRecordV1? = db.rawQuery(
        "SELECT request_id,task_id,session_id,owner_id,device_serial,route_id,route_version,route_fingerprint,task_state,current_step_id,completed_step_count,journal_sequence,journal_head_digest,dispatch_possible,current_step_possible,cancel_requested,fencing_token,started_elapsed_ms,lease_expires_elapsed_ms,boot_id FROM execution_requests WHERE request_id=?",
        arrayOf(requestId)).use { c -> if (!c.moveToFirst()) null else c.toRecord() }

    private fun readRecordByTask(db: SQLiteDatabase, taskId: String): ExecutionRecordV1? = db.rawQuery(
        "SELECT request_id,task_id,session_id,owner_id,device_serial,route_id,route_version,route_fingerprint,task_state,current_step_id,completed_step_count,journal_sequence,journal_head_digest,dispatch_possible,current_step_possible,cancel_requested,fencing_token,started_elapsed_ms,lease_expires_elapsed_ms,boot_id FROM execution_requests WHERE task_id=?",
        arrayOf(taskId)).use { c -> if (!c.moveToFirst()) null else c.toRecord() }

    private fun readEvent(db: SQLiteDatabase, requestId: String, sequence: Long): JournalEventV1? = db.rawQuery(
        "SELECT sequence,task_id,request_id,session_id,owner_id,device_serial,event_type,step_id,task_state,dispatch_possible,previous_digest,event_digest FROM journal_events WHERE request_id=? AND sequence=?",
        arrayOf(requestId, sequence.toString())).use { c -> if (c.moveToFirst()) c.toEvent() else null }

    private fun android.database.Cursor.toRecord() = ExecutionRecordV1(
        ExecutionTupleV1(getString(0), getString(1), getString(2), getString(3), getString(4)),
        getString(5), getLong(6), getString(7), getString(8), if (isNull(9)) null else getString(9),
        getInt(10), getLong(11), getString(12), getInt(13) != 0, getInt(14) != 0,
        getInt(15) != 0, getLong(16), getLong(17), getLong(18), getString(19))

    private fun android.database.Cursor.toEvent() = JournalEventV1(
        getLong(0), getString(1), getString(2), getString(3), getString(4), getString(5),
        getString(6), if (isNull(7)) null else getString(7), getString(8), getInt(9) != 0,
        getString(10), getString(11))

    private fun matches(record: ExecutionRecordV1, tuple: ExecutionTupleV1) = record.tuple == tuple
    private fun matches(lease: LeaseRow, tuple: ExecutionTupleV1) = lease.tuple == tuple
    private fun matchesOwner(stored: ExecutionTupleV1, query: ExecutionTupleV1) =
        stored.taskId == query.taskId && stored.sessionId == query.sessionId &&
            stored.ownerId == query.ownerId && stored.deviceSerial == query.deviceSerial
    private fun matchesOwner(lease: LeaseRow, query: ExecutionTupleV1) = matchesOwner(lease.tuple, query)
    private fun isTerminal(state: String) = state in setOf("VERIFIED_SUCCESS", "REFUSED", "CANCELLED", "RECOVERY_REQUIRED")
    private fun safeAdd(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    override fun close() = helper.close()

    private class JournalDb(context: Context, name: String, private val marker: File,
                            private val corrupt: AtomicBoolean) :
        SQLiteOpenHelper(context, name, null, DB_VERSION, DatabaseErrorHandler { corrupt.set(true) }) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("PRAGMA journal_mode=DELETE")
        }
        override fun onCreate(db: SQLiteDatabase) {
            if (marker.exists()) throw RegistryStorageException("execution-database-missing")
            db.execSQL("CREATE TABLE journal_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1),schema_version INTEGER NOT NULL,fencing_counter INTEGER NOT NULL)")
            db.execSQL("INSERT INTO journal_meta VALUES(1,1,0)")
            db.execSQL("CREATE TABLE execution_requests(request_id TEXT PRIMARY KEY,task_id TEXT NOT NULL UNIQUE,session_id TEXT NOT NULL,owner_id TEXT NOT NULL,device_serial TEXT NOT NULL,route_id TEXT NOT NULL,route_version INTEGER NOT NULL,route_fingerprint TEXT NOT NULL,task_state TEXT NOT NULL,current_step_id TEXT,completed_step_count INTEGER NOT NULL,journal_sequence INTEGER NOT NULL,journal_head_digest TEXT NOT NULL,dispatch_possible INTEGER NOT NULL,current_step_possible INTEGER NOT NULL,cancel_requested INTEGER NOT NULL,fencing_token INTEGER NOT NULL,started_elapsed_ms INTEGER NOT NULL,lease_expires_elapsed_ms INTEGER NOT NULL,boot_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE execution_lease(singleton INTEGER PRIMARY KEY CHECK(singleton=1),request_id TEXT NOT NULL,task_id TEXT NOT NULL,session_id TEXT NOT NULL,owner_id TEXT NOT NULL,device_serial TEXT NOT NULL,expires_elapsed_ms INTEGER NOT NULL,fencing_token INTEGER NOT NULL,boot_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE journal_events(request_id TEXT NOT NULL,sequence INTEGER NOT NULL,task_id TEXT NOT NULL,session_id TEXT NOT NULL,owner_id TEXT NOT NULL,device_serial TEXT NOT NULL,event_type TEXT NOT NULL,step_id TEXT,task_state TEXT NOT NULL,dispatch_possible INTEGER NOT NULL,previous_digest TEXT NOT NULL,event_digest TEXT NOT NULL,PRIMARY KEY(request_id,sequence),FOREIGN KEY(request_id) REFERENCES execution_requests(request_id))")
            db.execSQL("CREATE INDEX journal_task_idx ON execution_requests(task_id)")
            db.version = DB_VERSION
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw RegistryStorageException("execution-journal-migration-required") }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw RegistryStorageException("execution-journal-downgrade-refused") }
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (corrupt.get()) throw RegistryStorageException("execution-journal-corrupt")
            val check = db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
            if (check != "ok") throw RegistryStorageException("execution-journal-integrity-failed")
            val meta = db.rawQuery("SELECT schema_version FROM journal_meta WHERE singleton=1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            if (meta != DB_VERSION) throw RegistryStorageException("execution-journal-schema-invalid")
            verifyJournalChains(db)
        }
        private fun verifyJournalChains(db: SQLiteDatabase) {
            val taskStates = setOf("PREPARED", "RUNNING", "VERIFYING", "VERIFIED_SUCCESS",
                "REFUSED", "CANCELLED", "RECOVERY_REQUIRED")
            val eventTypes = setOf("TASK_PREPARED", "LEASE_ACQUIRED", "STEP_OBSERVED", "STEP_MATCHED",
                "DISPATCH_POSSIBLE", "ACTION_ACCEPTED", "ACTION_REJECTED", "ACTION_UNCERTAIN",
                "STEP_VERIFIED", "TASK_CANCELLED", "TASK_VERIFIED", "TASK_REFUSED",
                "LEASE_EXPIRED", "RECOVERY_REQUIRED")
            db.rawQuery("SELECT request_id,task_id,session_id,owner_id,device_serial,task_state,current_step_id,completed_step_count,journal_sequence,journal_head_digest,dispatch_possible FROM execution_requests", null).use { requests ->
                while (requests.moveToNext()) {
                    val request = requests.getString(0)
                    val taskId = requests.getString(1)
                    val sessionId = requests.getString(2)
                    val ownerId = requests.getString(3)
                    val deviceSerial = requests.getString(4)
                    val taskState = requests.getString(5)
                    val currentStep = if (requests.isNull(6)) null else requests.getString(6)
                    val completedCount = requests.getInt(7)
                    if (taskState !in taskStates) throw RegistryStorageException("execution-task-state-invalid")
                    var previous = ZERO_DIGEST
                    var expected = 1L
                    var last: JournalEventV1? = null
                    var verifiedSteps = 0
                    var sawDispatch = false
                    db.rawQuery("SELECT sequence,task_id,request_id,session_id,owner_id,device_serial,event_type,step_id,task_state,dispatch_possible,previous_digest,event_digest FROM journal_events WHERE request_id=? ORDER BY sequence", arrayOf(request)).use { events ->
                        while (events.moveToNext()) {
                            val event = JournalEventV1(events.getLong(0), events.getString(1), events.getString(2),
                                events.getString(3), events.getString(4), events.getString(5), events.getString(6),
                                if (events.isNull(7)) null else events.getString(7), events.getString(8),
                                events.getInt(9) != 0, events.getString(10), events.getString(11))
                            if (event.sequence != expected++ || event.previousDigest != previous ||
                                event.taskId != taskId || event.requestId != request ||
                                event.sessionId != sessionId || event.ownerId != ownerId ||
                                event.deviceSerial != deviceSerial || event.eventType !in eventTypes ||
                                event.taskState !in taskStates || (sawDispatch && !event.dispatchPossible) ||
                                sha256(CanonicalRouteV1.encode(event.digestInput())) != event.eventDigest)
                                throw RegistryStorageException("execution-journal-chain-broken")
                            if (event.dispatchPossible) sawDispatch = true
                            if (event.eventType == "STEP_VERIFIED") verifiedSteps++
                            previous = event.eventDigest
                            last = event
                        }
                    }
                    if (last == null || requests.getLong(8) != expected - 1 || requests.getString(9) != previous ||
                        last.taskState != taskState || last.stepId != currentStep ||
                        verifiedSteps != completedCount || last.dispatchPossible != (requests.getInt(10) != 0))
                        throw RegistryStorageException("execution-journal-head-mismatch")
                }
            }
            db.rawQuery("SELECT request_id,task_id,session_id,owner_id,device_serial,expires_elapsed_ms,fencing_token,boot_id FROM execution_lease WHERE singleton=1", null).use { leases ->
                if (leases.moveToFirst()) {
                    val requestId = leases.getString(0)
                    val owner = db.rawQuery("SELECT task_id,session_id,owner_id,device_serial,lease_expires_elapsed_ms,fencing_token,boot_id FROM execution_requests WHERE request_id=?",
                        arrayOf(requestId)).use { c ->
                        if (!c.moveToFirst()) null else listOf(c.getString(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getLong(4), c.getLong(5), c.getString(6))
                    } ?: throw RegistryStorageException("execution-lease-orphaned")
                    if (leases.getString(1) != owner[0] || leases.getString(2) != owner[1] ||
                        leases.getString(3) != owner[2] || leases.getString(4) != owner[3] ||
                        leases.getLong(5) != owner[4] || leases.getLong(6) != owner[5] ||
                        leases.getString(7) != owner[6])
                        throw RegistryStorageException("execution-lease-projection-invalid")
                }
            }
        }
    }

    companion object {
        const val IDLE_LEASE_MS = 30_000L
        const val RENEW_INTERVAL_MS = 5_000L
        const val MAX_EXECUTION_MS = 120_000L
        private const val DB_VERSION = 1
        private const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        private val MARKER_BYTES = "tessera.fast-path.execution-v1\n".toByteArray(Charsets.US_ASCII)
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
