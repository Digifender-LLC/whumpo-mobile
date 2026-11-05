package com.whumpo.core.push

import com.whumpo.core.api.DeviceRemoved
import com.whumpo.core.api.DeviceResponse
import com.whumpo.core.api.EventInfo
import com.whumpo.core.api.MobileApiClient

/**
 * The seam to Firebase Cloud Messaging. The real implementation needs the owner's Firebase project
 * (docs/android-notifications.md); until then [NoOpPushMessaging] keeps the app free of any Firebase dependency or config.
 */
interface PushMessaging {
    /** False when this build has no push provider. The UI then explains instead of offering a switch. */
    val available: Boolean

    /** The current FCM registration token, or null when there is none. A token is a delivery address, never a credential. */
    suspend fun token(): String?

    /** Invalidates the local token (sign-out or opt-out). */
    suspend fun deleteToken()
}

/** The build without Firebase: no token, nothing to delete. */
object NoOpPushMessaging : PushMessaging {
    override val available: Boolean = false

    override suspend fun token(): String? = null

    override suspend fun deleteToken() = Unit
}

/** Device registration and event reads over the mobile API. Push delivery itself stays behind [PushMessaging]. */
class DeviceNotifications(private val api: MobileApiClient, private val push: PushMessaging) {
    val available: Boolean get() = push.available

    /** Registers this device. Returns null, calling nothing, when there is no push token. Call again when FCM rotates the token. */
    suspend fun register(notificationsEnabled: Boolean = true): DeviceResponse? =
        push.token()?.let { api.registerDevice(it, notificationsEnabled) }

    /** Removes this device from the server, then drops the local token. */
    suspend fun remove(): DeviceRemoved = api.removeDevice().also { push.deleteToken() }

    /** The current server state of a notification's event. Opening one reads only; it never sends, approves or retries. */
    suspend fun event(eventId: String): EventInfo = api.getEvent(eventId).event
}
