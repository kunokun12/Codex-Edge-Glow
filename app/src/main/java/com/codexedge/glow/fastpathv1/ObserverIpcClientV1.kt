package com.codexedge.glow.fastpathv1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class ObserverIpcRequestMessageV1(
    val protocol: String, val challenge: String, val requestId: String, val executionId: String,
    val expectedPackage: String, val expectedWindowId: Int, val maxAgeMs: Long, val maxNodes: Int,
    val dispatchElapsedRealtimeMs: Long,
    val bootTimebaseId: String
)

internal data class ObserverInstalledIdentityV1(val uid: Int, val versionCode: Long, val versionName: String,
    val signerSha256: String, val artifactSha256: String)

internal data class ObserverIpcTransportReplyV1(val reply: ObserverIpcReplyV1, val callerUid: Int,
                                                val callerPackages: Set<String>)
internal sealed interface ObserverIpcTransportResultV1 {
    data class Reply(val value: ObserverIpcTransportReplyV1) : ObserverIpcTransportResultV1
    data class Failure(val reasonCode: String) : ObserverIpcTransportResultV1
}
internal fun interface ObserverIpcTransportV1 {
    fun exchange(request: ObserverIpcRequestMessageV1, timeoutMs: Long): ObserverIpcTransportResultV1
}

