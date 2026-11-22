package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M8 regression guard: candidate/test state must never become production authority implicitly. */
@RunWith(RobolectricTestRunner::class)
class M8ProductionAuthorizationGateV1Test {
    @Test fun packagedProductionTrustAssetIsExplicitlyEmptyAndFailClosed() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.assets.open("fastpath-authorities-v1.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = org.json.JSONObject(raw)
        assertEquals(setOf("keysetVersion", "anchors"), root.keys().asSequence().toSet())
        assertEquals(1, root.getInt("keysetVersion"))
        assertEquals(0, root.getJSONArray("anchors").length())

        val trust = AuthorityTrustV1.fromApkAsset(context)
        assertFalse(trust.isConfigured)
        assertTrue(trust.keysForEpoch(1L).isEmpty())
    }

    private fun assertEquals(expected: Any, actual: Any) = org.junit.Assert.assertEquals(expected, actual)
}
