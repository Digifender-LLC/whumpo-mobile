package com.whumpo.android.wear

import android.content.Context
import android.util.AtomicFile
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.wear.CoreWearGenerationCounter
import com.whumpo.core.wear.CoreWearTodaySnapshotWriter
import com.whumpo.core.wear.CoreWearTodaySnapshot
import com.whumpo.core.wear.CoreWearTodayPublicationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Serializes app-process session changes into one persistent, counts-only Wear DataItem. */
internal class CoreWearTodayPublisher(
    context: Context,
    organizations: CoreOrganizationSessionController,
    processScope: CoroutineScope,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val generation = AtomicWearGeneration(File(appContext.noBackupFilesDir, "wear-today-generation"))
    private val writer = CoreWearTodaySnapshotWriter(
        beforeWrite = {
            withContext(Dispatchers.IO) {
                Tasks.await(
                    GoogleApiAvailability.getInstance().checkApiAvailability(dataClient),
                    5,
                    TimeUnit.SECONDS,
                )
            }
        },
        isCurrent = { expected -> organizations.state.value === expected },
        write = { snapshot ->
            Tasks.await(dataClient.putDataItem(coreWearTodayPutDataRequest(snapshot)), 10, TimeUnit.SECONDS)
        },
    )
    private val worker = CoreWearTodayPublicationWorker(
        organizations = organizations,
        processScope = processScope,
        nextGeneration = generation::next,
        publish = { snapshot, active -> writer.publish(snapshot, active) },
    )

    private val watchCapabilityListener = CapabilityClient.OnCapabilityChangedListener { info: CapabilityInfo ->
        if (info.nodes.isNotEmpty()) refreshToday()
    }

    init {
        capabilityClient.addListener(watchCapabilityListener, CoreWearTodaySnapshot.WATCH_CAPABILITY)
    }

    /** Called when the paired Wear app becomes reachable or the phone activity resumes. */
    fun refreshToday() = worker.refreshToday()

    override fun close() {
        capabilityClient.removeListener(watchCapabilityListener, CoreWearTodaySnapshot.WATCH_CAPABILITY)
        worker.close()
    }

    private class AtomicWearGeneration(path: File) {
        private val file = AtomicFile(path)
        private val counter = CoreWearGenerationCounter(::read, ::persist)

        fun next(): Long = counter.next()

        private fun read(): Long = if (!file.baseFile.exists()) 0L else DataInputStream(file.openRead()).use { input ->
                val value = input.readLong()
                check(input.read() == -1) { "Invalid persisted Wear generation" }
                value
            }

        private fun persist(next: Long): Boolean {
            val output = file.startWrite()
            try {
                DataOutputStream(output).apply { writeLong(next); flush() }
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            return true
        }
    }

}
