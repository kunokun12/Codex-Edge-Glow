package com.codexedge.glow

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 24)
    private val surface = Color.rgb(23, 30, 46)
    private val primary = Color.rgb(227, 238, 255)
    private val muted = Color.rgb(151, 166, 191)
    private val accent = Color.rgb(68, 178, 255)
    private val prefs by lazy { getSharedPreferences("glow_settings", MODE_PRIVATE) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun section(root: LinearLayout, title: String, subtitle: String): LinearLayout {
        root.addView(text(title.uppercase(), 12f, accent, true).apply { setPadding(0, dp(22), 0, dp(6)) })
        root.addView(text(subtitle, 13f, muted).apply { setPadding(0, 0, 0, dp(12)) })
        return column().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(surface)
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun button(label: String, color: Int, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER; background = rounded(color, 14)
        setPadding(dp(12), dp(16), dp(12), dp(16))
        setOnClickListener { action() }
    }
    private fun slider(parent: LinearLayout, label: String, key: String, default: Int, suffix: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = text(label, 15f, primary, true)
        val value = text("${prefs.getInt(key, default)}$suffix", 13f, muted)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(value)
        parent.addView(row)
        parent.addView(SeekBar(this).apply {
            max = 100; progress = prefs.getInt(key, default)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, user: Boolean) {
                    value.text = "$p$suffix"
                    if (user) prefs.edit().putInt(key, p).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val root = column().apply { setPadding(dp(22), dp(30), dp(22), dp(36)) }
        scroll.addView(root)
        root.addView(text("CODEX  /  ANDROID", 12f, accent, true))
        root.addView(text("Codex Companion", 32f, primary, true).apply { setPadding(0, dp(7), 0, dp(5)) })
        root.addView(text("Visible automation status + native accessibility actions in one app.", 14f, muted))
        val control = section(root, "Session control", "Preview the activity indicator without starting an automation task")
        control.addView(button("Start preview", Color.rgb(34, 112, 197)) { startGlow() })
        control.addView(View(this).apply { minimumHeight = dp(10) })
        control.addView(button("Stop preview", Color.rgb(53, 65, 84)) {
            stopService(Intent(this@MainActivity, GlowService::class.java))
        })
        val appearance = section(root, "Appearance", "Soft, feathered lighting · changes apply live")
        slider(appearance, "Brightness", "intensity", 65, "%")
        slider(appearance, "Glow width", "spread", 52, " dp")
        slider(appearance, "Breathing speed", "speed", 35, "%")
        val toggleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toggleRow.addView(text("Purple side accent", 15f, primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        toggleRow.addView(Switch(this).apply {
            isChecked = prefs.getBoolean("purple", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("purple", checked).apply() }
        })
        appearance.addView(toggleRow)
        val bottomRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        val bottomLabel = column()
        bottomLabel.addView(text("Bottom glow", 15f, primary, true))
        bottomLabel.addView(text("Off by default · cleaner gesture bar", 12f, muted))
        bottomRow.addView(bottomLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bottomRow.addView(Switch(this).apply {
            isChecked = prefs.getBoolean("bottom_glow", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("bottom_glow", checked).apply() }
        })
        appearance.addView(bottomRow)
        val automation = section(root, "Native actions", "Exact accessibility targets only. Android MCP sends one authorized action at a time.")
        automation.addView(text("Allow automation only in these package IDs (comma-separated). Benchmark is enabled by default. Add other apps only when you intend Codex to control them; avoid banking, payments, and sensitive accounts.", 12f, muted))
        val allowPrefs = getSharedPreferences("automation_settings", MODE_PRIVATE)
        val allowedPackages = EditText(this).apply {
            setText(allowPrefs.getString("allowed_packages", "dev.androidroute.benchmark"))
            setSingleLine(false)
            minLines = 2
            setTextColor(primary)
            setHintTextColor(muted)
            textSize = 13f
            hint = "dev.androidroute.benchmark"
        }
        automation.addView(allowedPackages, LinearLayout.LayoutParams(-1, -2))
        automation.addView(button("Save allowed apps", Color.rgb(34, 112, 197)) {
            val candidates = allowedPackages.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val pattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
            if (candidates.isEmpty() || candidates.size > 20 || candidates.any { !pattern.matches(it) || it == packageName }) {
                Toast.makeText(this, "Enter 1–20 valid app package IDs", Toast.LENGTH_LONG).show()
            } else {
                allowPrefs.edit().putString("allowed_packages", candidates.distinct().joinToString(",")).apply()
                Toast.makeText(this, "Native action allowlist saved", Toast.LENGTH_SHORT).show()
            }
        })
        automation.addView(text("Native clicks require an active Codex session, connected accessibility service, an exact unique match, and an ADB-authorized caller. The app reports dispatch acceptance; Android MCP must verify the destination.", 12f, muted))
        val access = section(root, "Permissions & status", "Overlay and accessibility permissions for one companion app")
        access.addView(button("Manage display-over-apps", Color.rgb(53, 65, 84)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        access.addView(View(this).apply { minimumHeight = dp(10) })
        access.addView(button("Notification settings", Color.rgb(53, 65, 84)) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        access.addView(View(this).apply { minimumHeight = dp(10) })
        access.addView(button("Enable Codex accessibility", Color.rgb(53, 65, 84)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        access.addView(text("Enable Codex Companion in Accessibility settings for native node actions and UI snapshots. Screen labels remain stored privately on-device.", 12f, muted).apply {
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(text("Live Alerts are managed by ColorOS. An ongoing notification remains available as fallback. The glow is touch-through.", 12f, muted).apply {
            setPadding(0, dp(22), 0, 0)
        })
        setContentView(scroll)
    }
    private fun startGlow() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, GlowService::class.java).setAction("START"))
    }
}
