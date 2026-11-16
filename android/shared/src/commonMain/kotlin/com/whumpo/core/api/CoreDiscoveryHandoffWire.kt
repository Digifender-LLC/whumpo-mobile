package com.whumpo.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CoreHandoffState { WaitingApproval, Queued, Confirmed, Failed, Canceled }
enum class CoreHandoffLineage { Current, Superseded }

data class CoreHandoffDependency(val actionId: CoreDiscoveryActionId, val taskId: String)
data class CoreHandoffTask(val taskId: String, val receiptId: String, val taskVersion: Long)

data class CoreDiscoveryHandoff(
    val orgId: String,
    val id: String,
    val projectId: String,
    val actionId: CoreDiscoveryActionId,
    val discoveryRevision: Long,
    val answerRevision: Long,
    val planRevision: Long,
    val dependencies: List<CoreHandoffDependency>,
    val state: CoreHandoffState,
    val approvalId: String?,
    val effectVersion: Long,
    val payloadHash: String,
    val task: CoreHandoffTask?,
    val lineage: CoreHandoffLineage,
    val supersedesHandoffId: String?,
)

internal data class CoreHandoffCommand(
    val projectId: String,
    val discoveryRevision: Long,
    val answerRevision: Long,
    val planRevision: Long,
    val actionId: CoreDiscoveryActionId,
) {
    fun encode(): String = buildJsonObject {
        put("schemaVersion", 1)
        put("projectId", projectId)
        put("discoveryRevision", discoveryRevision)
        put("answerRevision", answerRevision)
        put("planRevision", planRevision)
        put("actionId", actionId.code)
    }.toString()

    fun matches(handoff: CoreDiscoveryHandoff): Boolean =
        projectId == handoff.projectId && discoveryRevision == handoff.discoveryRevision &&
            answerRevision == handoff.answerRevision && planRevision == handoff.planRevision && actionId == handoff.actionId
}

internal fun decodeHandoff(body: String): CoreDiscoveryHandoff = discoveryRoot(body).handoff()

internal fun decodeHandoffInventory(body: String, orgId: String): List<CoreDiscoveryHandoff> {
    val root = discoveryRoot(body).closed("schemaVersion", "orgId", "handoffs")
    checkDiscovery(root.field("orgId").handoffIdentifier() == orgId)
    val entries = root.field("handoffs").uniqueList(CoreDiscoveryActionId.entries.size) { it.handoff() }
    checkDiscovery(entries.all { it.orgId == orgId } &&
        entries.map { it.id }.toSet().size == entries.size && entries.map { it.actionId }.toSet().size == entries.size)
    return entries
}

private fun JsonElement.handoff(): CoreDiscoveryHandoff {
    val row = objectValue().closed("schemaVersion", "orgId", "handoffId", "projectId", "actionId",
        "discoveryRevision", "answerRevision", "planRevision", "dependencies", "state", "approvalId",
        "effectVersion", "payloadHash", "task", "lineageStatus", "supersedesHandoffId")
    checkDiscovery(row.field("schemaVersion") == JsonPrimitive(1))
    val action = actionId(row.field("actionId"))
    val id = row.field("handoffId").handoffIdentifier()
    val supersedes = row.field("supersedesHandoffId").unlessNull { it.handoffIdentifier() }
    val dependencies = row.field("dependencies").uniqueList(10) {
        val pair = it.objectValue().closed("actionId", "taskId")
        CoreHandoffDependency(actionId(pair.field("actionId")), pair.field("taskId").handoffIdentifier())
    }
    checkDiscovery(supersedes != id && dependencies.none { it.actionId == action } &&
        dependencies.map { it.actionId }.toSet().size == dependencies.size &&
        dependencies.map { it.taskId }.toSet().size == dependencies.size)
    val state = when (row.field("state").text(16)) {
        "waiting_approval" -> CoreHandoffState.WaitingApproval
        "queued" -> CoreHandoffState.Queued
        "confirmed" -> CoreHandoffState.Confirmed
        "failed" -> CoreHandoffState.Failed
        "canceled" -> CoreHandoffState.Canceled
        else -> throw CoreProtocolException()
    }
    val lineage = when (row.field("lineageStatus").text(10)) {
        "current" -> CoreHandoffLineage.Current
        "superseded" -> CoreHandoffLineage.Superseded
        else -> throw CoreProtocolException()
    }
    val approval = row.field("approvalId").unlessNull { it.handoffIdentifier() }
    val task = row.field("task").unlessNull {
        val record = it.objectValue().closed("taskId", "receiptId", "taskVersion")
        CoreHandoffTask(record.field("taskId").handoffIdentifier(), record.field("receiptId").handoffIdentifier(),
            record.field("taskVersion").revision().also { version -> checkDiscovery(version == 1L) })
    }
    checkDiscovery((state == CoreHandoffState.Confirmed) == (task != null) &&
        (state != CoreHandoffState.WaitingApproval || approval != null))
    val documentRevision = row.field("discoveryRevision").revision()
    val answerRevision = row.field("answerRevision").revision()
    val planRevision = row.field("planRevision").revision()
    checkDiscovery(answerRevision <= documentRevision && planRevision <= documentRevision)
    val payloadHash = row.field("payloadHash").text(64)
    checkDiscovery(coreDigest.matches(payloadHash))
    return CoreDiscoveryHandoff(row.field("orgId").handoffIdentifier(), id, row.field("projectId").handoffIdentifier(),
        action, documentRevision, answerRevision, planRevision, dependencies, state, approval,
        row.field("effectVersion").revision(), payloadHash, task, lineage, supersedes)
}

private fun JsonElement.handoffIdentifier() = text(128).also { checkDiscovery(coreIdentifier.matches(it)) }
