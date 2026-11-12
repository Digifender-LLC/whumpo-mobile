package com.whumpo.android.ui

import androidx.lifecycle.viewModelScope
import com.whumpo.android.ui.ApiFixtures.Companion.reply
import com.whumpo.android.ui.activity.ActivityList
import com.whumpo.android.ui.activity.ActivityViewModel
import com.whumpo.android.ui.state.UiState
import com.whumpo.core.push.DeviceNotifications
import com.whumpo.core.push.NoOpPushMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(api.requests.all { it.method.value == "GET" })
    }

    @Test
    fun refreshKeepsItsPageWhenAnOlderPageSucceedsLate() = runTest {
        assertRefreshSupersedesOlderPage(200)
    }

    @Test
    fun refreshKeepsItsPageWhenAnOlderPageFailsLate() = runTest {
        assertRefreshSupersedesOlderPage(503)
    }

    private suspend fun assertRefreshSupersedesOlderPage(olderStatus: Int) {
        val initialId = "eeeeeeee-eeee-4eee-8eee-000000000009"
        val refreshedId = "eeeeeeee-eeee-4eee-8eee-000000000010"
        val obsoleteId = "eeeeeeee-eeee-4eee-8eee-000000000011"
        val olderStarted = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val olderSettled = CompletableDeferred<Unit>()
        var firstPageReads = 0
        fun page(id: String, cursor: String) =
            """{"version":1,"items":[{"id":"$id","projectId":null,"action":"site.saved","summary":"Saved activity $id","createdAt":"2026-10-04T12:00:00.000Z"}],"nextCursor":"$cursor"}"""
        api.override = { request ->
            if (request.url.parameters["cursor"] == "older") {
                olderStarted.complete(Unit)
                try {
                    releaseOlder.await()
                    if (olderStatus == 200) {
                        reply(200, page(obsoleteId, "obsolete-next"))
                    } else {
                        reply(503, """{"version":1,"error":{"code":"unavailable","message":"Synthetic older-page failure"}}""")
                    }
                } finally {
                    olderSettled.complete(Unit)
                }
            } else {
                firstPageReads++
                reply(200, if (firstPageReads == 1) page(initialId, "older") else page(refreshedId, "fresh-next"))
            }
        }
        val vm = viewModel()
        try {
            assertEquals(listOf(initialId), vm.loaded().rows.map { it.id })
            val before = vm.viewModelScope.coroutineContext.job.children.toSet()
            vm.loadMore()
            olderStarted.await()
            val olderJob = vm.viewModelScope.coroutineContext.job.children.single { it !in before }
            assertTrue(vm.loaded().loadingMore)

            vm.refresh()
            // Prove the new first page is published BEFORE the old request settles.
            val refreshed = (vm.state.first {
                it is UiState.Content && it.value.rows.singleOrNull()?.id == refreshedId
            } as UiState.Content<ActivityList>).value
            assertEquals("fresh-next", refreshed.nextCursor)
            assertFalse(refreshed.loadingMore)
            assertFalse(refreshed.moreFailed)

            releaseOlder.complete(Unit)
            olderSettled.await()
            olderJob.join() // Wait for the ViewModel's success/error publication too.
            val final = (vm.state.value as UiState.Content<ActivityList>).value
            assertEquals("An obsolete page must not replace refreshed rows", listOf(refreshedId), final.rows.map { it.id })
            assertEquals("fresh-next", final.nextCursor)
            assertFalse(final.loadingMore)
            assertFalse(final.moreFailed)
            assertEquals(listOf(null, "older", null), api.requests.map { it.url.parameters["cursor"] })
            assertTrue(api.requests.all { it.method.value == "GET" })
        } finally {
            releaseOlder.complete(Unit)
            withContext(Dispatchers.Default + NonCancellable) {
                withTimeout(5_000) {
                    vm.viewModelScope.coroutineContext.job.cancelAndJoin()
                    if (olderStarted.isCompleted) olderSettled.await()
                }
            }
        }
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
