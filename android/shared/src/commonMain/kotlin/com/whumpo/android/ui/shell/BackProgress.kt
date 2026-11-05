package com.whumpo.android.ui.shell

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * System back through the stable navigation-event API (it replaced the deprecated `PredictiveBackHandler`). While [enabled],
 * [onBack] runs when a back gesture completes, and the result is how far the gesture has gone (0 to 1) so the caller can
 * draw the preview; a cancelled gesture returns to 0.
 */
@Composable
fun rememberBackProgress(enabled: Boolean, onBack: () -> Unit): Float {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = onBack)
    return (state.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress ?: 0f
}
