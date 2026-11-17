package com.whumpo.android.core

import android.content.ContentResolver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.AccountSecurityState
import com.whumpo.core.api.RecoveryLossMode
import com.whumpo.core.api.RecoveryReview
import com.whumpo.core.api.SavedRecoveryProof
import com.whumpo.core.api.SecurityInvitationPreview
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityAdmission
import com.whumpo.core.api.SecurityMembershipPage
import com.whumpo.core.api.SecurityMethodKind
import com.whumpo.core.api.SecurityMethodStatus
import com.whumpo.core.api.SecurityPhase
import com.whumpo.core.api.SecurityProfile
import com.whumpo.core.api.SecurityPurpose
import com.whumpo.core.auth.AccountPasskeys
import com.whumpo.core.auth.AccountSecurityIdentityMismatch
import com.whumpo.core.auth.AccountSecurityProofRejected
import com.whumpo.core.auth.AccountSecurityRetryResult
import com.whumpo.core.auth.CoreAccountSecurityController
import com.whumpo.core.auth.EnrollmentAcknowledgementLost
import com.whumpo.core.auth.OneTimeMaterialUnavailable
import com.whumpo.core.auth.PendingRecoveryDocument
import com.whumpo.core.auth.PendingTotp
import com.whumpo.core.auth.RecoveryAcknowledgementLost
import com.whumpo.core.auth.ReservedSetupStart
import com.whumpo.core.auth.replacementMethodsReady
import com.whumpo.core.auth.verifiedSetupCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Entry { Choose, Enrollment, Reserved, ResumeReserved, Recovery, Password, Totp }

