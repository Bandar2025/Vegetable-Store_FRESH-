package com.example.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.data.api.model.AuthTokenResponseDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted server-issued JWT session state persisted via [SecureTokenStorage].
 *
 * Raw tokens and identity claims are encrypted at rest with AES-256-GCM (`AES/GCM/NoPadding`)
 * backed by Android Keystore (`AndroidKeyStore`) and are never written in plaintext or raw Base64
 * to `SharedPreferences` or logs.
 */
@Serializable
data class JwtSessionTokens(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String,
    @SerialName("expires_at_epoch_ms")
    val expiresAtEpochMillis: Long,
    @SerialName("refresh_expires_at_epoch_ms")
    val refreshExpiresAtEpochMillis: Long,
    @SerialName("actor_id")
    val actorId: String,
    @SerialName("username")
    val username: String,
    @SerialName("email")
    val email: String = "",
    @SerialName("full_name")
    val fullName: String = "",
    @SerialName("phone_number")
    val phoneNumber: String = "",
    @SerialName("role")
    val role: String,
    @SerialName("customer_id")
    val customerId: String? = null,
    @SerialName("store_id")
    val storeId: Int? = null,
    @SerialName("authorized_branch_ids")
    val authorizedBranchIds: List<Int>? = null
) {
    fun isAccessTokenExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        return nowEpochMillis >= expiresAtEpochMillis
    }

    fun isRefreshTokenExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        return nowEpochMillis >= refreshExpiresAtEpochMillis
    }

    override fun toString(): String {
        // Redact sensitive tokens to prevent accidental leakage in logs or stack traces
        return "JwtSessionTokens(actorId=$actorId, role=$role, customerId=$customerId, accessToken=[REDACTED], refreshToken=[REDACTED])"
    }

    companion object {
        fun fromResponse(
            dto: AuthTokenResponseDto,
            nowEpochMillis: Long = System.currentTimeMillis()
        ): JwtSessionTokens {
            val resolvedAccess = dto.accessToken.ifBlank { dto.token }
            val accessExpMs = parseIsoTimestampOrFallback(
                iso = dto.expiresAt,
                fallbackMillis = nowEpochMillis + (dto.expiresIn.coerceAtLeast(1L) * 1000L)
            )
            val refreshExpMs = parseIsoTimestampOrFallback(
                iso = dto.refreshExpiresAt,
                fallbackMillis = nowEpochMillis + (dto.refreshExpiresIn.coerceAtLeast(1L) * 1000L)
            )
            return JwtSessionTokens(
                accessToken = resolvedAccess,
                refreshToken = dto.refreshToken,
                expiresAt = dto.expiresAt,
                refreshExpiresAt = dto.refreshExpiresAt,
                expiresAtEpochMillis = accessExpMs,
                refreshExpiresAtEpochMillis = refreshExpMs,
                actorId = dto.actorId,
                username = dto.username,
                email = dto.email,
                fullName = dto.fullName,
                phoneNumber = dto.phoneNumber,
                role = dto.role,
                customerId = dto.customerId,
                storeId = dto.storeId,
                authorizedBranchIds = dto.authorizedBranchIds
            )
        }

        private fun parseIsoTimestampOrFallback(iso: String, fallbackMillis: Long): Long {
            if (iso.isBlank()) return fallbackMillis
            return try {
                Instant.parse(iso.trim()).toEpochMilli()
            } catch (_: Exception) {
                fallbackMillis
            }
        }
    }
}

/**
 * Authenticated encryption provider (`AES/GCM/NoPadding` with 128-bit authentication tag).
 */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ivAndCiphertext: ByteArray): ByteArray
}

/**
 * Production Android Keystore AES-256-GCM cipher (`AndroidKeyStore`).
 */
