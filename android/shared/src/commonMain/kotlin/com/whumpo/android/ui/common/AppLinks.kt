package com.whumpo.android.ui.common

/** Web destinations for what the mobile API does not cover. [origin] is the build's server origin, with no trailing slash. */
class AppLinks(private val origin: String) {
    /** Passkeys, recovery kit, signed-in devices and security review. */
    val security: String get() = "$origin/workspace/security"

    /** The account email change lives on the same page. */
    val accountEmail: String get() = "$origin/workspace/security"

    val support: String get() = "$origin/workspace/support"

    val playStore: String get() = "https://play.google.com/store/apps/details?id=com.whumpo.android"

    /** The same policies the web footer links to. */
    val legal: List<Pair<String, String>> = listOf(
        "Terms (draft)" to "https://digifender.com/legal/whumpo/terms",
        "Privacy policy (draft)" to "https://digifender.com/legal/whumpo/privacy",
        "Cookie notice (draft)" to "https://digifender.com/legal/whumpo/cookies",
    )
}
