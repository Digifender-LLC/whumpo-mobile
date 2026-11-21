package com.whumpo.core.wear

import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/** Reads the production Core Today endpoint and returns data only for the exact active workspace epoch. */
internal suspend fun CoreOrganizationSessionController.readCurrentWearTodaySnapshot(
    expected: CoreOrganizationSessionController.State.Active,
    generation: Long,
    publishedAtMillis: Long,
): CoreWearTodaySnapshot? {
    if (state.value !== expected) return null
    return try {
        val today = today()
        currentCoroutineContext().ensureActive()
        if (state.value !== expected || today.epoch != expected.epoch) return null

        CoreWearTodaySnapshot.fromBriefing(
            briefing = today.briefing,
            generation = generation,
            generatedAtMillis = Instant.parse(today.briefing.generatedAt).toEpochMilli(),
            publishedAtMillis = publishedAtMillis,
        )
    } catch (error: CancellationException) {
        if (state.value !== expected) null else throw error
    }
}