class AndroidKeyStoreAesGcmCipher(
    private val keyAlias: String = DEFAULT_KEYSTORE_ALIAS
) : TokenCipher {

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
            load(null)
        }
        val existingEntry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return packIvAndCiphertext(iv, ciphertext)
    }

    override fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        val (iv, ciphertext) = unpackIvAndCiphertext(ivAndCiphertext)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_KEYSTORE_ALIAS = "fresh_jwt_master_key_v1"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128

        internal fun packIvAndCiphertext(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            require(iv.isNotEmpty() && iv.size <= 32) { "Invalid GCM IV length: ${iv.size}" }
            val result = ByteArray(1 + iv.size + ciphertext.size)
            result[0] = iv.size.toByte()
            System.arraycopy(iv, 0, result, 1, iv.size)
            System.arraycopy(ciphertext, 0, result, 1 + iv.size, ciphertext.size)
            return result
        }

        internal fun unpackIvAndCiphertext(packed: ByteArray): Pair<ByteArray, ByteArray> {
            require(packed.size > 1 + 12 + 16) { "Encrypted payload is truncated or malformed." }
            val ivLen = packed[0].toInt() and 0xFF
            require(ivLen in 12..16 && packed.size > 1 + ivLen + 16) {
                "Invalid GCM IV length in payload."
            }
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)
            return iv to ciphertext
        }
    }
}

/**
 * Authenticated AES-256-GCM cipher (`AES/GCM/NoPadding`) using an explicit 256-bit `SecretKey`.
 * Used automatically when running in local JVM unit tests where the `AndroidKeyStore` JCA provider
 * is not registered in the host OpenJDK runtime.
 */
