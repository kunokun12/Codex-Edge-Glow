package com.codexedge.glow.fastpathv1

/** Explicit dependency ports for the experimental Android runtime. Production composition is unavailable. */
internal interface V1_1RuntimePorts {
    val executionGateApproved: Boolean
    val authorityAvailable: Boolean
    val observerApproved: Boolean
    /** Returns a complete, correlated route.execute.result only after the injected runtime finishes. */
    fun execute(request: Map<String, Any?>, nowEpochMs: Long): Map<String, Any?>? = null
    fun capture(request: Map<String, Any?>): CaptureResult
}

internal data class CaptureResult(val outcome: String, val reason: String, val snapshot: Map<String, Any?>? = null,
                                 val producer: Map<String, Any?>? = null)

/** No production trust material is compiled into this fail-closed composition. */
internal object ProductionV1_1RuntimePorts : V1_1RuntimePorts {
    override val executionGateApproved = false
    override val authorityAvailable = false
    override val observerApproved = false
    override fun execute(request: Map<String, Any?>, nowEpochMs: Long): Map<String, Any?>? = null
    override fun capture(request: Map<String, Any?>) = CaptureResult("OBSERVER_UNAVAILABLE", "observer-unavailable")
}

/** Versioned response construction and strict closed validation for the three Android runtime operations. */
internal object FastPathV1_1Runtime {
    private const val PROFILE = ExperimentalEnvelopeV1_1.VERSION
    private val common = setOf("protocolVersion", "messageType", "requestId", "taskId", "sessionId", "ownerId",
        "deviceBinding", "status", "reasonCode", "observedAtEpochMs")
    private val outer = common + setOf("lease", "execution", "snapshot", "verification", "provenance")

