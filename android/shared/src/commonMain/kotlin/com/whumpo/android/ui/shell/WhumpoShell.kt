package com.whumpo.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.state.OfflineBanner
import com.whumpo.android.ui.state.WhumpoIcons

/** Top-level destinations from the IA. Chat is first: it is the primary owner surface. */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    Chat("chat", "Chat", WhumpoIcons.Chat),
    Projects("projects", "Projects", WhumpoIcons.Projects),
    Activity("activity", "Activity", WhumpoIcons.Activity),
    Settings("settings", "Settings", WhumpoIcons.Settings),
}

/** Lets a screen switch destination, for example an empty state's "Go to Chat". Provided by [WhumpoShell]. */
val LocalNavigate = staticCompositionLocalOf<(TopDestination) -> Unit> { {} }

/** Material 3 window width classes (docs/android/app-ux.md, "Adaptive layouts"). */
enum class WidthClass {
    Compact, Medium, Expanded;

    companion object {
        fun of(width: Dp) = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            else -> Expanded
        }
    }
}

/**
 * The adaptive app shell: bottom bar on compact, navigation rail on medium and expanded. [content] draws the
 * selected destination and is told the width class so it can show two panes on expanded (see [AdaptivePanes]).
 *
 * Back follows the IA: from any destination but Chat it returns to Chat (with the system predictive-back
 * preview); from Chat the system leaves the app. Selection survives rotation and resize, and each
 * destination keeps its own saved state while another is shown.
 *
 * shortcut: no foldable hinge handling and no permanent drawer at 1200dp or more; add both with WindowInfoTracker
 * when a foldable or desktop pass needs them.
 */
@Composable
fun WhumpoShell(
    modifier: Modifier = Modifier,
    /** A destination another part of the app asks for (a deep link). Selecting it is the shell's job; clearing it is the caller's. */
    requested: TopDestination? = null,
    offline: Boolean = false,
    offlineMessage: String = "You're offline. We'll be ready when you're back.",
    content: @Composable (TopDestination, WidthClass) -> Unit = { destination, width ->
        PlaceholderShellContent(destination, width)
    },
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = TopDestination.entries[selectedIndex]
    val stateHolder = rememberSaveableStateHolder()
    LaunchedEffect(requested) { if (requested != null) selectedIndex = requested.ordinal }

    val backProgress = rememberBackProgress(enabled = selected != TopDestination.Chat) { selectedIndex = 0 }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val width = WidthClass.of(maxWidth)
        val body: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize().graphicsLayer {
                    // Only opacity and scale move, driven by the back gesture.
                    val scale = 1f - 0.05f * backProgress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.3f * backProgress
                },
            ) {
                if (offline) OfflineBanner(offlineMessage)
                Box(Modifier.weight(1f)) {
                    stateHolder.SaveableStateProvider(selected.route) {
                        CompositionLocalProvider(LocalNavigate provides { selectedIndex = it.ordinal }) { content(selected, width) }
                    }
                }
            }
        }
        if (width == WidthClass.Compact) {
            Scaffold(
                bottomBar = {
                    NavigationBar(Modifier.testTag("nav-bar")) {
                        TopDestination.entries.forEachIndexed { index, destination ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav-${destination.route}"),
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index },
                                // The visible label names the tab for TalkBack; the icon is decorative.
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding -> Box(Modifier.padding(padding).consumeWindowInsets(padding)) { body() } }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.testTag("nav-rail")) {
                    TopDestination.entries.forEachIndexed { index, destination ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("nav-${destination.route}"),
                            selected = index == selectedIndex,
                            onClick = { selectedIndex = index },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom,
                            ),
                        ),
                ) { body() }
            }
        }
    }
}

/** List then detail: one pane below Expanded, two side by side (40/60) on Expanded. */
@Composable
fun AdaptivePanes(
    width: WidthClass,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (width == WidthClass.Expanded) {
        Row(modifier.fillMaxSize()) {
            Box(Modifier.weight(0.4f).fillMaxHeight().testTag("pane-list")) { list() }
            Box(Modifier.background(MaterialTheme.colorScheme.outlineVariant).fillMaxHeight().padding(start = 1.dp))
            Box(Modifier.weight(0.6f).fillMaxHeight().testTag("pane-detail")) { detail() }
        }
    } else {
        Box(modifier.fillMaxSize().testTag("pane-list")) { list() }
    }
}
