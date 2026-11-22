package com.codexedge.glow

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import com.codexedge.glow.fastpathv1.AuthorityTrustV1
import com.codexedge.glow.fastpathv1.CanonicalRouteV1
import com.codexedge.glow.fastpathv1.ExecutionTupleV1
import com.codexedge.glow.fastpathv1.FastPathExecutionJournalV1
import com.codexedge.glow.fastpathv1.FastPathRegistryV1
import com.codexedge.glow.fastpathv1.FastPathResultStoreV1
import com.codexedge.glow.fastpathv1.RegistryOperationReceiptV1
import com.codexedge.glow.fastpathv1.StrictEnvelopeV1
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * DUMP-protected Fast Path v1 endpoint. Invalid requests never reach an older
 * protocol handler. Route execution remains fail-closed until the missing M1
 * fingerprint producer and independent verifier are available and release gates
 * are met.
 */
class FastPathV1Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        var ack = "REJECTED"
        try {
            // The exported component's manifest permission is the security
            // boundary. Broadcast delivery runs through system_server, so
            // Binder.getCallingUid() here is not a reliable sender identity.
            val extras = intent.extras ?: throw IllegalArgumentException("missing-envelope-extra")
            if (extras.keySet() != setOf(REQUEST_EXTRA)) throw IllegalArgumentException("unexpected-intent-extra")
            val encoded = intent.getStringExtra(REQUEST_EXTRA)
                ?: throw IllegalArgumentException("missing-envelope")
            require(encoded.length <= MAX_BASE64_CHARS && BASE64URL.matches(encoded)) { "invalid-base64url" }
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(bytes.size <= MAX_ENVELOPE_BYTES) { "oversized-envelope" }
            require(Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) == encoded) {
                "noncanonical-base64url"
            }
            val request = StrictEnvelopeV1.parseTransport(bytes, System.currentTimeMillis())
            val results = FastPathResultStoreV1(context)
            val prior = results.existingResponse(request)
            if (prior != null) {
                ack = "ACKNOWLEDGED"
            } else {
                val response = handle(context, request)
                if (response == null) {
                    ack = "UNIMPLEMENTED"
                } else {
                    // The handler returns only after its durable operation (if
                    // mutating) commits. A result-file error must not be reported
                    // as a definite rejection after that commit. Mutating route
                    // receipts remain readable from the registry database.
                    ack = "ACKNOWLEDGED"
                    try { results.save(request, response) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
            ack = "REJECTED"
        }
        if (isOrderedBroadcast) {
            resultCode = if (ack == "REJECTED") Activity.RESULT_CANCELED else Activity.RESULT_OK
            resultData = ack
        }
    }

    private fun handle(context: Context, request: Map<String, Any?>): Map<String, Any?>? {
        val type = request.getValue("messageType") as String
        val payload = request.getValue("payload") as Map<String, Any?>
        return when (type) {
            "route.promote" -> promote(context, request, payload)
            "route.revoke" -> revoke(context, request, payload)
            "route.cancel" -> cancel(context, request)
            "route.status" -> status(context, request)
            // The executor has no approved stateFingerprint producer or separate
            // verifier yet. Acknowledging an action would violate the fail-closed gate.
            "route.execute", "evidence.capture", "route.verify" -> null
            else -> null
        }
    }

    private fun promote(context: Context, request: Map<String, Any?>,
                        payload: Map<String, Any?>): Map<String, Any?> {
        val record = payload["promotionRecord"] as Map<String, Any?>
        val trust = AuthorityTrustV1.fromApkAsset(context)
        val routeId = record.getValue("routeId") as String
        val routeVersion = record.getValue("routeVersion") as Long
        val fingerprint = record.getValue("routeFingerprint") as String
        val epoch = record.getValue("registryEpoch") as Long
        val authority = record.getValue("authorityKeyId") as String
        val expiry = record.getValue("expiresAtEpochMs") as Long
        val success = response(request, "route.promote.result", "PROMOTED", "promoted",
            mapOf("routeId" to routeId, "routeVersion" to routeVersion,
                "routeFingerprint" to fingerprint, "lifecycle" to "PROMOTED",
                "registryEpoch" to epoch, "authorityKeyId" to authority,
                "expiresAtEpochMs" to expiry))
        val receipt = RegistryOperationReceiptV1(request.getValue("requestId") as String,
            "route.promote", sha256(CanonicalRouteV1.encode(request)), CanonicalRouteV1.encode(success))
        val refusal: (String, Long) -> String = { reason, currentEpoch ->
            CanonicalRouteV1.encode(response(request, "route.promote.result", "REFUSED", reason,
                mapOf("routeId" to routeId, "routeVersion" to routeVersion,
                    "routeFingerprint" to fingerprint, "lifecycle" to "REFUSED",
                    "registryEpoch" to currentEpoch, "authorityKeyId" to authority,
                    "expiresAtEpochMs" to expiry)))
        }
        FastPathRegistryV1(context, trust).use { registry ->
            val mutation = registry.applyPromotion(record, receipt, refusal)
            mutation.responseJson?.let { return responseFromJson(it) }
            if (mutation.accepted) return success
            return response(request, "route.promote.result", "REFUSED", mutation.reasonCode,
                mapOf("routeId" to routeId, "routeVersion" to routeVersion,
                    "routeFingerprint" to fingerprint, "lifecycle" to "REFUSED",
                    "registryEpoch" to mutation.registryEpoch, "authorityKeyId" to authority,
                    "expiresAtEpochMs" to expiry))
        }
    }

    private fun revoke(context: Context, request: Map<String, Any?>,
                       payload: Map<String, Any?>): Map<String, Any?> {
        val record = payload["revocationRecord"] as Map<String, Any?>
        val trust = AuthorityTrustV1.fromApkAsset(context)
        val routeId = record.getValue("routeId") as String
        val routeVersion = record.getValue("routeVersion") as Long
        val fingerprint = record.getValue("routeFingerprint") as String
        val epoch = record.getValue("registryEpoch") as Long
        val authorityReason = record.getValue("reasonCode") as String
        val success = response(request, "route.revoke.result", "REVOKED", "revoked",
            mapOf("routeId" to routeId, "routeVersion" to routeVersion,
                "routeFingerprint" to fingerprint, "lifecycle" to "REVOKED",
                "registryEpoch" to epoch, "reasonCode" to authorityReason))
        val receipt = RegistryOperationReceiptV1(request.getValue("requestId") as String,
            "route.revoke", sha256(CanonicalRouteV1.encode(request)), CanonicalRouteV1.encode(success),
            resultReasonCode = "revoked")
        val refusal: (String, Long) -> String = { reason, currentEpoch ->
            CanonicalRouteV1.encode(response(request, "route.revoke.result", "REFUSED", reason,
                mapOf("routeId" to routeId, "routeVersion" to routeVersion,
                    "routeFingerprint" to fingerprint, "lifecycle" to "REFUSED",
                    "registryEpoch" to currentEpoch, "reasonCode" to authorityReason)))
        }
        FastPathRegistryV1(context, trust).use { registry ->
            val mutation = registry.applyRevocation(record, receipt, refusal)
            mutation.responseJson?.let { return responseFromJson(it) }
            if (mutation.accepted) return success
            return response(request, "route.revoke.result", "REFUSED", mutation.reasonCode,
                mapOf("routeId" to routeId, "routeVersion" to routeVersion,
                    "routeFingerprint" to fingerprint, "lifecycle" to "REFUSED",
                    "registryEpoch" to mutation.registryEpoch, "reasonCode" to authorityReason))
        }
    }

    private fun cancel(context: Context, request: Map<String, Any?>): Map<String, Any?> {
        val tuple = tuple(request)
        val result = FastPathExecutionJournalV1(context).use { it.cancel(tuple) }
        val status = if (result.taskState == "RECOVERY_REQUIRED") "RECOVERY_REQUIRED"
            else if (result.accepted) "CANCELLED" else "REFUSED"
        return response(request, "route.cancel.result", status,
            if (result.accepted) "cancellation-recorded" else "execution-not-owned",
            mapOf("taskState" to result.taskState, "stopAccepted" to result.accepted,
                "stopAfterCurrentStep" to result.stopAfterCurrentStep,
                "journalSequence" to result.journalSequence,
                "dispatchPossible" to result.dispatchPossible))
    }

    private fun status(context: Context, request: Map<String, Any?>): Map<String, Any?> {
        val tuple = tuple(request)
        val requested = request.getValue("payload") as Map<String, Any?>
        val after = requested.getValue("afterSequence") as Long
        val requestedDigest = requested.getValue("afterDigest") as String
        val maxEvents = (requested.getValue("maxEvents") as Long).toInt()
        val journal = FastPathExecutionJournalV1(context)
        try {
            val record = journal.recordForTask(tuple)
            val page = try {
                journal.status(tuple, after, requestedDigest, maxEvents,
                    SystemClock.elapsedRealtime(), bootId())
            } catch (_: Exception) { null }
            val expectedRoute = requested["routeId"] == record?.routeId &&
                requested["routeVersion"] == record?.routeVersion &&
                requested["routeFingerprint"] == record?.routeFingerprint
            val state = when {
                record == null -> "REFUSED"
                page == null -> "STALE"
                !expectedRoute -> "REFUSED"
                page.leaseState != "ACTIVE" && record.taskState !in TERMINAL_STATES -> "STALE"
                else -> record.taskState
            }
            val reason = when {
                record == null -> "execution-not-found"
                page == null -> "journal-cursor-or-owner-invalid"
                !expectedRoute -> "route-correlation-mismatch"
                page.leaseState != "ACTIVE" && record.taskState !in TERMINAL_STATES -> "lease-not-active"
                else -> "status-read"
            }
            val events = page?.events?.map { it.wire() } ?: emptyList()
            val headSequence = page?.journalHeadSequence ?: record?.journalSequence ?: 0L
            val headDigest = page?.journalHeadDigest ?: record?.journalHeadDigest ?: ZERO_DIGEST
            val payload = mapOf(
                "taskState" to (record?.taskState ?: "REFUSED"),
                "currentStepId" to record?.currentStepId,
                "completedStepCount" to (record?.completedStepCount ?: 0),
                "journalSequence" to (record?.journalSequence ?: 0L),
                "dispatchPossible" to (record?.dispatchPossible ?: false),
                "leaseState" to (page?.leaseState ?: "NONE"),
                "leaseExpiresAtElapsedRealtimeMs" to page?.leaseExpiresElapsedMs,
                "observedAtElapsedRealtimeMs" to (page?.observedElapsedMs ?: SystemClock.elapsedRealtime()),
                "executionRequestId" to (record?.tuple?.requestId ?: tuple.requestId),
                "fromSequence" to (page?.fromSequence ?: after + 1),
                "throughSequence" to (page?.throughSequence ?: after),
                "journalHeadSequence" to headSequence,
                "journalHeadDigest" to headDigest,
                "events" to events,
                "hasMore" to (page?.hasMore ?: false)
            )
            return response(request, "route.status.result", state, reason, payload)
        } finally { journal.close() }
    }

    private fun tuple(request: Map<String, Any?>): ExecutionTupleV1 {
        val binding = request.getValue("deviceBinding") as Map<String, Any?>
        return ExecutionTupleV1(request.getValue("requestId") as String,
            request.getValue("taskId") as String, request.getValue("sessionId") as String,
            request.getValue("ownerId") as String, binding.getValue("deviceSerial") as String)
    }

    private fun response(request: Map<String, Any?>, type: String, status: String,
                         reason: String, payload: Map<String, Any?>): Map<String, Any?> = mapOf(
        "protocolVersion" to StrictEnvelopeV1.VERSION,
        "messageType" to type,
        "requestId" to request.getValue("requestId"),
        "taskId" to request.getValue("taskId"),
        "sessionId" to request.getValue("sessionId"),
        "ownerId" to request.getValue("ownerId"),
        "deviceBinding" to request.getValue("deviceBinding"),
        "status" to status,
        "reasonCode" to reason,
        "observedAtEpochMs" to System.currentTimeMillis(),
        "payload" to payload
    )

    private fun responseFromJson(json: String): Map<String, Any?> {
        val obj = JsonParser.parseString(json).asJsonObject
        @Suppress("UNCHECKED_CAST")
        return jsonValue(obj) as Map<String, Any?>
    }

    private fun jsonValue(value: JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to jsonValue(it.value) }
        value.isJsonArray -> value.asJsonArray.map(::jsonValue)
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> value.asLong
        else -> value.asString
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun bootId(): String = try { File("/proc/sys/kernel/random/boot_id").readText().trim() }
        catch (_: Exception) { "boot-unavailable" }

    companion object {
        const val ACTION = "com.codexedge.glow.FAST_PATH_V1"
        private const val REQUEST_EXTRA = "envelope_b64"
        private const val MAX_BASE64_CHARS = 87384
        private const val MAX_ENVELOPE_BYTES = 65536
        private val TERMINAL_STATES = setOf("VERIFIED_SUCCESS", "REFUSED", "CANCELLED", "RECOVERY_REQUIRED")
        private const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")
    }
}
