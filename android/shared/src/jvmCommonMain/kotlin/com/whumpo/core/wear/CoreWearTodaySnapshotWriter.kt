package com.whumpo.core.wear

import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs transport preflight, then rechecks the exact workspace authority immediately before DataClient write. */
class CoreWearTodaySnapshotWriter(
    private val beforeWrite: suspend () -> Unit,
    private val isCurrent: (CoreOrganizationSessionController.State.Active) -> Boolean,
    private val write: (CoreWearTodaySnapshot) -> Unit,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun publish(
        snapshot: CoreWearTodaySnapshot,
        expectedActive: CoreOrganizationSessionController.State.Active?,
    ): Boolean {
        beforeWrite()
        if (expectedActive != null && !isCurrent(expectedActive)) return false
        return withContext(writeDispatcher) {
            if (expectedActive != null && !isCurrent(expectedActive)) return@withContext false
            write(snapshot)
            true
        }
    }
}
