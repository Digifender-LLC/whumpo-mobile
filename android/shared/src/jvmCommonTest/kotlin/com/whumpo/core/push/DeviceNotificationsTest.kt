package com.whumpo.core.push

import com.whumpo.android.ui.ApiFixtures
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.assertFailsWith
import com.whumpo.core.api.MobileApiException

class DeviceNotificationsTest {
    private val api = ApiFixtures()

    @Test
    fun withoutFirebaseNothingIsRegisteredAndNoRequestIsMade() = runTest {
        val notifications = DeviceNotifications(api.client, NoOpPushMessaging)
        assertFalse(notifications.available)
        assertNull(notifications.register())
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun registerSendsTheTokenAndThePreference() = runTest {
        val push = object : PushMessaging {
            override val available = true

            override suspend fun token() = "SYNTHETIC-TOKEN-0001"

            override suspend fun deleteToken() = Unit
        }
        val response = DeviceNotifications(api.client, push).register(notificationsEnabled = true)!!
        assertTrue(response.preferences.notificationsEnabled)
        val body = api.bodies.single().decodeToString()
        assertTrue(body.contains("\"fcmRegistrationToken\":\"SYNTHETIC-TOKEN-0001\""))
        assertTrue(body.contains("\"notificationsEnabled\":true"))
    }

    @Test
    fun removeDeletesTheServerDeviceThenTheLocalToken() = runTest {
        var deleted = false
        val push = object : PushMessaging {
            override val available = true

            override suspend fun token(): String? = null

            override suspend fun deleteToken() {
                deleted = true
            }
        }
        assertTrue(DeviceNotifications(api.client, push).remove().removed)
        assertEquals(HttpMethod.Delete, api.requests.single().method)
        assertTrue(deleted)
    }

    @Test
    fun eventReadIsAnAuthenticatedGetOnly() = runTest {
        val event = DeviceNotifications(api.client, NoOpPushMessaging).event("bbbbbbbb-bbbb-4bbb-8bbb-000000000001")
        assertEquals("review_pending", event.kind)
        assertEquals(HttpMethod.Get, api.requests.single().method)
        assertEquals("Bearer ${ApiFixtures.TOKEN}", api.requests.single().headers["Authorization"])
    }

    private fun pushWith(vararg tokens: String?) = object : PushMessaging {
        private val queue = ArrayDeque(tokens.toList())
        override val available = true
        override suspend fun token(): String? = queue.removeFirstOrNull()
        override suspend fun deleteToken() = Unit
    }

    @Test
    fun aRotatedTokenIsRegisteredAgainAndNeverReusedFromTheOldOne() = runTest {
        val notifications = DeviceNotifications(api.client, pushWith("SYNTHETIC-TOKEN-0001", "SYNTHETIC-TOKEN-0002"))
        notifications.register()
        notifications.register()
        val bodies = api.bodies.map { it.decodeToString() }
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contains("SYNTHETIC-TOKEN-0001") && !bodies[0].contains("SYNTHETIC-TOKEN-0002"))
        assertTrue(bodies[1].contains("SYNTHETIC-TOKEN-0002") && !bodies[1].contains("SYNTHETIC-TOKEN-0001"))
        assertTrue(api.requests.all { it.method == HttpMethod.Put })
    }

    @Test
    fun anOwnerWhoDeniedNotificationsRegistersAsOptedOut() = runTest {
        // Permission denial is a preference, not an error: the device stays known to the server, switched off.
        DeviceNotifications(api.client, pushWith("SYNTHETIC-TOKEN-0003")).register(notificationsEnabled = false)
        assertTrue(api.bodies.single().decodeToString().contains("\"notificationsEnabled\":false"))
    }

    @Test
    fun aSignedOutDeviceMakesNoRegistrationCallAndLeavesCoreReadsAlone() = runTest {
        api.sessions.token = null
        val notifications = DeviceNotifications(api.client, pushWith("SYNTHETIC-TOKEN-0004"))
        assertFailsWith<MobileApiException> { notifications.register() }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun theNotificationPayloadTypeCarriesOnlyIdsAndAKind() {
        // What the Worker may put in a message is {v,eventId,kind}; the app's read model of the event has no free text either.
        val fields = com.whumpo.core.api.EventInfo.serializer().descriptor.elementNames.toSet()
        assertEquals(setOf("id", "kind", "destination", "state"), fields)
    }
}
