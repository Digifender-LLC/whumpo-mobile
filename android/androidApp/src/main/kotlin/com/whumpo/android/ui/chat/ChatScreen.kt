package com.whumpo.android.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.core.auth.BrowserLauncher

/** The Android host for the shared Chat layout: it only supplies the ViewModel and the three platform hand-offs. */
@Composable
fun ChatScreen(viewModel: ChatViewModel, width: WidthClass, origin: String, browser: BrowserLauncher) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ChatLayout(
        screen = screen,
        width = width,
        actions = viewModel,
        // A Custom Tab shares the browser's session, which is how the contract authenticates a preview: the bearer is never
        // sent. Signed out of the browser, the site shows its own sign-in and returns to the allowlisted path.
        onOpenPreview = { preview -> if (!browser.open(origin + preview.path)) open(context, origin + preview.path) },
        onUpdateApp = { open(context, "market://details?id=${context.packageName}") },
        onHelp = { open(context, origin) },
    )
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No handler installed; nothing else to do.
    }
}
