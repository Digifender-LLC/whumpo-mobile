package com.whumpo.android.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whumpo.core.auth.AuthController
import com.whumpo.core.auth.AuthState

/** Shows the app only for a signed-in account that finished the welcome; otherwise the sign-in screens. */
@Composable
fun AuthGate(auth: AuthController, content: @Composable () -> Unit) {
    val state by auth.state.collectAsStateWithLifecycle()
    if (state == AuthState.Ready) content() else AuthFlow(state, auth)
}
