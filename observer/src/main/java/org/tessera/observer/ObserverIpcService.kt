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

/** Read-only, candidate-only observer IPC protocol. It cannot return a VERIFIED decision. */
class ObserverIpcService : Service() {
    private val policy = ObserverIpcPolicy()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), ::handle)) }
    override fun onBind(intent: android.content.Intent?): IBinder = messenger.binder

    private fun handle(message: Message): Boolean {
        val data = runCatching { message.data }.getOrNull()
        val callerUid = message.sendingUid
        val callerPackages = packageManager.getPackagesForUid(callerUid)?.toSet().orEmpty()
        var parsed: ObserverIpcRequestV1? = null
        val response = try {
            require(data != null && data.keySet() == REQUEST_KEYS) { "ipc-fields-invalid" }
            val protocol = data.getString("protocol") ?: error("ipc-protocol-missing")
            val request = ObserverIpcRequestV1(
                protocol = protocol,
                challenge = data.getString("challenge") ?: error("ipc-challenge-missing"),
                requestId = data.getString("requestId") ?: error("ipc-request-id-missing"),
                executionId = data.getString("executionId") ?: error("ipc-execution-id-missing"),
                expectedPackage = data.getString("expectedPackage") ?: error("ipc-package-missing"),
                expectedWindowId = data.getInt("expectedWindowId", -1),
                dispatchElapsedRealtimeMs = data.getLong("dispatchElapsedRealtimeMs", -1L),
                bootTimebaseId = data.getString("bootTimebaseId") ?: error("ipc-timebase-missing")
            )
            parsed = request
            val currentBoot = bootTimebaseId() ?: error("ipc-timebase-unavailable")
            // Authenticate before asking the Accessibility adapter to inspect any window.
            // The manifest signature permission is the first boundary; this exact package
            // check is defense in depth and keeps malformed/untrusted IPC read-only.
            if (callerUid <= 0 || callerPackages != setOf(COMPANION_PACKAGE)) {
                policy.respond(request, callerUid, callerPackages,
                    ObserverSnapshotResult.Refused("ipc-caller-invalid"), SystemClock.elapsedRealtime(),
                    currentBoot, artifactIdentity())
            } else {
            val snapshot = ReadOnlyAccessibilityObserverService.captureForRequest(
                request.expectedPackage, request.expectedWindowId, request.dispatchElapsedRealtimeMs)
            policy.respond(request, callerUid, callerPackages, snapshot, SystemClock.elapsedRealtime(),
                currentBoot, artifactIdentity())
            }
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf { it.matches(REASON_REGEX) } ?: "ipc-request-invalid"
            parsed?.let { request ->
                // A well-formed caller receives a correlated, machine-readable refusal.
                if (callerUid > 0 && callerPackages == setOf(COMPANION_PACKAGE))
                    refused(request, reason, artifactIdentity())
                else refusedUncorrelated(reason)
            } ?: refusedUncorrelated(reason)
        }
        val reply = Message.obtain(null, MSG_EVIDENCE)
        reply.data = response.toBundle()
        runCatching { message.replyTo?.send(reply) }
        return true
    }

    private fun ObserverIpcResponseV1.toBundle() = Bundle().apply {
        putString("protocol", protocol)
        putString("outcome", outcome)
        putString("reasonCode", reasonCode)
        putString("requestId", requestId)
        putString("executionId", executionId)
        putString("challenge", challenge)
        putString("bootTimebaseId", bootTimebaseId)
        putString("packageName", packageName)
        putInt("windowId", windowId ?: -1)
        putLong("capturedElapsedRealtimeMs", capturedElapsedRealtimeMs ?: -1L)
        putLong("observerElapsedRealtimeMs", observerElapsedRealtimeMs)
        putString("evidenceDigest", evidenceDigest)
        putString("observerPackage", observerPackage)
        putString("observerArtifactIdentity", observerArtifactIdentity)
        putBoolean("complete", complete)
        putBoolean("verified", verified)
    }

    private fun refused(request: ObserverIpcRequestV1, reason: String, identity: String) = ObserverIpcResponseV1(
        protocol = ObserverIpcPolicy.PROTOCOL, outcome = "REFUSED", reasonCode = reason,
        requestId = request.requestId, executionId = request.executionId, challenge = request.challenge,
        bootTimebaseId = request.bootTimebaseId, packageName = null, windowId = null,
        capturedElapsedRealtimeMs = null, observerElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        evidenceDigest = null, observerPackage = packageName, observerArtifactIdentity = identity,
        complete = false)

    private fun refusedUncorrelated(reason: String) = ObserverIpcResponseV1(
        protocol = ObserverIpcPolicy.PROTOCOL, outcome = "REFUSED", reasonCode = reason,
        requestId = "", executionId = "", challenge = "", bootTimebaseId = "",
        packageName = null, windowId = null, capturedElapsedRealtimeMs = null,
        observerElapsedRealtimeMs = SystemClock.elapsedRealtime(), evidenceDigest = null,
        observerPackage = packageName, observerArtifactIdentity = artifactIdentity(), complete = false)

    @Suppress("DEPRECATION")
    private fun artifactIdentity(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 28)
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val certDigest = signatures.orEmpty().firstOrNull()?.toByteArray()?.let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
        } ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$packageName/$versionCode/$certDigest"
    }.getOrDefault("$packageName/0/unknown")

    private fun bootTimebaseId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            .also { require(it.matches(BOOT_ID_REGEX)) }
    }.getOrNull()

    companion object {
        private const val COMPANION_PACKAGE = "com.codexedge.glow"
        private const val MSG_EVIDENCE = 1
        private val REASON_REGEX = Regex("[a-z][a-z0-9-]{0,63}")
        private val BOOT_ID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val REQUEST_KEYS = setOf("protocol", "challenge", "requestId", "executionId", "expectedPackage", "expectedWindowId", "dispatchElapsedRealtimeMs", "bootTimebaseId")
    }
}
