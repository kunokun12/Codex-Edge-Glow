package com.codexedge.glow

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.codexedge.glow.fastpathv1.ExperimentalEnvelopeV1_1
import com.codexedge.glow.fastpathv1.FastPathV1_1Runtime
import com.codexedge.glow.fastpathv1.StrictEnvelopeV1
import com.codexedge.glow.fastpathv1.V1_1RuntimePorts
import com.codexedge.glow.fastpathv1.AndroidV1_1RuntimePorts
import java.util.concurrent.Executors

/** Protected experimental profile entry point. It has no v1/legacy dispatch path. */
class FastPathV1_1Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!isOrderedBroadcast) return
        val pending = goAsync()
        worker.execute {
            try {
                val (code, data) = handleProtectedIntent(context, intent, runtimePortsFactory(context))
                pending.setResultCode(code)
                pending.setResultData(data)
            } catch (_: Exception) {
                pending.setResultCode(Activity.RESULT_CANCELED)
                pending.setResultData("REJECTED")
            } finally {
                // goAsync keeps this ordered result alive until both local dispatch and
                // any bounded Observer IPC wait have ended.
                pending.finish()
            }
        }
    }

    internal var runtimePortsFactory: (Context) -> V1_1RuntimePorts = { AndroidV1_1RuntimePorts(it) }

    /** Faithful protected receiver parsing/dispatch boundary, callable by JVM/Robolectric tests. */
    internal fun handleProtectedIntent(context: Context, intent: Intent,
                                       ports: V1_1RuntimePorts = com.codexedge.glow.fastpathv1.ProductionV1_1RuntimePorts): Pair<Int, String> {
        if (intent.action != ACTION) return Activity.RESULT_CANCELED to "REJECTED"
        return try {
            val extras = intent.extras ?: error("missing-envelope-extra")
            require(extras.keySet() == setOf(REQUEST_EXTRA)) { "unexpected-intent-extra" }
            val encoded = intent.getStringExtra(REQUEST_EXTRA) ?: error("missing-envelope")
            require(encoded.length <= MAX_BASE64_CHARS && BASE64URL.matches(encoded)) { "invalid-base64url" }
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(bytes.size <= MAX_ENVELOPE_BYTES) { "oversized-envelope" }
            require(Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) == encoded) { "noncanonical-base64url" }
            val request = StrictEnvelopeV1.parseExperimentalV1_1Transport(bytes, System.currentTimeMillis())
            val type = request["messageType"] as String
            require(type in HANDLED) { "unsupported-v1.1-operation" }
            val response = FastPathV1_1Runtime.dispatch(request, System.currentTimeMillis(), ports)
            val json = ExperimentalEnvelopeV1_1.canonicalBytes(response)
            Activity.RESULT_OK to Base64.encodeToString(json, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) {
            // No parse failure, timeout, or unknown profile is forwarded to v1 or legacy handlers.
            Activity.RESULT_CANCELED to "REJECTED"
        }
    }

    companion object {
        const val ACTION = "com.codexedge.glow.FAST_PATH_V1_1_EXPERIMENTAL"
        const val REQUEST_EXTRA = "request_base64url"
        private const val MAX_ENVELOPE_BYTES = 65536
        private const val MAX_BASE64_CHARS = 87384
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")
        private val HANDLED = setOf("route.execute", "evidence.capture", "route.verify")
        private val worker = Executors.newCachedThreadPool()
    }
}