@Composable
internal fun AccountSecurityScreen(
    controller: CoreAccountSecurityController,
    state: AccountSecurityState?,
    passkeys: AccountPasskeys,
    passkeysConfigured: Boolean,
    existingAccountOnly: Boolean = false,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val retry by controller.retry.collectAsState()
    val reviewedRecovery by controller.recoveryReview.collectAsState()
    var entry by remember { mutableStateOf(Entry.Choose) }
    var invitation by remember { mutableStateOf<ReviewedInvitation?>(null) }
    var joining by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(SecurityProfile.Passkeys) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var totpSetup by remember { mutableStateOf<PendingTotp?>(null) }
    var recovery by remember { mutableStateOf<PendingRecoveryDocument?>(null) }
    var written by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var leavingFailed by remember { mutableStateOf(false) }
    var organizations by remember { mutableStateOf<SecurityMembershipPage?>(null) }
    var editingReview by remember { mutableStateOf(false) }
    var checkingCompletion by remember { mutableStateOf(false) }
    var reservedStart by remember { mutableStateOf<ReservedSetupStart?>(null) }
    var reservedKeySaved by remember { mutableStateOf(false) }
    val offer = state?.recovery?.offer
    var offerExpired by remember(offer?.id, offer?.expiresAt) { mutableStateOf(false) }
    LaunchedEffect(offer?.id, offer?.expiresAt) {
        if (offer != null) {
            delay((offer.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            offerExpired = true
            recovery = null
            written = false
        }
    }

    fun run(action: suspend () -> Unit) {
        if (busy || exporting) return
        busy = true
        message = null
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: EnrollmentAcknowledgementLost) {
                entry = Entry.Choose
                recovery = null
                totpSetup = null
                message = "The setup result was lost. Try signing in with the credentials you just set up."
            } catch (_: RecoveryAcknowledgementLost) {
                message = "The service did not confirm this step."
            } catch (_: AccountSecurityProofRejected) {
                message = "The service rejected the proof. Try again with your passkey or a current authenticator code."
            } catch (_: AccountSecurityIdentityMismatch) {
                entry = Entry.Choose
                message = "Those credentials did not confirm the account you were setting up."
            } catch (_: OneTimeMaterialUnavailable) {
                message = "The service confirmed the request without returning its secret. Prepare a fresh one."
            } catch (_: Exception) {
                message = "Could not complete this step. Check the local service and try again."
            } finally {
                busy = false
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        exporting = false
        val document = recovery
        if (uri != null && document != null) run {
            check(uri.scheme == ContentResolver.SCHEME_CONTENT)
            val current = controller.state.value?.recovery?.offer
            check(current?.id == document.offerId && current.generation == document.generation)
            withContext(Dispatchers.IO) {
                checkNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                    it.write(document.text)
                }
            }
            written = true
        }
    }

    val exportReservedKey = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        exporting = false
        val draft = reservedStart
        if (uri != null && draft != null) run {
            check(uri.scheme == ContentResolver.SCHEME_CONTENT)
            withContext(Dispatchers.IO) {
                checkNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                    it.write(draft.continuationKey)
                }
            }
            reservedKeySaved = true
        }
    }

    fun clearStepSecrets() {
        recovery = null
        totpSetup = null
        written = false
        organizations = null
        reservedStart = null
        reservedKeySaved = false
    }

    fun restart(next: Entry, explanation: String? = null) {
        controller.clear()
        clearStepSecrets()
        editingReview = false
        checkingCompletion = false
        entry = next
        message = explanation
    }

    fun retryStep() {
        val requested = retry
        run {
            when (val result = controller.retryPending()) {
                is AccountSecurityRetryResult.Material -> {
                    recovery = result.document
                    written = false
                }
                is AccountSecurityRetryResult.Totp -> totpSetup = result.setup
                AccountSecurityRetryResult.Updated -> when (requested) {
                    CoreAccountSecurityController.Retry.Review -> editingReview = false
                    CoreAccountSecurityController.Retry.SaveMaterial -> {
                        recovery = null
                        written = false
                    }
                    CoreAccountSecurityController.Retry.VerifyTotp -> totpSetup = null
                    CoreAccountSecurityController.Retry.Complete -> clearStepSecrets()
                    else -> Unit
                }
            }
        }
    }

    @Composable
    fun materialStep(selectedProfile: SecurityProfile, replacement: Boolean) {
        val kit = selectedProfile == SecurityProfile.Passkeys
        val label = if (kit) "recovery kit" else "ten recovery codes"
        val document = recovery?.takeIf { !offerExpired && offer?.id == it.offerId && offer.generation == it.generation }
        RecoveryMaterialStep(label, replacement, document != null, written, busy || exporting,
            prepare = { run { recovery = controller.prepareRecovery(); written = false } },
            saveFile = {
                exporting = true
                try {
                    export.launch(if (kit) "Whumpo recovery kit.txt" else "Whumpo recovery codes.txt")
                } catch (_: Exception) {
                    exporting = false
                    message = "The document picker could not open."
                }
            },
            confirm = {
                checkNotNull(document)
                run {
                    controller.confirmSaved(document.offerId, document.generation)
                    recovery = null
                    written = false
                }
            },
        )
    }

    @Composable
    fun methodsStep(snapshot: AccountSecurityState) {
        val passkeySetup = snapshot.setupProfile == SecurityProfile.Passkeys
        val passkeysInSetup = snapshot.verifiedSetupCount(SecurityMethodKind.Passkey)
        val hasPassword = snapshot.verifiedSetupCount(SecurityMethodKind.Password) > 0
        when {
            passkeySetup -> {
                val backup = passkeysInSetup > 0
                StepTitle(if (backup) "Add a backup passkey" else "Create a passkey")
                Text(if (backup) "Choose a separate credential, ideally on another device or security key." else "Your device will ask you to verify your identity.")
                StepButton(if (backup) "Add backup passkey" else "Create passkey", !busy && passkeysConfigured) {
                    run {
                        val label = if (backup) "Backup passkey" else "First passkey"
                        if (!controller.registerPasskey(label, passkeys)) message = "Passkey setup cancelled."
                    }
                }
                if (!passkeysConfigured) Text("This build needs a relying-party host to create passkeys.")
            }
            !hasPassword -> {
                StepTitle("Set your password")
                PasswordStep("Set password", busy) { username, password ->
                    run { controller.stagePassword(username, password) }
                }
            }
            else -> {
                StepTitle("Connect an authenticator")
                val setup = totpSetup
                if (setup == null) {
                    StepButton("Get setup key", !busy) { run { totpSetup = controller.prepareTotp() } }
                } else {
                    Text("Enter this setup key in your authenticator, then enter its current code here.")
                    Text(setup.secret, style = MaterialTheme.typography.titleMedium)
                    TotpStep("Verify code", busy) { code ->
                        run { controller.verifyTotp(setup.factorId, code); totpSetup = null }
                    }
                }
            }
        }
    }

    @Composable
    fun finalProofStep(snapshot: AccountSecurityState) {
        StepTitle("Verify your sign-in method")
        if (snapshot.setupProfile == SecurityProfile.Passkeys) {
            StepButton("Verify with a passkey", !busy && passkeysConfigured) {
                run { if (!controller.complete(passkeys, "", "")) message = "Verification cancelled." }
            }
        } else {
            Text("If you just used the current authenticator code, wait for a new one.")
            PasswordProofStep(busy) { password, code -> run { controller.complete(passkeys, password, code) } }
        }
    }

    fun exit() {
        if (editingReview && retry == null && !busy && !exporting) {
            editingReview = false
            message = null
        } else if (joining && !busy && !exporting) {
            joining = false
            invitation = null
            message = null
        } else if (entry != Entry.Choose && state == null && !busy && !exporting) {
            restart(Entry.Choose)
            invitation = null
        } else if (leavingFailed && !busy && !exporting) {
            controller.clear()
            recovery = null
            totpSetup = null
            close()
        } else run {
            try {
                if (existingAccountOnly) controller.signOutWorkspace() else controller.leave()
                recovery = null
                totpSetup = null
                close()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                leavingFailed = true
                message = "The service did not confirm sign-out or cancellation. You can close this screen and clear this device’s session."
            }
        }
    }

    BackHandler { exit() }
    WhumpoEntryTheme(darkTheme = true) {
        androidx.compose.material3.Surface {
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Whumpo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                val pending = retry
                if (pending != null) {
                    if (pending == CoreAccountSecurityController.Retry.ReservedStart) {
                        StepTitle("Continue reserved setup")
                        Text("Retry this request or use your saved continuation key and original invitation token to resume later.")
                    } else {
                        StepTitle("Confirm the last step")
                        Text("This step is still incomplete here. Retry to check for the service’s confirmation.")
                    }
                    StepButton("Retry last step", !busy, ::retryStep)
                    when (pending) {
                        CoreAccountSecurityController.Retry.Secure -> WhumpoQuietButton(onClick = {
                            restart(Entry.Recovery, "Use the replacement kit or a replacement code you just saved to resume recovery.")
                        }, enabled = !busy) { Text("Resume with replacement material") }
                        CoreAccountSecurityController.Retry.Complete -> WhumpoQuietButton(onClick = {
                            controller.checkCompletionThroughSignIn()
                            clearStepSecrets()
                            entry = Entry.Choose
                            checkingCompletion = true
                            message = "Completion is unconfirmed. Sign in with the credentials for the same account."
                        }, enabled = !busy) { Text("Return to sign-in") }
                        else -> Unit
                    }
                } else when (state?.purpose) {
                    null -> when (entry) {
                        Entry.Choose -> {
                            if (existingAccountOnly) {
                                StepTitle("Sign in to Core")
                                if (!passkeysConfigured) Text("Passkey sign-in requires a verified Android association.")
                            } else if (checkingCompletion) {
                                StepTitle("Check account setup")
                            } else {
                                StepTitle("Choose how to secure your account")
                                Text("Both setup methods give you the same workspace access.")
                                StepButton("Set up two passkeys", !busy && passkeysConfigured) {
                                    profile = SecurityProfile.Passkeys
                                    entry = Entry.Enrollment
                                }
                                Text("Recommended. Add a backup passkey and save a recovery kit. Either passkey can sign you in.")
                                if (!passkeysConfigured) Text("Set a relying-party host for this local build to enable passkeys.")
                                StepButton("Set up password and authenticator", !busy) {
                                    profile = SecurityProfile.PasswordTotp
                                    entry = Entry.Enrollment
                                }
                                Text("Use a password and authenticator app, then save ten recovery codes.")
                            }
                            WhumpoQuietButton(onClick = {
                                run { if (!controller.loginPasskey(passkeys)) message = "Passkey sign-in cancelled." }
                            }, enabled = !busy && passkeysConfigured) { Text("Sign in with a passkey") }
                            WhumpoQuietButton(onClick = { entry = Entry.Password }, enabled = !busy) { Text("Sign in with password and authenticator") }
                            if (checkingCompletion) {
                                WhumpoQuietButton(onClick = { restart(Entry.Choose) }, enabled = !busy) { Text("Back to account security") }
                            } else if (!existingAccountOnly) {
                                WhumpoQuietButton(onClick = { entry = Entry.Recovery }, enabled = !busy) { Text("Recover an account") }
                                WhumpoQuietButton(onClick = { entry = Entry.Reserved }, enabled = !busy) { Text("Use a reserved invitation") }
                                WhumpoQuietButton(onClick = { entry = Entry.ResumeReserved }, enabled = !busy) { Text("Resume reserved setup") }
                            }
                        }
                        Entry.Enrollment -> AdmissionStep(profile, invitation?.preview, busy,
                            preview = { code ->
                                run { invitation = ReviewedInvitation(code, controller.previewInvitation(code)) }
                            },
                            accept = {
                                val reviewed = checkNotNull(invitation)
                                run {
                                    controller.enroll(profile, reviewed.admission())
                                    invitation = null
                                }
                            },
                            bootstrap = { token -> run { controller.enroll(profile, SecurityAdmission.Bootstrap(token)) } },
                            reset = { invitation = null },
                        )
                        Entry.Password -> {
                            StepTitle("Sign in with a password")
                            PasswordStep("Continue", busy) { username, password ->
                                run {
                                    controller.loginPassword(username, password)
                                    entry = Entry.Totp
                                }
                            }
                        }
                        Entry.Totp -> {
                            StepTitle("Enter your authenticator code")
                            TotpStep("Sign in", busy) { code -> run { controller.loginTotp(code) } }
                        }
                        Entry.Reserved -> if (reservedStart == null) {
                            ReservedEnrollmentStep(busy, passkeysConfigured) { selectedProfile, token ->
                                run { reservedStart = controller.prepareReservedSetup(selectedProfile, token) }
                            }
                        } else {
                            SaveReservedKeyStep(reservedKeySaved, busy || exporting, save = {
                                exporting = true
                                message = null
                                try {
                                    exportReservedKey.launch("Whumpo setup continuation key.txt")
                                } catch (_: Exception) {
                                    exporting = false
                                    message = "The document picker could not open."
                                }
                            }, start = {
                                val draft = checkNotNull(reservedStart)
                                run {
                                    reservedStart = null
                                    reservedKeySaved = false
                                    controller.enrollReserved(draft)
                                }
                            })
                        }
                        Entry.ResumeReserved -> ResumeReservedStep(busy) { token, continuationKey ->
                            run { controller.resumeReserved(token, continuationKey) }
                        }
                        Entry.Recovery -> SavedRecoveryStep(busy) { locator, proof ->
                            run { controller.startRecovery(locator, proof) }
                        }
                    }

                    SecurityPurpose.Enrollment -> when {
                        !state.replacementMethodsReady() -> methodsStep(state)
                        offer?.saved != true || offerExpired -> materialStep(checkNotNull(state.setupProfile), replacement = false)
                        else -> finalProofStep(state)
                    }

                    SecurityPurpose.Account -> {
                        if (joining) {
                            JoinInvitationStep(invitation?.preview, busy,
                                preview = { code ->
                                    run { invitation = ReviewedInvitation(code, controller.previewInvitation(code)) }
                                },
                                accept = {
                                    val reviewed = checkNotNull(invitation)
                                    run {
                                        controller.acceptInvitation(reviewed.admission())
                                        invitation = null
                                        organizations = null
                                        joining = false
                                    }
                                },
                                reset = { invitation = null },
                            )
                        } else {
                            StepTitle("Choose an organization")
                            if (state.workspaceAccess != SecurityAccess.Available) {
                                Text("This account does not currently have workspace access.")
                            } else {
                                val page = organizations
                                if (page == null) {
                                    StepButton("Load organizations", !busy) { run { organizations = controller.organizations() } }
                                } else {
                                    if (page.items.isEmpty()) Text("No current organization memberships.")
                                    for (membership in page.items) {
                                        StepButton("${membership.orgId} · ${membership.role}", !busy) {
                                            run { controller.chooseOrganization(membership) }
                                        }
                                    }
                                    page.nextCursor?.let { cursor ->
                                        WhumpoQuietButton(onClick = { run { organizations = controller.organizations(cursor) } }, enabled = !busy) {
                                            Text("More organizations")
                                        }
                                    }
                                    WhumpoQuietButton(onClick = { run { organizations = controller.organizations() } }, enabled = !busy) {
                                        Text("First page")
                                    }
                                }
                            }
                            if (!existingAccountOnly) {
                                WhumpoQuietButton(onClick = { joining = true }, enabled = !busy) {
                                    Text("Join with a person invitation")
                                }
                            }
                        }
                    }

                    SecurityPurpose.Recovery -> {
                        val review = reviewedRecovery
                        when {
                            state.phase == SecurityPhase.Recovering -> {
                                if (state.replacementMethodsReady()) finalProofStep(state) else methodsStep(state)
                            }
                            review == null || editingReview -> key(state.attemptId, review?.id) {
                                ReviewRecoveryStep(state, review, busy, passkeysConfigured) { mode, lost, selectedProfile ->
                                    run {
                                        controller.reviewRecovery(mode, lost, selectedProfile)
                                        editingReview = false
                                    }
                                }
                            }
                            offer?.saved != true || offerExpired -> {
                                ReviewedRecoveryStep(state, review)
                                materialStep(review.completionProfile, replacement = true)
                            }
                            else -> {
                                ReviewedRecoveryStep(state, review)
                                Text("Securing the account replaces your old recovery material and removes the sign-in methods marked lost. You’ll still need to verify your sign-in method.")
                                StepButton("Secure account", !busy) { run { controller.secureRecovery() } }
                            }
                        }
                        if (review != null && !editingReview && state.phase != SecurityPhase.Recovering) {
                            WhumpoQuietButton(onClick = {
                                run {
                                    controller.refreshRecoveryReview()
                                    recovery = null
                                    written = false
                                    editingReview = true
                                }
                            }, enabled = !busy && !exporting) { Text("Edit recovery choices") }
                        }
                    }
                }
                if (pending == CoreAccountSecurityController.Retry.ReservedStart ||
                    (state?.purpose == SecurityPurpose.Enrollment && state.attemptId != null)) {
                    WhumpoQuietButton(onClick = {
                        restart(Entry.Choose, "Resume with your saved continuation key and original invitation token.")
                    }, enabled = !busy && !exporting) { Text("Pause reserved setup") }
                }
                message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                if (busy) Text("Working…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                WhumpoQuietButton(onClick = ::exit, enabled = !busy && !exporting) {
                    Text(when {
                        leavingFailed -> "Close and clear local session"
                        editingReview && retry == null -> "Back"
                        state?.purpose == SecurityPurpose.Enrollment -> "Cancel setup"
                        state?.purpose == SecurityPurpose.Recovery -> "Close recovery"
                        joining -> "Back to organizations"
                        state?.purpose == SecurityPurpose.Account -> "Sign out"
                        entry != Entry.Choose -> "Back"
                        else -> "Exit account security"
                    })
                }
            }
        }
    }
}

