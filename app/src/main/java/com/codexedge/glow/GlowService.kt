package com.codexedge.glow

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Codex Edge Glow active")
            .setContentText("Android automation indicator is on")
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            )
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
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val start = SystemClock.uptimeMillis()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val t = (SystemClock.uptimeMillis() - start) / 1000.0
            val pulse = (sin(t * 2.5) + 1.0) / 2.0
            val phase = ((t * 0.15) % 1.0).toFloat()
            val colors = intArrayOf(Color.CYAN, Color.rgb(142, 67, 255), Color.CYAN)
            paint.shader = LinearGradient(
                width * phase, 0f, width * (1f - phase), height.toFloat(),
                colors, null, Shader.TileMode.MIRROR
            )
            val inset = 8f * density
            val rect = RectF(inset, inset, width - inset, height - inset)
            paint.strokeWidth = (12 + 5 * pulse).toFloat() * density
            paint.alpha = (55 + 55 * pulse).toInt()
            canvas.drawRoundRect(rect, 22f * density, 22f * density, paint)
            paint.strokeWidth = 3.5f * density
            paint.alpha = 245
            canvas.drawRoundRect(rect, 22f * density, 22f * density, paint)
            postInvalidateDelayed(33)
        }
    }
}
