package com.codexedge.glow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.codexedge.glow.fastpathv1.StrictEnvelopeV1

/**
 * M3 staging boundary: protected, pinned v1 broadcast entrypoint.
 * Deliberately rejects EVERY operation, including valid envelopes, until
 * full M1 nested schema, signature verification, journal, and lease exist.
 * No accessibility service, registry, legacy handler, or durable action invoked.
 */
class FastPathV1Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val result = try {
            val encoded = intent.getStringExtra("envelope_b64")
                ?: throw IllegalArgumentException("missing-envelope")
            require(encoded.length <= 87384 && encoded.matches(Regex("[A-Za-z0-9_-]+"))) {
                "invalid-base64url"
            }
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(bytes.size <= 65536) { "oversized-envelope" }
            StrictEnvelopeV1.parseTransport(bytes, System.currentTimeMillis())
            "UNIMPLEMENTED"
        } catch (_: Exception) {
            "REJECTED"
        }
        // Broadcast acknowledgement only. Never claim accepted or executed.
        if (isOrderedBroadcast) {
            resultCode = android.app.Activity.RESULT_CANCELED
            resultData = result
        }
    }

    companion object {
        const val ACTION = "com.codexedge.glow.FAST_PATH_V1"
    }
}
