package ch.nthnbch.kalendar.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import ch.nthnbch.kalendar.data.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow

object Widgets {

    /**
     * Bumped on every refresh. A widget that is still composed re-reads the clock when this changes,
     * which is how "now" moves forward without any data change.
     */
    val tick = MutableStateFlow(0L)

    suspend fun refresh(context: Context) {
        tick.value = System.currentTimeMillis()
        runCatching {
            AgendaWidget().updateAll(context)
            MonthWidget().updateAll(context)
        }
    }

    /** Rounded widget surface following the launcher's own corner radius where the system exposes it. */
    @Composable
    fun surface(): GlanceModifier {
        val base = GlanceModifier.background(GlanceTheme.colors.widgetBackground).appWidgetBackground()
        return if (Build.VERSION.SDK_INT >= 31) base.cornerRadius(android.R.dimen.system_app_widget_background_radius)
        else base.cornerRadius(20.dp)
    }

    /** A calendar color at low opacity: readable as a tint on both the light and the dark widget surface. */
    fun tint(color: Int, alpha: Float = 0.16f): ColorProvider = ColorProvider(Color(color).copy(alpha = alpha))

    fun solid(color: Int): ColorProvider = ColorProvider(Color(color))
}

/** The refresh button of the widgets: asks for a synchronisation and redraws right away. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SyncWorker.now(context)
        Widgets.refresh(context)
    }
}
