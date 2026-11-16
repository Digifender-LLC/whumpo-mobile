package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class PartnerAgentKind { partner, specialist }

@Serializable
data class PartnerPage<T>(val items: List<T>, val nextCursor: String? = null)

@Serializable
data class PartnerAgent(val id: String, val kind: PartnerAgentKind, val name: String, val enabled: Boolean, val version: Long)

@Serializable
data class PartnerConversation(val id: String, val agentId: String, val kind: PartnerAgentKind)

@Serializable
enum class PartnerTopicState { open, closed }

@Serializable
enum class PartnerTopicKind { temporary, persistent }

@Serializable
enum class PartnerRetention { retained }

@Serializable
data class PartnerTopic(
    val id: String,
    val agentId: String,
    val agentKind: PartnerAgentKind,
    val conversationKind: PartnerTopicKind,
    val title: String,
    val pinned: Boolean,
    val version: Long,
    val state: PartnerTopicState,
    val retention: PartnerRetention,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String? = null,
) {
    val conversation get() = PartnerConversation(id, agentId, agentKind)
    val closed get() = state == PartnerTopicState.closed
}

@Serializable
enum class PartnerMessageRole { user, assistant }

@Serializable
data class PartnerMessage(
    val id: String,
    val userId: String,
    val sequence: Long,
    val role: PartnerMessageRole,
    val content: String,
    val createdAt: String,
    val runId: String? = null,
    val receiptIds: List<String>? = null,
    val delegation: PartnerDelegation? = null,
    val delegationSource: PartnerDelegationSource? = null,
)

@Serializable
data class PartnerDelegationSource(
    val parentRunId: String,
    val sourceConversationId: String,
    val assignedAgentId: String,
    val assignmentId: String,
)

@Serializable
enum class PartnerDelegationOutcome { pending, complete, partial, failed, canceled }

@Serializable
data class PartnerDelegation(val outcome: PartnerDelegationOutcome, val children: List<PartnerDelegationChild>)

@Serializable
data class PartnerDelegationChild(
    val runId: String,
    val conversationId: String,
    val required: Boolean,
    val status: PartnerRunStatus,
    val receiptId: String? = null,
    val artifact: PartnerStepResult? = null,
    val errorCode: String? = null,
)

@Serializable
enum class PartnerRunStatus {
    queued, planning, tool, waiting_approval, completed, failed, canceled;

    val shouldPoll get() = this in setOf(queued, planning, tool)
}

@Serializable
enum class PartnerPlanner {
    @SerialName("local-task-fixture@1") Fixture,
    @SerialName("workers-ai-structured@1") Workers,
}

@Serializable
enum class PartnerTool {
    @SerialName("tasks.create@1") Create,
    @SerialName("tasks.read@1") Read,
    @SerialName("tasks.update@1") Update,
    @SerialName("tasks.transition@1") Transition,
}

@Serializable
data class PartnerStepResult(val taskId: String, val taskVersion: Long, val receiptId: String? = null)

@Serializable
data class PartnerRunStep(
    val index: Int,
    val tool: PartnerTool,
    val effectId: String? = null,
    val approvalId: String? = null,
    val result: PartnerStepResult? = null,
)

@Serializable
data class PartnerRun(
    val id: String,
    val conversationId: String,
    val sourceMessageId: String,
    val status: PartnerRunStatus,
    val planner: PartnerPlanner,
    val deadlineAt: Long,
    val modelCalls: Int,
    val toolCalls: Int,
    val steps: List<PartnerRunStep>,
    val errorCode: String? = null,
    val delegation: PartnerDelegation? = null,
    val delegationSource: PartnerDelegationSource? = null,
)

@Serializable
internal data class PartnerMessageAck(val message: PartnerMessage, val replayed: Boolean)

@Serializable
internal data class PartnerFailureEnvelope(val error: PartnerFailure)

@Serializable
internal data class PartnerFailure(val code: String, val message: String, val requestId: String, val retryable: Boolean)

internal class PartnerHttpException(status: Int, val code: String) : CoreHttpException(status) {
    val rejectsWrite get() = when (status) {
        403 -> code == "FORBIDDEN"
        404 -> code == "TENANT_NOT_FOUND"
        409 -> code == "IDEMPOTENCY_KEY_REUSED" || code == "VERSION_CONFLICT"
        422 -> code == "VALIDATION_FAILED"
        423 -> code == "POLICY_DISABLED"
        429 -> code == "RATE_LIMITED" || code == "CONVERSATION_LIMIT"
        else -> false
    }
}

@Serializable
enum class PartnerApprovalStatus { pending, approved, rejected, expired, revoked }

