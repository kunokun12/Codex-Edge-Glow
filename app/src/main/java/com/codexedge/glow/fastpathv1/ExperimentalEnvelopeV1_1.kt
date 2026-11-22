package com.codexedge.glow.fastpathv1

/**
 * Experimental protocol v1.1 adapter. This parser is deliberately not wired to
 * the v1 broadcast receiver or a production serializer/handler. All use remains
 * draft until the protocol owner approves the M8 proposal and reason catalogue.
 */
internal object ExperimentalEnvelopeV1_1 {
    const val VERSION = "tessera.fast-path/1.1"
    const val APPROVAL_STATE = "EXPERIMENTAL_NOT_APPROVED"

    private val responseTypes = mapOf(
        "route.promote" to "route.promote.result",
        "route.revoke" to "route.revoke.result",
        "route.execute" to "route.execute.result",
        "route.cancel" to "route.cancel.result",
        "route.status" to "route.status.result",
        "evidence.capture" to "evidence.capture.result",
        "route.verify" to "route.verify.result",
    )

    fun parseRequest(json: String): Map<String, Any?> = StrictEnvelopeV1.parseExperimentalV1_1(json)

    fun expectedResponseType(requestType: String): String? = responseTypes[requestType]

    /** Correlates exact operation and all request identity fields; no v1 result alias. */
    fun validateResponseCorrelation(request: Map<String, Any?>, response: Map<String, Any?>): String? {
        if (request["protocolVersion"] != VERSION || response["protocolVersion"] != VERSION)
            return "unsupported-protocol-version"
        if (response["messageType"] != responseTypes[request["messageType"]])
            return "response-operation-mismatch"
        for (key in listOf("requestId", "taskId", "sessionId", "ownerId")) {
            if (request[key] != response[key]) return "response-correlation-mismatch"
        }
        if (CanonicalRouteV1.encode(request["deviceBinding"]) != CanonicalRouteV1.encode(response["deviceBinding"]))
            return "response-device-binding-mismatch"
        return null
    }

    /** Canonical UTF-8 serializer for already validated experimental records. */
    fun canonicalBytes(value: Any?): ByteArray = CanonicalRouteV1.encode(value).toByteArray(Charsets.UTF_8)
}
