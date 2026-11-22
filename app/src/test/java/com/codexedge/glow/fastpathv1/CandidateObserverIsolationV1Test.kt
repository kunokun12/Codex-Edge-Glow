package com.codexedge.glow.fastpathv1

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CandidateObserverIsolationV1Test {
    @Test fun candidateObserverRunsInSeparateProcessAndProviderIsInternal() {
        val context = RuntimeEnvironment.getApplication()
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, CandidateObserverAccessibilityServiceV1::class.java),
            PackageManager.GET_META_DATA)
        assertTrue(service.processName.endsWith(":fastpath-observer"))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
        assertTrue(service.exported)

        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, CandidateObserverSnapshotProviderV1::class.java),
            PackageManager.GET_META_DATA)
        assertFalse(provider.exported)
        assertTrue(provider.processName.endsWith(":fastpath-observer"))
    }

    @Test fun candidateServiceConfigurationDoesNotRequestGestureCapability() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, CandidateObserverAccessibilityServiceV1::class.java),
            PackageManager.GET_META_DATA)
        val parser = context.resources.getXml(info.metaData.getInt("android.accessibilityservice"))
        try {
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
            val androidNs = "http://schemas.android.com/apk/res/android"
            assertTrue(parser.getAttributeBooleanValue(androidNs, "canRetrieveWindowContent", false))
            assertFalse(parser.getAttributeBooleanValue(androidNs, "canPerformGestures", false))
            assertFalse(parser.getAttributeBooleanValue(androidNs, "canTakeScreenshot", false))
        } finally { parser.close() }
    }

    @Test fun observerSnapshotHandoffIsEphemeralAndOneShot() {
        CandidateObserverSnapshotStoreV1.publish("candidate-evidence")
        assertEquals("candidate-evidence", CandidateObserverSnapshotStoreV1.take())
        assertNull(CandidateObserverSnapshotStoreV1.take())
        CandidateObserverSnapshotStoreV1.publish("candidate-evidence")
        CandidateObserverSnapshotStoreV1.clear()
        assertNull(CandidateObserverSnapshotStoreV1.take())
    }
}
