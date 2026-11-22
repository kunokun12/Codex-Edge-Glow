package com.codexedge.glow.fastpathv1

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Public promotion keys are loaded only from an APK-owned asset. The wire record
 * cannot add or replace an anchor. An empty/missing keyset is a supported,
 * fail-closed production configuration.
 *
 * Rotation is shipped as an app update with overlapping entries and epoch bounds.
 * No keyset is present in this baseline because no production authority public
 * key was supplied with the M1 artifacts.
 */
internal data class AuthorityAnchorV1(
    val keyId: String,
    val publicKey: RSAPublicKey,
    val acceptsFromEpoch: Long,
    val acceptsThroughEpoch: Long?
) {
    fun accepts(epoch: Long): Boolean = epoch >= acceptsFromEpoch &&
        (acceptsThroughEpoch == null || epoch <= acceptsThroughEpoch)
}

internal class AuthorityTrustV1 private constructor(
    private val anchors: Map<String, AuthorityAnchorV1>
) {
    val isConfigured: Boolean get() = anchors.isNotEmpty()

    fun keysForEpoch(epoch: Long): Map<String, PublicKey> = anchors.values
        .filter { it.accepts(epoch) }
        .associate { it.keyId to it.publicKey }

    fun anchor(keyId: String, epoch: Long): AuthorityAnchorV1? =
        anchors[keyId]?.takeIf { it.accepts(epoch) }

    companion object {
        private val KEY_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        fun fromApkAsset(context: Context, name: String = "fastpath-authorities-v1.json"): AuthorityTrustV1 {
            val root = JSONObject(context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() })
            require(root.keys().asSequence().toSet() == setOf("keysetVersion", "anchors"))
            require(root.getInt("keysetVersion") == 1)
            val array = root.getJSONArray("anchors")
            val anchors = linkedMapOf<String, AuthorityAnchorV1>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                require(item.keys().asSequence().toSet() ==
                    setOf("authorityKeyId", "publicKeyX509Base64", "acceptsFromEpoch", "acceptsThroughEpoch"))
                val id = item.getString("authorityKeyId")
                require(KEY_ID.matches(id) && id !in anchors)
                val encoded = Base64.decode(item.getString("publicKeyX509Base64"), Base64.NO_WRAP)
                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(encoded)) as? RSAPublicKey
                    ?: error("Authority key is not RSA")
                require(publicKey.modulus.bitLength() == 3072)
                val from = item.getLong("acceptsFromEpoch")
                val through = if (item.isNull("acceptsThroughEpoch")) null else item.getLong("acceptsThroughEpoch")
                require(from >= 1 && (through == null || through >= from))
                anchors[id] = AuthorityAnchorV1(id, publicKey, from, through)
            }
            return AuthorityTrustV1(anchors.toMap())
        }

        /** Test-only constructor. Production call sites use [fromApkAsset]. */
        internal fun forTests(anchors: Collection<AuthorityAnchorV1>): AuthorityTrustV1 {
            require(anchors.map { it.keyId }.toSet().size == anchors.size)
            require(anchors.all { it.publicKey.modulus.bitLength() == 3072 })
            return AuthorityTrustV1(anchors.associateBy { it.keyId })
        }

        fun unconfigured(): AuthorityTrustV1 = AuthorityTrustV1(emptyMap())
    }
}
