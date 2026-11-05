package com.whumpo.core.environment

import java.net.URI

/**
 * The two environments an owner build can target. `LOCAL` reaches a developer's own
 * `wrangler dev` server over loopback (`adb reverse`); `TEST` is the deployed private
 * test platform. There is no third, "public development" option by design.
 */
enum class EnvironmentKind(val wireName: String) {
    LOCAL("local"),
    TEST("test"),
}

/** A validated server origin for one build variant. Construct only through [from]. */
class WhumpoEnvironment private constructor(
    val kind: EnvironmentKind,
    val origin: String,
) {
    /** Frozen v1 mobile boundary from docs/mobile-api-v1.md. */
    val mobileApiBase: String get() = "$origin/api/mobile/v1"

    override fun equals(other: Any?): Boolean =
        other is WhumpoEnvironment && other.kind == kind && other.origin == origin

    override fun hashCode(): Int = 31 * kind.hashCode() + origin.hashCode()

    override fun toString(): String = "WhumpoEnvironment(${kind.wireName}, $origin)"

    companion object {
        const val TEST_ORIGIN: String = "https://whumpo.com"

        /** Hosts that only ever resolve to the device itself. */
        val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "[::1]")

        /**
         * Validates a build-time environment. Local builds must use an explicit loopback
         * origin with a port; test builds must use exactly [TEST_ORIGIN]. Anything else
         * (LAN addresses, tunnels, public hosts, paths, credentials in URLs) is refused.
         */
        fun from(kind: String, origin: String): WhumpoEnvironment {
            val parsedKind = EnvironmentKind.entries.firstOrNull { it.wireName == kind }
                ?: throw IllegalArgumentException("Unknown environment kind: $kind")
            val uri = try {
                URI(origin)
            } catch (error: Exception) {
                throw IllegalArgumentException("Environment origin is not a valid URI", error)
            }
            require(uri.rawUserInfo == null) { "Environment origin must not carry credentials" }
            require(uri.rawPath.isNullOrEmpty()) { "Environment origin must not include a path" }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Environment origin must not include a query or fragment"
            }
            when (parsedKind) {
                EnvironmentKind.LOCAL -> {
                    require(uri.scheme == "http" || uri.scheme == "https") {
                        "Local origin must use http or https"
                    }
                    require(uri.host in LOOPBACK_HOSTS) {
                        "Local origin must be a loopback host; use adb reverse instead of a LAN or public address"
                    }
                    require(uri.port in 1..65535) { "Local origin must name an explicit port" }
                }
                EnvironmentKind.TEST -> require(origin == TEST_ORIGIN) {
                    "Test origin must be exactly $TEST_ORIGIN"
                }
            }
            return WhumpoEnvironment(parsedKind, origin)
        }
    }
}
