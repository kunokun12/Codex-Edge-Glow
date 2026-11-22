package com.codexedge.glow.fastpathv1

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.SystemClock
import org.json.JSONObject

/**
 * Internal read-only, one-shot handoff from the isolated candidate observer
 * process to the Companion process. It is not a route.verify endpoint and does
 * not supply approved build provenance.
 */
internal class CandidateObserverSnapshotProviderV1 : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val appContext = context ?: throw IllegalStateException("provider-context-unavailable")
        if (Binder.getCallingUid() != appContext.applicationInfo.uid) throw SecurityException("same-app-caller-required")
        require(uri.authority == AUTHORITY && uri.pathSegments == listOf("latest")) { "observer-uri-invalid" }
        require(selection == null && selectionArgs == null && sortOrder == null) { "observer-query-invalid" }
        require(projection == null || projection.contentEquals(arrayOf(COLUMN_SNAPSHOT_JSON))) { "observer-projection-invalid" }

        val cursor = MatrixCursor(arrayOf(COLUMN_SNAPSHOT_JSON))
        val stored = CandidateObserverSnapshotStoreV1.take() ?: return cursor
        val snapshot = try { JSONObject(stored) } catch (_: Exception) { return cursor }
        val capturedAt = try { snapshot.getLong("capturedAtElapsedRealtimeMs") } catch (_: Exception) { return cursor }
        val now = SystemClock.elapsedRealtime()
        if (capturedAt < 0L || now < capturedAt) return cursor
        val age = now - capturedAt
        if (age > MAX_AGE_MS || !snapshot.optBoolean("treeComplete", false)) return cursor
        snapshot.put("ageMs", age)
        snapshot.remove("sha256")
        snapshot.put("sha256", CandidateObserverSnapshotV1.candidateDigest(snapshot))
        cursor.addRow(arrayOf(snapshot.toString()))
        return cursor
    }

    override fun getType(uri: Uri): String? {
        require(uri.authority == AUTHORITY && uri.pathSegments == listOf("latest")) { "observer-uri-invalid" }
        return "vnd.android.cursor.item/vnd.com.codexedge.glow.candidate-snapshot-v1"
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException("read-only-observer")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("read-only-observer")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException("read-only-observer")

    companion object {
        const val AUTHORITY = "com.codexedge.glow.observer.internal"
        const val COLUMN_SNAPSHOT_JSON = "snapshot_json"
        private const val MAX_AGE_MS = 2500L
    }
}