/** Bounded, read-only client. Its transport is injectable so protocol success and failure paths are testable offline. */
internal class ObserverIpcClientV1(
    private val context: Context,
    private val transport: ObserverIpcTransportV1 = AndroidMessengerObserverTransportV1(context),
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val identityProvider: () -> ObserverInstalledIdentityV1? = { installedObserverIdentity(context) }
) {
    data class CandidateEvidence(val requestId: String, val executionId: String, val packageName: String,
        val windowId: Int, val capturedElapsedMs: Long, val digest: String, val bootTimebaseId: String,
        val artifactIdentity: String, val authenticatedObserverUid: Int, val snapshot: Map<String, Any?>,
        val observerVersionName: String, val observerVersionCode: Long, val observerSignerSha256: String,
        val observerArtifactSha256: String, val observerSourceCommit: String)
    sealed interface Result {
        data class Candidate(val evidence: CandidateEvidence) : Result
        data class Refused(val reason: String) : Result
    }

    fun capture(requestId: String, executionId: String, expectedPackage: String, expectedWindowId: Int,
                dispatchElapsedMs: Long, maxAgeMs: Long = MAX_AGE_MS, maxNodes: Int = MAX_NODES,
                timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val bootId = bootTimebaseId() ?: return Result.Refused("observer-timebase-unavailable")
        val identity = identityProvider() ?: return Result.Refused("observer-provenance-unavailable")
        if (identity.signerSha256 != EXPECTED_SIGNER_SHA256 || maxAgeMs !in 1..MAX_AGE_MS || maxNodes !in 1..MAX_NODES)
            return Result.Refused("observer-provenance-unavailable")
        val challenge = ByteArray(36).also(SecureRandom()::nextBytes).let {
            android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
        val request = ObserverIpcRequestMessageV1(PROTOCOL, challenge, requestId, executionId,
            expectedPackage, expectedWindowId, maxAgeMs, maxNodes, dispatchElapsedMs, bootId)
        return when (val result = try { transport.exchange(request, timeoutMs) }
            catch (_: SecurityException) { ObserverIpcTransportResultV1.Failure("observer-permission-denied") }
            catch (_: Exception) { ObserverIpcTransportResultV1.Failure("observer-transport-failed") }) {
            is ObserverIpcTransportResultV1.Failure -> Result.Refused(result.reasonCode)
            is ObserverIpcTransportResultV1.Reply -> try {
                when (val validated = ObserverIpcReplyValidatorV1.validate(
                    ObserverIpcExpectedV1(requestId, executionId, challenge, bootId, expectedPackage,
                        expectedWindowId, dispatchElapsedMs, maxAgeMs, maxNodes, identity.uid, identity.versionCode, identity.versionName,
                        identity.signerSha256, identity.artifactSha256), result.value.reply, result.value.callerUid,
                    result.value.callerPackages, elapsedRealtime(), maxAgeMs)) {
                    is ObserverIpcValidatedResponseV1.Refused -> Result.Refused(validated.reasonCode)
                    is ObserverIpcValidatedResponseV1.Candidate -> {
                        val e = validated.evidence
                        Result.Candidate(CandidateEvidence(e.requestId, e.executionId, e.packageName, e.windowId,
                            e.capturedElapsedMs, e.digest, e.bootTimebaseId, e.artifactIdentity, e.observerUid,
                            e.snapshot, e.observerVersionName, e.observerVersionCode, e.observerSignerSha256,
                            e.observerArtifactSha256, e.observerSourceCommit))
                    }
                }
            } catch (failure: Exception) {
                val code = failure.message?.takeIf { it.matches(REASON_REGEX) } ?: "observer-response-invalid"
                Result.Refused(code)
            }
        }
    }

    private fun bootTimebaseId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            .also { require(it.matches(BOOT_ID_REGEX)) }
    }.getOrNull()

    companion object {
        private const val OBSERVER_PACKAGE = "org.tessera.observer"
        private const val PROTOCOL = "tessera.observer-ipc/1"
        private const val MAX_AGE_MS = 2500L
        private const val MAX_NODES = 4096
        private const val DEFAULT_TIMEOUT_MS = 1500L
        internal const val EXPECTED_SIGNER_SHA256 = "37d7923bc149506e0d5fbed5298fb4456bf176f79634170f1b74a5241f3b68eb"
        private val BOOT_ID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val REASON_REGEX = Regex("[a-z][a-z0-9-]{0,63}")
    }

    @Suppress("DEPRECATION")
    private fun installedObserverIdentity(context: Context): ObserverInstalledIdentityV1? = runCatching {
        val info = if (android.os.Build.VERSION.SDK_INT >= 28)
            context.packageManager.getPackageInfo(OBSERVER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        else context.packageManager.getPackageInfo(OBSERVER_PACKAGE, PackageManager.GET_SIGNATURES)
        val signer = (if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.firstOrNull()?.toByteArray()?.let { sha256(it) } ?: return null
        val appInfo = context.packageManager.getApplicationInfo(OBSERVER_PACKAGE, 0)
        val apkDigest = java.io.File(appInfo.sourceDir).inputStream().use { stream ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
        val version = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        ObserverInstalledIdentityV1(appInfo.uid, version, info.versionName ?: "", signer, apkDigest)
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { b -> "%02x".format(b) }
}

/** Android Messenger binding adapter with explicit lifecycle and permission failure results. */
private class AndroidMessengerObserverTransportV1(private val context: Context) : ObserverIpcTransportV1 {
    override fun exchange(request: ObserverIpcRequestMessageV1, timeoutMs: Long): ObserverIpcTransportResultV1 {
        val component = ComponentName(OBSERVER_PACKAGE, "$OBSERVER_PACKAGE.ObserverIpcService")
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return ObserverIpcTransportResultV1.Failure("observer-service-unresolved")
        } catch (_: SecurityException) {
            return ObserverIpcTransportResultV1.Failure("observer-service-lookup-denied")
        }

        val callbackThread = HandlerThread("tessera-observer-ipc-reply").apply { start() }
        val completed = CountDownLatch(1)
        val result = AtomicReference<ObserverIpcTransportResultV1?>(null)
        fun finish(value: ObserverIpcTransportResultV1) {
            if (result.compareAndSet(null, value)) completed.countDown()
        }
        val replyMessenger = Messenger(Handler(callbackThread.looper) { message ->
            val parsed = runCatching { parseReply(message) }.getOrElse {
                finish(ObserverIpcTransportResultV1.Failure("observer-response-invalid"))
                return@Handler true
            }
            finish(ObserverIpcTransportResultV1.Reply(parsed))
            true
        })
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    finish(ObserverIpcTransportResultV1.Failure("observer-null-binder"))
                    return
                }
                try {
                    val data = Bundle().apply {
                        putString("protocol", request.protocol)
                        putString("challenge", request.challenge)
                        putString("requestId", request.requestId)
                        putString("executionId", request.executionId)
                        putString("expectedPackage", request.expectedPackage)
                        putInt("expectedWindowId", request.expectedWindowId)
                        putLong("maxAgeMs", request.maxAgeMs)
                        putInt("maxNodes", request.maxNodes)
                        putLong("dispatchElapsedRealtimeMs", request.dispatchElapsedRealtimeMs)
                        putString("bootTimebaseId", request.bootTimebaseId)
                    }
                    Messenger(binder).send(Message.obtain(null, MSG_EVIDENCE).apply {
                        this.data = data
                        replyTo = replyMessenger
                    })
                } catch (_: SecurityException) {
                    finish(ObserverIpcTransportResultV1.Failure("observer-permission-denied"))
                } catch (_: RemoteException) {
                    finish(ObserverIpcTransportResultV1.Failure("observer-send-failed"))
                } catch (_: Exception) {
                    finish(ObserverIpcTransportResultV1.Failure("observer-send-failed"))
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                finish(ObserverIpcTransportResultV1.Failure("observer-service-disconnected"))
            }
            override fun onBindingDied(name: ComponentName?) {
                finish(ObserverIpcTransportResultV1.Failure("observer-binding-died"))
            }
            override fun onNullBinding(name: ComponentName?) {
                finish(ObserverIpcTransportResultV1.Failure("observer-null-binding"))
            }
        }

        try {
            bound = try { context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE) }
                catch (_: SecurityException) {
                    finish(ObserverIpcTransportResultV1.Failure("observer-permission-denied")); false
                } catch (_: Exception) {
                    finish(ObserverIpcTransportResultV1.Failure("observer-bind-failed")); false
                }
            if (!bound && result.get() == null) finish(ObserverIpcTransportResultV1.Failure("observer-bind-rejected"))
            if (!completed.await(timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS))
                finish(ObserverIpcTransportResultV1.Failure("observer-reply-timeout"))
            return result.get() ?: ObserverIpcTransportResultV1.Failure("observer-reply-timeout")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ObserverIpcTransportResultV1.Failure("observer-wait-interrupted")
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            callbackThread.quitSafely()
        }
    }

    private fun parseReply(message: Message): ObserverIpcTransportReplyV1 {
        val data = message.data ?: error("missing-bundle")
        require(data.keySet() == RESPONSE_KEYS) { "response-fields-invalid" }
        val reply = ObserverIpcReplyV1(
            protocol = data.getString("protocol") ?: "",
            outcome = data.getString("outcome") ?: "",
            reasonCode = data.getString("reasonCode"),
            requestId = data.getString("requestId") ?: "",
            executionId = data.getString("executionId") ?: "",
            challenge = data.getString("challenge") ?: "",
            bootTimebaseId = data.getString("bootTimebaseId") ?: "",
            packageName = data.getString("packageName"),
            windowId = data.getInt("windowId", -1).takeIf { it >= 0 },
            capturedElapsedMs = data.getLong("capturedElapsedRealtimeMs", -1L).takeIf { it >= 0 },
            observerElapsedMs = data.getLong("observerElapsedRealtimeMs", -1L),
            complete = data.getBoolean("complete"),
            digest = data.getString("evidenceDigest"),
            snapshotJson = data.getString("snapshotJson"),
            observerPackage = data.getString("observerPackage") ?: "",
            artifactIdentity = data.getString("observerArtifactIdentity") ?: "",
            observerVersionName = data.getString("observerVersionName") ?: "",
            observerVersionCode = data.getLong("observerVersionCode", 0L),
            observerSignerSha256 = data.getString("observerSignerSha256") ?: "",
            observerArtifactSha256 = data.getString("observerArtifactSha256") ?: "",
            observerSourceCommit = data.getString("observerSourceCommit") ?: "",
            verified = data.getBoolean("verified", true)
        )
        val uid = message.sendingUid
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        return ObserverIpcTransportReplyV1(reply, uid, packages)
    }

    companion object {
        private const val OBSERVER_PACKAGE = "org.tessera.observer"
        private const val MSG_EVIDENCE = 1
        private const val MAX_TIMEOUT_MS = 5000L
        private val RESPONSE_KEYS = setOf("protocol", "outcome", "reasonCode", "requestId", "executionId", "challenge",
            "bootTimebaseId", "packageName", "windowId", "capturedElapsedRealtimeMs", "observerElapsedRealtimeMs",
            "evidenceDigest", "snapshotJson", "observerPackage", "observerArtifactIdentity", "observerVersionName",
            "observerVersionCode", "observerSignerSha256", "observerArtifactSha256", "observerSourceCommit", "complete", "verified")
    }
}

