package io.legado.app.receiver

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.legado.app.R
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReadRankWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateWidgets(context, manager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE) {
            updateAll(context, force = true)
        }
    }

    companion object {
        private const val ACTION_UPDATE = "io.legado.app.action.UPDATE_READ_RANK_WIDGET"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile
        private var lastRefreshTime = 0L

        fun updateAll(context: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshTime < 30_000L) return
            lastRefreshTime = now
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, ReadRankWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateWidgets(appContext, manager, ids)
        }

        private fun updateWidgets(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            scope.launch {
                val views = buildRemoteViews(context)
                appWidgetIds.forEach { id -> manager.updateAppWidget(id, views) }
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val items = ReadRecordWidgetStore.buildRankItems(5)
            val nameIds = intArrayOf(
                R.id.tvWidgetRankName1,
                R.id.tvWidgetRankName2,
                R.id.tvWidgetRankName3,
                R.id.tvWidgetRankName4,
                R.id.tvWidgetRankName5
            )
            val timeIds = intArrayOf(
                R.id.tvWidgetRankTime1,
                R.id.tvWidgetRankTime2,
                R.id.tvWidgetRankTime3,
                R.id.tvWidgetRankTime4,
                R.id.tvWidgetRankTime5
            )
            return RemoteViews(context.packageName, R.layout.widget_read_rank).apply {
                setTextViewText(R.id.tvWidgetRankTitle, context.getString(R.string.read_record_read_rank))
                nameIds.forEachIndexed { index, viewId ->
                    val item = items.getOrNull(index)
                    setTextViewText(viewId, item?.displayName ?: "")
                    setTextViewText(timeIds[index], item?.readTime?.let { formatDuring(context, it) }.orEmpty())
                }
                setOnClickPendingIntent(R.id.readRankWidgetRoot, openReadRecordIntent(context))
            }
        }

        private fun openReadRecordIntent(context: Context): PendingIntent {
            val mainIntent = Intent(context, MainActivity::class.java)
            val recordIntent = Intent(context, ReadRecordActivity::class.java)
            return TaskStackBuilder.create(context)
                .addNextIntent(mainIntent)
                .addNextIntent(recordIntent)
                .getPendingIntent(
                    30,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ) ?: PendingIntent.getActivity(
                context,
                30,
                recordIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun formatDuring(context: Context, mss: Long): String {
            val days = mss / (1000 * 60 * 60 * 24)
            val hours = (mss % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
            val minutes = (mss % (1000 * 60 * 60)) / (1000 * 60)
            val d = if (days > 0) context.getString(R.string.duration_day, days) else ""
            val h = if (hours > 0) context.getString(R.string.duration_hour, hours) else ""
            val m = if (minutes > 0) context.getString(R.string.duration_minute, minutes) else ""
            return "$d$h$m".ifBlank { context.getString(R.string.duration_zero) }
        }
    }
}
