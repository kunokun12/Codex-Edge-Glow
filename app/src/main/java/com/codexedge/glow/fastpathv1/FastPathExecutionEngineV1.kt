package com.codexedge.glow.fastpathv1

import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AppIdentityV1(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerCertificateSha256: String
)

internal data class SelectorV1(val kind: String, val value: String)
internal data class StateConstraintV1(val stateFingerprint: String, val requiredMarkers: List<String>)
internal data class RouteStepV1(
    val stepId: String,
    val selector: SelectorV1,
    val expectedBefore: StateConstraintV1,
    val expectedAfter: StateConstraintV1
)
internal data class ExecutableRouteV1(
    val routeId: String,
    val routeVersion: Long,
    val routeFingerprint: String,
    val packageName: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val appSignerSha256: String,
    val origin: StateConstraintV1,
    val destination: StateConstraintV1,
    val steps: List<RouteStepV1>
)

internal data class RequestedRouteV1(
    val routeId: String,
    val routeVersion: Long,
    val routeFingerprint: String,
    val routeDefinition: Map<String, Any?>
)

internal data class VisibleTargetV1(
    val opaqueHandle: String,
    val packageName: String,
    val windowId: Int,
    val visible: Boolean,
    val enabled: Boolean,
    val actionable: Boolean,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val textAliases: Set<String> = emptySet(),
    val contentDescriptionAliases: Set<String> = emptySet(),
    val resourceIdAliases: Set<String> = emptySet()
)

internal data class UiEvidenceV1(
    val packageName: String,
    val windowId: Int,
    val windowIdBefore: Int,
    val windowIdAfter: Int,
    val capturedElapsedMs: Long,
    val complete: Boolean,
    val truncated: Boolean,
    val stateFingerprint: String,
    val targets: List<VisibleTargetV1>
) {
    fun markers(): Set<String> = targets.asSequence().filter { it.visible }
        .flatMap { sequenceOfNotNull(it.text, it.contentDescription) }.toSet()
}

internal data class VerificationEvidenceV1(
    val verified: Boolean,
    val packageName: String,
    val windowId: Int,
    val destinationFingerprint: String,
    val observedElapsedMs: Long,
    val ageMs: Long,
    val independenceClass: String,
    val observerBuildSha256: String,
    val observedMarkers: Set<String> = emptySet()
)

internal interface FastPathActionPortV1 {
    fun installedApp(packageName: String): AppIdentityV1?
    fun captureFresh(packageName: String, expectedWindowId: Int?, maxNodes: Int, maxAgeMs: Int): UiEvidenceV1?
    /** Exactly one ACTION_CLICK attempt. Implementations must not retry. */
    fun dispatchClick(target: VisibleTargetV1, expectedPackage: String, expectedWindowId: Int): Boolean
    /** Must use a separately approved observer; API dispatch receipt is not evidence. */
    fun independentlyVerify(route: ExecutableRouteV1, expectedWindowId: Int, maxAgeMs: Int): VerificationEvidenceV1?
    fun release() {}
}

internal data class ExecutionMetricsV1(
    val routeLookupNanos: Long = 0,
    val evidenceNanos: Long = 0,
    val selectorNanos: Long = 0,
    val dispatchNanos: Long = 0,
    val verificationNanos: Long = 0
)

internal data class ExecutionOutcomeV1(
    val taskState: String,
    val reasonCode: String,
    val completedStepCount: Int,
    val dispatchPossible: Boolean,
    val verificationStatus: String,
    val metrics: ExecutionMetricsV1
)

/**
 * Bounded, deterministic M5 executor. The production runtime gate is false by
 * default. No model, host callback, coordinate fallback, or action retry exists
 * in the per-step loop.
 */
