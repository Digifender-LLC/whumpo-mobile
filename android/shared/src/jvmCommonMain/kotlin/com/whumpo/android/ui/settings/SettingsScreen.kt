package com.whumpo.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.common.AppIcons
import com.whumpo.android.ui.common.AppLinks
import com.whumpo.android.ui.common.PageTitle
import com.whumpo.android.ui.common.ScrollingColumn
import com.whumpo.android.ui.common.SectionHeading
import com.whumpo.android.ui.shell.AdaptivePanes
import com.whumpo.android.ui.shell.DetailPlaceholder
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.android.ui.theme.ThemePreference
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.core.environment.EnvironmentKind

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, width: WidthClass, links: AppLinks) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state, width, links,
        onTheme = viewModel::setTheme,
        onNotifications = viewModel::setNotifications,
        onRetryAccount = viewModel::retryAccount,
        onSignOut = viewModel::signOut,
    )
}

/** Account, appearance, notifications, security and help (opened in the browser), legal, about and sign-out. */
@Composable
fun SettingsContent(
    state: SettingsUi,
    width: WidthClass,
    links: AppLinks,
    onTheme: (ThemePreference) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onRetryAccount: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    AdaptivePanes(
        width = width,
        modifier = modifier,
        list = {
            ScrollingColumn(Modifier.testTag("screen-settings")) {
                PageTitle("Settings")

                SectionHeading("Account")
                if (state.signedIn) {
                    AccountBlock(state, onRetryAccount)
                } else {
                    Text("Not signed in.", style = MaterialTheme.typography.bodyLarge)
                }

                HorizontalDivider()
                SectionHeading("Appearance")
                Column(Modifier.selectableGroup()) {
                    ThemePreference.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = state.theme == option, role = Role.RadioButton, onClick = { onTheme(option) })
                                .testTag("theme-${option.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.theme == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                HorizontalDivider()
                SectionHeading("Notifications")
                NotificationsBlock(state, onNotifications)

                HorizontalDivider()
                SectionHeading("Security and help")
                Text(
                    "Passkeys, recovery and your account email are managed on the Whumpo website.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkRow("Security review and passkeys", links.security, "settings-link-security") { uri.openUri(it) }
                LinkRow("Account email", links.accountEmail, "settings-link-email") { uri.openUri(it) }
                LinkRow("Help and support", links.support, "settings-link-support") { uri.openUri(it) }

                HorizontalDivider()
                SectionHeading("Legal")
                links.legal.forEachIndexed { i, (label, url) -> LinkRow(label, url, "settings-link-legal-$i") { uri.openUri(it) } }

                HorizontalDivider()
                SectionHeading("About")
                Text(
                    text = when (state.environmentKind) {
                        EnvironmentKind.LOCAL -> "Local development (this device only): ${state.origin}"
                        EnvironmentKind.TEST -> "Whumpo test platform: ${state.origin}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("settings-environment"),
                )
                Text(
                    "App version ${state.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.signedIn) {
                    HorizontalDivider()
                    WhumpoSecondaryButton(
                        onClick = { confirming = true },
                        enabled = !state.signingOut,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("settings-sign-out"),
                    ) { Text(if (state.signingOut) "Signing out" else "Sign out") }
                }
            }
        },
        detail = { DetailPlaceholder(hint = "Settings are on the left. Security changes open in your browser.") },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out?") },
            text = { Text("Sign out of Whumpo on this phone? Unsent drafts will be removed.") },
            confirmButton = {
                WhumpoPrimaryButton(
                    onClick = {
                        confirming = false
                        onSignOut()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings-sign-out-confirm"),
                ) { Text("Sign out") }
            },
            dismissButton = {
                WhumpoQuietButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AccountBlock(state: SettingsUi, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("settings-account")) {
        when {
            state.accountLabel != null -> {
                Text(
                    state.accountLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "Signed in as ${state.accountLabel}" },
                )
                Text(
                    "Signed in on this phone. Your email is shown partly hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.accountUnavailable -> {
                Text("Signed in on this phone. We couldn't read your account details.", style = MaterialTheme.typography.bodyLarge)
                WhumpoQuietButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            }
            else -> Text("Signed in on this phone.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun NotificationsBlock(state: SettingsUi, onChange: (Boolean) -> Unit) {
    if (!state.pushAvailable) {
        Text(
            "Notifications aren't available in this version of the app yet. Open Activity to see what's new.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("settings-notifications-unavailable"),
        )
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = state.notificationsOn, role = Role.Switch, onClick = { onChange(!state.notificationsOn) })
            .semantics { stateDescription = if (state.notificationsOn) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Heads-up when work finishes or needs you", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = state.notificationsOn, onCheckedChange = null)
    }
    if (state.notificationsError) {
        Text(
            "Couldn't change notifications. Nothing was changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** A row that opens a web page. The icon and the words say it leaves the app. */
@Composable
private fun LinkRow(label: String, url: String, tag: String, open: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = { open(url) })
            .clearAndSetSemantics { contentDescription = "$label, opens in your browser" }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Icon(AppIcons.External, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}
