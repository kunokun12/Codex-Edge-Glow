package com.codexedge.glow

import android.app.Activity
import android.content.Intent
import android.util.Base64
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.util.UUID

class FastPathV1_1ReceiverTest {
    private fun envelope(profile: String = "tessera.fast-path/1.1", operation: String = "evidence.capture"): String {
        val now = System.currentTimeMillis()
        return Gson().toJson(mapOf(
            "protocolVersion" to profile, "messageType" to operation,
            "requestId" to UUID.randomUUID().toString(), "taskId" to UUID.randomUUID().toString(),
            "sessionId" to UUID.randomUUID().toString(), "ownerId" to UUID.randomUUID().toString(),
            "deviceBinding" to mapOf("deviceSerial" to "offline-test", "companionPackage" to "com.codexedge.glow",
                "companionVersionCode" to 18, "signerCertificateSha256" to "a".repeat(64)),
            "expiresAtEpochMs" to now + 60000,
            "payload" to mapOf("expectedPackage" to "org.example.app", "expectedWindowId" to 1,
                "maxAgeMs" to 2500, "maxNodes" to 4096)))
    }
    private fun intent(json: String) = Intent(FastPathV1_1Receiver.ACTION).putExtra(
        FastPathV1_1Receiver.REQUEST_EXTRA,
        Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    @Test fun protectedReceiverBoundaryRoutesV11ToTypedCaptureResponse() {
        val receiver = FastPathV1_1Receiver()
        val result = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope()))
        assertEquals(Activity.RESULT_OK, result.first)
        val response = String(Base64.decode(result.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        assertTrue(response.contains("evidence.capture.result"))
        assertTrue(response.contains("OBSERVER_UNAVAILABLE"))
        assertFalse(response.contains("UNIMPLEMENTED"))
        assertFalse(response.contains("\"snapshot\":{"))
    }

    @Test fun v1AndUnknownProfileNeverReachV1HandlerOrRuntime() {
        val receiver = FastPathV1_1Receiver()
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope("tessera.fast-path/1"))).first)
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope("tessera.fast-path/99"))).first)
    }

    @Test fun wrongActionAndUnexpectedIntentFieldsAreRejected() {
        val receiver = FastPathV1_1Receiver()
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), Intent("other")).first)
        val bad = intent(envelope()).putExtra("unexpected", "value")
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), bad).first)
    }
}