private class ReviewedInvitation(private val code: String, val preview: SecurityInvitationPreview) {
    fun admission() = SecurityAdmission.PersonInvitation(code, preview.invitationId, preview.orgId, preview.role)
}

@Composable
private fun JoinInvitationStep(
    reviewed: SecurityInvitationPreview?,
    busy: Boolean,
    preview: (String) -> Unit,
    accept: () -> Unit,
    reset: () -> Unit,
) {
    StepTitle("Join an organization")
    if (reviewed == null) {
        var code by remember { mutableStateOf("") }
        SecretField("Invitation code", code, { code = it }, busy)
        StepButton("Review invitation", !busy && code.isNotBlank()) {
            val secret = code.trim()
            code = ""
            preview(secret)
        }
    } else {
        InvitationConsent(reviewed, "Accept invitation", busy, accept, reset)
    }
}

@Composable
private fun InvitationConsent(
    preview: SecurityInvitationPreview,
    action: String,
    busy: Boolean,
    accept: () -> Unit,
    reset: () -> Unit,
) {
    Text("Join ${preview.organizationName ?: preview.orgId} as ${preview.role.name}.")
    StepButton(action, !busy, accept)
    WhumpoQuietButton(onClick = reset, enabled = !busy) { Text("Use a different invitation") }
}

