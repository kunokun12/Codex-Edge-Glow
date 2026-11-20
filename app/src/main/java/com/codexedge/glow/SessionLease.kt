package com.codexedge.glow

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Process-independent session lease for the visual indicator.
 *
 * STOP is durable and cannot be undone by a late PING/STATUS.
 * Only explicit START re-arms the indicator. The MCP must use PING for
 * periodic renewal and must check state before dispatching more actions.
 */
internal object SessionLease {
    const val TIMEOUT_MS = 120_000L
    private const val PREF = "codex_session_lease"
    private const val TAG = "CodexEdgeLease"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun start(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val ok = prefs(context).edit()
            .putBoolean("active", true)
            .putLong("lastHeartbeatEpochMs", now)
            .putString("reason", "start")
            .commit()
        if (ok) writeSnapshot(context, "active", "start", now)
        return ok
    }

    @Synchronized
    fun heartbeat(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong("lastHeartbeatEpochMs", 0L)
        if (!p.getBoolean("active", false) || last <= 0L ||
            now < last || now - last >= TIMEOUT_MS) {
            if (p.getBoolean("active", false)) stop(context, "lease_expired_before_heartbeat")
            return false
        }
        val ok = p.edit().putLong("lastHeartbeatEpochMs", now)
            .putString("reason", "heartbeat").commit()
        if (ok) writeSnapshot(context, "active", "heartbeat", now)
        return ok
    }

    @Synchronized
    fun stop(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        val ok = prefs(context).edit()
            .putBoolean("active", false)
            .putLong("lastHeartbeatEpochMs", now)
            .putString("reason", reason)
            .commit()
        if (ok) writeSnapshot(context, "stopped", reason, now)
        Log.i(TAG, "Stopped visual indicator: $reason, persisted=$ok")
    }

    @Synchronized
    fun isActive(context: Context): Boolean = prefs(context).getBoolean("active", false)

    @Synchronized
    fun lastHeartbeat(context: Context): Long =
        prefs(context).getLong("lastHeartbeatEpochMs", 0L)

    private fun writeSnapshot(context: Context, state: String, reason: String, now: Long) {
        // Debug APK: read-only MCP access via adb shell run-as.
        // No request content, UI text, or external data is included.
        val json = JSONObject()
            .put("schema", 1)
            .put("state", state)
            .put("reason", reason)
            .put("lastUpdatedEpochMs", now)
            .put("watchdogTimeoutMs", TIMEOUT_MS)
        val file = AtomicFile(File(context.filesDir, "session-state.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            Log.w(TAG, "Could not persist session state", error)
        }
    }
}
