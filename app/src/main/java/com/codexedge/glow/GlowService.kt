package com.codexedge.glow

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

class GlowService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: GlowView? = null
    private var expiresAt = 0L
    private val maxIdleMs = 120_000L
    private val watchdog = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() > expiresAt) { stopSelf(); return }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val channelId = "edge_glow_active"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "Codex automation", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, GlowReceiver::class.java).apply {
            action = "com.codexedge.glow.STOP"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 43, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Android 16+ can promote an eligible ongoing notification to a live
        // status chip. ColorOS decides whether to show a Live Alert; on other
        // systems the ordinary foreground notification remains visible.
        // Use the documented extras key to stay compatible with compileSdk 35.
        val liveExtras = Bundle().apply {
            if (Build.VERSION.SDK_INT >= 36) {
                putBoolean("android.requestPromotedOngoing", true)
                putString("android.shortCriticalText", "Codex")
            }
        }
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Codex is using your phone")
            .setContentText("Android automation session active")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setExtras(liveExtras)
            .addAction(Notification.Action.Builder(
                null, "Stop session", stopPendingIntent
            ).build())
            .build()
        startForeground(42, notification)
        expiresAt = SystemClock.elapsedRealtime() + maxIdleMs
        if (view == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            view = GlowView()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try { wm?.addView(view, params) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
            handler.post(watchdog)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        view = null
        super.onDestroy()
    }
    inner class GlowView : View(this@GlowService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val start = SystemClock.uptimeMillis()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val d = resources.displayMetrics.density
            val t = (SystemClock.uptimeMillis() - start) / 1000.0
            val pulse = (sin(t * 2.5) + 1.0) / 2.0
            val phase = ((t * 0.12) % 1.0).toFloat()
            val colors = intArrayOf(Color.rgb(0, 229, 255), Color.rgb(159, 86, 255), Color.rgb(0, 229, 255))
            paint.shader = LinearGradient(
                width * phase, 0f, width * (1f - phase), height.toFloat(),
                colors, null, Shader.TileMode.MIRROR
            )
            val inset = 4.5f * d
            val rect = RectF(inset, inset, width - inset, height - inset)
            paint.strokeWidth = (9 + 4 * pulse).toFloat() * d
            paint.alpha = (65 + 55 * pulse).toInt()
            canvas.drawRoundRect(rect, 24f * d, 24f * d, paint)
            paint.strokeWidth = 2.8f * d
            paint.alpha = 255
            canvas.drawRoundRect(rect, 24f * d, 24f * d, paint)
            paint.shader = null

            postInvalidateDelayed(33)
        }
    }
}
