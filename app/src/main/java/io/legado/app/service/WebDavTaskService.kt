package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskState
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import splitties.systemservices.notificationManager

class WebDavTaskService : BaseService() {

    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        observeJob = lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                updateNotification(states.values.toList())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentAction.stop) {
            WebDavTaskManager.cancelAll()
            stopSelf()
            return super.onStartCommand(intent, flags, startId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (WebDavTaskManager.states.value.values.any { it.active }) {
            startForegroundNotification()
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        notificationManager.cancel(NotificationId.WebDavTask)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    override fun startForegroundNotification() {
        startForeground(NotificationId.WebDavTask, buildNotification(WebDavTaskManager.states.value.values.toList()).build())
    }

    private fun updateNotification(states: List<WebDavTaskState>) {
        notificationManager.notify(NotificationId.WebDavTask, buildNotification(states).build())
        if (states.none { it.active }) {
            lifecycleScope.launch {
                delay(STOP_DELAY_MS)
                if (WebDavTaskManager.states.value.values.none { it.active }) {
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(states: List<WebDavTaskState>): NotificationCompat.Builder {
        val active = states.firstOrNull { it.status == WebDavTaskStatus.RUNNING }
            ?: states.firstOrNull { it.active }
        val content = active?.let {
            getString(R.string.cache_manage_webdav_task_content, it.bookName, it.message)
        } ?: states.lastOrNull()?.let {
            getString(R.string.cache_manage_webdav_task_content, it.bookName, it.message)
        } ?: getString(R.string.cache_manage_webdav_task_waiting)
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.cache_manage_webdav_task_title))
            .setContentText(content)
            .setOngoing(active != null)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(activityPendingIntent<CacheManageActivity>("cacheManage"))
            .apply {
                if (active != null) {
                    addAction(
                        R.drawable.ic_stop_black_24dp,
                        getString(R.string.cancel),
                        servicePendingIntent<WebDavTaskService>(IntentAction.stop)
                    )
                }
            }
    }

    private companion object {
        const val STOP_DELAY_MS = 1500L
    }
}
