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

}
