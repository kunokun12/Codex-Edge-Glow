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
                maxAgeMs = data.getLong("maxAgeMs", -1L),
                maxNodes = data.getInt("maxNodes", -1),
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
                    currentBoot, artifact())
            } else {
                val snapshot = ReadOnlyAccessibilityObserverService.captureForRequest(
                    request.expectedPackage, request.expectedWindowId, request.dispatchElapsedRealtimeMs, request.maxNodes)
                policy.respond(request, callerUid, callerPackages, snapshot, SystemClock.elapsedRealtime(),
                    currentBoot, artifact())
            }
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf { it.matches(REASON_REGEX) } ?: "ipc-request-invalid"
            parsed?.let { request ->
                // A well-formed caller receives a correlated, machine-readable refusal.
                if (callerUid > 0 && callerPackages == setOf(COMPANION_PACKAGE))
                    refused(request, reason, artifact())
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
        putString("snapshotJson", snapshotJson)
        putString("observerPackage", observerPackage)
        putString("observerArtifactIdentity", observerArtifactIdentity)
        putString("observerVersionName", observerVersionName)
        putLong("observerVersionCode", observerVersionCode)
        putString("observerSignerSha256", observerSignerSha256)
        putString("observerArtifactSha256", observerArtifactSha256)
        putString("observerSourceCommit", observerSourceCommit)
        putBoolean("complete", complete)
        putBoolean("verified", verified)
    }

    private fun refused(request: ObserverIpcRequestV1, reason: String, artifact: ObserverArtifactV1) = ObserverIpcResponseV1(
        protocol = ObserverIpcPolicy.PROTOCOL, outcome = "REFUSED", reasonCode = reason,
        requestId = request.requestId, executionId = request.executionId, challenge = request.challenge,
        bootTimebaseId = request.bootTimebaseId, packageName = null, windowId = null,
        capturedElapsedRealtimeMs = null, observerElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        evidenceDigest = null, snapshotJson = null, observerPackage = packageName,
        observerArtifactIdentity = artifact.identity, observerVersionName = artifact.versionName,
        observerVersionCode = artifact.versionCode, observerSignerSha256 = artifact.signerSha256,
        observerArtifactSha256 = artifact.apkSha256, observerSourceCommit = artifact.sourceCommit, complete = false)

    private fun refusedUncorrelated(reason: String): ObserverIpcResponseV1 {
        val artifact = artifact()
        return ObserverIpcResponseV1(
            protocol = ObserverIpcPolicy.PROTOCOL, outcome = "REFUSED", reasonCode = reason,
            requestId = "", executionId = "", challenge = "", bootTimebaseId = "",
            packageName = null, windowId = null, capturedElapsedRealtimeMs = null,
            observerElapsedRealtimeMs = SystemClock.elapsedRealtime(), evidenceDigest = null, snapshotJson = null,
            observerPackage = packageName, observerArtifactIdentity = artifact.identity,
            observerVersionName = artifact.versionName, observerVersionCode = artifact.versionCode,
            observerSignerSha256 = artifact.signerSha256, observerArtifactSha256 = artifact.apkSha256,
            observerSourceCommit = artifact.sourceCommit, complete = false)
    }

    @Suppress("DEPRECATION")
    private fun artifact(): ObserverArtifactV1 = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 28)
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val certDigest = signatures.orEmpty().firstOrNull()?.toByteArray()?.let(::sha256) ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val apkSha = java.io.File(applicationInfo.sourceDir).inputStream().use { stream ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
        ObserverArtifactV1("$packageName/$versionCode/$certDigest", info.versionName ?: "",
            versionCode, certDigest, apkSha, BuildConfig.TESSERA_SOURCE_COMMIT)
    }.getOrElse { ObserverArtifactV1("$packageName/0/unknown", "", 0, "", "", "") }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { b -> "%02x".format(b) }

    private fun bootTimebaseId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            .also { require(it.matches(BOOT_ID_REGEX)) }
    }.getOrNull()

    companion object {
        private const val COMPANION_PACKAGE = "com.codexedge.glow"
        private const val MSG_EVIDENCE = 1
        private val REASON_REGEX = Regex("[a-z][a-z0-9-]{0,63}")
        private val BOOT_ID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val REQUEST_KEYS = setOf("protocol", "challenge", "requestId", "executionId", "expectedPackage", "expectedWindowId", "maxAgeMs", "maxNodes", "dispatchElapsedRealtimeMs", "bootTimebaseId")
    }
}
