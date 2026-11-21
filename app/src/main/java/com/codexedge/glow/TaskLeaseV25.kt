package com.codexedge.glow

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Isolated candidate fixed-task lease. DUMP-protected broadcast access is NOT
 * independently cryptographic host authentication. Never authorizes legacy
 * NATIVE_CLICK; v2.5 actions must validate this exact lease before dispatch.
 */
internal object TaskLeaseV25 {
    private const val PREF = "tessera_v25_task_lease"
    private const val TIMEOUT_MS = 30_000L
    private const val PACKAGE = "dev.androidroute.benchmark"
    // The receiver is fixed to this operation. Wire task_id is the task UUID.
    private const val TASK = "benchmark.open_guide"
    private const val PROTOCOL = "tessera.companion.fast-path.v2.5"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun uuid4(s: String): Boolean = try {
        val u = UUID.fromString(s)
        u.version() == 4 && u.toString() == s.lowercase()
    } catch (_: Exception) { false }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun read(c: Context): JSONObject {
        val p = prefs(c)
        return JSONObject()
            .put("active", p.getBoolean("active", false))
            .put("taskId", p.getString("taskId", ""))
            .put("taskUuid", p.getString("taskUuid", ""))
            .put("requestId", p.getString("requestId", ""))
            .put("sessionId", p.getString("sessionId", ""))
            .put("ownerHash", p.getString("ownerHash", ""))
            .put("deviceSerialHash", p.getString("deviceSerialHash", ""))
            .put("lastHeartbeatEpochMs", p.getLong("lastHeartbeatEpochMs", 0))
    }

