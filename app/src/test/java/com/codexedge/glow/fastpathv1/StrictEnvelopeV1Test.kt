package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictEnvelopeV1Test {
    private val base = """{
      "protocolVersion":"tessera.fast-path/1",
      "messageType":"route.status",
      "requestId":"00000000-0000-4000-8000-000000000001",
      "taskId":"00000000-0000-4000-8000-000000000002",
      "sessionId":"00000000-0000-4000-8000-000000000003",
      "ownerId":"00000000-0000-4000-8000-000000000004",
      "deviceBinding":{
        "deviceSerial":"fixture-device",
        "companionPackage":"com.codexedge.glow",
        "companionVersionCode":18,
        "signerCertificateSha256":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      },
      "expiresAtEpochMs":1790000000000,
      "payload":{"routeId":"benchmark.open_guide","routeVersion":1,"routeFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","maxAgeMs":1000,"afterSequence":0,"afterDigest":"0000000000000000000000000000000000000000000000000000000000000000","maxEvents":256}
    }""".trimIndent()

    @Test fun acceptsWellFormedEnvelope() {
        assertEquals("route.status", StrictEnvelopeV1.parse(base)["messageType"])
    }

    @Test fun rejectsMissingAndUnknownProtocols() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("tessera.fast-path/1", "tessera.fast-path/2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"protocolVersion\":\"tessera.fast-path/1\",", ""))
        }
    }

    @Test fun rejectsDuplicateAndUnknownMembers() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"messageType\":\"route.status\"",
                "\"messageType\":\"route.status\",\"messageType\":\"route.status\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"payload\":", "\"extra\":1,\"payload\":"))
        }
    }

    @Test fun rejectsMissingAndExtraPayloadFields() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"routeId\":\"benchmark.open_guide\",", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"maxAgeMs\":1000", "\"maxAgeMs\":1000,\"unknown\":true"))
        }
    }

    @Test fun rejectsUnsafeNumbersAndFractions() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("1790000000000", "9007199254740992"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("1790000000000", "1.5"))
        }
    }

    @Test fun rejectsTrailingJson() {
        assertThrows(Exception::class.java) { StrictEnvelopeV1.parse(base + " {}") }
    }
    @Test fun statusRequiresJournalCursor() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"afterSequence\":0,", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"maxEvents\":256", "\"maxEvents\":257"))
        }
    }

    @Test fun strictTransportRejectsMalformedUtf8AndExcessTtl() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            StrictEnvelopeV1.parseTransport(byteArrayOf(0xC3.toByte(), 0x28), 1789999999000L)
        }
        assertEquals("route.status", StrictEnvelopeV1.parseTransport(
            base.toByteArray(Charsets.UTF_8), 1789999999000L)["messageType"])
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parseTransport(base.toByteArray(Charsets.UTF_8), 1789999000000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parseTransport(base.toByteArray(Charsets.UTF_8), 1790000000000L)
        }
    }

    @Test fun serialLengthCountsUnicodeCodePoints() {
        val oneHundredTwentyEight = "😀".repeat(128)
        val allowed = base.replace("fixture-device", oneHundredTwentyEight)
        assertEquals("route.status", StrictEnvelopeV1.parse(allowed)["messageType"])
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("fixture-device", "😀".repeat(129)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("fixture-device", ""))
        }
    }

    @Test fun statusRejectsInvalidJournalCursor() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"afterSequence\":0", "\"afterSequence\":-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"maxEvents\":256", "\"maxEvents\":0"))
        }
    }


    private val hash = "a".repeat(64)
    private val routeDefinition: String get() = """
        {"packageName":"dev.androidroute.benchmark",
         "appConstraint":{"versionName":"1.0","versionCode":1,"signerCertificateSha256":"$hash"},
         "origin":{"stateFingerprint":"$hash","requiredMarkers":["Home"]},
         "destination":{"stateFingerprint":"$hash","requiredMarkers":["Guide"]},
         "windowPolicy":"same_window",
         "steps":[{"stepId":"open-guide",
            "selector":{"kind":"text","value":"Guide"},
            "uniquenessPolicy":"exactly_one_actionable_target",
            "expectedBefore":{"stateFingerprint":"$hash","requiredMarkers":["Home"]},
            "expectedAfter":{"stateFingerprint":"$hash","requiredMarkers":["Guide"]}}]}
    """.trimIndent()
    private fun jsonToKotlin(node: com.google.gson.JsonElement): Any? = when {
        node.isJsonNull -> null
        node.isJsonObject -> node.asJsonObject.entrySet().associate { it.key to jsonToKotlin(it.value) }
        node.isJsonArray -> node.asJsonArray.map { jsonToKotlin(it) }
        node.asJsonPrimitive.isBoolean -> node.asBoolean
        node.asJsonPrimitive.isNumber -> node.asLong
        else -> node.asString
    }

    private fun envelope(type: String, payload: String): String {
        var content = payload
        if (type == "route.execute" || type == "route.promote") {
            val root = com.google.gson.JsonParser.parseString(payload).asJsonObject
            val record = if (type == "route.promote") root.getAsJsonObject("promotionRecord") else root
            val definition = jsonToKotlin(record.get("routeDefinition"))
            @Suppress("UNCHECKED_CAST")
            val calculated = CanonicalRouteV1.fingerprint(
                record.get("routeId").asString, record.get("routeVersion").asInt,
                definition as Map<String, Any?>)
            content = payload.replace("\"routeFingerprint\":\"$hash\"",
                "\"routeFingerprint\":\"$calculated\"")
        }
        return base.replace("\"messageType\":\"route.status\"", "\"messageType\":\"$type\"")
            .replace(Regex("\"payload\":\\{.*\\}\\s*\\}$", RegexOption.DOT_MATCHES_ALL),
                "\"payload\":$content}")
    }

    @Test fun nestedRouteAcceptsExactSchemaAndRejectsUnknownFields() {
        val payload = """{"routeId":"benchmark.open_guide","routeVersion":1,
            "routeFingerprint":"$hash","routeDefinition":$routeDefinition}"""
        assertEquals("route.execute", StrictEnvelopeV1.parse(envelope("route.execute", payload))["messageType"])
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(envelope("route.execute",
                payload.replace("\"windowPolicy\":\"same_window\"",
                    "\"windowPolicy\":\"same_window\",\"extra\":true")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(envelope("route.execute",
                payload.replace("\"kind\":\"text\"", "\"kind\":\"coordinates\"")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(envelope("route.execute",
                payload.replace("\"requiredMarkers\":[\"Home\"]",
                    "\"requiredMarkers\":[\"Home\",\"Home\"]")))
        }
    }

    @Test fun promotionAndRevocationRequireClosedSignedRecords() {
        val promotion = """{"promotionRecord":{"authorityKeyId":"root-1",
            "registryEpoch":1,"issuedAtEpochMs":1,"expiresAtEpochMs":2,
            "routeId":"benchmark.open_guide","routeVersion":1,
            "routeFingerprint":"$hash","routeDefinition":$routeDefinition,
            "signature":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}"""
        assertEquals("route.promote",
            StrictEnvelopeV1.parse(envelope("route.promote", promotion))["messageType"])
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(envelope("route.promote",
                promotion.replace("\"registryEpoch\":1,", "")))
        }
        val revoke = """{"revocationRecord":{"authorityKeyId":"root-1",
            "registryEpoch":2,"issuedAtEpochMs":1,"routeId":"benchmark.open_guide",
            "routeVersion":1,"routeFingerprint":"$hash","reasonCode":"retired",
            "signature":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}"""
        assertEquals("route.revoke",
            StrictEnvelopeV1.parse(envelope("route.revoke", revoke))["messageType"])
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(envelope("route.revoke",
                revoke.replace("\"reasonCode\":\"retired\"", "\"reasonCode\":\"INVALID\"")))
        }
    }

    @Test fun schemaLengthUsesRawCodePointsBeforeNfc() {
        val decomposed = "e\u0301".repeat(65)
        val payload = """{"routeId":"benchmark.open_guide","routeVersion":1,
            "routeFingerprint":"$hash","routeDefinition":""" +
            routeDefinition.replace("\"versionName\":\"1.0\"",
                "\"versionName\":\"$decomposed\"") + "}"
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(envelope("route.execute", payload))
        }
    }

    @Test fun nfcEquivalentJsonKeysAreRejectedBeforeSchemaValidation() {
        val altered = base.replace("\"deviceSerial\":\"fixture-device\"",
            "\"deviceSerial\":\"fixture-device\",\"de\u0301vice\":\"x\",\"dévice\":\"y\"")
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(altered)
        }
    }

    @Test fun canonicalFingerprintNormalizesEquivalentRouteStrings() {
        val decomposed = routeDefinition.replace("Guide", "Guide\u0301")
        val composed = routeDefinition.replace("Guide", "Guidé")
        val payloadPrefix = """{"routeId":"benchmark.open_guide","routeVersion":1,
            "routeFingerprint":"$hash","routeDefinition":"""
        val b = StrictEnvelopeV1.parse(envelope("route.execute", payloadPrefix + decomposed + "}"))
        val c = StrictEnvelopeV1.parse(envelope("route.execute", payloadPrefix + composed + "}"))
        @Suppress("UNCHECKED_CAST")
        fun digest(v: Map<String, Any?>) = CanonicalRouteV1.fingerprint(
            "benchmark.open_guide", 1,
            (v["payload"] as Map<String, Any?>)["routeDefinition"] as Map<String, Any?>)
        assertEquals(digest(b), digest(c))
    }

    @Test fun routeExecuteRejectsIncorrectFingerprint() {
        val payload = """{"routeId":"benchmark.open_guide","routeVersion":1,
            "routeFingerprint":"$hash","routeDefinition":$routeDefinition}"""
        val valid = envelope("route.execute", payload)
        assertEquals("route.execute", StrictEnvelopeV1.parse(valid)["messageType"])
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(valid.replace(
                Regex("\"routeFingerprint\":\"[0-9a-f]{64}\""),
                "\"routeFingerprint\":\"0000000000000000000000000000000000000000000000000000000000000000\""))
        }
    }

}
