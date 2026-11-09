package com.whumpo.android.ui.chat.forms

import com.whumpo.core.api.FrozenMutation
import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.InteractionReceipt
import com.whumpo.core.api.MobileApiClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** [FormApi] over the adopted `interaction.*` operations (docs/mobile-api-v1.md). */
class ClientFormApi(private val client: MobileApiClient) : FormPagesApi {
    override fun freeze(request: InteractionMutateRequest): FrozenMutation = client.freezeMutation(request)
    override suspend fun mutate(mutation: FrozenMutation): InteractionReceipt = client.interactionMutate(mutation)
    override suspend fun receipt(requestId: String): InteractionReceipt = client.interactionReceipt(requestId)
    override suspend fun read(interactionId: String): JsonObject = client.interactionRead(interactionId)

    /** One `interaction.resources` page; [before] is the previous page's opaque `nextBefore`, echoed unchanged. */
    override suspend fun resources(interactionId: String, definitionDigest: String, fieldId: String, before: JsonElement?): JsonObject =
        client.interactionResources(interactionId, definitionDigest, fieldId, PAGE_SIZE, before)

    companion object {
        const val PAGE_SIZE = 20
    }
}