@Composable
private fun AdmissionStep(
    profile: SecurityProfile,
    reviewed: SecurityInvitationPreview?,
    busy: Boolean,
    preview: (String) -> Unit,
    accept: () -> Unit,
    bootstrap: (String) -> Unit,
    reset: () -> Unit,
) {
    var invitation by remember { mutableStateOf(true) }
    StepTitle(if (profile == SecurityProfile.Passkeys) "Set up two passkeys" else "Set up password and authenticator")
    if (reviewed != null) {
        InvitationConsent(reviewed, "Accept invitation and continue", busy, accept, reset)
        return
    }
    Text("Use an invitation or a bootstrap token from your local test operator.")
    WhumpoQuietButton(onClick = { invitation = !invitation }, enabled = !busy) {
        Text(if (invitation) "Use operator bootstrap token" else "Use a person invitation")
    }
    key(invitation) {
        var code by remember { mutableStateOf("") }
        SecretField(if (invitation) "Invitation code" else "Bootstrap token", code, { code = it }, busy)
        StepButton(if (invitation) "Review invitation" else "Start setup", !busy && code.isNotBlank()) {
            val secret = code.trim()
            code = ""
            if (invitation) preview(secret) else bootstrap(secret)
        }
    }
}

@Composable
private fun PasswordStep(action: String, busy: Boolean, submit: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, enabled = !busy, singleLine = true)
    SecretField("Password", password, { password = it }, busy)
    StepButton(action, !busy && username.isNotBlank() && password.isNotEmpty()) {
        val secret = password
        password = ""
        submit(username.trim(), secret)
    }
}