    fun dispatch(request: Map<String, Any?>, nowEpochMs: Long,
                 ports: V1_1RuntimePorts = ProductionV1_1RuntimePorts): Map<String, Any?> {
        require(request["protocolVersion"] == PROFILE) { "unsupported-protocol-version" }
        require(nowEpochMs > 0) { "invalid-clock" }
        val response = when (request["messageType"]) {
            "route.execute" -> execute(request, nowEpochMs, ports)
            "evidence.capture" -> captureResponse(request, nowEpochMs, ports.capture(request))
            "route.verify" -> verifyUnavailable(request, nowEpochMs)
            else -> throw IllegalArgumentException("unsupported-v1.1-operation")
        }
        ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response)?.let { error(it) }
        validateResponse(response)
        return response
    }

    private fun execute(request: Map<String, Any?>, now: Long, ports: V1_1RuntimePorts): Map<String, Any?> {
        // A request cannot set these gates. Only an explicitly injected runtime composition
        // (used by isolated tests) can supply an executor; production stays closed by default.
        if (!ports.executionGateApproved || !ports.authorityAvailable || !ports.observerApproved)
            return executionRefusal(request, now, "runtime-approval-required")
        val response = try { ports.execute(request, now) } catch (_: Exception) { null }
            ?: return executionRefusal(request, now, "runtime-adapter-unavailable")
        require(response["messageType"] == "route.execute.result") { "response-operation-invalid" }
        return response
    }

    private fun base(request: Map<String, Any?>, type: String, status: String, reason: String, now: Long) = mapOf(
        "protocolVersion" to PROFILE, "messageType" to type, "requestId" to request["requestId"],
        "taskId" to request["taskId"], "sessionId" to request["sessionId"], "ownerId" to request["ownerId"],
        "deviceBinding" to request["deviceBinding"], "status" to status, "reasonCode" to reason,
        "observedAtEpochMs" to now)

    private fun executionRefusal(request: Map<String, Any?>, now: Long, reason: String): Map<String, Any?> =
        base(request, "route.execute.result", "REFUSED", reason, now) + mapOf(
            "lease" to mapOf("state" to "NONE", "expiresAtEpochMs" to null),
            "execution" to mapOf("currentStepId" to null, "completedStepCount" to 0L, "journalSequence" to 0L,
                "dispatchPossible" to false, "actionReceipt" to null),
            "snapshot" to null, "verification" to null, "provenance" to null)

    private fun captureResponse(request: Map<String, Any?>, now: Long, capture: CaptureResult): Map<String, Any?> {
        val reason = capture.reason
        require(captureOutcomeForReason(reason) == capture.outcome) { "capture-reason-outcome-mismatch" }
        if (capture.outcome == "CAPTURED") {
            require(capture.snapshot != null && capture.producer != null) { "capture-evidence-required" }
        } else require(capture.snapshot == null && capture.producer == null) { "failure-must-not-carry-evidence" }
        val status = when (capture.outcome) {
            "CAPTURED" -> "ACCEPTED"
            "STALE" -> "STALE"
            else -> "REFUSED"
        }
        return base(request, "evidence.capture.result", status, reason, now) + mapOf(
            "payload" to mapOf("captureOutcome" to capture.outcome, "reasonCode" to reason,
                "snapshot" to capture.snapshot, "producer" to capture.producer))
    }

    private fun captureOutcomeForReason(reason: String): String = when (reason) {
        "capture-complete" -> "CAPTURED"
        "snapshot-incomplete", "active-window-unavailable", "accessibility-service-unavailable", "observer-ipc-incomplete" -> "INCOMPLETE"
        "snapshot-stale", "snapshot-pre-dispatch", "observer-ipc-stale-or-pre-dispatch" -> "STALE"
        "permission-unavailable", "observer-permission-denied", "observer-service-lookup-denied" -> "PERMISSION_UNAVAILABLE"
        "observer-timeout", "observer-reply-timeout", "observer-wait-interrupted" -> "TIMEOUT"
        "unsupported-evidence-profile" -> "UNSUPPORTED_PROFILE"
        "observer-unavailable", "observer-service-unresolved", "observer-bind-rejected", "observer-bind-failed",
        "observer-service-disconnected", "observer-binding-died", "observer-null-binding", "observer-null-binder",
        "observer-send-failed", "observer-transport-failed", "observer-response-invalid", "observer-caller-invalid",
        "observer-protocol-invalid", "observer-request-invalid", "observer-timebase-unavailable",
        "observer-ipc-identity-mismatch", "observer-ipc-request-invalid", "observer-ipc-challenge-invalid",
        "observer-ipc-timebase-invalid", "observer-ipc-protocol-invalid", "observer-ipc-package-missing",
        "observer-ipc-window-missing", "observer-ipc-capture-time-missing", "observer-ipc-digest-missing",
        "observer-ipc-correlation-mismatch", "observer-ipc-timebase-mismatch", "observer-ipc-refusal-code-missing",
        "observer-ipc-refusal-code-invalid", "observer-ipc-refusal-carried-evidence", "observer-ipc-outcome-invalid",
        "observer-ipc-window-mismatch", "observer-ipc-incomplete", "observer-ipc-digest-invalid",
        "observer-ipc-package-mismatch", "observer-ipc-artifact-invalid", "observer-ipc-forged-verification",
        "ipc-caller-invalid", "ipc-protocol-invalid", "ipc-correlation-invalid", "ipc-challenge-invalid",
        "ipc-boundary-invalid", "ipc-timebase-mismatch", "ipc-timebase-unavailable", "ipc-challenge-replayed",
        "snapshot-package-mismatch", "snapshot-window-mismatch", "snapshot-digest-invalid" -> "OBSERVER_UNAVAILABLE"
        else -> error("unsupported-capture-reason")
    }

    private fun verifyUnavailable(request: Map<String, Any?>, now: Long): Map<String, Any?> =
        base(request, "route.verify.result", "REFUSED", "observer-unavailable", now) + mapOf(
            "payload" to mapOf("outcome" to "UNAVAILABLE", "reasonCode" to "observer-unavailable", "evidence" to null))

    /** Validates all response members emitted by the Android router; no extra fields or legacy aliases. */
    fun validateResponse(value: Map<String, Any?>) {
        val requestType = when (value["messageType"]) {
            "route.execute.result" -> "route.execute"
            "evidence.capture.result" -> "evidence.capture"
            "route.verify.result" -> "route.verify"
            else -> error("response-operation-invalid")
        }
        val expectedFields = when (requestType) {
            "route.execute" -> outer
            else -> common + "payload"
        }
        require(value.keys == expectedFields) { "response-fields-invalid" }
        require(value["protocolVersion"] == PROFILE) { "response-profile-invalid" }
        require(value["requestId"] is String && value["taskId"] is String && value["sessionId"] is String && value["ownerId"] is String)
        require(value["deviceBinding"] is Map<*, *>)
        require(value["status"] in setOf("ACCEPTED", "REFUSED", "STALE", "RECOVERY_REQUIRED", "VERIFIED_SUCCESS"))
        require(value["reasonCode"] is String && Regex("[a-z][a-z0-9-]{0,63}").matches(value["reasonCode"] as String))
        require(value["observedAtEpochMs"] is Long && (value["observedAtEpochMs"] as Long) > 0)
        when (requestType) {
            "route.execute" -> {
                require(value.keys == outer)
                val e = value["execution"] as? Map<*, *> ?: error("execution-invalid")
                require(e.keys == setOf("currentStepId", "completedStepCount", "journalSequence", "dispatchPossible", "actionReceipt"))
                val lease = value["lease"] as? Map<*, *> ?: error("lease-invalid")
                require(lease.keys == setOf("state", "expiresAtEpochMs") && lease["state"] in setOf("ACTIVE", "EXPIRED", "RELEASED", "NONE"))
                require(lease["expiresAtEpochMs"] == null || (lease["expiresAtEpochMs"] as? Number)?.toLong()?.let { it > 0 } == true)
                require(e["currentStepId"] == null || e["currentStepId"] is String)
                require((e["completedStepCount"] as? Number)?.toLong()?.let { it >= 0 } == true)
                require((e["journalSequence"] as? Number)?.toLong()?.let { it >= 0 } == true)
                require(e["dispatchPossible"] is Boolean && e["actionReceipt"] in setOf("ACCEPTED", "REJECTED", "UNCERTAIN", null))
                if (value["status"] == "VERIFIED_SUCCESS") {
                    val verification = value["verification"] as? Map<*, *> ?: error("verification-required")
                    require(verification.keys == setOf("status", "packageName", "windowId", "observedAtEpochMs", "ageMs",
                        "destinationFingerprint", "independent", "observerId"))
                    require(verification["status"] == "VERIFIED" && verification["independent"] == true &&
                        verification["packageName"] is String && verification["observerId"] is String)
                    require((verification["windowId"] as? Number)?.toLong()?.let { it >= 0 } == true &&
                        (verification["observedAtEpochMs"] as? Number)?.toLong()?.let { it > 0 } == true &&
                        (verification["ageMs"] as? Number)?.toDouble()?.let { it >= 0.0 } == true &&
                        (verification["destinationFingerprint"] as? String)?.let { Regex("[0-9a-f]{64}").matches(it) } == true)
                    require(e["dispatchPossible"] == true && e["actionReceipt"] == "ACCEPTED" &&
                        (e["journalSequence"] as Number).toLong() > 0 && (e["completedStepCount"] as Number).toLong() > 0)
                    val provenance = value["provenance"] as? Map<*, *> ?: error("provenance-required")
                    require(provenance.keys == setOf("sourceCommit", "artifactSha256", "signerCertificateSha256", "versionName", "versionCode"))
                    require((provenance["sourceCommit"] as? String)?.let { it.length in 7..64 } == true &&
                        (provenance["artifactSha256"] as? String)?.let { Regex("[0-9a-f]{64}").matches(it) } == true &&
                        (provenance["signerCertificateSha256"] as? String)?.let { Regex("[0-9a-f]{64}").matches(it) } == true &&
                        (provenance["versionName"] as? String)?.isNotEmpty() == true &&
                        (provenance["versionCode"] as? Number)?.toLong()?.let { it > 0 } == true)
                    require(value["snapshot"] == null || value["snapshot"] is Map<*, *>)
                } else if (e["dispatchPossible"] == false) {
                    require(value["snapshot"] == null && value["verification"] == null && value["provenance"] == null)
                    require(e["actionReceipt"] == null)
                } else {
                    require(value["status"] in setOf("RECOVERY_REQUIRED", "UNKNOWN"))
                    require(value["verification"] == null || (value["verification"] as? Map<*, *>)?.get("status") != "VERIFIED")
                    require(value["provenance"] == null)
                }
            }
            "evidence.capture" -> {
                val p = value["payload"] as? Map<*, *> ?: error("capture-payload-invalid")
                require(p.keys == setOf("captureOutcome", "reasonCode", "snapshot", "producer"))
                require(p["reasonCode"] == value["reasonCode"])
                require(captureOutcomeForReason(p["reasonCode"] as? String ?: "") == p["captureOutcome"])
                if (p["captureOutcome"] == "CAPTURED") require(value["status"] == "ACCEPTED" && p["snapshot"] is Map<*, *> && p["producer"] is Map<*, *>)
                else require(value["status"] == (if (p["captureOutcome"] == "STALE") "STALE" else "REFUSED") && p["snapshot"] == null && p["producer"] == null)
            }
            "route.verify" -> {
                val p = value["payload"] as? Map<*, *> ?: error("verify-payload-invalid")
                require(p.keys == setOf("outcome", "reasonCode", "evidence"))
                require(p["outcome"] == "UNAVAILABLE" && p["reasonCode"] == "observer-unavailable" && p["evidence"] == null)
                require(value["status"] == "REFUSED")
            }
        }
    }
}
