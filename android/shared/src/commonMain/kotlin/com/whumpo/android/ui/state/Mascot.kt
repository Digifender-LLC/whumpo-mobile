package com.whumpo.android.ui.state

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val rhino = PathParser().parsePathString(
    "M5 51 8 34q4-11 15-15l-1-9q7 0 12 10l4 2q7 4 9 11 8-3 13-9 0 9-5 15l4 2-2 9-12 5-10-3-7-7-8 10H7Z",
).toPath()
private val shoulder = PathParser().parsePathString(
    "M5 51 8 34q3-9 13-14l-3 11q-3 9 2 16l-5 8H7Z",
).toPath()
private val horn = PathParser().parsePathString("m47 33 13-9q0 9-5 15Z").toPath()
private val slate = Color(0xFF20373A)
private val teal = Color(0xFF70A69D)
private val ivory = Color(0xFFF4F4EC)

/** Decorative mark. The adjacent text supplies the accessible label. */
@Composable
fun WhumpoMascot(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        scale(this.size.minDimension / 64f, pivot = Offset.Zero) {
            drawPath(rhino, if (dark) ivory else slate)
            drawPath(shoulder, teal)
            drawPath(horn, teal)
            drawCircle(if (dark) slate else ivory, 1.6f, Offset(38f, 34f))
        }
    }
}
