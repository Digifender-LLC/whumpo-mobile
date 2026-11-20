package com.whumpo.core.auth

import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.PartnerHttpException
import com.whumpo.core.api.PartnerRunStatus
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PartnerAgentEventsTest {
    private fun golden(): JsonArray {
        var directory: File? = File(checkNotNull(System.getProperty("user.dir")))
        val name = "tests/fixtures/partner-agent-events-v1/events.json"
        while (directory != null && !File(directory, name).isFile) directory = directory.parentFile
        return Json.parseToJsonElement(File(checkNotNull(directory), name).readText()) as JsonArray
    }
    private fun decode(raw: String) = decodePartnerAgentEvent(raw, "org-example", "conversation-example", "run-example")
    private fun frame(raw: String): ByteArray {
        val event = decode(raw)
        return "id:${event.eventId}\nevent:${event.type.wire}\ndata:$raw\n\n".encodeToByteArray()
    }
    private fun decoder() = PartnerAgentFrameDecoder("org-example", "conversation-example", "run-example")

    @Test fun allClosedKindsAndMultibyteChunksUseTheProductionDecoder() {
        val items = golden()
        val parser = decoder()
        val events = items.flatMap { value -> frame(value.toString()).flatMap { parser.feed(byteArrayOf(it)) } }
        parser.finish()
        assertEquals(PartnerAgentEventType.entries.toSet(), events.map { it.type }.toSet())
        assertEquals(9, events.size)
        assertTrue((events[7].part as PartnerAgentPart.Text).text.endsWith("Café ☕"))
        val state = events.fold(PartnerAgentEventState()) { state, event -> state.receive(event) }
        assertTrue(state.completed)
        assertEquals(PartnerRunStatus.completed, state.runStatus)
    }

    @Test fun crlfFramesAndControlsWorkAcrossEveryByteBoundary() {
        val parser = decoder()
        val bytes = frame(golden()[0].toString()).decodeToString().replace("\n", "\r\n").encodeToByteArray()
        assertEquals(1, bytes.flatMap { parser.feed(byteArrayOf(it)) }.size)
        parser.finish()
        val control = "event:session.revoked\r\ndata:{\"code\":\"SESSION_REVOKED\"}\r\n\r\n".encodeToByteArray()
        assertEquals(401, assertFailsWith<PartnerHttpException> {
            val revoked = decoder()
            control.forEach { revoked.feed(byteArrayOf(it)) }
        }.status)
        val partial = decoder()
        partial.feed(byteArrayOf(13))
        assertFailsWith<CoreProtocolException> { partial.finish() }
    }

    @Test fun exactReplayIsIdempotentButChangedIdentityOrOrderFails() {
        val items = golden()
        val first = decode(items[0].toString())
        val state = PartnerAgentEventState().receive(first)
        assertSame(state, state.receive(first))
        assertFailsWith<CoreProtocolException> { state.receive(first.copy(createdAt = "2026-11-19T01:00:00.000Z")) }
        assertFailsWith<CoreProtocolException> { state.receive(first.copy(eventId = "other-event")) }
        assertFailsWith<CoreProtocolException> { state.receive(first.copy(runId = "other-run", sequence = 2)) }
        val parser = decoder()
        assertEquals(1, parser.feed(frame(items[0].toString())).size)
        assertTrue(parser.feed(frame(items[0].toString())).isEmpty())
        val changed = JsonObject((items[0] as JsonObject) + ("createdAt" to JsonPrimitive("2026-11-19T01:00:00.000Z")))
        assertFailsWith<CoreProtocolException> { parser.feed(frame(changed.toString())) }
    }

    @Test fun closedPayloadsAndScopeNeverCoerceOrAcceptExtraPrivateFields() {
        val original = golden()[6] as JsonObject
        val payload = original["payload"] as JsonObject
        for (wrong in listOf(
            JsonObject(payload + ("state" to JsonPrimitive("failed"))),
            JsonObject(payload + ("step" to JsonPrimitive("1"))),
            JsonObject(payload + ("capability" to JsonArray(listOf(JsonPrimitive("tasks.update@1"))))),
            JsonObject(payload + ("rawOutput" to JsonPrimitive("forbidden"))),
            JsonObject(payload - "taskId"),
        )) assertFailsWith<CoreProtocolException> { decode(JsonObject(original + ("payload" to wrong)).toString()) }
        for (field in listOf("orgId", "conversationId", "runId")) {
            assertFailsWith<CoreProtocolException> { decode(JsonObject(original + (field to JsonPrimitive("foreign"))).toString()) }
        }
        assertFailsWith<CoreProtocolException> { decode(JsonObject(original + ("visibility" to JsonPrimitive("internal"))).toString()) }
    }

    @Test fun actualControlFramesDenyAccessWithoutRequiringAnEventId() {
        val failure = assertFailsWith<PartnerHttpException> {
            decoder().feed("event:session.revoked\ndata:{\"code\":\"SESSION_REVOKED\"}\n\n".encodeToByteArray())
        }
        assertEquals(401, failure.status)
        assertEquals("SESSION_REVOKED", failure.code)
        assertFailsWith<CoreProtocolException> {
            decoder().feed("id:fake\nevent:session.revoked\ndata:{\"code\":\"FORBIDDEN\"}\n\n".encodeToByteArray())
        }
        assertFailsWith<CoreProtocolException> {
            decoder().feed("event:stream.error\ndata:{\"code\":\"UPSTREAM_UNAVAILABLE\"}\n\n".encodeToByteArray())
        }
    }

    @Test fun eofAndTransportInterruptionNeverImplyExecutionCompletion() {
        val parser = decoder()
        val first = parser.feed(frame(golden()[0].toString())).single()
        parser.finish()
        assertFalse(PartnerAgentEventState().receive(first).completed)
        val partial = decoder()
        partial.feed("id:unfinished".encodeToByteArray())
        assertFailsWith<CoreProtocolException> { partial.finish() }
        assertFailsWith<CoreProtocolException> { decoder().feed(byteArrayOf(0xff.toByte(), 10, 10)) }
        assertFailsWith<CoreProtocolException> { decoder().feed((":" + "x".repeat(65_537) + "\n\n").encodeToByteArray()) }
    }

    @Test fun displayedStatusUsesOnlyCurrentScopedCanonicalOutcomeAndNeverTransportSuccess() {
        val selected = com.whumpo.core.api.PartnerRun("run-example", "conversation-example", "source-message",
            PartnerRunStatus.queued, com.whumpo.core.api.PartnerPlanner.Fixture, 2_000_000_000_000, 0, 0, emptyList())
        val topic = com.whumpo.core.api.PartnerConversation("conversation-example", "agent-example", com.whumpo.core.api.PartnerAgentKind.partner)
        val done = decode(golden().last().toString())
        for (status in listOf(PartnerRunStatus.completed, PartnerRunStatus.failed, PartnerRunStatus.canceled)) {
            val events = PartnerAgentEventState().receive(done.copy(part = PartnerAgentPart.Completed("message-response", status)))
            val state = PartnerState(conversation = topic, run = selected, agentEvents = events,
                eventConnection = PartnerEventConnection.Completed)
            assertEquals(status, state.displayedRunStatus())
            assertEquals(PartnerRunStatus.queued, state.copy(run = selected.copy(id = "other-run")).displayedRunStatus())
            assertEquals(PartnerRunStatus.queued, state.copy(conversation = topic.copy(id = "other-conversation")).displayedRunStatus())
        }
        val waiting = PartnerAgentEventState().receive(done.copy(type = PartnerAgentEventType.RunState,
            part = PartnerAgentPart.State(PartnerRunStatus.waiting_approval)))
        for (connection in listOf(PartnerEventConnection.Retrying, PartnerEventConnection.Disconnected, PartnerEventConnection.Completed)) {
            val state = PartnerState(conversation = topic, run = selected, agentEvents = waiting, eventConnection = connection)
            assertEquals(PartnerRunStatus.waiting_approval, state.displayedRunStatus())
            assertEquals(PartnerRunStatus.failed, state.copy(run = selected.copy(status = PartnerRunStatus.failed)).displayedRunStatus())
        }
    }

    @Test fun terminalOutcomeWinsAndLateEventsCannotResurrectAnActiveRun() {
        val done = decode(golden().last().toString())
        val state = PartnerAgentEventState().receive(done.copy(part = PartnerAgentPart.Completed("message-response", PartnerRunStatus.failed)))
        assertEquals(PartnerRunStatus.failed, state.runStatus)
        assertTrue(state.completed)
        assertFailsWith<CoreProtocolException> { state.receive(done.copy(eventId = "late", sequence = 10)) }
    }
}
