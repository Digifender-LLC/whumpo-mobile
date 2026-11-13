package com.whumpo.android.ui.chat.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class JavaZoneTimeTest {
    private val z = JavaZoneTime

    @Test
    fun convertsWallClockTimeToTheUtcWireValue() {
        assertEquals(ZoneTime.Local.Exact("2030-03-04T15:30:00.000Z", "2030-03-04", "15:30", "UTC"), z.toUtc("2030-03-04", "15:30", "UTC"))
        assertEquals(ZoneTime.Local.Exact("2030-07-01T10:00:00.000Z", "2030-07-01", "12:00", "UTC+02:00"), z.toUtc("2030-07-01", "12:00", "Europe/Berlin"))
        assertEquals<ZoneTime.Local?>(z.toUtc("2030-07-01", "12:00", "Europe/Berlin"), z.fromUtc("2030-07-01T10:00:00.000Z", "Europe/Berlin"))
    }

    @Test
    fun midnightAndTheLastMinuteStayOnTheTypedDate() {
        assertEquals(ZoneTime.Local.Exact("2030-02-01T00:00:00.000Z", "2030-02-01", "00:00", "UTC"), z.toUtc("2030-02-01", "00:00", "UTC"))
        assertEquals(ZoneTime.Local.Exact("2030-02-01T23:59:00.000Z", "2030-02-01", "23:59", "UTC"), z.toUtc("2030-02-01", "23:59", "UTC"))
    }

    @Test
    fun localYearBoundariesCannotProduceAnUnsupportedUtcYear() {
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("0001-01-01", "00:00", "Etc/GMT-1"))
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("9999-12-31", "23:59", "America/Chicago"))
    }

    @Test
    fun representableUtcYearBoundariesStillRoundTrip() {
        val cases = listOf(
            listOf("0001-01-01", "00:00", "UTC", "0001-01-01T00:00:00.000Z"),
            listOf("0001-01-01", "01:00", "Etc/GMT-1", "0001-01-01T00:00:00.000Z"),
            listOf("9999-12-31", "22:59", "Etc/GMT+1", "9999-12-31T23:59:00.000Z"),
            listOf("9999-12-31", "23:59", "UTC", "9999-12-31T23:59:00.000Z"),
        )
        for ((date, time, zone, utc) in cases) {
            val exact = assertIs<ZoneTime.Local.Exact>(z.toUtc(date, time, zone))
            assertEquals(utc, exact.utc)
            assertEquals(exact, z.fromUtc(utc, zone))
        }
    }

    @Test
    fun daylightSavingGapsAndRepeatsAreNeverGuessed() {
        assertEquals(ZoneTime.Local.Gap, z.toUtc("2030-03-31", "02:30", "Europe/Berlin"))
        assertEquals(ZoneTime.Local.Ambiguous, z.toUtc("2030-10-27", "02:30", "Europe/Berlin"))
    }

    @Test
    fun invalidInputAndUnknownZonesAreRejected() {
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("2030-02-30", "10:00", "UTC"))
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("2030-02-01", "9:00", "UTC"))
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("2030-02-01", "24:00", "UTC"))
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("2030-02-01", "12:60", "UTC"))
        assertEquals(ZoneTime.Local.Invalid, z.toUtc("2030-02-01", "09:00", "Example/Nowhere"))
        assertEquals(null, z.fromUtc("2030-03-04T15:30:00Z", "UTC"))
        assertTrue(z.isKnownZone("UTC"))
        assertTrue(z.isKnownZone("America/Chicago"))
        assertFalse(z.isKnownZone("Example/Nowhere"))
    }
}
