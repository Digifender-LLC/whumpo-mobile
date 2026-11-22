package com.whumpo.android.wear

import com.google.android.gms.wearable.PutDataRequest
import com.whumpo.core.wear.CoreWearTodaySnapshot

/** Sends the encoded snapshot as the DataItem payload itself, not as a serialized DataMap field. */
internal fun coreWearTodayPutDataRequest(snapshot: CoreWearTodaySnapshot): PutDataRequest =
    PutDataRequest.create(CoreWearTodaySnapshot.DATA_PATH)
        .setData(CoreWearTodaySnapshot.encode(snapshot))
        .setUrgent()
