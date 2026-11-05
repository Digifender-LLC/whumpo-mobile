package com.whumpo.core.account

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JVM AES-GCM stand-in for the Android Keystore box, with an in-memory key. */
class FakeSecretBox : SecretBox {
    private var key: SecretKey = newKey()
    var destroyCount: Int = 0
        private set

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        if (ciphertext.size < 12) throw AEADBadTagException("short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }

    override fun destroyKey() {
        destroyCount += 1
        key = newKey()
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
