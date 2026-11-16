package com.whumpo.core.auth

import com.whumpo.core.api.*
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun partnerSpace(character: Char): Boolean =
    character in '\u0009'..'\u000D' || character == '\u2028' || character == '\u2029' ||
        character == '\uFEFF' || Character.getType(character) == Character.SPACE_SEPARATOR.toInt()

internal fun partnerText(value: String, bytes: Int): Boolean =
    value.length <= bytes && value.any { !partnerSpace(it) } &&
        value.codePoints().noneMatch { it == 0 || it in 0xD800..0xDFFF } && value.encodeToByteArray().size <= bytes

internal fun partnerTitle(value: String): String? {
    if (!partnerText(value, 512) || value.any { it < ' ' || it == '\u007F' }) return null
    val normalized = Normalizer.normalize(value.trim(::partnerSpace), Normalizer.Form.NFC)
    return normalized.takeIf { partnerText(it, 512) && it.codePointCount(0, it.length) <= 120 }
}

internal fun partnerOperationKey(): String = UUID.randomUUID().toString()

internal data class PartnerWrite(val key: String, val body: String) {
    companion object {
        fun create(agent: PartnerAgent, title: String) = PartnerWrite(partnerOperationKey(), buildJsonObject {
            put("agentId", agent.id)
            put("title", title)
        }.toString())

        fun append(content: String): PartnerWrite {
            val key = partnerOperationKey()
            return PartnerWrite(key, buildJsonObject {
                put("content", content)
                put("clientOperationId", key)
            }.toString())
        }

        fun run(source: PartnerMessage, projectId: String, title: String) = PartnerWrite(partnerOperationKey(), buildJsonObject {
            put("sourceMessageId", source.id)
            put("task", buildJsonObject {
                put("projectId", projectId)
                put("title", title)
            })
        }.toString())

        fun decision(approval: PartnerApproval, choice: PartnerDecision): PartnerWrite {
            val key = partnerOperationKey()
            return PartnerWrite(key, buildJsonObject {
                put("schemaVersion", 1)
                put("decision", choice.name)
                put("expectedVersion", approval.version)
                put("payloadHash", approval.payloadHash)
                put("clientOperationId", key)
            }.toString())
        }
    }
}

