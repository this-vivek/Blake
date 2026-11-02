package com.blake.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private val LOG_ACCENT = Color(0xFFE6C07B)
private val LOG_TEXT = Color(0xFFFFFFFF)

/** A one-tap "Log anything" button, drawn as frosted glass (a translucent sheen with a bright
 * rim; home-screen widgets can't blur what is behind them, so the wallpaper just shows through).
 * It opens the log sheet directly (QuickLogActivity), not the app's landing page. */
class LogAnythingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val open = actionStartActivity(
                Intent(context, QuickLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ImageProvider(R.drawable.widget_glass))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(open),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "+",
                    style = TextStyle(color = ColorProvider(LOG_ACCENT), fontSize = 26.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "Log anything",
                    style = TextStyle(color = ColorProvider(LOG_TEXT), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

class LogAnythingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LogAnythingWidget()
}
