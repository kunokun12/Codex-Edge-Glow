package com.codexedge.glow.fastpathv1

/** Explicit dependency ports for the experimental Android runtime. Production composition is unavailable. */
internal interface V1_1RuntimePorts {
    val executionGateApproved: Boolean
    val authorityAvailable: Boolean
    val observerApproved: Boolean
    fun capture(request: Map<String, Any?>): CaptureResult
}

internal data class CaptureResult(val outcome: String, val reason: String, val snapshot: Map<String, Any?>? = null,
                                 val producer: Map<String, Any?>? = null)

/** No production trust material is compiled into this fail-closed composition. */
internal object ProductionV1_1RuntimePorts : V1_1RuntimePorts {
    override val executionGateApproved = false
    override val authorityAvailable = false
    override val observerApproved = false
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
            "route.execute" -> executionRefusal(request, nowEpochMs,
                if (ports.executionGateApproved && ports.authorityAvailable && ports.observerApproved) "runtime-adapter-unavailable" else "runtime-approval-required")
            "evidence.capture" -> captureResponse(request, nowEpochMs, ports.capture(request))
            "route.verify" -> verifyUnavailable(request, nowEpochMs)
            else -> throw IllegalArgumentException("unsupported-v1.1-operation")
        }
        ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response)?.let { error(it) }
        validateResponse(response)
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
        val expectedReason = when (capture.outcome) {
            "CAPTURED" -> "capture-complete"
            "OBSERVER_UNAVAILABLE" -> "observer-unavailable"
            "INCOMPLETE" -> "snapshot-incomplete"
            "STALE" -> "snapshot-stale"
            "PERMISSION_UNAVAILABLE" -> "permission-unavailable"
            "TIMEOUT" -> "observer-timeout"
            "UNSUPPORTED_PROFILE" -> "unsupported-evidence-profile"
            else -> error("unsupported-capture-outcome")
        }
        require(reason == expectedReason) { "capture-reason-outcome-mismatch" }
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
                require(value["lease"] == mapOf("state" to "NONE", "expiresAtEpochMs" to null))
                val e = value["execution"] as? Map<*, *> ?: error("execution-invalid")
                require(e.keys == setOf("currentStepId", "completedStepCount", "journalSequence", "dispatchPossible", "actionReceipt"))
                require(e["dispatchPossible"] == false && e["actionReceipt"] == null && (e["completedStepCount"] as? Number)?.toLong() == 0L && (e["journalSequence"] as? Number)?.toLong() == 0L)
                require(value["snapshot"] == null && value["verification"] == null && value["provenance"] == null)
                require(value["status"] == "REFUSED" && value["reasonCode"] in setOf("runtime-approval-required", "runtime-adapter-unavailable"))
            }
            "evidence.capture" -> {
                val p = value["payload"] as? Map<*, *> ?: error("capture-payload-invalid")
                require(p.keys == setOf("captureOutcome", "reasonCode", "snapshot", "producer"))
                require(p["reasonCode"] == value["reasonCode"])
                val expectedReason = when (p["captureOutcome"]) {
                    "CAPTURED" -> "capture-complete"
                    "OBSERVER_UNAVAILABLE" -> "observer-unavailable"
                    "INCOMPLETE" -> "snapshot-incomplete"
                    "STALE" -> "snapshot-stale"
                    "PERMISSION_UNAVAILABLE" -> "permission-unavailable"
                    "TIMEOUT" -> "observer-timeout"
                    "UNSUPPORTED_PROFILE" -> "unsupported-evidence-profile"
                    else -> error("capture-outcome-invalid")
                }
                require(p["reasonCode"] == expectedReason)
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
