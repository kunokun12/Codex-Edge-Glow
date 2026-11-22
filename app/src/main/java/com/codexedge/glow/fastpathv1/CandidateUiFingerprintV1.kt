package com.codexedge.glow.fastpathv1

import java.security.MessageDigest

/**
 * M7.1 EXPERIMENTAL fingerprint proposal, NOT part of the M1 wire contract.
 *
 * Deliberately not connected to FastPathAndroidActionPortV1. An authority must
 * approve and version the producer and its expected state digests before use.
 * No opaque node handles, timestamps, or transient window IDs are hashed.
 */
internal object CandidateUiFingerprintV1 {
    const val VERSION = "tessera.ui-state-candidate/1"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun fingerprint(packageName: String, targets: List<VisibleTargetV1>): String {
        require(packagePattern.matches(packageName)) { "Invalid package" }
        require(targets.size <= 4096) { "Too many nodes" }
        require(targets.all { it.packageName == packageName }) { "Mixed packages" }
        val nodes = targets.filter { it.visible }.map { target ->
            mapOf<String, Any?>(
                "text" to target.text,
                "contentDescription" to target.contentDescription,
                "resourceId" to target.resourceId,
                "enabled" to target.enabled,
                "actionable" to target.actionable
            )
        }.map { CanonicalRouteV1.encode(it) }.sorted()
        val encoded = CanonicalRouteV1.encode(mapOf(
            "fingerprintVersion" to VERSION,
            "packageName" to packageName,
            "visibleNodes" to nodes
        ))
        return MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
