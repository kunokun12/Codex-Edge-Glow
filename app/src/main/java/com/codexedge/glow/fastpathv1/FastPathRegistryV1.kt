package com.codexedge.glow.fastpathv1

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseErrorHandler
import android.util.AtomicFile
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class RegistryMutation(
    val accepted: Boolean,
    val reasonCode: String,
    val registryEpoch: Long,
    val routeId: String,
    val routeVersion: Long,
    val routeFingerprint: String,
    val authorityKeyId: String,
    val expiresAtEpochMs: Long? = null,
    val responseJson: String? = null,
    val replayed: Boolean = false
)

internal data class RegistryOperationReceiptV1(
    val requestId: String,
    val operation: String,
    val requestDigest: String,
    val responseJson: String,
    val accepted: Boolean = true,
    val resultReasonCode: String = "promoted"
)

internal data class PromotedRouteV1(
    val routeId: String,
    val routeVersion: Long,
    val routeFingerprint: String,
    val routeDefinition: Map<String, Any?>,
    val promotionRecord: Map<String, Any?>,
    val authorityKeyId: String,
    val registryEpoch: Long,
    val expiresAtEpochMs: Long
)

internal class RegistryStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** SQLite backed registry. Every authorization decision and epoch update shares
 * one SQLite transaction; a successful result is returned only after commit.
 */