/** Android runtime composition returns precise diagnostics; candidate digest is never promoted to a full snapshot. */
internal class AndroidV1_1RuntimePorts(private val context: Context) : V1_1RuntimePorts {
    override val executionGateApproved = false
    override val authorityAvailable = false
    override val observerApproved = false

    override fun capture(request: Map<String, Any?>): CaptureResult {
        val payload = request["payload"] as Map<*, *>
        val pkg = payload["expectedPackage"] as String
        val window = (payload["expectedWindowId"] as Long).toInt()
        val maxAgeMs = payload["maxAgeMs"] as Long
        val maxNodes = (payload["maxNodes"] as Long).toInt()
        val requestId = request["requestId"] as String
        val result = ObserverIpcClientV1(context).capture(requestId, requestId, pkg, window,
            SystemClock.elapsedRealtime(), maxAgeMs, maxNodes)
        return when (result) {
            is ObserverIpcClientV1.Result.Refused -> refusalFor(result.reason)
            is ObserverIpcClientV1.Result.Candidate -> {
                val evidence = result.evidence
                val snapshot = evidence.snapshot
                val producer = mapOf(
                    "producerId" to "org.tessera.observer.ReadOnlyAccessibilityObserverService",
                    "producerPackage" to "org.tessera.observer",
                    "producerUid" to evidence.authenticatedObserverUid.toLong(),
                    "producerVersionName" to evidence.observerVersionName,
                    "producerVersionCode" to evidence.observerVersionCode,
                    "signerCertificateSha256" to evidence.observerSignerSha256,
                    "deviceSerial" to ((request["deviceBinding"] as Map<*, *>)["deviceSerial"] as String),
                    "taskId" to request["taskId"], "sessionId" to request["sessionId"],
                    "snapshotId" to snapshot["snapshotId"], "sourceCommit" to evidence.observerSourceCommit,
                    "artifactSha256" to evidence.observerArtifactSha256)
                CaptureResult("CAPTURED", "capture-complete", snapshot, producer)
            }
        }
    }

