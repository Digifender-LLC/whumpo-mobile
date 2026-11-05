package com.whumpo.android.ui.state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Vector icons drawn in code so they live in commonMain. Tinted by `Icon`, so the colour here is irrelevant. */
internal object WhumpoIcons {
    private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private fun ImageVector.Builder.filled(path: String) {
        addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    }

    private fun ImageVector.Builder.stroked(path: String) {
        addPath(
            addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    val Chat = icon("chat") { filled("M6,4h12a2,2 0,0 1,2 2v9a2,2 0,0 1,-2 2H9l-5,4V6a2,2 0,0 1,2 -2z") }
    val Projects = icon("projects") { filled("M4,4h7v7H4z M13,4h7v7h-7z M4,13h7v7H4z M13,13h7v7h-7z") }
    val Activity = icon("activity") { stroked("M3,12h4l3,-7l4,14l3,-7h4") }
    val Settings = icon("settings") {
        stroked("M4,7h16 M4,17h16")
        filled("M6.5,7a2.5,2.5 0,1 0,5 0a2.5,2.5 0,1 0,-5 0z M12.5,17a2.5,2.5 0,1 0,5 0a2.5,2.5 0,1 0,-5 0z")
    }
    val Alert = icon("alert") { stroked("M12,3a9,9 0,1 0,0.01 0z M12,7.5v5 M12,16v0.5") }
    val Offline = icon("offline") { stroked("M12,3a9,9 0,1 0,0.01 0z M5.6,5.6L18.4,18.4") }
}