@Composable
private fun TotpStep(action: String, busy: Boolean, canSubmit: Boolean = true, submit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    SecretField("Authenticator code", code, { code = it.filter { char -> char in '0'..'9' }.take(6) }, busy, numeric = true)
    StepButton(action, !busy && canSubmit && code.length == 6) {
        val proof = code
        code = ""
        submit(proof)
    }
}

@Composable
private fun PasswordProofStep(busy: Boolean, submit: (String, String) -> Unit) {
    var password by remember { mutableStateOf("") }
    SecretField("Password", password, { password = it }, busy)
    TotpStep("Verify and finish", busy, canSubmit = password.isNotEmpty()) { code ->
        val secret = password
        password = ""
        submit(secret, code)
    }
}

@Composable
private fun SecretField(label: String, value: String, change: (String) -> Unit, busy: Boolean, numeric: Boolean = false) {
    OutlinedTextField(
        value, change, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun StepTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun StepButton(text: String, enabled: Boolean, click: () -> Unit) {
    WhumpoPrimaryButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun ReservedEnrollmentStep(busy: Boolean, passkeysConfigured: Boolean, submit: (SecurityProfile, String) -> Unit) {
    var profile by remember {
        mutableStateOf(if (passkeysConfigured) SecurityProfile.Passkeys else SecurityProfile.PasswordTotp)
    }
    var token by remember { mutableStateOf("") }
    StepTitle("Use a reserved invitation")
    Text("Enter the token provided by your operator for your reserved account and organization membership.")
    SecretField("Reserved invitation token", token, { token = it.take(80) }, busy)
    ProfileChoices(profile, busy, passkeysConfigured) { profile = it }
    StepButton("Continue", !busy && token.trim().length == 43) {
        val secret = token.trim()
        token = ""
        submit(profile, secret)
    }
}

@Composable
private fun SaveReservedKeyStep(saved: Boolean, busy: Boolean, save: () -> Unit, start: () -> Unit) {
    StepTitle("Save your continuation key")
    Text("Choose a private location for the key. To resume setup, you’ll need this key and your original invitation token.")
    if (saved) {
        Text("Continuation key saved.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        StepButton("Start reserved setup", !busy, start)
    } else {
        StepButton("Save continuation key", !busy, save)
    }
}

@Composable
private fun ResumeReservedStep(busy: Boolean, submit: (String, String) -> Unit) {
    var token by remember { mutableStateOf("") }
    var continuationKey by remember { mutableStateOf("") }
    StepTitle("Resume reserved setup")
    Text("Open your saved continuation key file and enter the key and your original invitation token.")
    SecretField("Reserved invitation token", token, { token = it.take(80) }, busy)
    SecretField("Continuation key", continuationKey, { continuationKey = it.take(80) }, busy)
    StepButton("Continue setup", !busy && token.trim().length == 43 && continuationKey.trim().length == 36) {
        val originalToken = token.trim()
        val originalKey = continuationKey.trim()
        token = ""
        continuationKey = ""
        submit(originalToken, originalKey)
    }
}

@Composable
private fun SavedRecoveryStep(busy: Boolean, submit: (String, SavedRecoveryProof) -> Unit) {
    var kit by remember { mutableStateOf(true) }
    var locator by remember { mutableStateOf("") }
    StepTitle("Recover an account")
    Text("Open your saved recovery file and enter the recovery locator and either the kit key or one unused code.")
    Column(Modifier.selectableGroup()) {
        SecurityChoice("Recovery kit", kit, !busy) { kit = true }
        SecurityChoice("Recovery codes", !kit, !busy) { kit = false }
    }
    SecretField("Recovery locator", locator, { locator = it.take(160) }, busy)
    key(kit) {
        var material by remember { mutableStateOf("") }
        SecretField(if (kit) "Recovery kit key" else "Unused recovery code", material, { material = it.take(160) }, busy)
        StepButton("Continue recovery", !busy && locator.isNotBlank() && material.isNotBlank()) {
            val proof = if (kit) SavedRecoveryProof.Kit(material.trim()) else SavedRecoveryProof.Code(material.trim())
            val reference = locator.trim()
            locator = ""
            material = ""
            submit(reference, proof)
        }
    }
}

@Composable
private fun ReviewRecoveryStep(
    state: AccountSecurityState,
    previous: RecoveryReview?,
    busy: Boolean,
    passkeysConfigured: Boolean,
    submit: (RecoveryLossMode, Set<String>, SecurityProfile) -> Unit,
) {
    val active = state.methods.filter { it.state == SecurityMethodStatus.Active }
    var lost by remember(active.map { it.id }) { mutableStateOf(previous?.lostIds?.toSet().orEmpty()) }
    var profile by remember {
        mutableStateOf(previous?.completionProfile?.takeIf { it != SecurityProfile.Passkeys || passkeysConfigured }
            ?: if (passkeysConfigured) SecurityProfile.Passkeys else SecurityProfile.PasswordTotp)
    }
    StepTitle("Which sign-in methods did you lose?")
    Text("Select the methods you can no longer use. You’ll review the changes before they take effect.")
    if (active.isEmpty()) Text("There are no current sign-in methods to mark lost.")
    active.forEachIndexed { index, method ->
        key(method.id) {
            val checked = method.id in lost
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = checked, enabled = !busy, role = Role.Checkbox,
                    onValueChange = { selected -> lost = if (selected) lost + method.id else lost - method.id },
                ).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked, onCheckedChange = null, enabled = !busy)
                Text(methodLabel(method.kind, method.label, index))
            }
        }
    }
    StepTitle("Choose how to sign in after recovery")
    ProfileChoices(profile, busy, passkeysConfigured) { profile = it }
    val mode = when {
        lost.size == active.size -> RecoveryLossMode.All
        lost.isEmpty() -> RecoveryLossMode.None
        else -> RecoveryLossMode.Some
    }
    val needsRetainedPasskey = mode != RecoveryLossMode.All && profile == SecurityProfile.Passkeys &&
        active.none { it.kind == SecurityMethodKind.Passkey && it.id !in lost }
    if (needsRetainedPasskey) {
        Text("Keep at least one passkey, mark all methods lost, or choose password and authenticator.")
    }
    StepButton("Review account changes", !busy && !needsRetainedPasskey) { submit(mode, lost, profile) }
}

@Composable
private fun ProfileChoices(value: SecurityProfile, busy: Boolean, passkeysConfigured: Boolean, change: (SecurityProfile) -> Unit) {
    Column(Modifier.selectableGroup()) {
        SecurityChoice("Two passkeys and a recovery kit", value == SecurityProfile.Passkeys, !busy && passkeysConfigured) {
            change(SecurityProfile.Passkeys)
        }
        SecurityChoice("Password, authenticator and ten recovery codes", value == SecurityProfile.PasswordTotp, !busy) {
            change(SecurityProfile.PasswordTotp)
        }
    }
    Text("Both methods give you the same workspace access.")
}

@Composable
private fun SecurityChoice(label: String, selected: Boolean, enabled: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = click)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun ReviewedRecoveryStep(state: AccountSecurityState, review: RecoveryReview) {
    StepTitle("Review account changes")
    val labels = state.methods.associate { it.id to it.label }
    if (review.lostIds.isEmpty()) Text("No sign-in methods will be removed.")
    review.inventory.forEachIndexed { index, method ->
        val name = methodLabel(method.kind, labels[method.id], index)
        Text(if (method.id in review.lostIds) "Remove $name" else "Keep $name")
    }
}

private fun methodLabel(kind: SecurityMethodKind, label: String?, index: Int): String {
    val type = when (kind) {
        SecurityMethodKind.Passkey -> "Passkey"
        SecurityMethodKind.Password -> "Password"
        SecurityMethodKind.Totp -> "Authenticator"
    }
    return label?.takeIf(String::isNotBlank)?.let { "$type · $it" } ?: "$type ${index + 1}"
}

@Composable
private fun RecoveryMaterialStep(
    label: String,
    replacement: Boolean,
    prepared: Boolean,
    written: Boolean,
    busy: Boolean,
    prepare: () -> Unit,
    saveFile: () -> Unit,
    confirm: () -> Unit,
) {
    val material = if (replacement) "replacement $label" else label
    StepTitle("Save your $material")
    Text("Choose a trusted location that only you can access. You’ll need this file if you lose your sign-in methods.")
    when {
        !prepared -> StepButton("Prepare $material", !busy, prepare)
        !written -> StepButton("Save document", !busy, saveFile)
        else -> StepButton("I saved my $material", !busy, confirm)
    }
}
