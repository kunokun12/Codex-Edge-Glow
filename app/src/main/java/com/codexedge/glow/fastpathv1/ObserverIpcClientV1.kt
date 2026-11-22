package com.codexedge.glow.fastpathv1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synchronous bounded client for the observer's separately packaged candidate IPC contract. */
internal class ObserverIpcClientV1(private val context: Context) {
    data class CandidateEvidence(val requestId: String, val executionId: String, val packageName: String,
        val windowId: Int, val capturedElapsedMs: Long, val digest: String, val bootTimebaseId: String,
        val artifactIdentity: String, val authenticatedObserverUid: Int)
    sealed interface Result {
        data class Candidate(val evidence: CandidateEvidence) : Result
        data class Refused(val reason: String) : Result
    }

    fun capture(requestId: String, executionId: String, expectedPackage: String, expectedWindowId: Int,
                dispatchElapsedMs: Long, timeoutMs: Long = 1500): Result {
        val bootId = bootTimebaseId() ?: return Result.Refused("timebase-unavailable")
        val challenge = ByteArray(36).also(SecureRandom()::nextBytes).let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
        val latch = CountDownLatch(1)
        var result: Result = Result.Refused("observer-timeout")
        var service: Messenger? = null
        val callbackThread = android.os.HandlerThread("tessera-observer-ipc-reply").apply { start() }
        val replyMessenger = Messenger(Handler(callbackThread.looper) { message ->
            try {
                val data = message.data
                val uid = message.sendingUid
                val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
                require(uid > 0 && packages == setOf(OBSERVER_PACKAGE)) { "observer-ipc-identity-mismatch" }
                require(data.getString("protocol") == PROTOCOL && data.getString("outcome") == "CANDIDATE_EVIDENCE") { "observer-ipc-refused" }
                require(data.getString("requestId") == requestId && data.getString("executionId") == executionId && data.getString("challenge") == challenge) { "observer-ipc-correlation-mismatch" }
                require(data.getString("bootTimebaseId") == bootId) { "observer-ipc-timebase-mismatch" }
                require(data.getString("packageName") == expectedPackage && data.getInt("windowId", -1) == expectedWindowId) { "observer-ipc-window-mismatch" }
                val captured = data.getLong("capturedElapsedRealtimeMs", -1)
                val now = SystemClock.elapsedRealtime()
                require(data.getBoolean("complete") && captured > dispatchElapsedMs && captured <= now && now - captured <= MAX_AGE_MS) { "observer-ipc-stale-or-incomplete" }
                val digest = data.getString("evidenceDigest") ?: error("observer-ipc-digest-missing")
                require(HEX64.matches(digest)) { "observer-ipc-digest-invalid" }
                result = Result.Candidate(CandidateEvidence(requestId, executionId, expectedPackage, expectedWindowId,
                    captured, digest, bootId, data.getString("observerArtifactIdentity") ?: "", uid))
            } catch (e: Exception) { result = Result.Refused(e.message?.takeIf { it.matches(Regex("[a-z-]{1,64}")) } ?: "observer-ipc-invalid") }
            latch.countDown(); true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    service = Messenger(binder)
                    val data = Bundle().apply {
                        putString("protocol", PROTOCOL); putString("challenge", challenge)
                        putString("requestId", requestId); putString("executionId", executionId)
                        putString("expectedPackage", expectedPackage); putInt("expectedWindowId", expectedWindowId)
                        putLong("dispatchElapsedRealtimeMs", dispatchElapsedMs); putString("bootTimebaseId", bootId)
                    }
                    service!!.send(Message.obtain(null, MSG_EVIDENCE).apply { this.data = data; replyTo = replyMessenger })
                } catch (e: Exception) { result = Result.Refused("observer-unavailable"); latch.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName?) { result = Result.Refused("observer-unavailable"); latch.countDown() }
        }
        val intent = Intent().setComponent(ComponentName(OBSERVER_PACKAGE, "$OBSERVER_PACKAGE.ObserverIpcService"))
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return Result.Refused("observer-unavailable")
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result = Result.Refused("observer-timeout")
        } catch (_: Exception) { result = Result.Refused("observer-unavailable") }
        finally { runCatching { context.unbindService(connection) }; callbackThread.quitSafely() }
        return result
    }

    private fun bootTimebaseId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().also { require(it.matches(Regex("[0-9a-f-]{36}"))) }
    }.getOrNull()

    companion object {
        private const val OBSERVER_PACKAGE = "org.tessera.observer"
        private const val PROTOCOL = "tessera.observer-ipc/1"
        private const val MSG_EVIDENCE = 1
        private const val MAX_AGE_MS = 2500L
        private val HEX64 = Regex("[0-9a-f]{64}")
    }
}

/** Android runtime composition for read-only capture; candidate digests cannot be serialized as raw snapshots. */
internal class AndroidV1_1RuntimePorts(private val context: Context) : V1_1RuntimePorts {
    override val executionGateApproved = false
    override val authorityAvailable = false
    override val observerApproved = false
    override fun capture(request: Map<String, Any?>): CaptureResult {
        val payload = request["payload"] as Map<*, *>
        val pkg = payload["expectedPackage"] as String
        val window = (payload["expectedWindowId"] as Long).toInt()
        val requestId = request["requestId"] as String
        val result = ObserverIpcClientV1(context).capture(requestId, requestId, pkg, window, SystemClock.elapsedRealtime())
        return when (result) {
            is ObserverIpcClientV1.Result.Refused -> when (result.reason) {
                "observer-timeout" -> CaptureResult("TIMEOUT", "observer-timeout")
                "permission-unavailable" -> CaptureResult("PERMISSION_UNAVAILABLE", "permission-unavailable")
                "snapshot-incomplete" -> CaptureResult("INCOMPLETE", "snapshot-incomplete")
                "evidence-stale-or-pre-dispatch", "observer-ipc-stale" -> CaptureResult("STALE", "snapshot-stale")
                else -> CaptureResult("OBSERVER_UNAVAILABLE", "observer-unavailable")
            }
            is ObserverIpcClientV1.Result.Candidate -> CaptureResult("UNSUPPORTED_PROFILE", "unsupported-evidence-profile")
        }
    }
}
