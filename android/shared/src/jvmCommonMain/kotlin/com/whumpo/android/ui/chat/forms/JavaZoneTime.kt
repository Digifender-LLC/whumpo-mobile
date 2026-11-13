package com.whumpo.android.ui.chat.forms

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** [ZoneTime] on java.time, shared by the Android and JVM targets. */
object JavaZoneTime : ZoneTime {
    private val wire = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    // STRICT: the default SMART style reads "24:00" as midnight of the same date.
    private val hm = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

    private fun zoneOf(zone: String): ZoneId? = try {
        if (zone in ZoneId.getAvailableZoneIds() || zone == "UTC") ZoneId.of(zone) else null
    } catch (_: DateTimeException) {
        null
    }

    override fun isKnownZone(zone: String) = zoneOf(zone) != null

    override fun toUtc(date: String, time: String, zone: String): ZoneTime.Local {
        val id = zoneOf(zone) ?: return ZoneTime.Local.Invalid
        if (!isIsoDate(date) || !Regex("""\d{2}:\d{2}""").matches(time)) return ZoneTime.Local.Invalid
        val local = try {
            LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time, hm))
        } catch (_: DateTimeParseException) {
            return ZoneTime.Local.Invalid
        }
        val offsets = id.rules.getValidOffsets(local)
        return when (offsets.size) {
            0 -> ZoneTime.Local.Gap
            1 -> exact(local.toInstant(offsets[0]), id) ?: ZoneTime.Local.Invalid
            else -> ZoneTime.Local.Ambiguous
        }
    }

    override fun fromUtc(utc: String, zone: String): ZoneTime.Local.Exact? {
        val id = zoneOf(zone) ?: return null
        if (!isUtcInstant(utc)) return null
        return try {
            exact(Instant.parse(utc), id)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun exact(instant: Instant, id: ZoneId): ZoneTime.Local.Exact? {
        val z = instant.atZone(id)
        if (z.year !in 1..9999) return null
        val utc = wire.format(instant)
        // An admitted local year can cross the UTC wire-year boundary after applying its offset.
        if (!isUtcInstant(utc)) return null
        val offset = z.offset.let { if (it == ZoneOffset.UTC) "UTC" else "UTC${it.id}" }
        return ZoneTime.Local.Exact(utc, z.toLocalDate().toString(), hm.format(z), offset)
    }
}