    companion object {
        internal fun refusalFor(reason: String): CaptureResult = when (reason) {
            "observer-permission-denied", "observer-service-lookup-denied" ->
                CaptureResult("PERMISSION_UNAVAILABLE", reason)
            "observer-reply-timeout", "observer-wait-interrupted" -> CaptureResult("TIMEOUT", reason)
            "snapshot-incomplete", "active-window-unavailable", "accessibility-service-unavailable" ->
                CaptureResult("INCOMPLETE", reason)
            "snapshot-stale", "snapshot-pre-dispatch" -> CaptureResult("STALE", reason)
            "unsupported-evidence-profile" -> CaptureResult("UNSUPPORTED_PROFILE", reason)
            "observer-provenance-unavailable", "observer-ipc-artifact-mismatch", "observer-ipc-source-provenance-invalid" ->
                CaptureResult("OBSERVER_UNAVAILABLE", reason)
            "snapshot-payload-too-large", "observer-ipc-snapshot-size-invalid", "observer-ipc-snapshot-invalid",
            "observer-ipc-snapshot-fields-invalid", "observer-ipc-snapshot-profile-invalid", "observer-ipc-snapshot-id-invalid",
            "observer-ipc-snapshot-identity-mismatch", "observer-ipc-snapshot-time-invalid", "observer-ipc-snapshot-incomplete",
            "observer-ipc-snapshot-node-count-invalid", "observer-ipc-node-invalid", "observer-ipc-node-fields-invalid",
            "observer-ipc-node-tree-invalid", "observer-ipc-node-flag-invalid", "observer-ipc-node-bounds-invalid",
            "observer-ipc-node-resource-invalid", "observer-ipc-node-class-invalid", "observer-ipc-traversal-invalid",
            "observer-ipc-snapshot-digest-mismatch" -> CaptureResult("INCOMPLETE", reason)
            "observer-ipc-snapshot-stale" -> CaptureResult("STALE", reason)
            else -> CaptureResult("OBSERVER_UNAVAILABLE", reason)
        }
    }
}