internal class FastPathRegistryV1(
    context: Context,
    private val trust: AuthorityTrustV1,
    private val wallClock: () -> Long = System::currentTimeMillis,
    databaseName: String = "fastpath-registry-v1.db"
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val markerFile = File(appContext.filesDir, "fastpath-registry-v1.present")
    private val corruptionObserved = AtomicBoolean(false)
    private val helper = RegistryDb(appContext, databaseName, markerFile, corruptionObserved)
    private val marker = AtomicFile(markerFile)

    private fun database(write: Boolean = false): SQLiteDatabase {
        try {
            val db = if (write) helper.writableDatabase else helper.readableDatabase
            if (!marker.baseFile.exists()) {
                val version = db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
                if (version != SCHEMA_VERSION) throw RegistryStorageException("registry-schema-unavailable")
                val output = marker.startWrite()
                try {
                    output.write(MARKER_BYTES)
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
            } else {
                val bytes = marker.baseFile.readBytes()
                if (!bytes.contentEquals(MARKER_BYTES)) throw RegistryStorageException("registry-marker-corrupt")
            }
            return db
        } catch (error: RegistryStorageException) {
            throw error
        } catch (error: Throwable) {
            throw RegistryStorageException("registry-storage-unavailable", error)
        }
    }

    @Synchronized
    fun applyPromotion(record: Map<String, Any?>, receipt: RegistryOperationReceiptV1? = null,
                       refusalResponse: ((String, Long) -> String)? = null): RegistryMutation {
        val db = database(write = true)
        db.beginTransaction()
        try {
            val committedEpoch = highestEpoch(db)
            priorReceipt(db, receipt)?.let { prior ->
                if (prior.operation != "route.promote" || prior.requestDigest != receipt?.requestDigest)
                    return refused(record, "request-id-body-mismatch", committedEpoch)
                return RegistryMutation(prior.accepted, prior.resultReasonCode,
                    if (prior.accepted) record["registryEpoch"] as Long else committedEpoch,
                    record["routeId"] as String, record["routeVersion"] as Long,
                    record["routeFingerprint"] as String, record["authorityKeyId"] as String,
                    record["expiresAtEpochMs"] as Long, prior.responseJson, true)
            }
            fun reject(reason: String): RegistryMutation {
                val base = refused(record, reason, committedEpoch)
                if (receipt == null || refusalResponse == null) return base
                val json = refusalResponse(reason, committedEpoch)
                persistReceipt(db, receipt.copy(responseJson = json, accepted = false,
                    resultReasonCode = reason))
                db.setTransactionSuccessful()
                return base.copy(responseJson = json)
            }
            try { StrictEnvelopeV1.validatePromotionRecord(record) }
            catch (_: Exception) { return reject("invalid-record") }
            val id = record["routeId"] as? String ?: return reject("invalid-record")
            val version = record["routeVersion"] as? Long ?: return reject("invalid-record")
            val fingerprint = record["routeFingerprint"] as? String ?: return reject("invalid-record")
            val authority = record["authorityKeyId"] as? String ?: return reject("invalid-record")
            val expires = record["expiresAtEpochMs"] as? Long ?: return reject("invalid-record")
            val epoch = record["registryEpoch"] as? Long ?: return reject("invalid-record")
            if (!trust.isConfigured) return reject("no-trusted-authority")
            val keys = trust.keysForEpoch(epoch)
            if (!AuthorityAdmissionV1.authorize(record, AuthorityAdmissionV1.Kind.PROMOTION,
                    wallClock(), committedEpoch, keys)) return reject("authorization-failed")
            @Suppress("UNCHECKED_CAST")
            val definition = record["routeDefinition"] as? Map<String, Any?>
                ?: return reject("invalid-route-definition")
            if (CanonicalRouteV1.fingerprint(id, version, definition) != fingerprint)
                return reject("fingerprint-mismatch")

            val existing = routeVersionState(db, id, version)
            val latest = latestVersion(db, id)
            if (latest != null && version < latest) return reject("route-version-downgrade")
            if (existing?.first == "REVOKED") return reject("route-version-revoked")
            if (existing != null && existing.second != fingerprint)
                return reject("route-version-conflict")
            if (version <= 0 || epoch <= committedEpoch) return reject("stale-registry-epoch")

            val values = ContentValues().apply {
                put("route_id", id)
                put("route_version", version)
                put("route_fingerprint", fingerprint)
                put("route_definition", CanonicalRouteV1.encode(definition))
                put("promotion_record", CanonicalRouteV1.encode(record))
                put("authority_key_id", authority)
                put("registry_epoch", epoch)
                put("expires_at_epoch_ms", expires)
                put("authorization_status", "PROMOTED")
                putNull("revocation_record")
            }
            db.insertWithOnConflict("routes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            appendRegistryEvent(db, epoch, "PROMOTION", id, version, fingerprint, digest(record))
            db.execSQL("UPDATE registry_meta SET highest_epoch=? WHERE singleton=1", arrayOf(epoch))
            persistReceipt(db, receipt)
            db.setTransactionSuccessful()
            return RegistryMutation(true, "promoted", epoch, id, version, fingerprint, authority, expires,
                receipt?.responseJson)
        } catch (error: RegistryStorageException) {
            throw error
        } catch (error: Throwable) {
            throw RegistryStorageException("registry-transaction-failed", error)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun applyRevocation(record: Map<String, Any?>, receipt: RegistryOperationReceiptV1? = null,
                        refusalResponse: ((String, Long) -> String)? = null): RegistryMutation {
        val db = database(write = true)
        db.beginTransaction()
        try {
            val committedEpoch = highestEpoch(db)
            priorReceipt(db, receipt)?.let { prior ->
                if (prior.operation != "route.revoke" || prior.requestDigest != receipt?.requestDigest)
                    return refused(record, "request-id-body-mismatch", committedEpoch)
                return RegistryMutation(prior.accepted, prior.resultReasonCode,
                    if (prior.accepted) record["registryEpoch"] as Long else committedEpoch,
                    record["routeId"] as String, record["routeVersion"] as Long,
                    record["routeFingerprint"] as String, record["authorityKeyId"] as String,
                    responseJson = prior.responseJson, replayed = true)
            }
            fun reject(reason: String): RegistryMutation {
                val base = refused(record, reason, committedEpoch)
                if (receipt == null || refusalResponse == null) return base
                val json = refusalResponse(reason, committedEpoch)
                persistReceipt(db, receipt.copy(responseJson = json, accepted = false,
                    resultReasonCode = reason))
                db.setTransactionSuccessful()
                return base.copy(responseJson = json)
            }
            try { StrictEnvelopeV1.validateRevocationRecord(record) }
            catch (_: Exception) { return reject("invalid-record") }
            val id = record["routeId"] as? String ?: return reject("invalid-record")
            val version = record["routeVersion"] as? Long ?: return reject("invalid-record")
            val fingerprint = record["routeFingerprint"] as? String ?: return reject("invalid-record")
            val authority = record["authorityKeyId"] as? String ?: return reject("invalid-record")
            val epoch = record["registryEpoch"] as? Long ?: return reject("invalid-record")
            if (!trust.isConfigured) return reject("no-trusted-authority")
            if (!AuthorityAdmissionV1.authorize(record, AuthorityAdmissionV1.Kind.REVOCATION,
                    wallClock(), committedEpoch, trust.keysForEpoch(epoch)))
                return reject("authorization-failed")
            val existing = routeVersionState(db, id, version)
            if (existing != null && existing.second != fingerprint)
                return reject("route-version-conflict")
            if (epoch <= committedEpoch) return reject("stale-registry-epoch")

            val previous = readRoute(db, id, version)
            val values = ContentValues().apply {
                put("route_id", id)
                put("route_version", version)
                put("route_fingerprint", fingerprint)
                if (previous == null) {
                    putNull("route_definition")
                    putNull("promotion_record")
                } else {
                    put("route_definition", previous.definitionJson)
                    put("promotion_record", previous.promotionJson)
                }
                put("authority_key_id", authority)
                put("registry_epoch", epoch)
                put("expires_at_epoch_ms", previous?.expiresAt ?: 0L)
                put("authorization_status", "REVOKED")
                put("revocation_record", CanonicalRouteV1.encode(record))
            }
            db.insertWithOnConflict("routes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            appendRegistryEvent(db, epoch, "REVOCATION", id, version, fingerprint, digest(record))
            db.execSQL("UPDATE registry_meta SET highest_epoch=? WHERE singleton=1", arrayOf(epoch))
            persistReceipt(db, receipt)
            db.setTransactionSuccessful()
            return RegistryMutation(true, "revoked", epoch, id, version, fingerprint, authority)
        } catch (error: RegistryStorageException) {
            throw error
        } catch (error: Throwable) {
            throw RegistryStorageException("registry-transaction-failed", error)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun findPromoted(routeId: String, routeVersion: Long, fingerprint: String): PromotedRouteV1? {
        val db = database()
        val row = readRoute(db, routeId, routeVersion) ?: return null
        if (row.status != "PROMOTED" || row.fingerprint != fingerprint) return null
        if (latestVersion(db, routeId) != routeVersion || row.expiresAt <= wallClock()) return null
        if (row.definitionJson == null || row.promotionJson == null) throw RegistryStorageException("registry-row-incomplete")
        val definition = parseObject(row.definitionJson)
        val record = parseObject(row.promotionJson)
        val epoch = record["registryEpoch"] as? Long ?: throw RegistryStorageException("registry-record-corrupt")
        val expires = record["expiresAtEpochMs"] as? Long ?: throw RegistryStorageException("registry-record-corrupt")
        val issued = record["issuedAtEpochMs"] as? Long ?: throw RegistryStorageException("registry-record-corrupt")
        if (row.registryEpoch != epoch || row.authorityKeyId != record["authorityKeyId"] || row.expiresAt != expires)
            throw RegistryStorageException("registry-promotion-metadata-mismatch")
        if (expires <= wallClock() || issued > wallClock() || expires <= issued || expires - issued > MAX_PROMOTION_LIFETIME_MS)
            return null
        if (CanonicalRouteV1.fingerprint(routeId, routeVersion, definition) != fingerprint)
            throw RegistryStorageException("registry-fingerprint-corrupt")
        if (!AuthoritySignatureV1.verify(record, trust.keysForEpoch(epoch))) return null
        return PromotedRouteV1(routeId, routeVersion, fingerprint, definition, record,
            row.authorityKeyId, row.registryEpoch, expires)
    }

    @Synchronized
    fun highestEpoch(): Long = highestEpoch(database())

    @Synchronized
    fun operationResponse(requestId: String): String? = database().rawQuery(
        "SELECT response_json FROM operation_receipts WHERE request_id=?", arrayOf(requestId))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Synchronized
    fun registryEvents(): List<Map<String, Any?>> {
        val db = database()
        val result = mutableListOf<Map<String, Any?>>()
        db.rawQuery("SELECT sequence,epoch,operation,route_id,route_version,route_fingerprint,record_digest,previous_digest,event_digest FROM registry_events ORDER BY sequence", null)
            .use { c -> while (c.moveToNext()) result.add(mapOf(
                "sequence" to c.getLong(0), "epoch" to c.getLong(1), "operation" to c.getString(2),
                "routeId" to c.getString(3), "routeVersion" to c.getLong(4), "routeFingerprint" to c.getString(5),
                "recordDigest" to c.getString(6), "previousDigest" to c.getString(7), "eventDigest" to c.getString(8))) }
        return result
    }

    private data class RouteRow(
        val fingerprint: String,
        val definitionJson: String?,
        val promotionJson: String?,
        val status: String,
        val authorityKeyId: String,
        val registryEpoch: Long,
        val expiresAt: Long
    )

    private fun readRoute(db: SQLiteDatabase, id: String, version: Long): RouteRow? =
        db.rawQuery("SELECT route_fingerprint,route_definition,promotion_record,authorization_status,authority_key_id,registry_epoch,expires_at_epoch_ms FROM routes WHERE route_id=? AND route_version=?",
            arrayOf(id, version.toString())).use { c ->
            if (!c.moveToFirst()) null else RouteRow(c.getString(0), if (c.isNull(1)) null else c.getString(1),
                if (c.isNull(2)) null else c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6))
        }

    private fun routeVersionState(db: SQLiteDatabase, id: String, version: Long): Pair<String, String>? =
        db.rawQuery("SELECT authorization_status,route_fingerprint FROM routes WHERE route_id=? AND route_version=?",
            arrayOf(id, version.toString())).use { c -> if (!c.moveToFirst()) null else c.getString(0) to c.getString(1) }

    private fun latestVersion(db: SQLiteDatabase, id: String): Long? =
        db.rawQuery("SELECT MAX(route_version) FROM routes WHERE route_id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) null else c.getLong(0)
        }

    private fun highestEpoch(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT highest_epoch FROM registry_meta WHERE singleton=1", null).use { c ->
        if (!c.moveToFirst()) throw RegistryStorageException("registry-metadata-missing") else c.getLong(0)
    }

    private fun appendRegistryEvent(db: SQLiteDatabase, epoch: Long, operation: String,
                                    routeId: String, version: Long, fingerprint: String, recordDigest: String) {
        val previous = db.rawQuery("SELECT event_digest FROM registry_events ORDER BY sequence DESC LIMIT 1", null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else ZERO_DIGEST }
        val sequence = db.rawQuery("SELECT COALESCE(MAX(sequence),0)+1 FROM registry_events", null)
            .use { c -> c.moveToFirst(); c.getLong(0) }
        val fields = mapOf("sequence" to sequence, "epoch" to epoch, "operation" to operation,
            "routeId" to routeId, "routeVersion" to version, "routeFingerprint" to fingerprint,
            "recordDigest" to recordDigest, "previousDigest" to previous)
        val values = ContentValues().apply {
            put("sequence", sequence); put("epoch", epoch); put("operation", operation)
            put("route_id", routeId); put("route_version", version); put("route_fingerprint", fingerprint)
            put("record_digest", recordDigest); put("previous_digest", previous); put("event_digest", digest(fields))
        }
        db.insertOrThrow("registry_events", null, values)
    }

    private fun priorReceipt(db: SQLiteDatabase, receipt: RegistryOperationReceiptV1?): RegistryOperationReceiptV1? {
        if (receipt == null) return null
        val prior = db.rawQuery("SELECT operation,request_digest,response_json,response_digest,result_accepted,result_reason_code FROM operation_receipts WHERE request_id=?",
            arrayOf(receipt.requestId)).use { c ->
            if (!c.moveToFirst()) null else RegistryOperationReceiptV1(receipt.requestId,
                c.getString(0), c.getString(1), c.getString(2), c.getInt(4) != 0, c.getString(5)).also {
                if (sha256(c.getString(2)) != c.getString(3))
                    throw RegistryStorageException("registry-operation-response-digest-invalid")
            }
        }
        return prior
    }

    private fun persistReceipt(db: SQLiteDatabase, receipt: RegistryOperationReceiptV1?) {
        if (receipt == null) return
        require(UUID4.matches(receipt.requestId) && receipt.operation in setOf("route.promote", "route.revoke") &&
            receipt.requestDigest.matches(Regex("[0-9a-f]{64}")) &&
            receipt.resultReasonCode.matches(Regex("[a-z][a-z0-9-]{0,63}")) &&
            receipt.responseJson.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val values = ContentValues().apply {
            put("request_id", receipt.requestId); put("operation", receipt.operation)
            put("request_digest", receipt.requestDigest); put("response_json", receipt.responseJson)
            put("response_digest", sha256(receipt.responseJson))
            put("result_accepted", if (receipt.accepted) 1 else 0)
            put("result_reason_code", receipt.resultReasonCode)
        }
        db.insertOrThrow("operation_receipts", null, values)
    }

    private fun refused(record: Map<String, Any?>, reason: String, committed: Long): RegistryMutation =
        RegistryMutation(false, reason, committed, record["routeId"] as? String ?: "invalid",
            record["routeVersion"] as? Long ?: 0L, record["routeFingerprint"] as? String ?: "0".repeat(64),
            record["authorityKeyId"] as? String ?: "unknown", record["expiresAtEpochMs"] as? Long)

    private fun digest(value: Map<String, Any?>): String = sha256(CanonicalRouteV1.encode(value))

    private fun parseObject(json: String): Map<String, Any?> {
        val element = JsonParser.parseString(json)
        require(element.isJsonObject)
        @Suppress("UNCHECKED_CAST")
        val value = jsonToValue(element) as Map<String, Any?>
        if (CanonicalRouteV1.encode(value) != json)
            throw RegistryStorageException("registry-json-noncanonical")
        return value
    }

    private fun jsonToValue(value: JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to jsonToValue(it.value) }
        value.isJsonArray -> value.asJsonArray.map(::jsonToValue)
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> value.asLong
        else -> value.asString
    }

    override fun close() = helper.close()

    private inner class RegistryDb(context: Context, name: String, private val markerFile: File,
                                   private val corruptionObserved: AtomicBoolean) :
        SQLiteOpenHelper(context, name, null, SCHEMA_VERSION, DatabaseErrorHandler { corruptionObserved.set(true) }) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("PRAGMA journal_mode=DELETE")
        }

        override fun onCreate(db: SQLiteDatabase) {
            if (markerFile.exists()) throw RegistryStorageException("registry-database-missing")
            db.execSQL("CREATE TABLE registry_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), schema_version INTEGER NOT NULL, highest_epoch INTEGER NOT NULL)")
            db.execSQL("INSERT INTO registry_meta(singleton,schema_version,highest_epoch) VALUES(1,$SCHEMA_VERSION,0)")
            db.execSQL("CREATE TABLE routes(route_id TEXT NOT NULL, route_version INTEGER NOT NULL, route_fingerprint TEXT NOT NULL, route_definition TEXT, promotion_record TEXT, authority_key_id TEXT NOT NULL, registry_epoch INTEGER NOT NULL, expires_at_epoch_ms INTEGER NOT NULL, authorization_status TEXT NOT NULL CHECK(authorization_status IN ('PROMOTED','REVOKED')), revocation_record TEXT, PRIMARY KEY(route_id,route_version))")
            db.execSQL("CREATE TABLE registry_events(sequence INTEGER PRIMARY KEY, epoch INTEGER NOT NULL UNIQUE, operation TEXT NOT NULL CHECK(operation IN ('PROMOTION','REVOCATION')), route_id TEXT NOT NULL, route_version INTEGER NOT NULL, route_fingerprint TEXT NOT NULL, record_digest TEXT NOT NULL, previous_digest TEXT NOT NULL, event_digest TEXT NOT NULL)")
            db.execSQL("CREATE TABLE operation_receipts(request_id TEXT PRIMARY KEY,operation TEXT NOT NULL CHECK(operation IN ('route.promote','route.revoke')),request_digest TEXT NOT NULL,response_json TEXT NOT NULL,response_digest TEXT NOT NULL,result_accepted INTEGER NOT NULL CHECK(result_accepted IN (0,1)),result_reason_code TEXT NOT NULL)")
            db.version = SCHEMA_VERSION
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) throw RegistryStorageException("registry-migration-required")
            db.execSQL("ALTER TABLE operation_receipts ADD COLUMN response_digest TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE operation_receipts ADD COLUMN result_accepted INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE operation_receipts ADD COLUMN result_reason_code TEXT NOT NULL DEFAULT 'promoted'")
            val receipts = mutableListOf<Pair<String, String>>()
            db.rawQuery("SELECT request_id,operation,response_json FROM operation_receipts", null).use { c ->
                while (c.moveToNext()) receipts += c.getString(0) to c.getString(2)
            }
            receipts.forEach { (requestId, responseJson) ->
                val reason = if (responseJson.contains("route.revoke.result")) "revoked" else "promoted"
                db.execSQL("UPDATE operation_receipts SET response_digest=?,result_reason_code=? WHERE request_id=?",
                    arrayOf(sha256(responseJson), reason, requestId))
            }
            db.execSQL("UPDATE registry_meta SET schema_version=? WHERE singleton=1", arrayOf(newVersion))
            db.version = newVersion
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw RegistryStorageException("registry-downgrade-refused")
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (corruptionObserved.get()) throw RegistryStorageException("registry-corruption-detected")
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            }
            if (integrity != "ok") throw RegistryStorageException("registry-integrity-check-failed")
            val meta = db.rawQuery("SELECT schema_version,highest_epoch FROM registry_meta WHERE singleton=1", null).use { c ->
                if (!c.moveToFirst()) null else c.getInt(0) to c.getLong(1)
            } ?: throw RegistryStorageException("registry-metadata-missing")
            if (meta.first != SCHEMA_VERSION) throw RegistryStorageException("registry-schema-mismatch")
            val maxEventEpoch = db.rawQuery("SELECT COALESCE(MAX(epoch),0) FROM registry_events", null).use { c -> c.moveToFirst(); c.getLong(0) }
            if (maxEventEpoch != meta.second) throw RegistryStorageException("registry-epoch-regression")
            verifyEventChain(db)
            verifyRouteFingerprints(db)
            verifyRouteProjection(db)
            verifyReceipts(db)
        }

        private fun verifyEventChain(db: SQLiteDatabase) {
            var previous = ZERO_DIGEST
            var expectedSequence = 1L
            var previousEpoch = 0L
            db.rawQuery("SELECT sequence,epoch,operation,route_id,route_version,route_fingerprint,record_digest,previous_digest,event_digest FROM registry_events ORDER BY sequence", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) != expectedSequence++ || c.getString(7) != previous ||
                        c.getLong(1) <= previousEpoch)
                        throw RegistryStorageException("registry-event-chain-broken")
                    previousEpoch = c.getLong(1)
                    val event = mapOf("sequence" to c.getLong(0), "epoch" to c.getLong(1),
                        "operation" to c.getString(2), "routeId" to c.getString(3),
                        "routeVersion" to c.getLong(4), "routeFingerprint" to c.getString(5),
                        "recordDigest" to c.getString(6), "previousDigest" to c.getString(7))
                    val digest = sha256(CanonicalRouteV1.encode(event))
                    if (digest != c.getString(8)) throw RegistryStorageException("registry-event-digest-invalid")
                    previous = digest
                }
            }
        }

        private fun verifyRouteFingerprints(db: SQLiteDatabase) {
            db.rawQuery("SELECT route_id,route_version,route_fingerprint,route_definition,authorization_status FROM routes", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(4) == "PROMOTED") {
                        if (c.isNull(3)) throw RegistryStorageException("registry-route-definition-missing")
                        val parsed = JsonParser.parseString(c.getString(3))
                        @Suppress("UNCHECKED_CAST")
                        val definition = jsonToValue(parsed) as? Map<String, Any?>
                            ?: throw RegistryStorageException("registry-route-definition-invalid")
                        if (CanonicalRouteV1.fingerprint(c.getString(0), c.getLong(1), definition) != c.getString(2))
                            throw RegistryStorageException("registry-route-fingerprint-invalid")
                    }
                }
            }
        }

        /** Ensure the SQL route projection still agrees with its signed-record
         * event head. This makes a damaged PROMOTED/REVOKED bit fail closed. */
        private fun verifyRouteProjection(db: SQLiteDatabase) {
            data class Event(val epoch: Long, val operation: String, val routeId: String,
                             val version: Long, val fingerprint: String, val recordDigest: String)
            val events = mutableMapOf<Pair<String, Long>, MutableList<Event>>()
            db.rawQuery("SELECT epoch,operation,route_id,route_version,route_fingerprint,record_digest FROM registry_events ORDER BY sequence", null).use { c ->
                while (c.moveToNext()) {
                    val event = Event(c.getLong(0), c.getString(1), c.getString(2),
                        c.getLong(3), c.getString(4), c.getString(5))
                    events.getOrPut(event.routeId to event.version) { mutableListOf() }.add(event)
                }
            }
            db.rawQuery("SELECT route_id,route_version,route_fingerprint,route_definition,promotion_record,authority_key_id,registry_epoch,expires_at_epoch_ms,authorization_status,revocation_record FROM routes", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val version = c.getLong(1)
                    val fingerprint = c.getString(2)
                    val definitionJson = if (c.isNull(3)) null else c.getString(3)
                    val promotionJson = if (c.isNull(4)) null else c.getString(4)
                    val authority = c.getString(5)
                    val epoch = c.getLong(6)
                    val expiry = c.getLong(7)
                    val status = c.getString(8)
                    val revocationJson = if (c.isNull(9)) null else c.getString(9)
                    val history = events[id to version] ?: throw RegistryStorageException("registry-route-event-missing")
                    val latest = history.last()
                    if (latest.epoch != epoch || latest.routeId != id || latest.version != version ||
                        latest.fingerprint != fingerprint ||
                        (status == "PROMOTED" && (latest.operation != "PROMOTION" || revocationJson != null)) ||
                        (status == "REVOKED" && (latest.operation != "REVOCATION" || revocationJson == null)) ||
                        status !in setOf("PROMOTED", "REVOKED"))
                        throw RegistryStorageException("registry-route-event-projection-mismatch")

                    if (promotionJson != null) {
                        val promotion = parseObject(promotionJson)
                        if (digest(promotion) != history.lastOrNull { it.operation == "PROMOTION" }?.recordDigest ||
                            promotion["routeId"] != id || promotion["routeVersion"] != version ||
                            promotion["routeFingerprint"] != fingerprint ||
                            promotion["expiresAtEpochMs"] != expiry || definitionJson == null ||
                            promotion["routeDefinition"] != parseObject(definitionJson))
                            throw RegistryStorageException("registry-promotion-projection-invalid")
                    } else if (definitionJson != null) {
                        throw RegistryStorageException("registry-route-definition-orphaned")
                    }

                    if (status == "PROMOTED") {
                        val promotion = promotionJson?.let(::parseObject)
                            ?: throw RegistryStorageException("registry-promotion-record-missing")
                        if (digest(promotion) != latest.recordDigest || promotion["authorityKeyId"] != authority ||
                            promotion["registryEpoch"] != epoch || promotion["expiresAtEpochMs"] != expiry)
                            throw RegistryStorageException("registry-promotion-event-mismatch")
                    } else {
                        val revocation = revocationJson?.let(::parseObject)
                            ?: throw RegistryStorageException("registry-revocation-record-missing")
                        if (digest(revocation) != latest.recordDigest || revocation["routeId"] != id ||
                            revocation["routeVersion"] != version || revocation["routeFingerprint"] != fingerprint ||
                            revocation["registryEpoch"] != epoch || revocation["authorityKeyId"] != authority)
                            throw RegistryStorageException("registry-revocation-event-mismatch")
                        val hadPriorPromotion = history.any { it.operation == "PROMOTION" }
                        if ((promotionJson != null) != hadPriorPromotion)
                            throw RegistryStorageException("registry-revoked-route-history-invalid")
                    }
                }
            }
        }

        private fun verifyReceipts(db: SQLiteDatabase) {
            db.rawQuery("SELECT request_id,operation,request_digest,response_json,response_digest,result_accepted,result_reason_code FROM operation_receipts", null).use { c ->
                while (c.moveToNext()) {
                    if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(c.getString(0)) ||
                        c.getString(1) !in setOf("route.promote", "route.revoke") ||
                        !Regex("[0-9a-f]{64}").matches(c.getString(2)) ||
                        c.getString(3).toByteArray(Charsets.UTF_8).size > 1_048_576 ||
                        !Regex("[0-9a-f]{64}").matches(c.getString(4)) ||
                        sha256(c.getString(3)) != c.getString(4) || c.getInt(5) !in 0..1 ||
                        !Regex("[a-z][a-z0-9-]{0,63}").matches(c.getString(6)))
                        throw RegistryStorageException("registry-operation-receipt-corrupt")
                    val response = JsonParser.parseString(c.getString(3)).asJsonObject
                    @Suppress("UNCHECKED_CAST")
                    val responseValue = jsonToValue(response) as Map<String, Any?>
                    val accepted = c.getInt(5) != 0
                    val expectedMessage = "${c.getString(1)}.result"
                    val expectedStatus = if (!accepted) "REFUSED" else if (c.getString(1) == "route.promote") "PROMOTED" else "REVOKED"
                    if (response.keySet() != setOf("protocolVersion", "messageType", "requestId", "taskId",
                            "sessionId", "ownerId", "deviceBinding", "status", "reasonCode", "observedAtEpochMs", "payload") ||
                        response.get("protocolVersion")?.asString != StrictEnvelopeV1.VERSION ||
                        response.get("messageType")?.asString != expectedMessage ||
                        response.get("requestId")?.asString != c.getString(0) ||
                        response.get("status")?.asString != expectedStatus ||
                        response.get("reasonCode")?.asString != c.getString(6) ||
                        response.get("observedAtEpochMs")?.asLong?.let { it > 0 } != true ||
                        !response.get("deviceBinding").isJsonObject || !response.get("payload").isJsonObject ||
                        CanonicalRouteV1.encode(responseValue) != c.getString(3))
                        throw RegistryStorageException("registry-operation-receipt-correlation-invalid")
                }
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val MAX_PROMOTION_LIFETIME_MS = 86_400_000L
        private const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        private val UUID4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val MARKER_BYTES = "tessera.fast-path.registry-v1\n".toByteArray(Charsets.US_ASCII)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun jsonToValue(value: JsonElement): Any? = when {
            value.isJsonNull -> null
            value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to jsonToValue(it.value) }
            value.isJsonArray -> value.asJsonArray.map(::jsonToValue)
            value.asJsonPrimitive.isBoolean -> value.asBoolean
            value.asJsonPrimitive.isNumber -> value.asLong
            else -> value.asString
        }
    }
}