internal class CorePartnerProtocol(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
) {
    private suspend inline fun <reified T> read(route: PartnerRoute, id: String? = null, cursor: String? = null): T =
        sessions.requestForActiveSession(active) { api, token -> api.partner<T>(token, route, id = id, cursor = cursor) }

    private suspend inline fun <reified T> write(route: PartnerRoute, command: PartnerWrite, id: String? = null): T =
        sessions.requestForActiveSession(active) { api, token -> api.partner<T>(token, route, id = id, key = command.key, body = command.body) }

    suspend fun active(cursor: String?): PartnerPage<PartnerConversation> =
        read<PartnerPage<PartnerConversation>>(PartnerRoute.Conversations, cursor = cursor).also { page ->
            page.check(20, cursor, id = PartnerConversation::id)
            page.items.forEach { checkWire(coreIdentifier.matches(it.agentId)) }
        }

    suspend fun history(cursor: String?): PartnerPage<PartnerTopic> =
        read<PartnerPage<PartnerTopic>>(PartnerRoute.History, cursor = cursor).also { page ->
            page.check(20, cursor, id = PartnerTopic::id)
            page.items.forEach(PartnerTopic::check)
        }

    suspend fun agents(cursor: String?): PartnerPage<PartnerAgent> =
        read<PartnerPage<PartnerAgent>>(PartnerRoute.Agents, cursor = cursor).also { page ->
            page.check(20, cursor, id = PartnerAgent::id)
            page.items.forEach { checkWire(partnerText(it.name, 800) && it.version.isVersion()) }
        }

    suspend fun topic(conversation: PartnerConversation): PartnerTopic =
        read<PartnerTopic>(PartnerRoute.Topic, id = conversation.id).also {
            it.check()
            checkWire(it.conversation == conversation)
        }

    suspend fun create(agent: PartnerAgent, title: String, command: PartnerWrite): PartnerTopic {
        checkWire(active.session.role == CoreRole.owner && agent.enabled)
        return write<PartnerTopic>(PartnerRoute.Create, command).also {
            it.check()
            checkWire(it.agentId == agent.id && it.agentKind == agent.kind && it.title == title &&
                it.conversationKind == PartnerTopicKind.temporary && !it.closed && it.version == 1L)
        }
    }

    suspend fun messages(id: String, after: PartnerMessage?): PartnerPage<PartnerMessage> =
        read<PartnerPage<PartnerMessage>>(PartnerRoute.Messages, id, after?.id).also { page ->
            page.check(5, after?.id, ordered = false, id = PartnerMessage::id)
            var sequence = after?.sequence ?: 0
            page.items.forEach {
                it.check()
                checkWire(it.sequence > sequence)
                sequence = it.sequence
            }
        }

    suspend fun append(id: String, content: String, command: PartnerWrite): PartnerMessage {
        checkWire(active.session.role != CoreRole.viewer)
        return write<PartnerMessageAck>(PartnerRoute.Append, command, id).message.also {
            it.check()
            checkWire(it.role == PartnerMessageRole.user && it.userId == active.session.userId &&
                it.content.encodeToByteArray().contentEquals(content.encodeToByteArray()))
        }
    }

    suspend fun start(id: String, source: PartnerMessage, command: PartnerWrite): PartnerRun {
        checkWire(active.session.role != CoreRole.viewer && source.role == PartnerMessageRole.user &&
            source.userId == active.session.userId)
        return write<PartnerRun>(PartnerRoute.Start, command, id).also {
            it.check(id, source.id)
            checkWire(it.delegation == null && it.delegationSource == null)
        }
    }

    suspend fun run(conversationId: String, id: String, sourceMessageId: String?): PartnerRun =
        read<PartnerRun>(PartnerRoute.Run, id).also {
            it.check(conversationId, sourceMessageId)
            checkWire(it.id == id)
        }

    suspend fun approval(run: PartnerRun, step: PartnerRunStep): PartnerApproval {
        checkWire(step in run.steps)
        val id = step.approvalId ?: throw CoreProtocolException()
        return read<PartnerApproval>(PartnerRoute.Approval, id).also {
            checkWire(it.id == id && it.effectId == step.effectId && coreIdentifier.matches(it.runId) &&
                it.proposal.operation == step.tool && coreDigest.matches(it.payloadHash) &&
                it.policyRevision in 0..CORE_MAX_VERSION && it.version.isVersion() &&
                coreIdentifier.matches(it.requesterId) && it.errorCode.validCode())
            it.expiresAt.instant()
            it.proposal.check()
        }
    }

    suspend fun decide(approval: PartnerApproval, choice: PartnerDecision, command: PartnerWrite) {
        checkWire(active.session.role == CoreRole.owner)
        val result = write<PartnerApprovalResult>(PartnerRoute.Decide, command, approval.id)
        checkWire(result.approvalId == approval.id && result.effectId == approval.effectId &&
            result.runId == approval.runId && result.version.isVersion() && result.version > approval.version &&
            result.status == if (choice == PartnerDecision.approve) PartnerApprovalStatus.approved else PartnerApprovalStatus.rejected)
    }

    suspend fun verifyTask(run: PartnerRun, step: PartnerRunStep, sourceAuthor: String?): PartnerTaskProof {
        checkWire(step in run.steps && step.tool != PartnerTool.Read)
        val effectId = step.effectId ?: throw CoreProtocolException()
        val approval = step.approvalId?.let { approval(run, step) }
        val effect = read<PartnerEffect>(PartnerRoute.Effect, effectId)
        // An agent run owns the tool step. The effect and its evidence have a separate run ID.
        checkWire(effect.id == effectId && coreIdentifier.matches(effect.runId) && effect.approvalId == step.approvalId &&
            effect.version.isVersion() && coreDigest.matches(effect.payloadHash) && effect.errorCode.validCode() &&
            effect.policyRevision in 0..CORE_MAX_VERSION &&
            (approval == null || (approval.runId == effect.runId &&
                approval.payloadHash == effect.payloadHash && approval.policyRevision == effect.policyRevision)) &&
            (effect.taskId != null) == (effect.state == CoreEffectState.Confirmed))
        when (effect.state) {
            CoreEffectState.WaitingApproval -> return PartnerTaskProof.WaitingApproval
            CoreEffectState.Queued -> return PartnerTaskProof.Queued
            CoreEffectState.Failed -> return PartnerTaskProof.Failed
            CoreEffectState.Canceled -> return PartnerTaskProof.Canceled
            CoreEffectState.Confirmed -> checkWire(approval == null || approval.status == PartnerApprovalStatus.approved)
        }
        val receipt = read<PartnerReceipts>(PartnerRoute.Receipts, effectId).items.singleOrNull()
            ?: throw CoreProtocolException()
        val evidence = receipt.taskEvidence
        checkWire(receipt.schemaVersion == 1 && receipt.orgId == active.session.orgId && receipt.effectId == effectId &&
            receipt.runId == effect.runId && coreIdentifier.matches(receipt.receiptId) && partnerText(receipt.redactedSummary, 8_000) &&
            receipt.verifiedAt.instant() <= receipt.createdAt.instant() &&
            evidence.taskId == effect.taskId && coreIdentifier.matches(evidence.taskId) &&
            evidence.taskVersion.isVersion() && coreDigest.matches(evidence.taskDigest))
        step.result?.let {
            checkWire(it.taskId == evidence.taskId && it.taskVersion == evidence.taskVersion && it.receiptId == receipt.receiptId)
        }
        approval?.proposal?.let { proposal ->
            checkWire(evidence.taskVersion == (proposal.expectedVersion ?: 0) + 1 &&
                (proposal.taskId == null || proposal.taskId == evidence.taskId))
        }
        val completion = receipt.completion
        if (approval != null) {
            val completedBy = if ((approval.proposal.payload["status"] as? JsonPrimitive)?.content == "done") {
                approval.requesterId
            } else null
            checkWire(completion?.actorId == completedBy)
        }
        if (completion != null) {
            checkWire(step.tool == PartnerTool.Transition && coreIdentifier.matches(completion.actorId) &&
                (approval != null || sourceAuthor == null || completion.actorId == sourceAuthor))
        }
        val task = sessions.canonicalTaskForPartner(active, evidence.taskId)
        checkWire(task.version >= evidence.taskVersion &&
            (completion == null || task.version > evidence.taskVersion || task.status == CoreTaskStatus.Done))
        return PartnerTaskProof.Verified(task, receipt.receiptId, evidence.taskVersion)
    }
}

