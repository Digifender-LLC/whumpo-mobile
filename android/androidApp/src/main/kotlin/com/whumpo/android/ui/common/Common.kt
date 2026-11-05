package com.whumpo.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.R
import com.whumpo.core.data.Availability
import com.whumpo.core.data.UnavailableReason

/** Screen title exposed to TalkBack as a heading. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

/** Vertically scrollable page so large font scales never clip content. */
@Composable
fun ScrollingPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Honest explanation of why remote content is not shown. Never fabricates data. */
@Composable
fun UnavailableNotice(reason: UnavailableReason, modifier: Modifier = Modifier) {
    val message = when (reason) {
        UnavailableReason.SIGN_IN_REQUIRED -> stringResource(R.string.unavailable_sign_in)
        UnavailableReason.SERVICE_NOT_CONNECTED -> stringResource(R.string.unavailable_not_connected)
    }
    Card(
        modifier = modifier.fillMaxWidth().testTag("unavailable-${reason.name.lowercase()}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Renders loading, unavailable or ready content for a remote-backed value. */
@Composable
fun <T> AvailabilityContent(state: Availability<T>?, ready: @Composable (T) -> Unit) {
    when (state) {
        null -> Text(
            text = stringResource(R.string.loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is Availability.Unavailable -> UnavailableNotice(state.reason)
        is Availability.Ready -> ready(state.value)
    }
}
