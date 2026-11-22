package com.codexedge.glow

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.codexedge.glow.fastpathv1.AppIdentityV1
import com.codexedge.glow.fastpathv1.FastPathActionPortV1
import com.codexedge.glow.fastpathv1.UiEvidenceV1
import com.codexedge.glow.fastpathv1.VerificationEvidenceV1
import com.codexedge.glow.fastpathv1.VisibleTargetV1
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/**
 * Android-only adapter for the approved AccessibilityService interface. It does
 * not implement a route-state fingerprint algorithm because M1 defines the field
 * but does not define how an accessibility tree is converted to that digest.
 * Until an application-controlled implementation is installed, capture fails
 * closed. The independent verifier is also intentionally absent by default.
 */
internal class FastPathAndroidActionPortV1(
    private val context: Context,
    private val fingerprintProvider: FastPathStateFingerprintProviderV1?,
    private val independentReader: FastPathIndependentReaderV1? = null
) : FastPathActionPortV1 {
    // First node is the selector match; second is the actionable clickable
    // ancestor. Keep both so dispatch can refresh and revalidate the selector.
    private val targetHandles = mutableMapOf<String, Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>>()
    private var activeSnapshotToken = ""

    override fun installedApp(packageName: String): AppIdentityV1? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        if (signatures.size != 1) null else {
            val certificateSha256 = sha256(signatures.single().toByteArray())
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            AppIdentityV1(packageName, info.versionName ?: "", versionCode, certificateSha256)
        }
    } catch (_: Exception) { null }

    override fun captureFresh(packageName: String, expectedWindowId: Int?, maxNodes: Int, maxAgeMs: Int): UiEvidenceV1? {
        val service = ScreenAccessService.current ?: return null
        val started = SystemClock.elapsedRealtime()
        val root = service.rootInActiveWindow ?: return null
        val windowId = root.windowId
        val pkg = root.packageName?.toString() ?: return null
        if (pkg != packageName || windowId < 0 || (expectedWindowId != null && windowId != expectedWindowId)) {
            root.recycle()
            return null
        }
        recycleHandles()
        activeSnapshotToken = UUID.randomUUID().toString()
        val visible = mutableListOf<VisibleTargetV1>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var complete = true
        try {
            while (queue.isNotEmpty()) {
                if (visited >= maxNodes || SystemClock.elapsedRealtime() - started >= MAX_TRAVERSAL_MS) {
                    complete = false
                    break
                }
                val (node, depth) = queue.removeFirst()
                try {
                    visited++
                    if (node.isVisibleToUser) {
                        val clickable = clickableAncestor(node)
                        val handle = if (clickable == null) "$activeSnapshotToken:n$visited" else {
                            // Preserve one handle per selector node. If two nodes
                            // resolve to one clickable ancestor, the engine sees
                            // ambiguity and refuses instead of silently collapsing
                            // distinct selector matches.
                            val actionHandle = "$activeSnapshotToken:a${targetHandles.size}"
                            targetHandles[actionHandle] = AccessibilityNodeInfo.obtain(node) to
                                AccessibilityNodeInfo.obtain(clickable)
                            clickable.recycle()
                            actionHandle
                        }
                        visible.add(VisibleTargetV1(handle, pkg, node.windowId, true, node.isEnabled,
                            clickableHandle(handle), node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName))
                    }
                    if (depth >= MAX_TREE_DEPTH && node.childCount > 0) {
                        complete = false
                    } else {
                        for (index in 0 until node.childCount) {
                            val child = node.getChild(index)
                            if (child == null) complete = false else queue.add(child to depth + 1)
                        }
                    }
                } finally {
                    node.recycle()
                }
                if (!complete) break
            }
        } catch (_: Exception) {
            complete = false
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        val after = service.rootInActiveWindow
        val stable = try { after != null && after.windowId == windowId && after.packageName?.toString() == pkg }
            finally { after?.recycle() }
        val duration = SystemClock.elapsedRealtime() - started
        if (!complete || !stable || duration > MAX_TRAVERSAL_MS || visible.size > maxNodes) {
            recycleHandles()
            return null
        }
        val fingerprint = fingerprintProvider?.fingerprint(pkg, windowId, visible) ?: run {
            recycleHandles()
            return null
        }
        if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) {
            recycleHandles()
            return null
        }
        return UiEvidenceV1(pkg, windowId, windowId, after!!.windowId,
            SystemClock.elapsedRealtime(), true, false, fingerprint, visible)
    }

    override fun dispatchClick(target: VisibleTargetV1, expectedPackage: String, expectedWindowId: Int): Boolean {
        val service = ScreenAccessService.current ?: return false
        if (!target.opaqueHandle.startsWith("$activeSnapshotToken:")) return false
        val currentRoot = service.rootInActiveWindow ?: return false
        val foregroundMatches = try { currentRoot.packageName?.toString() == expectedPackage &&
            currentRoot.windowId == expectedWindowId }
            finally { currentRoot.recycle() }
        if (!foregroundMatches || target.packageName != expectedPackage || target.windowId != expectedWindowId ||
            !target.visible || !target.enabled || !target.actionable) return false
        val handles = targetHandles[target.opaqueHandle] ?: return false
        val selectorNode = handles.first
        val actionNode = handles.second
        if (!selectorNode.refresh() || !selectorNode.isVisibleToUser || !selectorNode.isEnabled ||
            selectorNode.windowId != expectedWindowId || selectorNode.text?.toString() != target.text ||
            selectorNode.contentDescription?.toString() != target.contentDescription ||
            selectorNode.viewIdResourceName != target.resourceId) return false
        if (!actionNode.refresh() || !actionNode.isVisibleToUser || !actionNode.isEnabled ||
            !actionNode.isClickable || actionNode.windowId != expectedWindowId ||
            actionNode.packageName?.toString() != expectedPackage) return false
        // Single Accessibility call only. The durable caller journal has already
        // committed DISPATCH_POSSIBLE; this method never retries.
        return actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(12) {
            val value = current ?: return null
            if (value.isVisibleToUser && value.isEnabled && value.isClickable && !value.isPassword) return value
            val parent = value.parent
            value.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    private fun clickableHandle(handle: String) = ":a" in handle

    override fun independentlyVerify(route: com.codexedge.glow.fastpathv1.ExecutableRouteV1,
                                     expectedWindowId: Int, maxAgeMs: Int): VerificationEvidenceV1? =
        independentReader?.verify(route, expectedWindowId, maxAgeMs)

    override fun release() = recycleHandles()

    private fun recycleHandles() {
        targetHandles.values.forEach { handles ->
            runCatching { handles.first.recycle() }
            runCatching { handles.second.recycle() }
        }
        targetHandles.clear()
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_TRAVERSAL_MS = 250L
        private const val MAX_TREE_DEPTH = 64
    }
}

/** M1 stateFingerprint has no producer algorithm in the supplied contract. */
internal fun interface FastPathStateFingerprintProviderV1 {
    fun fingerprint(packageName: String, windowId: Int, visibleTargets: List<VisibleTargetV1>): String?
}

/** No production observer is configured in this baseline. */
internal fun interface FastPathIndependentReaderV1 {
    fun verify(route: com.codexedge.glow.fastpathv1.ExecutableRouteV1,
               expectedWindowId: Int, maxAgeMs: Int): VerificationEvidenceV1?
}