sealed interface PartnerTaskProof {
    data object WaitingApproval : PartnerTaskProof
    data object Queued : PartnerTaskProof
    data object Failed : PartnerTaskProof
    data object Canceled : PartnerTaskProof
    data class Verified(val task: CoreWorkTask, val receiptId: String, val verifiedVersion: Long) : PartnerTaskProof
}

private fun Long.isVersion() = this in 1..CORE_MAX_VERSION
private fun String?.validId() = this == null || coreIdentifier.matches(this)
private fun String?.validCode() = this == null || Regex("[A-Z0-9_]{1,64}").matches(this)
private fun checkWire(value: Boolean) {
    if (!value) throw CoreProtocolException()
}

private fun String.instant(): Instant {
    checkWire(coreTimestamp.matches(this))
    return try {
        Instant.parse(this).also { checkWire(it.toString().let { text -> if (text.length == 20) text.dropLast(1) + ".000Z" else text } == this) }
    } catch (_: DateTimeParseException) {
        throw CoreProtocolException()
    }
}

private fun <T> PartnerPage<T>.check(limit: Int, cursor: String?, ordered: Boolean = true, id: (T) -> String) {
    val ids = items.map(id)
    checkWire(ids.size <= limit && ids.toSet().size == ids.size &&
        ids.all { coreIdentifier.matches(it) && it != cursor } &&
        (!ordered || (ids == ids.sorted() && (ids.firstOrNull()?.let { it > cursor.orEmpty() } != false))) &&
        (nextCursor == null || (ids.size == limit && nextCursor == ids.last())))
}

private fun PartnerTopic.check() {
    val created = createdAt.instant()
    val updated = updatedAt.instant()
    checkWire(coreIdentifier.matches(id) && coreIdentifier.matches(agentId) && partnerTitle(title) == title &&
        version.isVersion() && created <= updated && (closedAt != null) == closed &&
        (closedAt?.instant()?.let { it in created..updated } != false) &&
        (conversationKind != PartnerTopicKind.temporary || !pinned))
}

