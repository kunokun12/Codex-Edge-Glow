package com.codexedge.glow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(42, 72, 42, 24) }
        fun addButton(title: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }
        layout.addView(TextView(this).apply {
            text = "Codex Edge Glow\n\nAnimated cyan / purple screen border for Android automation.\n\nGrant overlay permission first. The glow is touch-through."
            textSize = 18f
        })
        addButton("1. Grant display-over-apps permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addButton("2. Test glow") { startGlow() }
        addButton("3. Stop glow") { stopService(Intent(this, GlowService::class.java)) }
        setContentView(layout)
    }
    private fun startGlow() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, GlowService::class.java).setAction("START"))
    }
}
