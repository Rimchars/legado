package io.legado.app.receiver

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.widget.RemoteViews
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class ReadGoalWidgetProvider : AppWidgetProvider() {

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
        private const val ACTION_UPDATE = "io.legado.app.action.UPDATE_READ_GOAL_WIDGET"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        @Volatile
        private var lastRefreshTime = 0L

        fun updateAll(context: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshTime < 30_000L) return
            lastRefreshTime = now
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, ReadGoalWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateWidgets(appContext, manager, ids)
            }
        }

        private fun updateWidgets(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            scope.launch {
                val views = buildRemoteViews(context)
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, views)
                }
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val config = ReadRecordWidgetStore.loadGoalConfig()
            val todayKey = Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(dateFormatter)
            val todayTime = appDb.readRecordDailyDao.get(todayKey)?.readTime ?: 0L
            val totalTime = appDb.readRecordDao.allTime
            val readBookCount = appDb.readRecordDao.allShow.size
            val goalMs = config.dailyGoalMinutes.coerceAtLeast(1) * 60L * 1000L
            val progress = ((todayTime.toDouble() / goalMs) * 100).roundToInt().coerceIn(0, 100)
            return RemoteViews(context.packageName, R.layout.widget_read_goal).apply {
                setTextViewText(
                    R.id.tvWidgetGoalTitle,
                    config.userName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.read_record_goal_card)
                )
                setTextViewText(R.id.tvWidgetGoalToday, context.getString(R.string.read_record_goal_today, formatDuring(context, todayTime)))
                setTextViewText(R.id.tvWidgetGoalTotal, context.getString(R.string.read_record_goal_total, formatDuring(context, totalTime)))
                setTextViewText(R.id.tvWidgetGoalBooks, context.getString(R.string.read_record_goal_books, readBookCount))
                setTextViewText(
                    R.id.tvWidgetGoalProgress,
                    context.getString(
                        R.string.read_record_goal_target_progress,
                        formatDuring(context, todayTime),
                        formatDuring(context, goalMs)
                    )
                )
                setProgressBar(R.id.progressWidgetGoal, 100, progress, false)
                bindAvatar(context, config.avatar)
                setOnClickPendingIntent(R.id.readGoalWidgetRoot, openReadRecordIntent(context))
            }
        }

        private fun RemoteViews.bindAvatar(context: Context, path: String?) {
            val value = path?.trim().orEmpty()
            val bitmap = decodeAvatarBitmap(context, value)
            if (bitmap != null) {
                setImageViewBitmap(R.id.ivWidgetGoalAvatar, bitmap)
            } else {
                setImageViewResource(R.id.ivWidgetGoalAvatar, R.drawable.ic_read_record_default_avatar)
            }
        }

        private fun decodeAvatarBitmap(context: Context, path: String): Bitmap? {
            if (path.isBlank()) return null
            return runCatching {
                val input = when {
                    path.startsWith("content://", ignoreCase = true) ||
                            path.startsWith("file://", ignoreCase = true) -> {
                        context.contentResolver.openInputStream(Uri.parse(path))
                    }
                    else -> File(path).takeIf { it.exists() }?.inputStream()
                } ?: return null
                input.use {
                    BitmapFactory.decodeStream(it)
                }?.centerCircleCrop(42.dp(context))
            }.getOrNull()
        }

        private fun Bitmap.centerCircleCrop(size: Int): Bitmap {
            val side = minOf(width, height).coerceAtLeast(1)
            val left = ((width - side) / 2).coerceAtLeast(0)
            val top = ((height - side) / 2).coerceAtLeast(0)
            val square = Bitmap.createBitmap(this, left, top, side, side)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(square, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
            paint.xfermode = null
            if (square != this) {
                square.recycle()
            }
            return output
        }

        private fun Int.dp(context: Context): Int {
            return (this * context.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun openReadRecordIntent(context: Context): PendingIntent {
            val mainIntent = Intent(context, MainActivity::class.java)
            val recordIntent = Intent(context, ReadRecordActivity::class.java)
            return TaskStackBuilder.create(context)
                .addNextIntent(mainIntent)
                .addNextIntent(recordIntent)
                .getPendingIntent(
                    0,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ) ?: PendingIntent.getActivity(
                    context,
                    0,
                    recordIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        }

        private fun formatDuring(context: Context, mss: Long): String {
            val days = mss / (1000 * 60 * 60 * 24)
            val hours = (mss % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
            val minutes = (mss % (1000 * 60 * 60)) / (1000 * 60)
            val seconds = (mss % (1000 * 60)) / 1000
            val d = if (days > 0) context.getString(R.string.duration_day, days) else ""
            val h = if (hours > 0) context.getString(R.string.duration_hour, hours) else ""
            val m = if (minutes > 0) context.getString(R.string.duration_minute, minutes) else ""
            val s = if (seconds > 0 && days == 0L && hours == 0L) {
                context.getString(R.string.duration_second, seconds)
            } else {
                ""
            }
            return "$d$h$m$s".ifBlank { context.getString(R.string.duration_zero) }
        }
    }
}