    private fun writeResult(c: Context, action: String, status: String, reason: String) {
        val state = read(c)
        val output = JSONObject()
            .put("schema", 3)
            .put("protocolId", PROTOCOL)
            .put("operation", action)
            .put("status", status)
            .put("reason", reason)
            .put("taskId", state.optString("taskUuid"))
            .put("taskName", TASK)
            .put("taskUuid", state.optString("taskUuid"))
            .put("requestId", state.optString("requestId"))
            .put("sessionId", state.optString("sessionId"))
            .put("ownerHash", state.optString("ownerHash"))
            .put("ownerIdHash", state.optString("ownerHash"))
            .put("observedAtEpochMs", System.currentTimeMillis())
        val file = AtomicFile(File(c.filesDir, "task-lease-v25-result.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(output.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
        }
    }

    /** Exact, unexpired owner/task/request/session/device lease authorization.
     * Caller-supplied identifiers are correlation, not caller authentication.
     */
    @Synchronized
    fun authorizeClick(c: Context, intent: Intent): Boolean {
        val taskUuid = intent.getStringExtra("task_id") ?: ""
        val taskId = TASK
        val requestId = intent.getStringExtra("request_id") ?: ""
        val sessionId = intent.getStringExtra("session_id") ?: ""
        val ownerId = intent.getStringExtra("owner_id") ?: ""
        val serial = intent.getStringExtra("device_serial") ?: ""
        if (intent.getStringExtra("protocol_id") != PROTOCOL ||
            !uuid4(taskUuid) || !uuid4(requestId) ||
            !uuid4(sessionId) || !uuid4(ownerId) ||
            serial.isBlank() || serial.length > 128) return false
        val s = read(c)
        val now = System.currentTimeMillis()
        val last = s.optLong("lastHeartbeatEpochMs")
        return s.optBoolean("active") && last > 0L && now >= last &&
            now - last < TIMEOUT_MS && s.optString("taskId") == taskId &&
            s.optString("taskUuid") == taskUuid &&
            s.optString("requestId") == requestId &&
            s.optString("sessionId") == sessionId &&
            s.optString("ownerHash") == hash(ownerId) &&
            s.optString("deviceSerialHash") == hash(serial)
    }

    /** Read-only, owner-correlated current state. Never renews the lease. */
    @Synchronized
    fun inspect(c: Context, intent: Intent) {
        val taskUuid = intent.getStringExtra("task_id") ?: ""
        val requestId = intent.getStringExtra("request_id") ?: ""
        val sessionId = intent.getStringExtra("session_id") ?: ""
        val owner = intent.getStringExtra("owner_id") ?: ""
        val serial = intent.getStringExtra("device_serial") ?: ""
        val state = read(c)
        val now = System.currentTimeMillis()
        val last = state.optLong("lastHeartbeatEpochMs")
        val same = intent.getStringExtra("protocol_id") == PROTOCOL &&
            uuid4(taskUuid) && uuid4(requestId) && uuid4(sessionId) &&
            uuid4(owner) && serial.isNotBlank() && serial.length <= 128 &&
            state.optString("taskUuid") == taskUuid &&
            state.optString("requestId") == requestId &&
            state.optString("sessionId") == sessionId &&
            state.optString("ownerHash") == hash(owner) &&
            state.optString("deviceSerialHash") == hash(serial)
        val active = same && state.optBoolean("active") &&
            last > 0 && now >= last && now - last < TIMEOUT_MS
        val result = JSONObject()
            .put("schema", 3).put("protocolId", PROTOCOL)
            .put("operation", "TASK_READ_V25")
            .put("status", if (active) "ACTIVE" else "REFUSED")
            .put("reason", if (active) "lease-active-at-observation" else "lease-not-owned-or-expired")
            .put("taskName", TASK)
            .put("taskId", taskUuid).put("taskUuid", taskUuid)
            .put("requestId", requestId).put("sessionId", sessionId)
            .put("ownerIdHash", if (uuid4(owner)) hash(owner) else "")
            .put("ownerHash", if (uuid4(owner)) hash(owner) else "")
            .put("deviceSerialHash", if (same) state.optString("deviceSerialHash") else "")
            .put("observedAtEpochMs", now)
            .put("ownershipAuthenticated", false)
        val file = AtomicFile(File(c.filesDir, "task-lease-v25-read.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(result.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
        }
    }

    @Synchronized
    fun handle(c: Context, action: String, intent: Intent) {
        val taskUuid = intent.getStringExtra("task_id") ?: ""
        val taskId = TASK
        val requestId = intent.getStringExtra("request_id") ?: ""
        val sessionId = intent.getStringExtra("session_id") ?: ""
        val ownerId = intent.getStringExtra("owner_id") ?: ""
        val deviceSerial = intent.getStringExtra("device_serial") ?: ""
        val protocol = intent.getStringExtra("protocol_id") ?: ""
        val now = System.currentTimeMillis()
        val p = prefs(c)
        if (protocol != PROTOCOL || !uuid4(taskUuid) ||
            !uuid4(sessionId) || !uuid4(requestId) || !uuid4(ownerId) || deviceSerial.isBlank() ||
            deviceSerial.length > 128) {
            writeResult(c, action, "REFUSED", "invalid-task-lease-request")
            return
        }
        val ownerHash = hash(ownerId)
        val serialHash = hash(deviceSerial)
        val existing = read(c)
        val active = existing.optBoolean("active")
        val last = existing.optLong("lastHeartbeatEpochMs")
        val valid = active && last > 0 && now >= last && now - last < TIMEOUT_MS
        val same = existing.optString("taskId") == taskId &&
            existing.optString("taskUuid") == taskUuid &&
            existing.optString("requestId") == requestId &&
            existing.optString("sessionId") == sessionId &&
            existing.optString("ownerHash") == ownerHash &&
            existing.optString("deviceSerialHash") == serialHash

        when (action) {
            "TASK_ACQUIRE_V25" -> {
                // An active or expired record requires explicit owner-scoped
                // recovery; never silently replace another task's identity.
                if (active || existing.optString("taskUuid").isNotBlank()) {
                    writeResult(c, action, "REFUSED", "lease-record-exists")
                    return
                }
                val ok = p.edit().putBoolean("active", true)
                    .putString("taskId", taskId)
                    .putString("taskUuid", taskUuid)
                    .putString("requestId", requestId)
                    .putString("sessionId", sessionId)
                    .putString("ownerHash", ownerHash)
                    .putString("deviceSerialHash", serialHash)
                    .putLong("lastHeartbeatEpochMs", now).commit()
                writeResult(c, action, if (ok) "ACQUIRED" else "REFUSED",
                    if (ok) "recorded" else "commit-failed")
            }
            "TASK_PING_V25" -> {
                if (!valid || !same) {
                    writeResult(c, action, "REFUSED", "lease-not-owned-or-expired")
                    return
                }
                val ok = p.edit().putLong("lastHeartbeatEpochMs", now).commit()
                writeResult(c, action, if (ok) "ACTIVE" else "REFUSED",
                    if (ok) "renewed" else "commit-failed")
            }
            "TASK_STOP_V25" -> {
                if (!same) {
                    writeResult(c, action, "REFUSED", "lease-not-owned")
                    return
                }
                // Persist stopped state with owner correlation for recovery.
                // Never clear a foreign lease or mark an action verified.
                val ok = p.edit().putBoolean("active", false)
                    .putLong("lastHeartbeatEpochMs", now).commit()
                writeResult(c, action, if (ok) "STOPPED" else "REFUSED",
                    if (ok) "owner-stopped" else "commit-failed")
            }
        }
    }
}
