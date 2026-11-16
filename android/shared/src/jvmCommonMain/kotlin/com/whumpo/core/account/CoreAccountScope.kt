package com.whumpo.core.account

import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.coreIdentifier
import java.security.MessageDigest

/** Core state has a separate storage namespace from the legacy masked-address store. */
class CoreAccountScope private constructor(val storageKey: String) {
    override fun equals(other: Any?): Boolean = other is CoreAccountScope && storageKey == other.storageKey
    override fun hashCode(): Int = storageKey.hashCode()
    override fun toString(): String = "CoreAccountScope(<redacted>)"

    companion object {
        internal fun of(origin: CoreOrigin, userId: String, orgId: String): CoreAccountScope {
            require(coreIdentifier.matches(userId) && coreIdentifier.matches(orgId))
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("whumpo-core-scope-v1\u0000${origin.value}\u0000$userId\u0000$orgId".toByteArray())
            return CoreAccountScope("core-v1-" + digest.joinToString("") { "%02x".format(it) })
        }
    }
}
