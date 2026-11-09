package com.whumpo.android.ui.chat.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaZoneTimeTest {
    private val z = JavaZoneTime

    @Test
    fun convertsWallClockTimeToTheUtcWireValue() {
        assertEquals(ZoneTime.Local.Exact("2030-03-04T15:30:00.000Z", "2030-03-04", "15:30", "UTC"), z.toUtc("2030-03-04", "15:30", "UTC"))
        assertEquals(ZoneTime.Local.Exact("2030-07-01T10:00:00.000Z", "2030-07-01", "12:00", "UTC+02:00"), z.toUtc("2030-07-01", "12:00", "Europe/Berlin"))
        assertEquals(z.toUtc("2030-07-01", "12:00", "Europe/Berlin"), z.fromUtc("2030-07-01T10:00:00.000Z", "Europe/Berlin"))
    }

    @Test
    fun midnightAndTheLastMinuteStayOnTheTypedDate() {
        assertEquals(ZoneTime.Local.Exact("2030-02-01T00:00:00.000Z", "2030-02-01", "00:00", "UTC"), z.toUtc("2030-02-01", "00:00", "UTC"))
        assertEquals(ZoneTime.Local.Exact("2030-02-01T23:59:00.000Z", "2030-02-01", "23:59", "UTC"), z.toUtc("2030-02-01", "23:59", "UTC"))
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