internal class FastPathExecutionEngineV1(
    private val registry: FastPathRegistryV1,
    private val journal: FastPathExecutionJournalV1,
    private val actionPort: FastPathActionPortV1,
    private val runtimeEnabled: () -> Boolean = { false },
    private val elapsedClock: () -> Long,
    private val bootId: () -> String,
    private val expectedObserverBuildSha256: String? = null
) {
    fun execute(tuple: ExecutionTupleV1, requested: RequestedRouteV1,
                requestDeadlineElapsedMs: Long): ExecutionOutcomeV1 {
        if (!runtimeEnabled()) return refused("execution-disabled")
        val admittedAt = elapsedClock()
        if (requestDeadlineElapsedMs <= admittedAt ||
            requestDeadlineElapsedMs - admittedAt > MAX_REQUEST_TTL_MS)
            return refused("request-expired")
        val lookupStart = System.nanoTime()
        val route = registry.findPromoted(requested.routeId, requested.routeVersion, requested.routeFingerprint)
            ?: return refused("route-not-authorized")
        val lookupNanos = System.nanoTime() - lookupStart
        if (route.routeId != requested.routeId || route.routeVersion != requested.routeVersion ||
            route.routeFingerprint != requested.routeFingerprint ||
            CanonicalRouteV1.encode(route.routeDefinition) != CanonicalRouteV1.encode(requested.routeDefinition))
            return refused("route-definition-mismatch", lookupNanos)
        val executable = try { parseRoute(route) } catch (_: Exception) {
            return refused("route-definition-invalid", lookupNanos)
        }
        val app = try { actionPort.installedApp(executable.packageName) } catch (_: Exception) { null }
        if (app == null || app.packageName != executable.packageName ||
            app.versionName != executable.appVersionName || app.versionCode != executable.appVersionCode ||
            app.signerCertificateSha256 != executable.appSignerSha256)
            return refused("target-app-identity-mismatch", lookupNanos)
        if (elapsedClock() >= requestDeadlineElapsedMs) return refused("request-expired", lookupNanos)

        val acquire = try {
            journal.acquire(tuple, executable.routeId, executable.routeVersion,
                executable.routeFingerprint, elapsedClock(), bootId())
        } catch (_: Exception) { return refused("execution-journal-unavailable", lookupNanos) }
        if (!acquire.acquired) return ExecutionOutcomeV1(
            acquire.record?.taskState ?: "REFUSED", acquire.reasonCode,
            acquire.record?.completedStepCount ?: 0, acquire.record?.dispatchPossible ?: false,
            "UNAVAILABLE", ExecutionMetricsV1(routeLookupNanos = lookupNanos))
        val fencingToken = acquire.record?.fencingToken ?: return refused("lease-acquisition-invalid", lookupNanos)
        val heartbeatFailed = AtomicBoolean(false)
        val heartbeat = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fastpath-lease-heartbeat").apply { isDaemon = true }
        }
        heartbeat.scheduleAtFixedRate({
            try {
                val now = elapsedClock()
                if (now >= requestDeadlineElapsedMs ||
                    !journal.renew(tuple, fencingToken, now, bootId())) heartbeatFailed.set(true)
            } catch (_: Exception) { heartbeatFailed.set(true) }
        }, FastPathExecutionJournalV1.RENEW_INTERVAL_MS, FastPathExecutionJournalV1.RENEW_INTERVAL_MS,
            TimeUnit.MILLISECONDS)
        try {
        var expectedWindow: Int? = null
        var evidenceNanos = 0L
        var selectorNanos = 0L
        var dispatchNanos = 0L

        for ((index, step) in executable.steps.withIndex()) {
            if (elapsedClock() >= requestDeadlineElapsedMs)
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos,
                    dispatchNanos, "request-expired")
            if (heartbeatFailed.get())
                return recover(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "lease-lost")
            val now = elapsedClock()
            if (index > 0 && !journal.renew(tuple, fencingToken, now, bootId()))
                return recover(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "lease-lost")
            val evidenceStart = System.nanoTime()
            val before = try { actionPort.captureFresh(executable.packageName, expectedWindow, MAX_EVIDENCE_NODES, MAX_EVIDENCE_AGE_MS) }
                catch (_: Exception) { null }
            evidenceNanos += System.nanoTime() - evidenceStart
            if (!fresh(before, executable.packageName, expectedWindow, elapsedClock()))
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "stale-or-ambiguous-evidence")
            if (!journal.recordStepObserved(tuple, fencingToken, step.stepId))
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    "observation-journal-failed")
            if (expectedWindow == null) expectedWindow = before!!.windowId
            if (index == 0 && !matches(before!!, executable.origin))
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "origin-state-mismatch")
            if (!matches(before!!, step.expectedBefore))
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "expected-before-mismatch")
            if (!journal.recordStepMatched(tuple, fencingToken, step.stepId))
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    "step-match-journal-failed")

            val selectorStart = System.nanoTime()
            val candidates = before.targets.filter { target ->
                target.packageName == executable.packageName && target.windowId == expectedWindow &&
                    target.visible && target.enabled && target.actionable && matchesSelector(target, step.selector)
            }.distinctBy { it.opaqueHandle }
            selectorNanos += System.nanoTime() - selectorStart
            if (candidates.size != 1)
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    if (candidates.isEmpty()) "selector-no-match" else "selector-ambiguous")

            if (elapsedClock() >= requestDeadlineElapsedMs)
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos,
                    dispatchNanos, "request-expired")

            val beforeDispatch = journal.record(tuple) ?: return recover(tuple, fencingToken, index,
                lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "execution-journal-unavailable")
            val intentCommitted = try {
                journal.commitDispatchPossible(tuple, fencingToken, step.stepId,
                    beforeDispatch.journalSequence, elapsedClock(), bootId())
            } catch (_: Exception) { false }
            if (!intentCommitted)
                return failStep(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    "dispatch-intent-not-committed")
            if (elapsedClock() >= requestDeadlineElapsedMs)
                return recover(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos,
                    dispatchNanos, "request-expired-after-dispatch-boundary")
            if (heartbeatFailed.get())
                return recover(tuple, fencingToken, index, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "lease-lost-before-dispatch")

            val dispatchStart = System.nanoTime()
            val accepted = try {
                actionPort.dispatchClick(candidates.single(), executable.packageName, expectedWindow!!)
            } catch (_: Throwable) {
                journal.recordActionOutcome(tuple, fencingToken, step.stepId, "UNCERTAIN")
                dispatchNanos += System.nanoTime() - dispatchStart
                return recover(tuple, fencingToken, index + 1, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "dispatch-outcome-uncertain")
            }
            dispatchNanos += System.nanoTime() - dispatchStart
            if (!journal.recordActionOutcome(tuple, fencingToken, step.stepId, if (accepted) "ACCEPTED" else "REJECTED"))
                return recover(tuple, fencingToken, index + 1, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "action-outcome-journal-failed")
            if (!accepted)
                return recover(tuple, fencingToken, index + 1, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, "action-rejected-after-possible-dispatch")

            val afterStart = System.nanoTime()
            val after = try { actionPort.captureFresh(executable.packageName, expectedWindow, MAX_EVIDENCE_NODES, MAX_EVIDENCE_AGE_MS) }
                catch (_: Exception) { null }
            evidenceNanos += System.nanoTime() - afterStart
            if (!fresh(after, executable.packageName, expectedWindow, elapsedClock()))
                return recover(tuple, fencingToken, index + 1, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    "expected-after-unverified")
            if (!journal.recordStepObserved(tuple, fencingToken, step.stepId) || !matches(after!!, step.expectedAfter))
                return recover(tuple, fencingToken, index + 1, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    "expected-after-unverified")
            if (!journal.recordStepVerified(tuple, fencingToken, step.stepId,
                    finalStep = index == executable.steps.lastIndex))
                return recover(tuple, fencingToken, index + 1, lookupNanos, evidenceNanos, selectorNanos, dispatchNanos,
                    "step-observation-journal-failed")
        }

        if (elapsedClock() >= requestDeadlineElapsedMs)
            return recover(tuple, fencingToken, executable.steps.size, lookupNanos, evidenceNanos,
                selectorNanos, dispatchNanos, "request-expired-before-verification")
        val verificationStart = System.nanoTime()
        val verification = try { actionPort.independentlyVerify(executable, expectedWindow!!, MAX_EVIDENCE_AGE_MS) }
            catch (_: Exception) { null }
        val verificationNanos = System.nanoTime() - verificationStart
        val verifyNow = elapsedClock()
        val verified = verification != null && verification.verified &&
            verification.packageName == executable.packageName && verification.windowId == expectedWindow &&
            verification.destinationFingerprint == executable.destination.stateFingerprint &&
            verification.observedMarkers.containsAll(executable.destination.requiredMarkers) &&
            verification.ageMs in 0..MAX_EVIDENCE_AGE_MS.toLong() && verification.observedElapsedMs <= verifyNow &&
            verifyNow - verification.observedElapsedMs <= MAX_EVIDENCE_AGE_MS &&
            verification.independenceClass in APPROVED_INDEPENDENCE &&
            expectedObserverBuildSha256?.let { HEX_64.matches(it) && verification.observerBuildSha256 == it } == true
        if (!verified || !journal.finishVerified(tuple, fencingToken, executable.steps.size,
                verifyNow, bootId())) {
            journal.finishRecoveryRequired(tuple, fencingToken)
            val record = journal.record(tuple)
            return ExecutionOutcomeV1(record?.taskState ?: "RECOVERY_REQUIRED", "destination-not-independently-verified",
                record?.completedStepCount ?: executable.steps.size, true,
                if (verification?.verified == true) "UNVERIFIED" else "UNAVAILABLE",
                ExecutionMetricsV1(lookupNanos, evidenceNanos, selectorNanos, dispatchNanos, verificationNanos))
        }
        return ExecutionOutcomeV1("VERIFIED_SUCCESS", "destination-verified", executable.steps.size,
            true, "VERIFIED", ExecutionMetricsV1(lookupNanos, evidenceNanos, selectorNanos,
                dispatchNanos, verificationNanos))
        } finally {
            heartbeat.shutdownNow()
            actionPort.release()
        }
    }

    private fun failStep(tuple: ExecutionTupleV1, fence: Long, completed: Int, lookup: Long,
                         evidence: Long, selector: Long, dispatch: Long, reason: String): ExecutionOutcomeV1 {
        val record = journal.record(tuple)
        if (record?.dispatchPossible == true) journal.finishRecoveryRequired(tuple, fence)
        else journal.refuseBeforeDispatch(tuple, reason)
        val after = journal.record(tuple)
        return ExecutionOutcomeV1(after?.taskState ?: "REFUSED", reason,
            after?.completedStepCount ?: completed, after?.dispatchPossible ?: false, "UNAVAILABLE",
            ExecutionMetricsV1(lookup, evidence, selector, dispatch))
    }

    private fun recover(tuple: ExecutionTupleV1, fence: Long, completed: Int, lookup: Long,
                        evidence: Long, selector: Long, dispatch: Long, reason: String): ExecutionOutcomeV1 {
        journal.finishRecoveryRequired(tuple, fence)
        val record = journal.record(tuple)
        return ExecutionOutcomeV1(record?.taskState ?: "RECOVERY_REQUIRED", reason,
            record?.completedStepCount ?: completed, record?.dispatchPossible ?: true, "UNAVAILABLE",
            ExecutionMetricsV1(lookup, evidence, selector, dispatch))
    }

    private fun refused(reason: String, lookupNanos: Long = 0) = ExecutionOutcomeV1(
        "REFUSED", reason, 0, false, "UNAVAILABLE", ExecutionMetricsV1(routeLookupNanos = lookupNanos))

    private fun fresh(evidence: UiEvidenceV1?, expectedPackage: String, expectedWindow: Int?, now: Long): Boolean =
        evidence != null && evidence.packageName == expectedPackage && evidence.windowId >= 0 &&
            (expectedWindow == null || evidence.windowId == expectedWindow) &&
            evidence.windowIdBefore == evidence.windowId && evidence.windowIdAfter == evidence.windowId &&
            evidence.complete && !evidence.truncated && evidence.capturedElapsedMs <= now &&
            now - evidence.capturedElapsedMs <= MAX_EVIDENCE_AGE_MS && HEX_64.matches(evidence.stateFingerprint)

    private fun matches(evidence: UiEvidenceV1, constraint: StateConstraintV1): Boolean =
        evidence.stateFingerprint == constraint.stateFingerprint && evidence.markers().containsAll(constraint.requiredMarkers)

    private fun matchesSelector(target: VisibleTargetV1, selector: SelectorV1): Boolean = when (selector.kind) {
        "text" -> target.text == selector.value || selector.value in target.textAliases
        "contentDescription" -> target.contentDescription == selector.value || selector.value in target.contentDescriptionAliases
        "resourceId" -> target.resourceId == selector.value || selector.value in target.resourceIdAliases
        else -> false
    }

    private fun parseRoute(route: PromotedRouteV1): ExecutableRouteV1 {
        fun obj(parent: Map<String, Any?>, key: String) = parent[key] as? Map<String, Any?> ?: error("invalid-$key")
        fun state(value: Map<String, Any?>): StateConstraintV1 {
            val markers = value["requiredMarkers"] as? List<*> ?: error("invalid-markers")
            return StateConstraintV1(value["stateFingerprint"] as String,
                markers.map { it as? String ?: error("invalid-marker") })
        }
        val definition = route.routeDefinition
        val app = obj(definition, "appConstraint")
        val steps = definition["steps"] as? List<*> ?: error("invalid-steps")
        return ExecutableRouteV1(route.routeId, route.routeVersion, route.routeFingerprint,
            definition["packageName"] as String, app["versionName"] as String,
            app["versionCode"] as Long, app["signerCertificateSha256"] as String,
            state(obj(definition, "origin")), state(obj(definition, "destination")),
            steps.map { raw ->
                @Suppress("UNCHECKED_CAST")
                val step = raw as Map<String, Any?>
                val selector = obj(step, "selector")
                RouteStepV1(step["stepId"] as String,
                    SelectorV1(selector["kind"] as String, selector["value"] as String),
                    state(obj(step, "expectedBefore")), state(obj(step, "expectedAfter")))
            })
    }

    companion object {
        private const val MAX_EVIDENCE_NODES = 4096
        private const val MAX_EVIDENCE_AGE_MS = 2500
        private const val MAX_REQUEST_TTL_MS = 300_000L
        private val HEX_64 = Regex("[0-9a-f]{64}")
        private val APPROVED_INDEPENDENCE = setOf("separate-observer-process", "host-independent-reader")
    }
}

private fun <T : Any> sequenceOfNotNull(vararg values: T?): Sequence<T> = values.asSequence().filterNotNull()
