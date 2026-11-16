package com.whumpo.android.core

import android.content.ContentResolver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
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
import com.whumpo.core.api.SecurityInvitationPreview
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityAdmission
import com.whumpo.core.api.SecurityMembershipPage
import com.whumpo.core.api.SecurityMethodKind
import com.whumpo.core.api.SecurityMethodStatus
import com.whumpo.core.api.SecurityProfile
import com.whumpo.core.api.SecurityPurpose
import com.whumpo.core.auth.AccountPasskeys
import com.whumpo.core.auth.CoreAccountSecurityController
import com.whumpo.core.auth.EnrollmentAcknowledgementLost
import com.whumpo.core.auth.PendingRecoveryDocument
import com.whumpo.core.auth.PendingTotp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Entry { Choose, Enrollment, Password, Totp }

@Composable
internal fun AccountSecurityScreen(
    controller: CoreAccountSecurityController,
    state: AccountSecurityState?,
    passkeys: AccountPasskeys,
    passkeysConfigured: Boolean,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
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

    fun exit() {
        if (joining && !busy && !exporting) {
            joining = false
            invitation = null
            message = null
        } else if (entry != Entry.Choose && state == null && !busy && !exporting) {
            controller.clear()
            invitation = null
            entry = Entry.Choose
            message = null
        } else if (leavingFailed && !busy && !exporting) {
            controller.clear()
            recovery = null
            totpSetup = null
            close()
        } else run {
            try {
                controller.leave()
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
                when (state?.purpose) {
                    null -> when (entry) {
                        Entry.Choose -> {
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
                            WhumpoQuietButton(onClick = {
                                run { if (!controller.loginPasskey(passkeys)) message = "Passkey sign-in cancelled." }
                            }, enabled = !busy && passkeysConfigured) { Text("Sign in with a passkey") }
                            WhumpoQuietButton(onClick = { entry = Entry.Password }, enabled = !busy) { Text("Sign in with password and authenticator") }
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
                    }

                    SecurityPurpose.Enrollment -> {
                        val passkeySetup = state.setupProfile == SecurityProfile.Passkeys
                        val stagedPasskeys = state.methods.count { it.kind == SecurityMethodKind.Passkey && it.state == SecurityMethodStatus.Staged && it.verified }
                        val hasPassword = state.methods.any { it.kind == SecurityMethodKind.Password && it.state == SecurityMethodStatus.Staged && it.verified }
                        val hasTotp = state.methods.any { it.kind == SecurityMethodKind.Totp && it.state == SecurityMethodStatus.Staged && it.verified }
                        when {
                            passkeySetup && stagedPasskeys < 2 -> {
                                val backup = stagedPasskeys == 1
                                StepTitle(if (backup) "Add a backup passkey" else "Create your first passkey")
                                Text(if (backup) "Choose a second credential, ideally on another device or security key." else "Your device will ask you to verify your identity.")
                                StepButton(if (backup) "Add backup passkey" else "Create passkey", !busy) {
                                    run {
                                        val label = if (backup) "Backup passkey" else "First passkey"
                                        if (!controller.registerPasskey(label, passkeys)) message = "Passkey setup cancelled."
                                    }
                                }
                            }
                            !passkeySetup && !hasPassword -> {
                                StepTitle("Set your password")
                                PasswordStep("Set password", busy) { username, password ->
                                    run { controller.stagePassword(username, password) }
                                }
                            }
                            !passkeySetup && !hasTotp -> {
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
                            state.recovery.offer?.saved != true -> {
                                val label = if (passkeySetup) "recovery kit" else "ten recovery codes"
                                StepTitle("Save your $label")
                                Text("Choose a trusted location that only you can access. You’ll need this file if you lose your sign-in methods.")
                                val document = recovery
                                when {
                                    document == null -> StepButton("Prepare $label", !busy) {
                                        run { recovery = controller.prepareRecovery() }
                                    }
                                    !written -> StepButton("Save document", !busy && !exporting) {
                                        exporting = true
                                        try {
                                            export.launch(if (passkeySetup) "Whumpo recovery kit.txt" else "Whumpo recovery codes.txt")
                                        } catch (_: Exception) {
                                            exporting = false
                                            message = "The document picker could not open."
                                        }
                                    }
                                    else -> StepButton("I saved my $label", !busy) {
                                        run {
                                            controller.confirmSaved(document.offerId, document.generation)
                                            recovery = null
                                            written = false
                                        }
                                    }
                                }
                            }
                            else -> {
                                StepTitle("Verify your sign-in method")
                                if (passkeySetup) {
                                    StepButton("Verify with a passkey", !busy) {
                                        run { if (!controller.complete(passkeys, "", "")) message = "Verification cancelled." }
                                    }
                                } else {
                                    PasswordProofStep(busy) { password, code ->
                                        run { controller.complete(passkeys, password, code) }
                                    }
                                }
                            }
                        }
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
                            WhumpoQuietButton(onClick = { joining = true }, enabled = !busy) {
                                Text("Join with a person invitation")
                            }
                        }
                    }

                    SecurityPurpose.Recovery -> {
                        StepTitle("Account recovery")
                        Text("This grant has restricted access.")
                    }
                }
                message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                if (busy) Text("Working…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                WhumpoQuietButton(onClick = ::exit, enabled = !busy && !exporting) {
                    Text(when {
                        leavingFailed -> "Close and clear local session"
                        state?.purpose == SecurityPurpose.Enrollment -> "Cancel setup"
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
