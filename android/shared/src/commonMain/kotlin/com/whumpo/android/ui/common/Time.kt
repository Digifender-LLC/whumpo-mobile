package com.whumpo.android.ui.common

import kotlin.time.Instant

/** A time shown as words ("5 min ago") with the absolute time kept for TalkBack ([description]). */
data class TimeLabel(val text: String, val description: String)

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Relative times for lists. [prefix] is "Updated" or "Saved". The absolute time is UTC and always spoken.
 * shortcut: UTC only (commonMain has no time zone database); switch to the device zone when a desktop pass adds kotlinx-datetime.
 */
fun timeLabel(iso: String, now: Instant, prefix: String): TimeLabel {
    val then = try {
        Instant.parse(iso)
    } catch (_: IllegalArgumentException) {
        return TimeLabel("$prefix $iso", "$prefix $iso")
    }
    val absolute = absoluteUtc(then)
    val seconds = (now - then).inWholeSeconds
    val relative = when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        seconds < 7 * 86_400 -> (seconds / 86_400).let { if (it == 1L) "1 day ago" else "$it days ago" }
        else -> "on ${absolute.substringBefore(',')}"
    }
    return TimeLabel("$prefix $relative", "$prefix $relative, $absolute")
}

/** A time with no prefix ("5 min ago") for rows that need none: Activity and Chat. Same wording and spoken absolute time. */
fun ageLabel(iso: String, now: Instant): TimeLabel =
    timeLabel(iso, now, "").let { TimeLabel(it.text.trim(), it.description.trim()) }

/** "4 Oct 2026, 12:00 UTC" (civil-from-days, proleptic Gregorian). */
fun absoluteUtc(at: Instant): String {
    val seconds = at.epochSeconds
    val days = seconds.floorDiv(86_400L)
    val secondOfDay = seconds.mod(86_400L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    val hh = (secondOfDay / 3_600).toString().padStart(2, '0')
    val mm = (secondOfDay % 3_600 / 60).toString().padStart(2, '0')
    return "$day ${MONTHS[(month - 1).toInt()]} $year, $hh:$mm UTC"
}