@Serializable
data class PartnerApproval(
    val id: String,
    val effectId: String,
    val runId: String,
    val status: PartnerApprovalStatus,
    val payloadHash: String,
    val policyRevision: Long,
    val requesterId: String,
    val proposal: PartnerProposal,
    val expiresAt: String,
    val version: Long,
    val errorCode: String? = null,
)

@Serializable
data class PartnerProposal(
    val operation: PartnerTool,
    val payload: JsonObject,
    val taskId: String? = null,
    val expectedVersion: Long? = null,
)

@Serializable
enum class PartnerDecision { approve, reject }

@Serializable
internal data class PartnerApprovalResult(
    val approvalId: String,
    val effectId: String,
    val runId: String,
    val status: PartnerApprovalStatus,
    val version: Long,
)

@Serializable
internal data class PartnerEffect(
    val id: String,
    val runId: String,
    val state: CoreEffectState,
    val version: Long,
    val payloadHash: String,
    val policyRevision: Long,
    val approvalId: String? = null,
    val taskId: String? = null,
    val errorCode: String? = null,
    val deliveryStatus: PartnerDeliveryStatus? = null,
)

@Serializable
internal enum class PartnerDeliveryStatus { pending, delivered, retry_exhausted }

@Serializable
internal enum class PartnerReceiptOutcome { confirmed }

@Serializable
internal enum class PartnerVerification { internal_read_after_write }

@Serializable
internal data class PartnerReceipt(
    val schemaVersion: Int,
    val receiptId: String,
    val effectId: String,
    val orgId: String,
    val runId: String,
    val outcome: PartnerReceiptOutcome,
    val verificationMethod: PartnerVerification,
    val redactedSummary: String,
    val verifiedAt: String,
    val createdAt: String,
    val taskEvidence: PartnerTaskEvidence,
    val completion: PartnerCompletion? = null,
)

@Serializable
internal data class PartnerCompletion(val kind: PartnerCompletionKind, val actorId: String)

@Serializable
internal enum class PartnerCompletionKind { human_assertion }

@Serializable
internal data class PartnerTaskEvidence(val taskId: String, val taskVersion: Long, val taskDigest: String)

@Serializable
internal data class PartnerReceipts(val items: List<PartnerReceipt>)

internal enum class PartnerRoute(
    val path: String,
    val status: Int = 200,
    val limit: Int? = null,
    val write: Boolean = false,
) {
    Conversations("/v1/conversations", limit = 20),
    History("/v1/conversation-history", limit = 20),
    Agents("/v1/agents/management", limit = 20),
    Create("/v1/temporary-conversations", status = 201, write = true),
    Topic("/v1/conversations/{id}/metadata"),
    Messages("/v1/conversations/{id}/messages", limit = 5),
    Append("/v1/conversations/{id}/messages", status = 201, write = true),
    Start("/v1/conversations/{id}/runs", status = 202, write = true),
    Run("/v1/agent-runs/{id}"),
    Approval("/v1/approvals/{id}"),
    Decide("/v1/approvals/{id}/decision", write = true),
    Effect("/v1/effects/{id}"),
    Receipts("/v1/effects/{id}/receipts");

    val withId get() = "{id}" in path
}

private val partnerIntegers = setOf(
    "version", "sequence", "deadlineAt", "modelCalls", "toolCalls", "index", "taskVersion",
    "policyRevision", "expectedVersion", "schemaVersion",
)
private val partnerBooleans = setOf("enabled", "pinned", "replayed", "retryable", "required", "archived")
private val partnerNullablePayloadFields = setOf("parentTaskId", "description", "dueAt")
private val partnerUnsignedInteger = Regex("0|[1-9][0-9]{0,15}")

/** JSON primitive decoding alone can accept quoted numbers. Keep wire scalar types exact. */
internal fun verifyPartnerFieldTypes(value: JsonElement, depth: Int = 0) {
    if (depth > 8) throw CoreProtocolException()
    when (value) {
        is JsonObject -> value.forEach { (key, field) ->
            when {
                field == JsonNull && key !in partnerNullablePayloadFields -> throw CoreProtocolException()
                field == JsonNull -> Unit
                key in partnerIntegers -> {
                    val scalar = field as? JsonPrimitive ?: throw CoreProtocolException()
                    if (scalar.isString || !partnerUnsignedInteger.matches(scalar.content)) throw CoreProtocolException()
                }
                key in partnerBooleans -> {
                    if (field != JsonPrimitive(true) && field != JsonPrimitive(false)) throw CoreProtocolException()
                }
                else -> verifyPartnerFieldTypes(field, depth + 1)
            }
        }
        is JsonArray -> value.forEach { verifyPartnerFieldTypes(it, depth + 1) }
        is JsonPrimitive -> if (!value.isString) throw CoreProtocolException()
    }
}
