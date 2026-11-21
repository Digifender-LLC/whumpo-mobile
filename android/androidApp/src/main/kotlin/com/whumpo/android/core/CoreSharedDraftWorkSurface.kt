package com.whumpo.android.core

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whumpo.android.CorePhoneComposition
import com.whumpo.android.ui.work.OfflineWorkActions
import com.whumpo.android.ui.work.OfflineWorkContent
import com.whumpo.core.account.CoreOfflineWorkStore
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.CoreOfflineWorkController
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reuses the encrypted, offline Work draft flow; the incoming item remains only in the transient review object. */
@Composable
internal fun CoreSharedDraftWorkSurface(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    store: CoreOfflineWorkStore,
    offer: CorePhoneComposition.SharedDraftOffer?,
    accept: (CorePhoneComposition.SharedDraftOffer) -> String?,
    dismiss: () -> Unit,
    close: () -> Unit,
    signOut: () -> Unit,
    openTask: (CoreWorkTask) -> Unit,
) {
    val controller = remember(sessions, active, store) { CoreOfflineWorkController(sessions, active, store) }
    DisposableEffect(controller) { onDispose { controller.retire() } }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, offer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && offer != null && !offer.accepted) dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(offer?.review) {
        val review = offer?.review ?: return@LaunchedEffect
        delay(review.remainingMillis(SystemClock.elapsedRealtime()))
        if (offer?.review === review) dismiss()
    }
    val back: () -> Unit = {
        dismiss()
        if (state.connected) {
            controller.offline()
            scope.launch { controller.load() }
        } else close()
    }
    androidx.compose.runtime.LaunchedEffect(controller) { controller.load() }
    BackHandler(onBack = back)
    OfflineWorkContent(
        state,
        OfflineWorkActions(
            save = { title, description -> scope.launch { controller.save(title, description) } },
            discard = { scope.launch { controller.discard() } },
            reconnect = { scope.launch { controller.reconnect() } },
            chooseWorkspace = { scope.launch { controller.chooseWorkspace(it) } },
            changeWorkspace = controller::changeWorkspace,
            moreWorkspaces = { scope.launch { controller.moreWorkspaces() } },
            chooseProject = { scope.launch { controller.chooseProject(it) } },
            moreProjects = { scope.launch { controller.moreProjects() } },
            submit = { scope.launch { controller.submit(it) } },
            cancelReview = controller::cancelReview,
            checkTask = { scope.launch { controller.checkTask() } },
            openTask = openTask,
            back = back,
            signOut = {
                dismiss()
                controller.retire()
                signOut()
            },
        ),
        sharedItem = offer?.takeUnless { it.accepted }?.review?.item,
        useSharedItem = {
            val current = controller.state.value
            if (offer != null && !offer.accepted && current.loaded && !current.connected && !current.busy &&
                !current.saved && !current.frozen && !current.accessLost && current.notice == null) accept(offer)
            else null
        },
        dismissShare = dismiss,
    )
}
