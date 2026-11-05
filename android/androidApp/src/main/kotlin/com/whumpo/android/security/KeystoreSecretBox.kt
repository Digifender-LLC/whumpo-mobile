package com.whumpo.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.whumpo.core.account.SecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Keystore keys never leave the
 * device's secure hardware or software keystore and are not included in backups, so
 * restored or copied ciphertext cannot be opened elsewhere. [destroyKey] deletes the
 * key entry; a fresh key is generated on next use.
 */
class KeystoreSecretBox(private val alias: String) : SecretBox {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val body = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length" }
        return iv + body
    }

    @Synchronized
    override fun open(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.size > IV_BYTES) { "Ciphertext is too short" }
        val existing = keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("No key for this state")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
    }

    @Synchronized
    override fun destroyKey() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
