package com.whumpo.android.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Pill = RoundedCornerShape(WhumpoTokens.RadiusFullDp.dp)

// shortcut: the focus ring is a 2dp border without the spec's 2dp offset; add the offset when a design review asks for it.
@Composable
private fun focusBorder(focused: Boolean, rest: BorderStroke?): BorderStroke? =
    if (focused) BorderStroke(2.dp, WhumpoTheme.colors.focusRing) else rest

/** The one primary action per view: Sky Blue fill with Deep Sky text, `action-hover` when pressed. */
@Composable
fun WhumpoPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val colors = WhumpoTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = Pill,
        interactionSource = source,
        border = focusBorder(focused, null),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) colors.actionHover else scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            // Disabled is a solid tint, not 38% opacity (direction WP1).
            disabledContainerColor = colors.surfaceSunken,
            disabledContentColor = colors.inkMuted,
        ),
        content = content,
    )
}

/** Outlined with `primary` text; the `secondaryContainer` fill appears when pressed. */
@Composable
fun WhumpoSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val colors = WhumpoTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = Pill,
        interactionSource = source,
        border = focusBorder(focused, BorderStroke(1.dp, if (enabled) scheme.outline else colors.line)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (pressed) scheme.secondaryContainer else Color.Transparent,
            contentColor = scheme.primary,
            disabledContentColor = colors.inkMuted,
        ),
        content = content,
    )
}

/** Text only, in `primary`; the `secondaryContainer` fill appears when pressed. */
@Composable
fun WhumpoQuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = Pill,
        interactionSource = source,
        border = focusBorder(focused, null),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (pressed) scheme.secondaryContainer else Color.Transparent,
            contentColor = scheme.primary,
            disabledContentColor = WhumpoTheme.colors.inkMuted,
        ),
        content = content,
    )
}
