package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateUiFingerprintV1Test {
    private fun node(
        handle: String = "h1",
        text: String? = "Settings",
        visible: Boolean = true,
        enabled: Boolean = true
    ) = VisibleTargetV1(
        opaqueHandle = handle,
        packageName = "com.example.app",
        windowId = 7,
        visible = visible,
        enabled = enabled,
        actionable = true,
        text = text,
        contentDescription = null,
        resourceId = "com.example.app:id/settings"
    )

    @Test fun stableAcrossNodeOrderingAndOpaqueHandles() {
        val a = node()
        val b = node(handle = "h2", text = "Help")
        val first = CandidateUiFingerprintV1.fingerprint("com.example.app", listOf(a, b))
        val second = CandidateUiFingerprintV1.fingerprint("com.example.app",
            listOf(b.copy(opaqueHandle = "random", windowId = 99),
                a.copy(opaqueHandle = "other", windowId = 99)))
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun changesWhenVisibleStateChanges() {
        val base = CandidateUiFingerprintV1.fingerprint("com.example.app", listOf(node()))
        assertNotEquals(base, CandidateUiFingerprintV1.fingerprint(
            "com.example.app", listOf(node(text = "Privacy"))))
        assertNotEquals(base, CandidateUiFingerprintV1.fingerprint(
            "com.example.app", listOf(node(enabled = false))))
    }

    @Test fun ignoresInvisibleNodesButPreservesDuplicates() {
        val base = CandidateUiFingerprintV1.fingerprint("com.example.app", listOf(node()))
        assertEquals(base, CandidateUiFingerprintV1.fingerprint("com.example.app",
            listOf(node(), node(handle = "hidden", visible = false))))
        assertNotEquals(base, CandidateUiFingerprintV1.fingerprint("com.example.app",
            listOf(node(), node(handle = "second"))))
    }

    @Test fun refusesMixedPackagesAndOversizedSnapshots() {
        assertThrows(IllegalArgumentException::class.java) {
            CandidateUiFingerprintV1.fingerprint("com.example.app",
                listOf(node().copy(packageName = "com.other.app")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CandidateUiFingerprintV1.fingerprint("com.example.app", List(4097) { node() })
        }
    }
}
