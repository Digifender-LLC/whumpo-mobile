package com.whumpo.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.core.push.NoOpPushMessaging
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WH-086 client push stays off. These pin the shipped build: no Firebase, no notification permission, no message service,
 * nothing that could register a device or show a notification. A change that wires push has to change these tests on purpose
 * (docs/android-notifications.md lists the owner steps). Emulator evidence only: it says nothing about delivery on any device.
 */
@RunWith(AndroidJUnit4::class)
class PushUnwiredTest {
    private val context get() = Fixtures.app
    // PackageInfoFlags.of(long) exists only from API 33 and crashed this test on API 28 (NoSuchMethodError). The int overload
    // is deprecated but is the one that exists on every API level this app supports (minSdk 28), so the pin holds on all of them.
    @Suppress("DEPRECATION")
    private val info
        get() = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )

    @Test
    fun theAppIsWiredToNoPushProvider() {
        assertSame(NoOpPushMessaging, Fixtures.container.push)
        assertFalse(Fixtures.container.notifications.available)
        assertNull(runBlocking { Fixtures.container.notifications.register() })
    }

    private fun String.isPushRelated() = "firebase" in lowercase() || "c2dm" in lowercase() || "messaging" in lowercase() || "gcm" in lowercase()

    @Test
    fun noNotificationPermissionIsRequested() {
        // Other libraries add their own (biometric, a dynamic-receiver guard); push adds none of these.
        val requested = info.requestedPermissions.orEmpty().toList()
        assertTrue(Manifest.permission.INTERNET in requested)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in requested)
        assertEquals(emptyList<String>(), requested.filter { it.isPushRelated() })
    }

    @Test
    fun noMessagingServiceReceiverOrProviderIsDeclared() {
        val messaging = Intent("com.google.firebase.MESSAGING_EVENT").setPackage(context.packageName)
        assertEquals(0, context.packageManager.queryIntentServices(messaging, 0).size)
        val declared = info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } +
            info.providers.orEmpty().flatMap { listOfNotNull(it.name, it.authority) }
        assertEquals(emptyList<String>(), declared.filter { it.isPushRelated() })
    }
}
