package com.whumpo.android.ui

import com.whumpo.android.ui.ApiFixtures.Companion.reply
import com.whumpo.android.ui.settings.SettingsViewModel
import com.whumpo.android.ui.theme.InMemoryThemeStore
import com.whumpo.android.ui.theme.ThemePreference
import com.whumpo.core.account.AccountScope
import com.whumpo.core.environment.EnvironmentKind
import com.whumpo.core.environment.WhumpoEnvironment
import com.whumpo.core.push.DeviceNotifications
import com.whumpo.core.push.NoOpPushMessaging
import com.whumpo.core.push.PushMessaging
import io.ktor.http.HttpMethod
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val api = ApiFixtures()
    private val account = FakeAccountRepository(AccountScope.of("acct_fixture_a"))
    private val themes = InMemoryThemeStore()

    private class FakePush(override val available: Boolean, private val token: String?) : PushMessaging {
        var deleted = 0

        override suspend fun token() = token

        override suspend fun deleteToken() {
            deleted++
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    // Main is deliberately not reset: a request still in flight when a test ends must not resume on a missing Main.

    private fun viewModel(push: PushMessaging = NoOpPushMessaging) = SettingsViewModel(
        account, api.client, themes, DeviceNotifications(api.client, push), push,
        WhumpoEnvironment.from("local", "http://127.0.0.1:8787"), "0.1.0-test",
    )

    @Test
    fun showsTheSignedInAccountLabelFromTheSession() = runTest {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        val state = vm.state.first { it.accountLabel != null }
        assertTrue(state.signedIn)
        assertEquals("o***@example.test", state.accountLabel)
        assertEquals(EnvironmentKind.LOCAL, state.environmentKind)
        assertEquals("0.1.0-test", state.versionName)
        job.cancel()
    }

    @Test
    fun anUnreadableAccountOffersARetryInsteadOfInventingDetails() = runTest {
        api.override = { if (it.url.encodedPath.endsWith("/session")) throw IOException("offline") else null }
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        val state = vm.state.first { it.accountUnavailable }
        assertNull(state.accountLabel)
        assertTrue(state.signedIn)

        api.override = { null }
        vm.retryAccount()
        assertEquals("o***@example.test", vm.state.first { it.accountLabel != null }.accountLabel)
        job.cancel()
    }

    @Test
    fun themeChoiceIsStored() = runTest {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        vm.setTheme(ThemePreference.Dark)
        assertEquals(ThemePreference.Dark, vm.state.first { it.theme == ThemePreference.Dark }.theme)
        assertEquals(ThemePreference.Dark, themes.preference.value)
        job.cancel()
    }

    @Test
    fun signOutDropsThePushTokenAndHandsOffToTheAccountRepository() = runTest {
        val push = FakePush(available = false, token = null)
        val vm = viewModel(push)
        val job = backgroundScope.launch { vm.state.collect {} }

        vm.signOut()
        vm.state.first { !it.signedIn }

        assertEquals(1, push.deleted)
        assertEquals(1, account.signOutCalls)
        job.cancel()
    }

    @Test
    fun signingOutIsNotRepeatedWhileItRuns() = runTest {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }

        vm.signOut()
        vm.state.first { !it.signedIn }

        assertEquals(1, account.signOutCalls)
        assertFalse(vm.state.value.signingOut)
        job.cancel()
    }

    @Test
    fun withoutAPushProviderNotificationsAreExplainedNotOffered() = runTest {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.first { it.signedIn }.pushAvailable)
        job.cancel()
    }

    @Test
    fun turningNotificationsOnRegistersTheDeviceAndOffRemovesIt() = runTest {
        val push = FakePush(available = true, token = "SYNTHETIC-TOKEN-0001")
        val vm = viewModel(push)
        val job = backgroundScope.launch { vm.state.collect {} }

        vm.setNotifications(true)
        vm.state.first { it.notificationsOn }
        val put = api.requests.single { it.method == HttpMethod.Put }
        assertTrue(put.url.encodedPath.endsWith("/devices/current"))
        assertTrue(api.bodies[api.requests.indexOf(put)].decodeToString().contains("\"fcmRegistrationToken\":\"SYNTHETIC-TOKEN-0001\""))

        vm.setNotifications(false)
        vm.state.first { !it.notificationsOn }
        assertEquals(1, api.requests.count { it.method == HttpMethod.Delete })
        assertEquals(1, push.deleted)
        job.cancel()
    }

    @Test
    fun aRejectedRegistrationIsReportedAndLeavesTheSwitchOff() = runTest {
        api.override = { if (it.method == HttpMethod.Put) reply(503, """{"version":1,"error":{"code":"unavailable","message":"Try again later."}}""") else null }
        val vm = viewModel(FakePush(available = true, token = "SYNTHETIC-TOKEN-0001"))
        val job = backgroundScope.launch { vm.state.collect {} }

        vm.setNotifications(true)
        val state = vm.state.first { it.notificationsError }
        assertFalse(state.notificationsOn)
        job.cancel()
    }
}
