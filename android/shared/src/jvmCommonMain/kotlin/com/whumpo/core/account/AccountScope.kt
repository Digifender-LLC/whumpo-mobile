package com.whumpo.core.account

import java.security.MessageDigest

/**
 * The signed-in account that owns local state. [storageKey] is a one-way digest so the
 * server account identifier never appears in file names. [toString] reveals neither.
 */
class AccountScope private constructor(
    internal val accountId: String,
    val storageKey: String,
) {
    override fun equals(other: Any?): Boolean = other is AccountScope && other.accountId == accountId

    override fun hashCode(): Int = accountId.hashCode()

    override fun toString(): String = "AccountScope(<redacted>)"

    companion object {
        private const val MAX_ACCOUNT_ID_LENGTH = 128

        fun of(accountId: String): AccountScope {
            require(accountId.isNotBlank()) { "Account id must not be blank" }
            require(accountId.length <= MAX_ACCOUNT_ID_LENGTH) { "Account id is too long" }
            require(accountId.none { it.isWhitespace() || it.isISOControl() }) {
                "Account id must not contain whitespace or control characters"
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("whumpo-account-scope-v1:$accountId".toByteArray(Charsets.UTF_8))
            val key = digest.take(16).joinToString("") { "%02x".format(it) }
            return AccountScope(accountId, key)
        }
    }
}
