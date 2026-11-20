package com.codexedge.glow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * STOP is a durable user-cancel request. A delayed PING or STATUS cannot
 * bring the indicator back; a new explicit START is required.
 */
class GlowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.codexedge.glow.STOP" -> {
                SessionLease.stop(context, "explicit_stop")
                context.stopService(Intent(context, GlowService::class.java))
            }
            "com.codexedge.glow.START" -> {
                if (Settings.canDrawOverlays(context) && SessionLease.start(context)) {
                    context.startForegroundService(Intent(context, GlowService::class.java)
                        .setAction(intent.action).putExtra("status", intent.getStringExtra("status")))
                }
            }
            "com.codexedge.glow.PING", "com.codexedge.glow.STATUS" -> {
                if (Settings.canDrawOverlays(context) && SessionLease.heartbeat(context)) {
                    context.startForegroundService(Intent(context, GlowService::class.java)
                        .setAction(intent.action).putExtra("status", intent.getStringExtra("status")))
                }
            }
        }
    }
}
