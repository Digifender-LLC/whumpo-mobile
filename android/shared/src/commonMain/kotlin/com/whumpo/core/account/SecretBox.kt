package com.whumpo.core.account

/**
 * Authenticated encryption for account-scoped state. The Android implementation keeps
 * its key in Android Keystore; the key material never leaves this interface.
 */
interface SecretBox {
    /** Encrypts [plaintext], binding it to [associatedData]. Output includes any nonce. */
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Reverses [seal]. Throws if the ciphertext, key or associated data do not match. */
    fun open(ciphertext: ByteArray, associatedData: ByteArray): ByteArray

    /** Irreversibly destroys the current key so previously sealed data cannot be opened. */
    fun destroyKey()
}
