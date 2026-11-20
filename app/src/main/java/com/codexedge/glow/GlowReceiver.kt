package com.codexedge.glow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class GlowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.codexedge.glow.STOP" -> context.stopService(Intent(context, GlowService::class.java))
            "com.codexedge.glow.START", "com.codexedge.glow.PING" -> {
                if (Settings.canDrawOverlays(context)) {
                    context.startForegroundService(Intent(context, GlowService::class.java).setAction(intent.action))
                }
            }
        }
    }
}
