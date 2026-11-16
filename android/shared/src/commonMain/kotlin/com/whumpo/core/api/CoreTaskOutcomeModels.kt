package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class CoreEffectState {
    @SerialName("queued") Queued,
    @SerialName("waiting_approval") WaitingApproval,
    @SerialName("confirmed") Confirmed,
    @SerialName("failed") Failed,
    @SerialName("canceled") Canceled,
}

@Serializable
internal data class CoreEffect(val id: String, val runId: String, val state: CoreEffectState, val taskId: String? = null)

/** Server-reported outcome of an accepted task command. Only [Confirmed] carries a canonical task. */
sealed interface CoreTaskOutcome {
    data object Queued : CoreTaskOutcome
    data object WaitingApproval : CoreTaskOutcome
    data object Failed : CoreTaskOutcome
    data object Canceled : CoreTaskOutcome
    data class Confirmed(val task: CoreWorkTask) : CoreTaskOutcome
}
