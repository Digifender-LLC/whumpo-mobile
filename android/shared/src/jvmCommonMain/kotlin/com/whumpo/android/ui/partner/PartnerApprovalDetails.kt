package com.whumpo.android.ui.partner

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.whumpo.core.api.PartnerApproval
import com.whumpo.core.api.PartnerTool
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun PartnerApprovalDetails(approval: PartnerApproval, requesterLabel: String? = null, taskLabel: String? = null) {
    Text(when (approval.proposal.operation) {
        PartnerTool.Create -> "Create task"
        PartnerTool.Update -> "Update task"
        PartnerTool.Transition -> "Change task status"
        PartnerTool.Read -> "Read task"
    }, style = MaterialTheme.typography.titleLarge)
    Text("Status: ${approval.status.name}")
    Text("Requested by: ${requesterLabel ?: approval.requesterId}")
    Text("Review expires: ${approval.expiresAt}")
    approval.proposal.taskId?.let { Text("Task: ${taskLabel ?: it}") }
    approval.proposal.expectedVersion?.let { Text("Expected task version: $it") }
    approval.proposal.payload.forEach { (field, value) ->
        val label = when (field) {
            "projectId" -> "Project"
            "title" -> "Title"
            "parentTaskId" -> "Parent task"
            "description" -> "Description"
            "dueAt" -> "Due date"
            "archived" -> "Archived"
            "status" -> "Task status"
            "reason" -> "Reason"
            else -> field
        }
        Text("$label: ${if (value == JsonNull) "None" else (value as JsonPrimitive).content}")
    }
}
