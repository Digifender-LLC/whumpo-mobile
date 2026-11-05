package com.whumpo.android.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.state.WhumpoMascot

/** Placeholder destination: later packages replace it with the real screen. */
@Composable
fun PlaceholderScreen(destination: TopDestination, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            destination.label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        WhumpoMascot(size = 80.dp)
        Text(
            "${destination.label} is coming in a later release.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The empty detail pane shown beside a list on expanded widths. */
@Composable
fun DetailPlaceholder(modifier: Modifier = Modifier, hint: String = "Pick something from the list to see it here.") {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        WhumpoMascot()
        Text(
            hint,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Default shell content: the placeholder as the list pane, with a detail pane on expanded widths. */
@Composable
fun PlaceholderShellContent(destination: TopDestination, width: WidthClass) {
    AdaptivePanes(
        width = width,
        list = { PlaceholderScreen(destination, Modifier.testTag("screen-${destination.route}")) },
        detail = { DetailPlaceholder() },
    )
}
