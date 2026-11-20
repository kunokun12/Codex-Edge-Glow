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
    private var currentStatus = "Ready for Android actions"
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
        val status = intent?.getStringExtra("status")?.replace(13.toChar(), ' ')?.replace(10.toChar(), ' ')?.take(72)
        if (!status.isNullOrBlank()) currentStatus = status
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
            .setContentText(currentStatus)
            .setStyle(Notification.BigTextStyle().bigText(currentStatus))
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
            // Continuous side gradients eliminate the seams produced by strip drawing.
            // Keep full brightness down the sides, fading only near the gesture bar.
            val sideEnd = if (bottom) h else h * 0.985f
            val sideAlpha = (225f * strength * breath).toInt().coerceIn(0, 255)
            val fadeStart = if (bottom) 1f else 0.87f
            fun sideGlow(left: Boolean) {
                val leftX = if (left) 0f else w - spread
                val rightX = if (left) spread else w
                val checkpoint = canvas.saveLayer(leftX, 0f, rightX, sideEnd, null)
                val c = cyan
                paint.shader = LinearGradient(
                    if (left) 0f else w, 0f,
                    if (left) spread else w - spread, 0f,
                    intArrayOf(
                        Color.argb(sideAlpha, Color.red(c), Color.green(c), Color.blue(c)),
                        Color.argb((sideAlpha * .42f).toInt(), Color.red(c), Color.green(c), Color.blue(c)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(leftX, 0f, rightX, sideEnd, paint)
                paint.shader = null
                // One continuous alpha mask: no horizontal seams or strip boundaries.
                if (!bottom) {
                    paint.shader = LinearGradient(
                        0f, 0f, 0f, sideEnd,
                        intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                        floatArrayOf(0f, fadeStart, 1f), Shader.TileMode.CLAMP
                    )
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    canvas.drawRect(leftX, 0f, rightX, sideEnd, paint)
                    paint.xfermode = null
                    paint.shader = null
                }
                canvas.restoreToCount(checkpoint)
            }
            sideGlow(true)
            sideGlow(false)
            drawGradient(0f, 0f, 0f, spread, RectF(0f, 0f, w, spread), cyan)
            if (bottom) drawGradient(0f, h, 0f, h - spread,
                RectF(0f, h - spread, w, h), if (purple) violet else cyan)
            postInvalidateDelayed(50)
        }
    }
}
