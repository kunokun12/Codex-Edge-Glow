package com.codexedge.glow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.codexedge.glow.fastpathv1.FastPathResultStoreV1
import com.codexedge.glow.fastpathv1.AuthorityTrustV1
import com.codexedge.glow.fastpathv1.FastPathRegistryV1
import com.google.gson.JsonParser

/** Read-only, tuple-scoped result API. It never exposes a path or arbitrary file. */
class FastPathResultProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        app.enforceCallingPermission("android.permission.DUMP", "Fast Path results are DUMP protected")
        if (method != "readResult" || arg != null) throw IllegalArgumentException("unsupported-provider-call")
        val expected = setOf("requestId", "taskId", "sessionId", "ownerId")
        if (extras == null || extras.keySet() != expected) throw IllegalArgumentException("invalid-result-query")
        val requestId = extras.getString("requestId") ?: ""
        val taskId = extras.getString("taskId") ?: ""
        val sessionId = extras.getString("sessionId") ?: ""
        val ownerId = extras.getString("ownerId") ?: ""
        val response = FastPathResultStoreV1(app).read(
            requestId, taskId, sessionId, ownerId)
            ?: readRegistryReceipt(app, requestId, taskId, sessionId, ownerId)
            ?: throw IllegalArgumentException("result-not-found-or-correlation-mismatch")
        return Bundle().apply { putString("responseJson", response) }
    }

    private fun readRegistryReceipt(app: android.content.Context, requestId: String,
                                    taskId: String, sessionId: String, ownerId: String): String? = try {
        FastPathRegistryV1(app, AuthorityTrustV1.fromApkAsset(app)).use { registry ->
            val json = registry.operationResponse(requestId) ?: return null
            val response = JsonParser.parseString(json).asJsonObject
            if (response.get("requestId")?.asString != requestId ||
                response.get("taskId")?.asString != taskId ||
                response.get("sessionId")?.asString != sessionId ||
                response.get("ownerId")?.asString != ownerId) null else json
        }
    } catch (_: Exception) { null }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("query-not-supported")

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
}
