package com.whumpo.core.api

/** Explicit native Core endpoint and the server's shared WebAuthn relying-party ID. */
class CoreClientSettings private constructor(
    val origin: CoreOrigin,
    val rpId: String,
) {
    companion object {
        private val rpIdPattern = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val ipv4Pattern = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")

        internal fun isCanonicalRpId(value: String): Boolean =
            value.length <= 253 && rpIdPattern.matches(value) && !ipv4Pattern.matches(value)

        /**
         * Creates settings only for a caller-supplied exact allowlist. The endpoint and RP ID are
         * separate configuration values: the Core server selects the RP from its primary auth
         * configuration, while the endpoint may be a distinct native ingress.
         */
        fun allowlisted(
            origin: String,
            rpId: String,
            allowedHttpsOrigins: Set<String>,
        ): CoreClientSettings {
            require(isCanonicalRpId(rpId)) { "Invalid Core WebAuthn RP ID" }
            val apiOrigin = CoreOrigin.allowlisted(origin, allowedHttpsOrigins)
            return CoreClientSettings(apiOrigin, rpId)
        }
    }
}
