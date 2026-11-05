package com.whumpo.android.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TimeTest {
    private val now = Instant.parse("2026-10-04T12:05:00Z")

    private fun label(iso: String) = timeLabel(iso, now, "Updated")

    @Test
    fun relativeTimesGrowFromSecondsToDays() {
        assertEquals("Updated just now", label("2026-10-04T12:04:30Z").text)
        assertEquals("Updated 5 min ago", label("2026-10-04T12:00:00.000Z").text)
        assertEquals("Updated 3 h ago", label("2026-10-04T09:05:00Z").text)
        assertEquals("Updated 1 day ago", label("2026-10-03T11:00:00Z").text)
        assertEquals("Updated 4 days ago", label("2026-09-30T12:00:00Z").text)
    }

    @Test
    fun olderThanAWeekShowsTheDate() {
        assertEquals("Updated on 1 Sep 2026", label("2026-09-01T08:30:00Z").text)
    }

    @Test
    fun aFutureTimeIsJustNow() {
        assertEquals("Updated just now", label("2026-10-04T13:00:00Z").text)
    }

    @Test
    fun theSpokenFormCarriesTheAbsoluteTime() {
        assertEquals("Updated 5 min ago, 4 Oct 2026, 12:00 UTC", label("2026-10-04T12:00:00Z").description)
    }

    @Test
    fun absoluteTimeHandlesLeapDaysAndYearEnds() {
        assertEquals("29 Feb 2028, 23:59 UTC", absoluteUtc(Instant.parse("2028-02-29T23:59:59Z")))
        assertEquals("31 Dec 1999, 00:00 UTC", absoluteUtc(Instant.parse("1999-12-31T00:00:00Z")))
        assertEquals("1 Jan 1970, 00:00 UTC", absoluteUtc(Instant.fromEpochSeconds(0)))
        assertEquals("31 Dec 1969, 23:59 UTC", absoluteUtc(Instant.fromEpochSeconds(-1)))
    }

    @Test
    fun anUnparseableTimeIsShownAsGivenNotInvented() {
        assertEquals("Updated soon-ish", label("soon-ish").text)
    }
}
