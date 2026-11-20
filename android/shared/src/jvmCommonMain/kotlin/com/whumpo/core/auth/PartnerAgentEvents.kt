package com.whumpo.core.auth

import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.PartnerHttpException
import com.whumpo.core.api.PartnerRunStatus
import com.whumpo.core.api.coreTimestamp
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Public, redacted agent-event.v1 snapshot. Transport closure never means execution completion. */
enum class PartnerAgentEventType(val wire: String) {
    MessageStarted("message.started"), MessageDelta("message.delta"), MessageCompleted("message.completed"),
    RunState("run.state"), ToolStarted("tool.started"), ToolCompleted("tool.completed"),
    ApprovalRequired("approval.required"), EffectState("effect.state"),
}

sealed interface PartnerAgentPart {
    data class Started(val messageId: String) : PartnerAgentPart
    data class Text(val messageId: String, val partId: String, val text: String) : PartnerAgentPart
    data class Completed(val messageId: String, val status: PartnerRunStatus) : PartnerAgentPart
    data class State(val status: PartnerRunStatus) : PartnerAgentPart
    data class Tool(val step: Int, val capability: String, val outcome: String?, val effectId: String?, val receiptId: String?) : PartnerAgentPart
    data class Approval(val step: Int, val capability: String, val effectId: String, val approvalId: String) : PartnerAgentPart
    data class Effect(val step: Int, val capability: String, val effectId: String, val state: String, val receiptId: String?, val taskId: String?) : PartnerAgentPart
}

data class PartnerAgentEvent(
    val eventId: String, val orgId: String, val conversationId: String, val runId: String,
    val sequence: Long, val type: PartnerAgentEventType, val part: PartnerAgentPart, val createdAt: String,
)

data class PartnerAgentEventState(
    val cursor: PartnerAgentEvent? = null,
    val runStatus: PartnerRunStatus? = null,
    val completed: Boolean = false,
    val parts: List<PartnerAgentEvent> = emptyList(),
) {
    fun receive(event: PartnerAgentEvent): PartnerAgentEventState {
        cursor?.let { previous ->
            wire(previous.orgId == event.orgId && previous.conversationId == event.conversationId && previous.runId == event.runId)
            if (previous.eventId == event.eventId) {
                wire(previous == event)
                return this
            }
            wire(event.sequence > previous.sequence && !completed)
        }
        val status = when (val part = event.part) {
            is PartnerAgentPart.State -> part.status
            is PartnerAgentPart.Completed -> part.status
            else -> runStatus
        }
        val visible = event.type !in setOf(PartnerAgentEventType.MessageStarted, PartnerAgentEventType.MessageCompleted, PartnerAgentEventType.RunState)
        return copy(cursor = event, runStatus = status, completed = event.type == PartnerAgentEventType.MessageCompleted,
            parts = if (visible) (parts + event).takeLast(24) else parts)
    }
}

private fun wire(valid: Boolean) { if (!valid) throw CoreProtocolException() }
private val eventIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
private val capabilities = setOf("tasks.read@1", "tasks.create@1", "tasks.update@1", "tasks.transition@1")
private fun JsonObject.exact(vararg names: String) = wire(keys == names.toSet())
private fun JsonObject.text(name: String): String {
    val value = get(name) as? JsonPrimitive ?: throw CoreProtocolException()
    wire(value.isString)
    return value.content
}
private fun JsonObject.identifier(name: String) = text(name).also { wire(eventIdentifier.matches(it)) }
private fun JsonObject.integer(name: String): Long {
    val value = get(name) as? JsonPrimitive ?: throw CoreProtocolException()
    wire(!value.isString && Regex("[0-9]+").matches(value.content))
    return value.content.toLongOrNull()?.also { wire(it <= 9_007_199_254_740_991L) } ?: throw CoreProtocolException()
}
private fun JsonObject.step() = integer("step").also { wire(it in 1..4) }.toInt()
private fun JsonObject.capability() = text("capability").also { wire(it in capabilities) }
private fun JsonObject.status(name: String): PartnerRunStatus = PartnerRunStatus.entries.find { it.name == text(name) } ?: throw CoreProtocolException()

