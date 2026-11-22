package org.tessera.observer

import android.app.Service
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.UUID

internal class ObserverIpcPolicy(private val capacity: Int = 4096) {
    private val consumed = LinkedHashSet<String>()
    @Synchronized fun consume(challenge: String): Boolean {
        if (!Regex("[A-Za-z0-9_-]{32,128}").matches(challenge) || consumed.size >= capacity || !consumed.add(challenge)) return false
        return true
    }
}

/** Read-only, candidate-only observer IPC protocol. Never returns a VERIFIED decision. */
class ObserverIpcService : Service() {
    private val policy = ObserverIpcPolicy()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), ::handle)) }
    override fun onBind(intent: android.content.Intent?): IBinder = messenger.binder

    private fun handle(message: Message): Boolean {
        val output = Bundle()
        try {
            val data = message.data ?: error("ipc-data-missing")
            require(data.keySet() == REQUEST_KEYS) { "ipc-fields-invalid" }
            require(data.getString("protocol") == PROTOCOL) { "ipc-version-invalid" }
            val callerUid = message.sendingUid
            require(callerUid > 0 && packagesForUid(callerUid).contains(COMPANION_PACKAGE)) { "ipc-caller-invalid" }
            val challenge = data.getString("challenge") ?: error("ipc-challenge-missing")
            val requestId = data.getString("requestId") ?: error("ipc-request-id-missing")
            val executionId = data.getString("executionId") ?: error("ipc-execution-id-missing")
            UUID.fromString(requestId); UUID.fromString(executionId)
            val expectedPackage = data.getString("expectedPackage") ?: error("ipc-package-missing")
            val expectedWindowId = data.getInt("expectedWindowId", -1)
            val dispatchElapsedMs = data.getLong("dispatchElapsedRealtimeMs", -1L)
            val expectedBootId = data.getString("bootTimebaseId") ?: error("ipc-timebase-missing")
            require(expectedWindowId >= 0 && dispatchElapsedMs >= 0 && expectedBootId == bootTimebaseId()) { "ipc-boundary-invalid" }
            require(policy.consume(challenge)) { "ipc-replay" }
            val snapshot = ObserverSnapshotStore.get() ?: error("observer-unavailable")
            val now = SystemClock.elapsedRealtime()
            require(snapshot.complete && snapshot.nodes.isNotEmpty()) { "snapshot-incomplete" }
            require(snapshot.packageName == expectedPackage && snapshot.windowId == expectedWindowId) { "package-window-mismatch" }
            require(snapshot.capturedElapsedRealtimeMs > dispatchElapsedMs && now >= snapshot.capturedElapsedRealtimeMs &&
                now - snapshot.capturedElapsedRealtimeMs <= MAX_AGE_MS) { "evidence-stale-or-pre-dispatch" }
            output.putString("outcome", "CANDIDATE_EVIDENCE")
            output.putString("protocol", PROTOCOL)
            output.putString("requestId", requestId); output.putString("executionId", executionId)
            output.putString("challenge", challenge); output.putString("bootTimebaseId", expectedBootId)
            output.putString("packageName", snapshot.packageName); output.putInt("windowId", snapshot.windowId)
            output.putLong("capturedElapsedRealtimeMs", snapshot.capturedElapsedRealtimeMs)
            output.putLong("observerElapsedRealtimeMs", now)
            output.putString("evidenceDigest", snapshot.digest)
            output.putString("observerPackage", packageName)
            output.putString("observerArtifactIdentity", artifactIdentity())
            output.putBoolean("complete", snapshot.complete)
            output.putBoolean("verified", false)
        } catch (failure: Exception) {
            output.putString("outcome", "REFUSED")
            output.putString("reasonCode", failure.message?.takeIf { it.matches(Regex("[a-z-]{1,64}")) } ?: "observer-refused")
            output.putBoolean("verified", false)
        }
        val response = Message.obtain(null, MSG_EVIDENCE); response.data = output
        runCatching { message.replyTo?.send(response) }
        return true
    }

    private fun packagesForUid(uid: Int): Set<String> = packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
    @Suppress("DEPRECATION")
    private fun artifactIdentity(): String {
        val info = if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            else packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val certDigest = signatures.orEmpty().firstOrNull()?.toByteArray()?.let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
        } ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "$packageName/$versionCode/$certDigest"
    }

    private fun bootTimebaseId(): String = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().also { require(it.matches(Regex("[0-9a-f-]{36}"))) }
    }.getOrElse { "unavailable" }

    companion object {
        private const val COMPANION_PACKAGE = "com.codexedge.glow"
        private const val MSG_EVIDENCE = 1
        private const val MAX_AGE_MS = 2500L
        private const val PROTOCOL = "tessera.observer-ipc/1"
        private val REQUEST_KEYS = setOf("protocol", "challenge", "requestId", "executionId", "expectedPackage", "expectedWindowId", "dispatchElapsedRealtimeMs", "bootTimebaseId")
    }
}
