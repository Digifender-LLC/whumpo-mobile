package com.whumpo.android.ui

import com.whumpo.android.ui.ApiFixtures.Companion.PROJECT
import com.whumpo.android.ui.ApiFixtures.Companion.reply
import com.whumpo.android.ui.photos.PendingUpload
import com.whumpo.android.ui.photos.PhotoPreparer
import com.whumpo.android.ui.photos.UploadStore
import kotlinx.coroutines.flow.MutableStateFlow
import com.whumpo.android.ui.photos.PhotoRejected
import com.whumpo.android.ui.photos.PreparedPhoto
import com.whumpo.android.ui.projects.PhotoRow
import com.whumpo.android.ui.projects.ProjectDetail
import com.whumpo.android.ui.projects.ProjectList
import com.whumpo.android.ui.projects.ProjectsViewModel
import com.whumpo.android.ui.projects.UploadPhase
import com.whumpo.android.ui.state.UiState
import io.ktor.http.HttpMethod
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ViewModel behaviour against the frozen mobile v1 fixtures (the WP4 MockEngine). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
    private val api = ApiFixtures()
    private val photoBytes = ByteArray(2_048) { (it % 200).toByte() }

    private class FakePreparer(val result: () -> PreparedPhoto) : PhotoPreparer {
        var calls = 0

        override suspend fun prepare(source: String): PreparedPhoto {
            calls++
            return result()
        }
    }

    private val preparer = FakePreparer { PreparedPhoto("van.jpg", photoBytes) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    // Main is deliberately not reset: a request still in flight when a test ends must not resume on a missing Main.

    private fun viewModel() = ProjectsViewModel(api.client, preparer) { ApiFixtures.NOW }

    private suspend fun ProjectsViewModel.loaded() = (list.first { it is UiState.Content } as UiState.Content<ProjectList>).value

    @Test
    fun listShowsProjectsWithRelativeAndAbsoluteTimes() = runTest {
        val row = viewModel().loaded().rows.single()
        assertEquals("Example Detailing", row.name)
        assertEquals("Draft", row.status.label)
        assertEquals("Updated 5 min ago", row.updated.text)
        assertEquals("Updated 5 min ago, 4 Oct 2026, 12:00 UTC", row.updated.description)
        assertEquals("Not live yet", row.website.label)
    }

    @Test
    fun emptyListIsTheEmptyState() = runTest {
        api.override = { if (it.url.encodedPath.endsWith("/projects")) reply(200, """{"version":1,"items":[],"nextCursor":null}""") else null }
        assertEquals(UiState.Empty, viewModel().list.first { it !is UiState.Loading })
    }

    @Test
    fun signedOutDeviceShowsTheSignedOutError() = runTest {
        api.sessions.token = null
        val state = viewModel().list.first { it !is UiState.Loading } as UiState.Error
        assertEquals("You've been signed out.", state.what)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun staleClientBecomesUpdateRequired() = runTest {
        api.override = {
            reply(426, """{"version":1,"error":{"code":"client_update_required","message":"Update Whumpo to continue.","minimumBuild":40,"contract":1}}""")
        }
        assertEquals(UiState.UpdateRequired, viewModel().list.first { it !is UiState.Loading })
    }

    @Test
    fun lostConnectionIsARecoverableErrorThatRetries() = runTest {
        var offline = true
        api.override = { if (offline) throw IOException("offline") else null }
        val vm = viewModel()
        val error = vm.list.first { it !is UiState.Loading } as UiState.Error
        assertEquals("Nothing was changed.", error.safe)

        offline = false
        vm.refresh()
        assertEquals("Example Detailing", vm.loaded().rows.single().name)
    }

    @Test
    fun morePagesAppendUsingTheCursor() = runTest {
        val first = """{"version":1,"items":[{"id":"$PROJECT","name":"One","purpose":"p","status":"draft","siteLive":false,"backendLive":false,"createdAt":"2026-10-04T12:00:00.000Z","updatedAt":"2026-10-04T12:00:00.000Z"}],"nextCursor":"c1"}"""
        api.override = { if (it.url.encodedPath.endsWith("/projects") && it.url.parameters["cursor"] == null) reply(200, first) else null }
        val vm = viewModel()
        assertEquals("c1", vm.loaded().nextCursor)

        vm.loadMore()
        val all = vm.list.first { it is UiState.Content && it.value.rows.size == 2 } as UiState.Content<ProjectList>
        assertEquals(listOf("One", "Example Detailing"), all.value.rows.map { it.name })
        assertEquals("c1", api.requests.last().url.parameters["cursor"])
    }

    @Test
    fun selectingAProjectLoadsDetailVersionsAndPhotos() = runTest {
        val vm = viewModel()
        vm.loaded()
        vm.select(PROJECT)

        val detail = vm.detail.first { it is UiState.Content } as UiState.Content<ProjectDetail>
        assertEquals("Example Detailing", detail.value.project.name)
        assertEquals("business-landing@1", detail.value.versions.single().component)
        assertEquals("Saved 5 min ago", detail.value.versions.single().saved.text)

        val photos = (vm.photos.first { it.photos is UiState.Content }.photos as UiState.Content<List<PhotoRow>>).value
        assertEquals("van.jpg", photos.single().name)
        // ready but safety still pending: never shown as usable
        assertEquals("Checking", photos.single().status.label)
    }

    @Test
    fun uploadSendsThePreparedBytesOnceWithACallerOwnedId() = runTest {
        val vm = viewModel()
        vm.select(PROJECT)
        vm.photos.first { it.photos is UiState.Content }

        vm.addPhoto("content://media/picker/1")
        vm.photos.first { it.uploads.isEmpty() }

        val puts = api.requests.withIndex().filter { it.value.method == HttpMethod.Put }
        assertEquals(1, puts.size)
        val request = puts.single().value
        assertTrue(request.url.encodedPath.matches(Regex(".*/projects/$PROJECT/photos/[0-9a-f-]{36}")))
        assertEquals("van.jpg", request.headers["X-Photo-Name"])
        assertArrayEquals(photoBytes, api.bodies[puts.single().index])
        assertEquals(1, preparer.calls)
    }

    @Test
    fun aLostUploadReplyIsResolvedByReadingThenRetriedWithTheSameIdAndBytes() = runTest {
        var puts = 0
        api.override = {
            when {
                it.method == HttpMethod.Put -> if (puts++ == 0) throw IOException("reply lost") else null
                it.method == HttpMethod.Get && it.url.encodedPath.contains("/photos/") ->
                    reply(404, """{"version":1,"error":{"code":"not_found","message":"This item is unavailable."}}""")
                else -> null
            }
        }
        val vm = viewModel()
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")

        val failed = vm.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single()
        assertTrue(failed.canRetry)
        assertEquals(UploadPhase.Failed, failed.phase)

        api.override = { null }
        vm.retryUpload(failed.id)
        vm.photos.first { it.uploads.isEmpty() }

        val uploads = api.requests.filter { it.method == HttpMethod.Put }
        assertEquals(2, uploads.size)
        assertEquals(uploads[0].url.encodedPath, uploads[1].url.encodedPath)
        assertTrue(uploads[0].url.encodedPath.endsWith("/photos/${failed.id}"))
        assertEquals(1, preparer.calls)
        val putBodies = api.requests.indices.filter { api.requests[it].method == HttpMethod.Put }.map { api.bodies[it] }
        assertArrayEquals(putBodies[0], putBodies[1])
    }

    @Test
    fun aLostReplyThatDidCommitIsNotUploadedAgain() = runTest {
        api.override = { if (it.method == HttpMethod.Put) throw IOException("reply lost") else null }
        val vm = viewModel()
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")

        // The status read finds the committed upload (fixture reply), so the row clears without a second PUT.
        vm.photos.first { it.uploads.isEmpty() && api.requests.any { r -> r.method == HttpMethod.Put } }
        assertEquals(1, putCount())
    }

    @Test
    fun aFullPhotoQuotaShowsTheServersMessage() = runTest {
        val quota = "Your project has no room for more photos."
        api.override = {
            if (it.method == HttpMethod.Put) reply(503, """{"version":1,"error":{"code":"unavailable","message":"$quota"}}""") else null
        }
        val vm = viewModel()
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")

        assertEquals(quota, vm.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single().message)
    }

    @Test
    fun aPhotoThatCannotBePreparedNeverReachesTheServer() = runTest {
        val rejecting = FakePreparer { throw PhotoRejected("Choose a photo under 10 MB.") }
        val vm = ProjectsViewModel(api.client, rejecting) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.photos.first { it.photos is UiState.Content }

        vm.addPhoto("content://media/picker/2")
        val row = vm.photos.first { it.uploads.isNotEmpty() }.uploads.single()
        assertEquals("Choose a photo under 10 MB.", row.message)
        assertFalse(row.canRetry)
        assertTrue(api.requests.none { it.method == HttpMethod.Put })

        vm.dismissUpload(row.id)
        assertTrue(vm.photos.value.uploads.isEmpty())
    }

    // ---- WH-085: upload recovery ----

    /** The PUT reply is lost and the status read finds nothing, so nothing committed. */
    private fun lostPut(): suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData? = { req: io.ktor.client.request.HttpRequestData ->
        when {
            req.method == HttpMethod.Put -> throw IOException("reply lost")
            req.method == HttpMethod.Get && req.url.encodedPath.contains("/photos/") ->
                reply(404, """{"version":1,"error":{"code":"not_found","message":"This item is unavailable."}}""")
            else -> null
        }
    }

    private val OTHER = "11111111-2222-4333-8444-555555555555"

    // The mock engine answers on another thread: read its request log under its own lock.
    private fun putCount() = synchronized(api.requests) { api.requests.count { it.method == HttpMethod.Put } }

    private fun listFetchCount() = synchronized(api.requests) { api.requests.count { it.url.encodedPath.endsWith("/projects") } }

    private class MemoryStore : UploadStore {
        val saved = linkedMapOf<String, PendingUpload>()
        override val account = MutableStateFlow<Any?>("a")
        var failSave = false
        override suspend fun save(upload: PendingUpload, owner: Any?): Boolean {
            if (failSave) throw java.security.GeneralSecurityException("Keystore unavailable")
            saved[upload.id] = upload
            return true
        }
        override suspend fun load() = saved.values.toList()
        override suspend fun remove(id: String) { saved.remove(id) }
    }

    @Test
    fun preparedBytesAreStoredBeforeTheFirstRequestAndReleasedOnSuccess() = runTest {
        val store = MemoryStore()
        var seenBeforePut: PendingUpload? = null
        api.override = {
            if (it.method == HttpMethod.Put) seenBeforePut = store.saved.values.singleOrNull()
            null
        }
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")
        vm.photos.first { it.uploads.isEmpty() && api.requests.any { r -> r.method == HttpMethod.Put } }

        assertArrayEquals(photoBytes, seenBeforePut!!.jpeg)
        assertEquals("van.jpg", seenBeforePut!!.name)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun anInterruptedUploadIsRetriedByANewProcessWithTheSameIdBytesAndName() = runTest {
        val store = MemoryStore()
        api.override = lostPut()
        val first = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        first.select(PROJECT)
        first.addPhoto("content://media/picker/1")
        val id = first.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single().id
        val firstBody = api.bodies[api.requests.indexOfLast { it.method == HttpMethod.Put }]

        // A new ViewModel on the same store is a restart. It never calls the preparer again.
        api.override = { null }
        val calls = preparer.calls
        val second = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        second.select(PROJECT)
        val restored = second.photos.first { it.uploads.isNotEmpty() }.uploads.single()
        assertEquals(id, restored.id)
        assertTrue(restored.canRetry)
        assertEquals(UploadPhase.Failed, restored.phase)

        second.retryUpload(id)
        second.photos.first { it.uploads.isEmpty() }
        val last = api.requests.indexOfLast { it.method == HttpMethod.Put }
        assertTrue(api.requests[last].url.encodedPath.endsWith("/photos/$id"))
        assertEquals("van.jpg", api.requests[last].headers["X-Photo-Name"])
        assertArrayEquals(firstBody, api.bodies[last])
        assertEquals(calls, preparer.calls)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun restoredUploadsShowOnlyOnTheirOwnProject() = runTest {
        val store = MemoryStore()
        store.saved["u1"] = PendingUpload("u1", OTHER, "a.jpg", photoBytes)
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        assertTrue(vm.photos.value.uploads.isEmpty())
        vm.select(OTHER)
        assertEquals("u1", vm.photos.value.uploads.single().id)
    }

    @Test
    fun accountChangeOrSignOutDropsHeldBytesAndNeverRetriesThem() = runTest {
        val store = MemoryStore()
        api.override = lostPut()
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")
        val id = vm.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single().id

        store.saved.clear() // what AccountStateStore.signOut does to the files
        store.account.value = null
        assertTrue(vm.photos.value.uploads.isEmpty())
        val puts = putCount()
        vm.retryUpload(id)
        assertEquals(puts, putCount())
    }

    @Test
    fun anUploadTheServerRefusesIsNotKept() = runTest {
        val store = MemoryStore()
        api.override = {
            if (it.method == HttpMethod.Put) reply(415, """{"version":1,"error":{"code":"unsupported_media_type","message":"No."}}""") else null
        }
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")
        val row = vm.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single()
        assertFalse(row.canRetry)
        assertTrue(store.saved.isEmpty())
        vm.retryUpload(row.id)
        assertEquals(1, putCount())
    }

    // ---- WH-085 review fixes: accounts never mix ----

    @Test
    fun anAccountChangeClearsTheListSelectionAndPhotosThenRefetchesForTheNewAccount() = runTest {
        val store = MemoryStore()
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.loaded()
        vm.select(PROJECT)
        vm.photos.first { it.photos is UiState.Content }
        val fetches = listFetchCount()

        // While B's list is loading, A's rows must not be on screen: hold the reply back.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.override = { if (it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/projects")) { gate.await(); null } else null }
        store.account.value = "b"
        assertEquals(UiState.Loading, vm.list.value)
        assertEquals(null, vm.selected.value)
        assertEquals(UiState.Loading, vm.detail.value)
        assertTrue(vm.photos.value.uploads.isEmpty())
        gate.complete(Unit)
        vm.loaded()
        assertEquals(fetches + 1, listFetchCount())
    }

    @Test
    fun aPhotoStillBeingPreparedWhenTheAccountChangesIsDroppedNotStoredNorSent() = runTest {
        val store = MemoryStore()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = FakePreparer { PreparedPhoto("a-photo.jpg", photoBytes) }
        val vm = ProjectsViewModel(api.client, object : PhotoPreparer {
            override suspend fun prepare(source: String): PreparedPhoto { gate.await(); return slow.prepare(source) }
        }, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://evil/slow")
        store.account.value = "b"
        val puts = putCount()
        gate.complete(Unit)
        kotlinx.coroutines.yield()
        assertTrue("A's bytes are not in B's store", store.saved.isEmpty())
        assertEquals("A's bytes are not sent with B's bearer", puts, putCount())
        assertTrue(vm.photos.value.uploads.isEmpty())
    }

    @Test
    fun theBytesHeldForOneAccountAreGoneForTheNextEvenWhenTheStoreStillListsThem() = runTest {
        val store = MemoryStore()
        api.override = lostPut()
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")
        val id = vm.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single().id
        store.saved.clear()
        store.account.value = "b"
        vm.select(PROJECT)
        assertTrue(vm.photos.value.uploads.isEmpty())
        val puts = putCount()
        vm.retryUpload(id)
        assertEquals(puts, putCount())
    }

    // ---- WH-085 review fixes: hostile input and failing storage never crash ----

    @Test
    fun aPreparerThatThrowsAnythingIsARefusedPhotoNotACrash() = runTest {
        val hostile = FakePreparer { throw SecurityException("grant revoked") }
        val vm = ProjectsViewModel(api.client, hostile, MemoryStore()) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://evil/throws")
        val row = vm.photos.first { it.uploads.any { u -> u.phase == UploadPhase.Failed } }.uploads.single()
        assertFalse(row.canRetry)
        assertTrue(api.requests.none { it.method == HttpMethod.Put })
    }

    @Test
    fun fixtureLogsKeepStableSnapshotsWhileNewRequestsArrive() = runTest {
        api.client.listProjects()
        val firstRequest = api.requests.single()
        val firstBody = api.bodies.single()
        val requests = api.requests.iterator()
        val bodies = api.bodies.iterator()

        api.client.getProject(PROJECT)

        assertEquals(listOf(firstRequest), requests.asSequence().toList())
        assertArrayEquals(firstBody, bodies.asSequence().toList().single())
        assertEquals(2, api.requests.size)
        assertEquals(2, api.bodies.size)
    }

    @Test
    fun aStoreThatFailsStillLetsTheUploadGoAhead() = runTest {
        val store = MemoryStore().apply { failSave = true }
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        vm.select(PROJECT)
        vm.addPhoto("content://media/picker/1")
        vm.photos.first { it.uploads.isEmpty() && api.requests.any { r -> r.method == HttpMethod.Put } }
        assertEquals(1, putCount())
    }

    @Test
    fun anUploadForAProjectThatNoLongerExistsIsDroppedAndItsSlotFreed() = runTest {
        val memory = MemoryStore()
        val id = "00000000-0000-4000-8000-0000000000aa"
        memory.saved[id] = PendingUpload(id, OTHER, "a.jpg", photoBytes)
        val releaseRestore = CompletableDeferred<Unit>()
        val removed = CompletableDeferred<String>()
        val store = object : UploadStore by memory {
            override suspend fun remove(id: String) {
                memory.remove(id)
                removed.complete(id)
            }
        }
        api.override = {
            if (it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/projects/$OTHER")) {
                releaseRestore.await()
                reply(404, """{"version":1,"error":{"code":"not_found","message":"This item is unavailable."}}""")
            } else null
        }
        val vm = ProjectsViewModel(api.client, preparer, store) { ApiFixtures.NOW }
        try {
            vm.loaded()
            // The list response is independent of startup restore's project read.
            assertEquals(setOf(id), memory.saved.keys)
            releaseRestore.complete(Unit)
            assertEquals(id, removed.await())
            vm.select(OTHER)
            assertTrue(vm.photos.value.uploads.isEmpty())
            assertTrue(memory.saved.isEmpty())
        } finally {
            releaseRestore.complete(Unit)
        }
    }
}
