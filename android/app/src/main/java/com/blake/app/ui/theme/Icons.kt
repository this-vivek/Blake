package com.blake.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Thin-stroke (1.8dp) outline icons on a 24 viewport, tinted by the caller. Path data is the
// same as the Blake Design System mockups.
private fun outline(name: String, vararg paths: String, strokeWidth: Float = 1.8f): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { d ->
        builder.addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

object BlakeIcons {
    val Today = outline("today", "M20 12a8 8 0 1 1-16 0 8 8 0 1 1 16 0", "M12 7v5l3 2")
    val Timeline = outline("timeline", "M5 6h14M5 12h14M5 18h9")
    val Insights = outline("insights", "M4 19V9M10 19V5M16 19v-7M22 19H2")
    val Settings = outline(
        "settings",
        "M15 12a3 3 0 1 1-6 0 3 3 0 1 1 6 0",
        "M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1",
    )
    val Dumbbell = outline("dumbbell", "M2 9h3v6H2z", "M19 9h3v6h-3z", "M5 12h14", "M7 7v10", "M17 7v10", strokeWidth = 1.2f)
    val Plus = outline("plus", "M12 5v14M5 12h14", strokeWidth = 2f)
    val Close = outline("close", "M6 6l12 12M18 6L6 18")
    val Camera = outline("camera", "M4 8h3l2-2h6l2 2h3v11H4z", "M15.2 13a3.2 3.2 0 1 1-6.4 0 3.2 3.2 0 1 1 6.4 0")
    val Gallery = outline("gallery", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M4 17l5-4 4 3 3-2 4 3")
    val Send = outline("send", "M12 19V5M6 11l6-6 6 6", strokeWidth = 2f)
}