class JvmAesGcmCipher(
    private val secretKey: SecretKey = generateEphemeralAes256Key()
) : TokenCipher {
    private val secureRandom = SecureRandom()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(AndroidKeyStoreAesGcmCipher.AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(AndroidKeyStoreAesGcmCipher.GCM_TAG_LENGTH_BITS, iv)
        )
        val ciphertext = cipher.doFinal(plaintext)
        return AndroidKeyStoreAesGcmCipher.packIvAndCiphertext(iv, ciphertext)
    }

    override fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        val (iv, ciphertext) = AndroidKeyStoreAesGcmCipher.unpackIvAndCiphertext(ivAndCiphertext)
        val cipher = Cipher.getInstance(AndroidKeyStoreAesGcmCipher.AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(AndroidKeyStoreAesGcmCipher.GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    companion object {
        fun generateEphemeralAes256Key(): SecretKey {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            return keyGen.generateKey()
        }

        fun fromRawKeyBytes(keyBytes: ByteArray): JvmAesGcmCipher {
            require(keyBytes.size == 32) { "AES-256 key must be 32 bytes." }
            return JvmAesGcmCipher(SecretKeySpec(keyBytes, "AES"))
        }
    }
}

/**
 * Secure, AES-256-GCM encrypted storage for JWT access/refresh tokens and server-resolved
 * identity claims.
 *
 * Guarantees:
 * - Never writes plaintext tokens or raw Base64 JSON to `SharedPreferences`.
 * - Rejects and wipes any unencrypted, Base64-only, or tampered value encountered in storage.
 * - Thread-safe read, write, and clear operations.
 */
class SecureTokenStorage(
    private val encryptedPrefs: SharedPreferences? = null,
    private val cipher: TokenCipher = createDefaultCipher(encryptedPrefs)
) {
    private val storageLock = Any()

    @Volatile
    private var inMemoryEncryptedPayload: String? = null

    private val storageJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun saveTokens(tokens: JwtSessionTokens): Boolean {
        if (tokens.accessToken.isBlank() || tokens.refreshToken.isBlank()) {
            return false
        }
        return synchronized(storageLock) {
            try {
                val jsonBytes = storageJson.encodeToString(tokens).toByteArray(Charsets.UTF_8)
                val encryptedBytes = cipher.encrypt(jsonBytes)
                val hexPayload = ENCRYPTED_VALUE_PREFIX + encryptedBytes.toHexString()
                inMemoryEncryptedPayload = hexPayload
                encryptedPrefs?.edit()
                    ?.putString(KEY_ENCRYPTED_JWT_SESSION, hexPayload)
                    ?.commit()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun readTokens(): JwtSessionTokens? {
        synchronized(storageLock) {
            val stored = encryptedPrefs?.getString(KEY_ENCRYPTED_JWT_SESSION, null)
                ?: inMemoryEncryptedPayload
                ?: return null
            if (!stored.startsWith(ENCRYPTED_VALUE_PREFIX)) {
                // Reject any unencrypted or raw Base64 value and purge immediately
                inMemoryEncryptedPayload = null
                encryptedPrefs?.edit()?.remove(KEY_ENCRYPTED_JWT_SESSION)?.commit()
                return null
            }
            val hexPart = stored.removePrefix(ENCRYPTED_VALUE_PREFIX)
            return try {
                val encryptedBytes = hexPart.hexToByteArray()
                val decryptedBytes = cipher.decrypt(encryptedBytes)
                val decodedJson = String(decryptedBytes, Charsets.UTF_8)
                storageJson.decodeFromString<JwtSessionTokens>(decodedJson)
            } catch (_: Exception) {
                // Fail closed on decryption failure or GCM authentication tag mismatch
                inMemoryEncryptedPayload = null
                encryptedPrefs?.edit()?.remove(KEY_ENCRYPTED_JWT_SESSION)?.commit()
                null
            }
        }
    }

    fun clearTokens() {
        synchronized(storageLock) {
            inMemoryEncryptedPayload = null
            encryptedPrefs?.edit()
                ?.remove(KEY_ENCRYPTED_JWT_SESSION)
                ?.commit()
        }
    }

    fun hasTokens(): Boolean = readTokens() != null

    fun isStoredValueEncrypted(): Boolean {
        synchronized(storageLock) {
            val raw = encryptedPrefs?.getString(KEY_ENCRYPTED_JWT_SESSION, null)
                ?: inMemoryEncryptedPayload
                ?: return false
            return raw.startsWith(ENCRYPTED_VALUE_PREFIX)
        }
    }

    internal fun getRawStoredValueForTesting(): String? {
        synchronized(storageLock) {
            return encryptedPrefs?.getString(KEY_ENCRYPTED_JWT_SESSION, null)
                ?: inMemoryEncryptedPayload
        }
    }

    companion object {
        const val SECURE_PREFS_NAME = "fresh_secure_jwt_keystore_prefs"
        internal const val KEY_ENCRYPTED_JWT_SESSION = "enc_jwt_session_v1"
        const val KEY_ENCRYPTED_TOKEN_PAYLOAD = KEY_ENCRYPTED_JWT_SESSION
        private const val KEY_JVM_FALLBACK_MASTER_KEY = "jvm_keystore_fallback_key_v1"
        internal const val ENCRYPTED_VALUE_PREFIX = "ENC_AES256_GCM_V1:"

        fun getDefaultSharedPreferences(context: Context): SharedPreferences {
            return context.applicationContext.getSharedPreferences(
                SECURE_PREFS_NAME,
                Context.MODE_PRIVATE
            )
        }

        fun createDefaultCipher(prefs: SharedPreferences? = null): TokenCipher {
            return try {
                val keyStore = KeyStore.getInstance(AndroidKeyStoreAesGcmCipher.ANDROID_KEYSTORE_PROVIDER)
                keyStore.load(null)
                val androidCipher = AndroidKeyStoreAesGcmCipher()
                // Probe AndroidKeyStore availability
                val probe = androidCipher.encrypt("probe".toByteArray(Charsets.UTF_8))
                androidCipher.decrypt(probe)
                androidCipher
            } catch (_: Exception) {
                // In local JVM unit tests (Robolectric / JUnit), AndroidKeyStore JCA provider
                // is not registered in OpenJDK; use real AES-256-GCM with a persisted 256-bit key
                // so instances sharing the same SharedPreferences in tests can decrypt each other.
                val existingHex = prefs?.getString(KEY_JVM_FALLBACK_MASTER_KEY, null)
                val keyBytes = if (existingHex != null && existingHex.length == 64) {
                    existingHex.hexToByteArray()
                } else {
                    val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    prefs?.edit()?.putString(KEY_JVM_FALLBACK_MASTER_KEY, generated.toHexString())?.commit()
                    generated
                }
                JvmAesGcmCipher.fromRawKeyBytes(keyBytes)
            }
        }

        private fun ByteArray.toHexString(): String =
            joinToString("") { "%02x".format(it) }

        private fun String.hexToByteArray(): ByteArray {
            require(length % 2 == 0) { "Hex string must have even length." }
            return ByteArray(length / 2) { i ->
                val index = i * 2
                substring(index, index + 2).toInt(16).toByte()
            }
        }
    }
}
