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
      "payload":{}
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
            StrictEnvelopeV1.parse(base.replace("\"payload\":{}", "\"payload\":{},\"payload\":{}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictEnvelopeV1.parse(base.replace("\"payload\":{}", "\"payload\":{},\"extra\":1"))
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
}