fun decodePartnerAgentEvent(raw: String, orgId: String, conversationId: String, runId: String): PartnerAgentEvent {
    val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: IllegalArgumentException) { null }
        ?: throw CoreProtocolException()
    root.exact("schemaVersion", "eventId", "orgId", "conversationId", "runId", "sequence", "eventType", "visibility", "payload", "createdAt")
    wire(root.integer("schemaVersion") == 1L && root.text("visibility") == "user")
    wire(root.identifier("orgId") == orgId && root.identifier("conversationId") == conversationId && root.identifier("runId") == runId)
    val sequence = root.integer("sequence").also { wire(it > 0) }
    val createdAt = root.text("createdAt")
    wire(coreTimestamp.matches(createdAt))
    try { Instant.parse(createdAt) } catch (_: java.time.format.DateTimeParseException) { throw CoreProtocolException() }
    val type = PartnerAgentEventType.entries.find { it.wire == root.text("eventType") } ?: throw CoreProtocolException()
    val payload = root["payload"] as? JsonObject ?: throw CoreProtocolException()
    val part = when (type) {
        PartnerAgentEventType.MessageStarted -> { payload.exact("messageId"); PartnerAgentPart.Started(payload.identifier("messageId")) }
        PartnerAgentEventType.MessageDelta -> {
            payload.exact("messageId", "partId", "text")
            val text = payload.text("text").also { wire(it.isNotEmpty() && it.length <= 512) }
            PartnerAgentPart.Text(payload.identifier("messageId"), payload.identifier("partId"), text)
        }
        PartnerAgentEventType.MessageCompleted -> {
            payload.exact("messageId", "runStatus")
            val status = payload.status("runStatus").also { wire(it in setOf(PartnerRunStatus.completed, PartnerRunStatus.failed, PartnerRunStatus.canceled)) }
            PartnerAgentPart.Completed(payload.identifier("messageId"), status)
        }
        PartnerAgentEventType.RunState -> { payload.exact("status"); PartnerAgentPart.State(payload.status("status")) }
        PartnerAgentEventType.ToolStarted, PartnerAgentEventType.ToolCompleted -> {
            val completed = type == PartnerAgentEventType.ToolCompleted
            val keys = mutableSetOf("step", "capability")
            if (completed) keys += "outcome"
            if (payload.containsKey("effectId")) keys += "effectId"
            if (completed && payload.containsKey("receiptId")) keys += "receiptId"
            wire(payload.keys == keys)
            val outcome = if (completed) payload.text("outcome").also { wire(it in setOf("verified", "failed", "canceled")) } else null
            val receipt = if (payload.containsKey("receiptId")) payload.identifier("receiptId").also { wire(outcome == "verified") } else null
            PartnerAgentPart.Tool(payload.step(), payload.capability(), outcome,
                if (payload.containsKey("effectId")) payload.identifier("effectId") else null, receipt)
        }
        PartnerAgentEventType.ApprovalRequired -> {
            payload.exact("step", "capability", "effectId", "approvalId")
            PartnerAgentPart.Approval(payload.step(), payload.capability(), payload.identifier("effectId"), payload.identifier("approvalId"))
        }
        PartnerAgentEventType.EffectState -> {
            val state = payload.text("state").also { wire(it in setOf("queued", "waiting_approval", "confirmed", "failed", "canceled")) }
            if (state == "confirmed") payload.exact("step", "capability", "effectId", "state", "receiptId", "taskId")
            else payload.exact("step", "capability", "effectId", "state")
            PartnerAgentPart.Effect(payload.step(), payload.capability(), payload.identifier("effectId"), state,
                if (state == "confirmed") payload.identifier("receiptId") else null,
                if (state == "confirmed") payload.identifier("taskId") else null)
        }
    }
    return PartnerAgentEvent(root.identifier("eventId"), orgId, conversationId, runId, sequence, type, part, createdAt)
}

/** One bounded HTTP stream; callers own cancellation and reconnect from the durable cursor. */
internal class PartnerAgentFrameDecoder(private val orgId: String, private val conversationId: String, private val runId: String) {
    private val bytes = ByteArrayOutputStream()
    private val seen = mutableMapOf<String, PartnerAgentEvent>()
    private var sequence = 0L
    private var newline = false
    private var carriageReturn = false
    private var wireBytes = 0
    fun feed(chunk: ByteArray): List<PartnerAgentEvent> {
        val events = mutableListOf<PartnerAgentEvent>()
        for (byte in chunk) {
            wire(++wireBytes <= 65_538)
            if (carriageReturn) {
                wire(byte == 10.toByte())
                carriageReturn = false
            } else if (byte == 13.toByte()) {
                carriageReturn = true
                continue
            }
            bytes.write(byte.toInt())
            val complete = newline && byte == 10.toByte()
            newline = byte == 10.toByte()
            if (!complete) continue
            val buffer = bytes.toByteArray()
            bytes.reset()
            wireBytes = 0
            newline = false
            val raw = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer, 0, buffer.size - 2)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { throw CoreProtocolException() }
            if (raw.startsWith(':')) continue
            val lines = raw.split('\n')
            val names = lines.filter { it.startsWith("event:") }
            val data = lines.filter { it.startsWith("data:") }
            val ids = lines.filter { it.startsWith("id:") }
            wire(names.size == 1 && data.size == 1)
            val name = names.single().substring(6).trim()
            val value = data.single().substring(5).removePrefix(" ")
            if (name == "session.revoked" || name == "stream.error") {
                wire(lines.size == 2 && ids.isEmpty())
                val control = try { Json.parseToJsonElement(value) as? JsonObject } catch (_: IllegalArgumentException) { null } ?: throw CoreProtocolException()
                control.exact("code")
                val code = control.text("code")
                if (name == "session.revoked") {
                    wire(code in setOf("SESSION_REVOKED", "AUTH_REQUIRED", "TENANT_NOT_FOUND", "FORBIDDEN"))
                    throw PartnerHttpException(if (code in setOf("SESSION_REVOKED", "AUTH_REQUIRED")) 401 else 403, code)
                }
                wire(code in setOf("UPSTREAM_UNAVAILABLE", "FRAME_TOO_LARGE"))
                throw CoreProtocolException()
            }
            wire(lines.size == 3 && ids.size == 1)
            val event = decodePartnerAgentEvent(value, orgId, conversationId, runId)
            wire(event.type.wire == name && event.eventId == ids.single().substring(3).trim())
            seen[event.eventId]?.let { wire(it == event) } ?: run {
                wire(event.sequence > sequence && seen.size < 512)
                sequence = event.sequence; seen[event.eventId] = event; events += event
            }
        }
        return events
    }
    fun finish() { wire(bytes.size() == 0 && !carriageReturn) }
}
