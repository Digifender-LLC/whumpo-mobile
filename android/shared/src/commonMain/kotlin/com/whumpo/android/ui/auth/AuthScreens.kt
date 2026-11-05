package com.whumpo.android.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.state.Skeleton
import com.whumpo.android.ui.state.UpdateRequiredScreen
import com.whumpo.android.ui.state.WhumpoIcons
import com.whumpo.android.ui.state.WhumpoMascot
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.core.auth.AuthActions
import com.whumpo.core.auth.AuthProblem
import com.whumpo.core.auth.AuthState

private val Target = 56.dp
private val QuietTarget = 48.dp

/**
 * The signed-out journey (UX spec, journey 1): Welcome, the browser hand-off, finishing setup, update required.
 * Stateless: the caller passes the current [AuthState] and the [AuthActions]. Nothing here shows for [AuthState.Ready].
 */
@Composable
fun AuthFlow(state: AuthState, actions: AuthActions, modifier: Modifier = Modifier) {
    when (state) {
        AuthState.Restoring -> AuthPage(modifier) {
            Skeleton(label = "Opening Whumpo", rows = 3, modifier = Modifier.padding(top = 24.dp))
        }
        is AuthState.SignedOut -> WelcomeScreen(state, actions, modifier)
        is AuthState.WaitingForBrowser -> WaitingForBrowserScreen(state, actions, modifier)
        is AuthState.FinishSetup -> FinishSetupScreen(state, actions, modifier)
        AuthState.UpdateRequired -> UpdateRequiredScreen(onUpdate = actions::openStore, onHelp = actions::openHelp, modifier = modifier)
        AuthState.Ready -> Unit
    }
}

/** Welcome (signed out): one primary action, with the invite and help as quiet links. */
@Composable
fun WelcomeScreen(state: AuthState.SignedOut, actions: AuthActions, modifier: Modifier = Modifier) {
    val noPasskey = state.problem == AuthProblem.NoPasskey
    val browserOffered = state.problem in BrowserOffered
    AuthPage(modifier.testTag("auth-welcome")) {
        WhumpoMascot()
        Title("Welcome back to Whumpo.", display = true)
        Body("Sign in with the passkey on this phone. There is no password to remember.")
        state.problem?.let { ProblemCard(it) }
        WhumpoPrimaryButton(
            onClick = actions::signInWithPasskey,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Target)
                .testTag("auth-sign-in")
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (state.busy) stateDescription = "Signing in"
                },
        ) {
            Text(
                when {
                    state.busy -> "Signing in…"
                    noPasskey -> "Use a passkey from another device"
                    else -> "Sign in with passkey"
                },
            )
        }
        if (browserOffered) {
            WhumpoSecondaryButton(
                onClick = actions::signInWithBrowser,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = Target).testTag("auth-browser"),
            ) { Text("Sign in through the browser") }
        }
        WhumpoQuietButton(
            onClick = actions::startInvite,
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = QuietTarget).testTag("auth-invite"),
        ) { Text("New to Whumpo? Start with an invite") }
        WhumpoQuietButton(onClick = actions::openHelp, modifier = Modifier.heightIn(min = QuietTarget).testTag("auth-help")) {
            Text("Get help")
        }
    }
}

