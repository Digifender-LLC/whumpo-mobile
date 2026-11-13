package com.whumpo.android.ui.chat.forms

/** Wall-clock time in a form's IANA zone, converted to and from the UTC wire value. */
interface ZoneTime {
    fun isKnownZone(zone: String): Boolean

    /** [date] is `YYYY-MM-DD`, [time] is `HH:MM`, both in [zone]. */
    fun toUtc(date: String, time: String, zone: String): Local

    /** The local date, time and offset label for a UTC wire value, or null when it can't be shown. */
    fun fromUtc(utc: String, zone: String): Local.Exact?

    sealed interface Local {
        data class Exact(val utc: String, val date: String, val time: String, val offset: String) : Local
        /** The clocks skip this time (a daylight-saving gap). */
        data object Gap : Local
        /** The clocks pass this time twice; the owner must pick another time. */
        data object Ambiguous : Local
        data object Invalid : Local
    }
}
