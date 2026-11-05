package com.whumpo.android.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons for Projects, Activity and Settings, drawn in code like `WhumpoIcons`. Tinted by `Icon`. */
internal object AppIcons {
    private fun icon(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private const val CIRCLE = "M12,3a9,9 0,1 0,0.01 0z"

    val Check = icon("check", "$CIRCLE M8,12.5l3,3l5,-6")
    val Clock = icon("clock", "$CIRCLE M12,7v5l3,2")
    val Dot = icon("dot", CIRCLE)
    val Alert = icon("alert", "$CIRCLE M12,7.5v5 M12,16v0.5")
    val Globe = icon("globe", "$CIRCLE M3,12h18 M12,3c3,3 3,15 0,18 M12,3c-3,3 -3,15 0,18")
    val Database = icon("database", "M5,6c0,-1.5 3,-3 7,-3s7,1.5 7,3s-3,3 -7,3s-7,-1.5 -7,-3z M5,6v12c0,1.5 3,3 7,3s7,-1.5 7,-3V6 M5,12c0,1.5 3,3 7,3s7,-1.5 7,-3")
    val Photo = icon("photo", "M4,5h16v14H4z M4,16l5,-5l4,4l3,-3l4,4")
    val Back = icon("back", "M15,5l-7,7l7,7")
    val External = icon("external", "M14,4h6v6 M20,4L10,14 M18,14v6H4V6h6")
    val Bell = icon("bell", "M6,16V11a6,6 0,0 1,12 0v5l2,2H4z M10,21h4")
    val Add = icon("add", "M12,5v14 M5,12h14")
    val Project = icon("project", "M4,6h16v12H4z M4,10h16")
}
