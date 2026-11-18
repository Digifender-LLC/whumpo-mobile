package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreAttachmentLink
import com.whumpo.core.api.CoreAttachmentLinks
import com.whumpo.core.api.CoreAttachmentMetadata
import com.whumpo.core.api.CoreAttachmentUpload
import com.whumpo.core.api.CoreAttachmentVersion
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreUploadState
import com.whumpo.core.api.CoreWorkTask
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreWorkAttachmentTest {
    private val original = CoreWorkTask("task-1", "project-1", "Install sign", null, null, null, CoreTaskStatus.Ready, 4)
    private val bytes = "fixture attachment".encodeToByteArray()
    private val selected get() = CoreWorkAttachmentFile("measurements.txt", "text/plain", bytes)

    private data class Write(val route: String, val key: String?, val body: String)

    private inner class Fixture(role: CoreRole = CoreRole.operator) : AutoCloseable {
        var task = original
        var identity = CoreSession("session-1", "org-1", "user-1", role, 60_000)
        var loseFinalize = false
        var loseLink = false
        var omitLink = false
        var changeAfterLink = false
        var waitTask: suspend () -> Unit = {}
        private val instant = "2026-11-17T00:00:00.000Z"
        val writes = mutableListOf<Write>()
        val uploads = mutableListOf<ByteArray>()
        private val link = CoreAttachmentLink("link-1", "file-1", 1, "task", original.id, instant)
        private lateinit var metadata: CoreAttachmentMetadata
        private var uploadState = CoreUploadState.reserved

        private fun upload(state: CoreUploadState) = Json.encodeToString(CoreAttachmentUpload(
            "upload-1", "file-1", 1, state, instant, metadata.fileName,
            metadata.mediaType, metadata.byteLength, metadata.sha256,
        ))

        private val engine = MockEngine { request ->
            assertEquals("Bearer ${"A".repeat(43)}", request.headers[HttpHeaders.Authorization])
            val route = request.url.encodedPath
            val method = request.method
            var status = HttpStatusCode.OK
            val body = when {
                route == "/v1/auth/session" -> Json.encodeToString(identity)
                route == "/v1/tasks/task-1" -> {
                    waitTask()
                    Json.encodeToString(task)
                }
                route == "/v1/files" && method == HttpMethod.Post -> {
                    val metadataJson = request.body.toByteArray().decodeToString()
                    metadata = Json.decodeFromString(metadataJson)
                    writes += Write(route, request.headers["Idempotency-Key"], metadataJson)
                    status = HttpStatusCode.Created
                    upload(CoreUploadState.reserved)
                }
                route == "/v1/uploads/upload-1" -> upload(uploadState)
                route.endsWith("/content") && method == HttpMethod.Put -> {
                    assertEquals("application/octet-stream", request.body.contentType.toString())
                    uploads += request.body.toByteArray()
                    uploadState = CoreUploadState.uploaded
                    upload(uploadState)
                }
                route.endsWith("/finalize") && method == HttpMethod.Post -> {
                    writes += Write(route, null, request.body.toByteArray().decodeToString())
                    uploadState = CoreUploadState.committed
                    if (loseFinalize) {
                        loseFinalize = false
                        status = HttpStatusCode.ServiceUnavailable
                    }
                    Json.encodeToString(CoreAttachmentVersion("file-1", 1, metadata.fileName,
                        metadata.mediaType, metadata.byteLength, metadata.sha256, "upload", instant))
                }
                route == "/v1/files/file-1/versions/1/links" && method == HttpMethod.Post -> {
                    writes += Write(route, request.headers["Idempotency-Key"], request.body.toByteArray().decodeToString())
                    status = if (loseLink) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Created
                    loseLink = false
                    if (changeAfterLink) task = task.copy(version = task.version + 1)
                    Json.encodeToString(link)
                }
                route == "/v1/files/file-1/versions/1/links" && method == HttpMethod.Get -> {
                    assertEquals("100", request.url.parameters["limit"])
                    Json.encodeToString(CoreAttachmentLinks(if (omitLink) emptyList() else listOf(link)))
                }
                else -> error("Unexpected attachment request")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        private val origin = "https://work.example.test"
        private val client = CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin)))
        val sessions = CoreOrganizationSessionController(client) { 1 }

        suspend fun attachment(task: CoreWorkTask = original): CoreWorkAttachmentController =
            CoreWorkAttachmentController(sessions, sessions.establish("A".repeat(43)), task)

        override fun close() = client.close()
    }

    @Test
    fun lostFinalizeAndLinkReuseExactRequestsAndNeverRepeatCommittedPut() = runTest {
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            fixture.loseFinalize = true
            fixture.loseLink = true
            controller.selected(selected)
            assertTrue(fixture.writes.isEmpty())
            controller.confirm()
            assertTrue(controller.state.value.canRetry)
            assertFalse(controller.state.value.saved)
            controller.retry()
            assertTrue(controller.state.value.saved)
            assertFalse(controller.state.value.attached)
            controller.retry()
            assertTrue(controller.state.value.attached)
            assertFalse(controller.state.value.canRetry)
            assertContentEquals(bytes, fixture.uploads.single())
            for (route in listOf("/v1/files", "/v1/files/file-1/versions/1/links")) {
                val calls = fixture.writes.filter { it.route == route }
                assertEquals(2, calls.size)
                assertEquals(calls.first(), calls.last())
                assertTrue(calls.first().key?.isNotEmpty() == true)
            }
        }
    }

    @Test
    fun historicalAcknowledgementCannotClaimAnAbsentLink() = runTest {
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            fixture.omitLink = true
            controller.selected(selected)
            controller.confirm()
            assertTrue(controller.state.value.saved)
            assertTrue(controller.state.value.canRetry)
            assertFalse(controller.state.value.attached)
        }
    }

    @Test
    fun taskOrSessionChangedAndViewerOrArchivedTasksCannotWrite() = runTest {
        for (role in listOf(CoreRole.viewer, CoreRole.operator)) {
            Fixture(role).use { fixture ->
                val controller = fixture.attachment()
                controller.selected(selected)
                fixture.identity = fixture.identity.copy(orgId = "other-org")
                controller.confirm()
                assertTrue(fixture.writes.isEmpty())
                assertNull(controller.state.value.fileName)
            }
        }
        Fixture().use { fixture ->
            val controller = fixture.attachment(original.copy(archivedAt = "2026-11-17"))
            controller.selected(selected)
            controller.confirm()
            assertTrue(fixture.writes.isEmpty())
        }
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            controller.selected(selected)
            fixture.task = original.copy(projectId = "moved-project", version = 5)
            controller.confirm()
            assertTrue(fixture.writes.isEmpty())
            assertEquals(CoreAttachmentNotice.DestinationChanged, controller.state.value.notice)
        }
    }

    @Test
    fun lateReadCannotStartAnUploadAfterRetirementAndTaskMoveCannotClaimSuccess() = runTest {
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            fixture.waitTask = { entered.complete(Unit); resume.await() }
            controller.selected(selected)
            val request = launch { controller.confirm() }
            entered.await()
            controller.retire()
            resume.complete(Unit)
            request.join()
            assertTrue(fixture.writes.isEmpty())
            assertEquals(CoreWorkAttachmentState(), controller.state.value)
        }
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            fixture.changeAfterLink = true
            controller.selected(selected)
            controller.confirm()
            assertEquals(CoreAttachmentNotice.DestinationChanged, controller.state.value.notice)
            assertFalse(controller.state.value.attached)
        }
    }

    @Test
    fun documentPhotoRequiresTheExactCurrentTaskBeforeAnyPreviewOrWrite() = runTest {
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            controller.selectedDocumentPhoto(selected)
            assertEquals("measurements.txt", controller.state.value.fileName)
            assertTrue(fixture.writes.isEmpty())
            assertTrue(fixture.uploads.isEmpty())
        }
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            fixture.task = original.copy(version = original.version + 1)
            controller.selectedDocumentPhoto(selected)
            assertNull(controller.state.value.fileName)
            assertEquals(CoreAttachmentNotice.DestinationChanged, controller.state.value.notice)
            controller.confirm()
            assertTrue(fixture.writes.isEmpty())
            assertTrue(fixture.uploads.isEmpty())
        }
    }

    @Test
    fun aLateDocumentPhotoReadCannotRestoreAnAttachmentAfterLeavingTheTask() = runTest {
        Fixture().use { fixture ->
            val controller = fixture.attachment()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            fixture.waitTask = { entered.complete(Unit); resume.await() }
            val check = launch { controller.selectedDocumentPhoto(selected) }
            entered.await()
            controller.retire()
            resume.complete(Unit)
            check.join()
            assertEquals(CoreWorkAttachmentState(), controller.state.value)
            assertTrue(fixture.writes.isEmpty())
            assertTrue(fixture.uploads.isEmpty())
        }
    }

    @Test
    fun selectionRejectsDisallowedNamesTypesAndSizes() {
        for ((name, type) in listOf(
            "../hidden.txt" to "text/plain", "wrong.apk" to "text/plain", "sheet.txt" to "text/html",
        )) assertFailsWith<IllegalArgumentException> { CoreWorkAttachmentFile(name, type, bytes) }
        assertFailsWith<IllegalArgumentException> { CoreWorkAttachmentFile("empty.txt", "text/plain", byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { CoreWorkAttachmentFile("mislabelled.png", "image/png", bytes) }
        assertFailsWith<IllegalArgumentException> {
            CoreWorkAttachmentFile("binary.txt", "text/plain", byteArrayOf(0, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            CoreWorkAttachmentFile("large.txt", "text/plain", ByteArray(CoreWorkAttachmentFile.MaximumBytes + 1))
        }
    }
}