/** The browser is open for the hand-off. The app finishes by itself when the verified link returns. */
@Composable
fun WaitingForBrowserScreen(state: AuthState.WaitingForBrowser, actions: AuthActions, modifier: Modifier = Modifier) {
    AuthPage(modifier.testTag("auth-waiting")) {
        WhumpoMascot()
        if (state.exchanging) {
            Title("Signing you in…")
            Body("Almost there. This takes a moment.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            Title("Finish in your browser")
            Body("Sign in there with your passkey, then come back. Whumpo finishes here by itself.")
            WhumpoPrimaryButton(
                onClick = actions::openSetup,
                modifier = Modifier.fillMaxWidth().heightIn(min = Target).testTag("auth-reopen"),
            ) { Text("Open the browser again") }
            WhumpoQuietButton(onClick = actions::cancelHandoff, modifier = Modifier.heightIn(min = QuietTarget).testTag("auth-cancel")) {
                Text("Cancel")
            }
        }
    }
}

/** The account or the first-run welcome is not finished. It happens in the browser; native access waits for it. */
@Composable
fun FinishSetupScreen(state: AuthState.FinishSetup, actions: AuthActions, modifier: Modifier = Modifier) {
    AuthPage(modifier.testTag("auth-finish-setup")) {
        WhumpoMascot()
        Title("Finish setting up in your browser")
        Body("A few steps are left before Whumpo can open your workspace. They happen in your browser, then you come back here.")
        state.problem?.let { ProblemCard(it) }
        WhumpoPrimaryButton(
            onClick = actions::openSetup,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = Target).testTag("auth-open-setup").semantics {
                liveRegion = LiveRegionMode.Polite
            },
        ) { Text(if (state.busy) "Working…" else "Open the browser") }
        if (state.canCheck) {
            WhumpoSecondaryButton(
                onClick = actions::checkSetupAgain,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = Target).testTag("auth-check-setup"),
            ) { Text("I've finished, check again") }
            WhumpoQuietButton(onClick = actions::signOut, modifier = Modifier.heightIn(min = QuietTarget).testTag("auth-sign-out")) {
                Text("Sign out")
            }
        }
        WhumpoQuietButton(onClick = actions::openHelp, modifier = Modifier.heightIn(min = QuietTarget).testTag("auth-help")) {
            Text("Get help")
        }
    }
}

// ---- Pieces ------------------------------------------------------------------------------------------------------

/** Problems where the browser is a sensible second way in. */
private val BrowserOffered = setOf(AuthProblem.NoPasskey, AuthProblem.PasskeyUnavailable, AuthProblem.Rejected, AuthProblem.CredentialTooLarge)

/** Safe-drawing insets, a readable column (at most 480dp) and scrolling so 200% text never clips. */
@Composable
private fun AuthPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Title(text: String, display: Boolean = false) {
    Text(
        text,
        style = if (display) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** What happened and what is safe, in plain words. Icon plus words plus colour; announced politely. */
internal fun problemCopy(problem: AuthProblem): Pair<String, String> = when (problem) {
    AuthProblem.NoPasskey ->
        "There's no Whumpo passkey on this phone." to "Nothing was changed. Use a passkey from another device, or sign in through the browser."
    AuthProblem.PasskeyUnavailable ->
        "This phone couldn't open the passkey sheet." to "Nothing was changed. You can sign in through the browser instead."
    AuthProblem.Network -> "Couldn't reach Whumpo." to "Nothing was changed. Check your connection and try again."
    AuthProblem.ServiceUnavailable -> "Whumpo is busy right now." to "Nothing was changed. Try again in a moment."
    AuthProblem.RateLimited -> "Too many tries." to "Nothing was changed. Wait a minute, then try again."
    AuthProblem.Rejected -> "That passkey wasn't accepted." to "Nothing was changed. Try again, or sign in through the browser."
    AuthProblem.SessionEnded -> "You've been signed out." to "Sign in again to continue. Unsent drafts were removed from this phone."
    AuthProblem.HandoffFailed -> "The browser sign-in didn't finish." to "Nothing was changed. Start again."
    AuthProblem.NoBrowser -> "No browser could open." to "Nothing was changed. Install or enable a browser, then try again."
    AuthProblem.CredentialTooLarge -> "This passkey's response was too large to send." to "Nothing was changed. Try another passkey, or sign in through the browser."
    AuthProblem.Generic -> "Something went wrong." to "Nothing was changed. Try again."
}

@Composable
private fun ProblemCard(problem: AuthProblem, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val notice = problem == AuthProblem.SessionEnded
    val (what, safe) = problemCopy(problem)
    Card(
        modifier.fillMaxWidth().testTag("auth-problem").semantics { liveRegion = LiveRegionMode.Polite },
        colors = if (notice) {
            CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer)
        } else {
            CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
        },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(WhumpoIcons.Alert, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(what, style = MaterialTheme.typography.titleMedium)
                Text(safe, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