private fun PartnerMessage.check() {
    checkWire(coreIdentifier.matches(id) && coreIdentifier.matches(userId) && sequence.isVersion() && partnerText(content, 8192))
    createdAt.instant()
    when (role) {
        PartnerMessageRole.user -> checkWire(runId == null && receiptIds == null && delegation == null && delegationSource == null)
        PartnerMessageRole.assistant -> {
            val receipts = receiptIds ?: throw CoreProtocolException()
            checkWire(runId != null && coreIdentifier.matches(runId) && receipts.size <= 20 &&
                receipts.toSet().size == receipts.size && receipts.all(coreIdentifier::matches) &&
                delegation?.outcome != PartnerDelegationOutcome.pending)
            delegation?.check()
            delegationSource?.check()
        }
    }
}

private fun PartnerRun.check(expectedConversation: String, expectedSource: String?) {
    checkWire(coreIdentifier.matches(id) && coreIdentifier.matches(sourceMessageId) && conversationId == expectedConversation &&
        (expectedSource == null || sourceMessageId == expectedSource) && deadlineAt.isVersion() && errorCode.validCode() &&
        modelCalls in 0..5 && toolCalls in 0..4 && steps.size == toolCalls)
    steps.forEachIndexed { index, step ->
        checkWire(step.index == index + 1 && step.effectId.validId() && step.approvalId.validId() &&
            (step.approvalId == null || step.effectId != null))
        step.result?.let {
            it.check()
            checkWire(it.receiptId == null || step.effectId != null)
        }
    }
    delegation?.check()
    delegationSource?.check()
}

private fun PartnerStepResult.check() =
    checkWire(coreIdentifier.matches(taskId) && taskVersion.isVersion() && receiptId.validId())

private fun PartnerDelegation.check() {
    checkWire(children.size in 1..2 && children.map { it.runId }.toSet().size == children.size)
    children.forEach {
        checkWire(coreIdentifier.matches(it.runId) && coreIdentifier.matches(it.conversationId) &&
            it.receiptId.validId() && it.errorCode.validCode())
        it.artifact?.let { artifact ->
            artifact.check()
            checkWire(artifact.receiptId != null)
        }
    }
}

private fun PartnerDelegationSource.check() =
    checkWire(listOf(parentRunId, sourceConversationId, assignedAgentId, assignmentId).all(coreIdentifier::matches))

private fun JsonObject.text(field: String): String {
    val value = get(field) as? JsonPrimitive ?: throw CoreProtocolException()
    checkWire(value.isString)
    return value.content
}

private fun JsonObject.nullable(field: String, validate: (String) -> Unit) {
    if (field in this && get(field) != JsonNull) validate(text(field))
}

private fun PartnerProposal.check() {
    fun title() {
        val title = payload.text("title")
        checkWire(partnerText(title, 800) && title.codePointCount(0, title.length) <= 200)
    }
    fun optionalDetails() {
        payload.nullable("description") { checkWire(it.codePointCount(0, it.length) <= 10_000) }
        payload.nullable("dueAt") { it.instant() }
    }
    if (operation != PartnerTool.Create) {
        checkWire(taskId != null && coreIdentifier.matches(taskId) && expectedVersion?.isVersion() == true)
    }
    when (operation) {
        PartnerTool.Create -> {
            checkWire(taskId == null && expectedVersion == null &&
                payload.keys == setOf("projectId", "title", "parentTaskId", "description", "dueAt") &&
                coreIdentifier.matches(payload.text("projectId")))
            title()
            optionalDetails()
            payload.nullable("parentTaskId") { checkWire(coreIdentifier.matches(it)) }
        }
        PartnerTool.Update -> {
            if (payload.keys == setOf("archived")) {
                checkWire(payload["archived"] == JsonPrimitive(true) || payload["archived"] == JsonPrimitive(false))
            } else {
                checkWire(payload.isNotEmpty() && payload.keys.all { it in setOf("title", "description", "dueAt") })
                if ("title" in payload) title()
                optionalDetails()
            }
        }
        PartnerTool.Transition -> {
            checkWire(payload.keys == setOf("status", "reason"))
            try {
                Json.decodeFromString<CoreTaskStatus>(payload.getValue("status").toString())
            } catch (_: IllegalArgumentException) {
                throw CoreProtocolException()
            }
            val reason = payload.text("reason")
            checkWire(partnerText(reason, 2_000) && reason.codePointCount(0, reason.length) <= 500)
        }
        PartnerTool.Read -> throw CoreProtocolException()
    }
}
