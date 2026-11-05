package com.whumpo.android.ui.state

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoTheme

/**
 * The cloud elephant as a simplified vector, drawn on a 100 x 100 grid from the brand six.
 * Decorative: the sentence next to it carries the meaning, so TalkBack skips it.
 */
@Composable
fun WhumpoMascot(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val brand = WhumpoTheme.colors
    val cloud = lerp(brand.skyBlue, brand.cloudWhite, 0.75f)
    val body = lerp(brand.skyBlue, brand.cloudWhite, 0.55f)
    val ear = lerp(brand.skyBlue, brand.cloudWhite, 0.25f)
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val u = this.size.minDimension / 100f
        fun disc(x: Float, y: Float, r: Float, color: Color) = drawCircle(color, r * u, Offset(x * u, y * u))
        // Cloud seat.
        disc(24f, 86f, 11f, cloud); disc(44f, 84f, 14f, cloud); disc(64f, 84f, 14f, cloud); disc(80f, 87f, 10f, cloud)
        drawRect(cloud, Offset(24f * u, 86f * u), Size(56f * u, 11f * u))
        // Ears, head, trunk.
        disc(28f, 48f, 17f, ear); disc(74f, 48f, 17f, ear); disc(51f, 52f, 26f, body)
        val trunk = Path().apply { moveTo(44f * u, 64f * u); quadraticTo(22f * u, 68f * u, 22f * u, 44f * u) }
        drawPath(trunk, body, style = Stroke(width = 11f * u, cap = StrokeCap.Round))
        // Smiling eyes and cheeks.
        val eye = Stroke(width = 3f * u, cap = StrokeCap.Round)
        drawArc(brand.deepSky, 200f, 140f, false, Offset(42f * u, 43f * u), Size(10f * u, 10f * u), style = eye)
        drawArc(brand.deepSky, 200f, 140f, false, Offset(60f * u, 43f * u), Size(10f * u, 10f * u), style = eye)
        disc(42f, 58f, 3.5f, brand.cloudCoral.copy(alpha = 0.55f))
        disc(68f, 58f, 3.5f, brand.cloudCoral.copy(alpha = 0.55f))
        // Four-point star.
        val star = Path().apply {
            moveTo(14f * u, 4f * u); quadraticTo(15f * u, 13f * u, 24f * u, 14f * u)
            quadraticTo(15f * u, 15f * u, 14f * u, 24f * u); quadraticTo(13f * u, 15f * u, 4f * u, 14f * u)
            quadraticTo(13f * u, 13f * u, 14f * u, 4f * u); close()
        }
        drawPath(star, brand.sunshine)
    }
}
