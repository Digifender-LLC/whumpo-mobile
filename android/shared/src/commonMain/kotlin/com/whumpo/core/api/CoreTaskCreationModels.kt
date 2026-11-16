package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CoreWorkspaceChoice(val id: String, val name: String)

@Serializable
data class CoreProjectChoice(val id: String, val workspaceId: String, val name: String)

@Serializable
data class CoreWorkspaceChoices(val items: List<CoreWorkspaceChoice>, val nextCursor: String? = null)

@Serializable
data class CoreProjectChoices(val items: List<CoreProjectChoice>, val nextCursor: String? = null)

@Serializable
internal data class CoreNewTask(val projectId: String, val title: String, val description: String? = null)

@Serializable
enum class CoreTaskCommandStatus {
    @SerialName("queued") Queued,
    @SerialName("waiting_approval") WaitingApproval,
}

@Serializable
data class CoreTaskCommandAcceptance(
    val runId: String,
    val effectId: String,
    val status: CoreTaskCommandStatus,
    val approvalId: String? = null,
)
