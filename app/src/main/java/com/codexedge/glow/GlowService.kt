package com.codexedge.glow

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.view.*
import kotlin.math.sin

class GlowService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var window: WindowManager? = null
    private var glow: GlowView? = null
    private var expiresAt = 0L
    private val timeout = 120_000L
    private val watchdog = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() >= expiresAt) { stopSelf(); return }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = "edge_glow_active"
        nm.createNotificationChannel(NotificationChannel(channel, "Codex automation", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(this, 43,
            Intent(this, GlowReceiver::class.java).setAction("com.codexedge.glow.STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val extras = Bundle().apply {
            if (Build.VERSION.SDK_INT >= 36) {
                putBoolean("android.requestPromotedOngoing", true)
                putString("android.shortCriticalText", "Codex")
            }
        }
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Codex is using your phone")
            .setContentText("Android automation session active")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true).setExtras(extras)
            .addAction(Notification.Action.Builder(null, "Stop session", stop).build())
            .build()
        startForeground(42, notification)
        expiresAt = SystemClock.elapsedRealtime() + timeout
        if (glow == null) {
            window = getSystemService(WINDOW_SERVICE) as WindowManager
            glow = GlowView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            try { window?.addView(glow, lp) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
            handler.post(watchdog)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        glow?.let { try { window?.removeView(it) } catch (_: Exception) {} }
        glow = null
        super.onDestroy()
    }
    private class GlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val started = SystemClock.uptimeMillis()
        private val prefs = context.getSharedPreferences("glow_settings", Context.MODE_PRIVATE)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val d = resources.displayMetrics.density
            val strength = prefs.getInt("intensity", 65) / 100f
            val spread = prefs.getInt("spread", 52) * d
            val speed = prefs.getInt("speed", 35) / 100f
            val purple = prefs.getBoolean("purple", true)
            val bottom = prefs.getBoolean("bottom_glow", false)
            val t = (SystemClock.uptimeMillis() - started) / 1000.0
            val breath = (0.74 + 0.26 * sin(t * (0.5 + speed * 1.8))).toFloat()
            val a = (160f * strength * breath).toInt().coerceIn(0, 255)
            val cyan = Color.rgb(35, 170, 255)
            val violet = Color.rgb(110, 74, 255)
            fun drawGradient(x0: Float, y0: Float, x1: Float, y1: Float, rect: RectF, color: Int) {
                paint.shader = LinearGradient(x0, y0, x1, y1,
                    intArrayOf(Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb((a * .34f).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                        Color.TRANSPARENT), floatArrayOf(0f, .34f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(rect, paint)
                paint.shader = null
            }
            // Draw horizontal gradient strips with a smooth fade toward the gesture bar.
            val steps = 64
            val sideEnd = if (bottom) h else h * 0.92f
            for (i in 0 until steps) {
                val y0 = sideEnd * i / steps
                val y1 = sideEnd * (i + 1) / steps + 1f
                val progress = y0 / sideEnd
                val fade = if (progress < 0.65f) 1f else
                    ((1f - progress) / 0.35f).coerceIn(0f, 1f)
                val alpha = (a * fade * fade).toInt().coerceIn(0, 255)
                val c = if (purple && progress > 0.55f) violet else cyan
                fun strip(x0: Float, x1: Float, rect: RectF) {
                    paint.shader = LinearGradient(x0, 0f, x1, 0f,
                        intArrayOf(Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c)),
                            Color.argb((alpha * .3f).toInt(), Color.red(c), Color.green(c), Color.blue(c)),
                            Color.TRANSPARENT),
                        floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(rect, paint)
                }
                strip(0f, spread, RectF(0f, y0, spread, y1))
                strip(w, w - spread, RectF(w - spread, y0, w, y1))
            }
            paint.shader = null
            drawGradient(0f, 0f, 0f, spread, RectF(0f, 0f, w, spread), cyan)
            if (bottom) drawGradient(0f, h, 0f, h - spread,
                RectF(0f, h - spread, w, h), if (purple) violet else cyan)
            postInvalidateDelayed(50)
        }
    }
}
