package com.whumpo.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
enum class SecurityNoticeDelivery { @kotlinx.serialization.SerialName("sent") Sent, @kotlinx.serialization.SerialName("unknown") Unknown, @kotlinx.serialization.SerialName("not_configured") NotConfigured }

@Serializable
data class SecurityNotice(val id: String, val kind: String, val createdAt: Long, val delivery: SecurityNoticeDelivery)

/** Account-only metadata; no workspace authority or notification contents are exposed. */
@Serializable(with = SecurityNoticePageSerializer::class)
data class SecurityNoticePage(val items: List<SecurityNotice>, val nextCursor: String?)

internal object SecurityNoticePageSerializer : KSerializer<SecurityNoticePage> {
    override val descriptor = buildClassSerialDescriptor("SecurityNoticePage")
    override fun deserialize(decoder: Decoder): SecurityNoticePage {
        val root = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: bad()
        if (root.keys != setOf("items", "nextCursor")) bad()
        val rows = root["items"] as? JsonArray ?: bad()
        if (rows.size > 20) bad()
        val items = rows.map { value ->
            val row = value as? JsonObject ?: bad()
            if (row.keys != setOf("id", "kind", "createdAt", "delivery")) bad()
            fun string(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad()
            val id = string("id"); val kind = string("kind")
            val stamp = row["createdAt"] as? JsonPrimitive ?: bad()
            val createdAt = stamp.takeUnless { it.isString }?.longOrNull ?: bad()
            if (!securityOpaqueId.matches(id) || kind.isEmpty() || kind.length > 128 || createdAt !in 0..9_007_199_254_740_991L) bad()
            val delivery = when (string("delivery")) {
                "sent" -> SecurityNoticeDelivery.Sent
                "unknown" -> SecurityNoticeDelivery.Unknown
                "not_configured" -> SecurityNoticeDelivery.NotConfigured
                else -> bad()
            }
            SecurityNotice(id, kind, createdAt, delivery)
        }
        if (items.map { it.id }.distinct().size != items.size) bad()
        val cursor = when (val raw = root["nextCursor"]) {
            JsonNull -> null
            is JsonPrimitive -> raw.takeIf { it.isString && securityOpaqueId.matches(it.content) }?.content ?: bad()
            else -> bad()
        }
        if (cursor != null && items.isEmpty()) bad()
        return SecurityNoticePage(items, cursor)
    }
    override fun serialize(encoder: Encoder, value: SecurityNoticePage): Unit = throw SerializationException("Read-only notice response")
    private fun bad(): Nothing = throw SerializationException("Invalid account notices")
}
