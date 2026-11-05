package com.whumpo.android.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.android.ui.theme.WhumpoTokens

/** Status colour roles. Always shown with an icon and a word, never colour alone. */
enum class Tone { Ready, Progress, Neutral, Attention }

data class StatusChip(val label: String, val tone: Tone)

private data class ToneColors(val container: Color, val content: Color, val icon: ImageVector)

@Composable
private fun Tone.colors(): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val w = WhumpoTheme.colors
    return when (this) {
        Tone.Ready -> ToneColors(w.readySoft, w.readyInk, AppIcons.Check)
        Tone.Progress -> ToneColors(w.sparkSoft, w.sparkInk, AppIcons.Clock)
        Tone.Neutral -> ToneColors(scheme.surfaceVariant, scheme.onSurfaceVariant, AppIcons.Dot)
        Tone.Attention -> ToneColors(scheme.errorContainer, scheme.onErrorContainer, AppIcons.Alert)
    }
}

/** Icon, word and colour. The word is the accessible name. */
@Composable
fun StatusBadge(chip: StatusChip, modifier: Modifier = Modifier) {
    val c = chip.tone.colors()
    Row(
        modifier.background(c.container, RoundedCornerShape(WhumpoTokens.RadiusFullDp.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(c.icon, contentDescription = null, tint = c.content, modifier = Modifier.size(14.dp))
        Text(chip.label, style = MaterialTheme.typography.labelMedium, color = c.content)
    }
}

/** The web ServiceTile: an icon in a soft square, a plain name and a status. */
@Composable
fun ServiceTile(
    name: String,
    icon: ImageVector,
    status: StatusChip,
    modifier: Modifier = Modifier,
    detail: String? = null,
    description: String = listOfNotNull(name, status.label, detail).joinToString(", "),
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(WhumpoTokens.RadiusMdDp.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, WhumpoTheme.colors.line),
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 48.dp)
                .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(scheme.primaryContainer, RoundedCornerShape(WhumpoTokens.RadiusSmDp.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                StatusBadge(status)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Section heading that TalkBack lists as a heading. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

/** Screen title, a heading for TalkBack. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

/** Readable column width on medium and expanded panes. */
fun Modifier.readable(): Modifier = widthIn(max = 720.dp).fillMaxWidth()
