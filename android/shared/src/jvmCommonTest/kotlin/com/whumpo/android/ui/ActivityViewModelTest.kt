package com.whumpo.android.ui

import com.whumpo.android.ui.ApiFixtures.Companion.reply
import com.whumpo.android.ui.activity.ActivityList
import com.whumpo.android.ui.activity.ActivityViewModel
import com.whumpo.android.ui.state.UiState
import com.whumpo.core.push.DeviceNotifications
import com.whumpo.core.push.NoOpPushMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    private val api = ApiFixtures()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    // Main is deliberately not reset: a request still in flight when a test ends must not resume on a missing Main.

    private fun viewModel() = ActivityViewModel(api.client, DeviceNotifications(api.client, NoOpPushMessaging)) { ApiFixtures.NOW }

    private suspend fun ActivityViewModel.loaded() = (state.first { it is UiState.Content } as UiState.Content<ActivityList>).value

    @Test
    fun feedShowsRelativeTimesAndKeepsTheAbsoluteTimeForTalkBack() = runTest {
        val row = viewModel().loaded().rows.single()
        assertEquals("Created project Example Detailing", row.summary)
        assertEquals("Project created", row.action)
        assertEquals("5 min ago", row.time.text)
        assertEquals("5 min ago, 4 Oct 2026, 12:00 UTC", row.time.description)
    }

    @Test
    fun emptyFeedIsTheEmptyState() = runTest {
        api.override = { reply(200, """{"version":1,"items":[],"nextCursor":null}""") }
        assertEquals(UiState.Empty, viewModel().state.first { it !is UiState.Loading })
    }

    @Test
    fun olderActivityLoadsWithTheCursor() = runTest {
        val first = """{"version":1,"items":[{"id":"eeeeeeee-eeee-4eee-8eee-000000000009","projectId":null,"action":"site.saved","summary":"Saved your site","createdAt":"2026-10-04T11:00:00.000Z"}],"nextCursor":"older"}"""
        api.override = { if (it.url.parameters["cursor"] == null) reply(200, first) else null }
        val vm = viewModel()
        assertEquals("1 h ago", vm.loaded().rows.single().time.text)

        vm.loadMore()
        vm.state.first { it is UiState.Content && it.value.rows.size == 2 }
        assertEquals("older", api.requests.last().url.parameters["cursor"])
    }

    @Test
    fun openingACurrentNotificationSaysWhatItIsAboutAndSendsNothing() = runTest {
        val vm = viewModel()
        vm.loaded()
        vm.openEvent("bbbbbbbb-bbbb-4bbb-8bbb-000000000001")

        val notice = vm.notice.first { it != null }!!
        assertEquals("A change is ready for your review.", notice.text)
        assertTrue(notice.available)
        // Reads only: every request was a GET.
        assertTrue(api.requests.all { it.method.value == "GET" })

        vm.dismissNotice()
        assertNull(vm.notice.value)
    }

    @Test
    fun aMissingEventIsNotAvailableAnymore() = runTest {
        api.override = {
            if (it.url.encodedPath.contains("/events/")) reply(404, """{"version":1,"error":{"code":"not_found","message":"This item is unavailable."}}""") else null
        }
        val vm = viewModel()
        vm.openEvent("bbbbbbbb-bbbb-4bbb-8bbb-000000000002")
        val notice = vm.notice.first { it != null }!!
        assertEquals("This item isn't available anymore.", notice.text)
        assertEquals(false, notice.available)
    }

    @Test
    fun anUnavailableEventStateIsNotAvailableAnymore() = runTest {
        api.override = {
            if (it.url.encodedPath.contains("/events/")) {
                reply(200, """{"version":1,"event":{"id":"bbbbbbbb-bbbb-4bbb-8bbb-000000000003","kind":"review_pending","destination":null,"state":"unavailable"}}""")
            } else {
                null
            }
        }
        val vm = viewModel()
        vm.openEvent("bbbbbbbb-bbbb-4bbb-8bbb-000000000003")
        assertEquals("This item isn't available anymore.", vm.notice.first { it != null }!!.text)
    }
}
