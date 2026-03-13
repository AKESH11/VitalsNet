package com.biovault.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * StrongBoxManager
 * - Detects StrongBox availability
 * - Generates an EC P-256 key pair inside AndroidKeyStore backed by StrongBox (when available)
 * - Requires biometric auth for every signature
 * - Signs caller-provided 32-byte hashes (no additional hashing performed)
 * - Provides JNI bridge for C++ Bio-Vault core
 */
class StrongBoxManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val REALITY_KEY_ALIAS = "biovault_reality_key"
        
        init {
            System.loadLibrary("BioVaultCore")
        }
    }
    
    // Native JNI methods
    private external fun initializeNativeBridge(strongBoxManager: StrongBoxManager)
    private external fun cleanupNativeBridge()
    
    init {
        // Initialize JNI bridge so C++ can call back to Kotlin
        initializeNativeBridge(this)
    }

    /**
     * @return true if device advertises StrongBox hardware keystore support
     */
    fun isStrongBoxSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    /**
     * Generates an EC P-256 key pair in AndroidKeyStore, preferring StrongBox.
     * Falls back to standard TEE if StrongBox is unavailable.
     * Requires biometric authentication for every signature operation.
     * 
     * @return true if key generation succeeded (either StrongBox or TEE)
     */
    fun generateRealityKey(): Boolean {
        // Try StrongBox first
        if (tryGenerateKeyWithStrongBox()) {
            android.util.Log.i("StrongBoxManager", "✅ Key generated in StrongBox HSM")
            return true
        }
        
        // Fallback to standard TEE
        android.util.Log.w("StrongBoxManager", "⚠️ StrongBox unavailable, falling back to TEE")
        return tryGenerateKeyWithTEE()
    }
    
    private fun tryGenerateKeyWithStrongBox(): Boolean {
        if (!isStrongBoxSupported()) {
            return false
        }
        
        return try {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val builder = buildKeyGenSpec()
            builder.setIsStrongBoxBacked(true)
            
            kpg.initialize(builder.build())
            kpg.generateKeyPair()
            true
        } catch (e: Exception) {
            android.util.Log.d("StrongBoxManager", "StrongBox key generation failed: ${e.message}")
            false
        }
    }
    
    private fun tryGenerateKeyWithTEE(): Boolean {
        return try {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val builder = buildKeyGenSpec()
            builder.setIsStrongBoxBacked(false)
            
            kpg.initialize(builder.build())
            kpg.generateKeyPair()
            android.util.Log.i("StrongBoxManager", "✅ Key generated in TEE (Trusted Execution Environment)")
            true
        } catch (e: Exception) {
            android.util.Log.e("StrongBoxManager", "❌ TEE key generation failed: ${e.message}", e)
            false
        }
    }
    
    private fun buildKeyGenSpec(): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(
            REALITY_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(true)
            .setDigests(KeyProperties.DIGEST_NONE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(0)
        }
        
        return builder
    }

    /**
     * Sign a 32-byte hash using the StrongBox-backed private key.
     * The input is treated as a pre-computed hash; no additional hashing is done.
     */
    fun signHash(data: ByteArray): ByteArray {
        require(data.size == 32) { "signHash expects a 32-byte hash" }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(REALITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Reality key not generated. Call generateRealityKey() first.")

        val signature = Signature.getInstance("NONEwithECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data)
        return signature.sign()
    }
    
    fun getPublicKey(): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(REALITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Reality key not generated")
        
        return entry.certificate.publicKey.encoded
    }
    
    fun hasRealityKey(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(REALITY_KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }
    
    fun isKeyInStrongBox(): Boolean? {
        if (!hasRealityKey()) {
            return null
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val entry = keyStore.getEntry(REALITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                val keyInfo = android.security.keystore.KeyInfo::class.java
                    .cast(entry?.privateKey?.let {
                        val factory = java.security.KeyFactory.getInstance(it.algorithm, ANDROID_KEYSTORE)
                        factory.getKeySpec(it, android.security.keystore.KeyInfo::class.java)
                    })
                keyInfo?.isInsideSecureHardware == true && 
                keyInfo.securityLevel == android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("StrongBoxManager", "Error checking key location: ${e.message}")
            null
        }
    }
    
    fun destroy() {
        cleanupNativeBridge()
    }
}
