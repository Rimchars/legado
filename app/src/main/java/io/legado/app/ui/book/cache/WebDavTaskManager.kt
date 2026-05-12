package io.legado.app.ui.book.cache

import android.content.Intent
import io.legado.app.R
import io.legado.app.service.WebDavTaskService
import io.legado.app.utils.startForegroundServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object WebDavTaskManager {

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "webdav-task-worker").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }.asCoroutineDispatcher()
    private val taskScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val taskMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, WebDavTaskState>>(emptyMap())
    val states: StateFlow<Map<String, WebDavTaskState>> = _states.asStateFlow()

    fun enqueueCacheUpload(
        item: CacheBookItem,
        block: suspend () -> Unit
    ): Boolean {
        return enqueue(
            key = item.cacheKey,
            bookName = item.book.name,
            type = WebDavTaskType.CACHE_UPLOAD,
            pendingMessage = appCtx.getString(R.string.cache_manage_upload_queued),
            runningMessage = appCtx.getString(R.string.cache_manage_uploading),
            successMessage = appCtx.getString(R.string.cache_manage_upload_success),
            block = block
        )
    }

    fun enqueueCacheDownload(
        item: CacheBookItem,
        block: suspend () -> Boolean
    ): Boolean {
        return enqueue(
            key = item.cacheKey,
            bookName = item.book.name,
            type = WebDavTaskType.CACHE_DOWNLOAD,
            pendingMessage = appCtx.getString(R.string.cache_manage_download_queued),
            runningMessage = appCtx.getString(R.string.cache_manage_downloading),
            successMessage = appCtx.getString(R.string.cache_manage_download_success)
        ) {
            if (!block()) {
                throw IllegalStateException(appCtx.getString(R.string.cache_manage_download_failed_simple))
            }
        }
    }

    fun enqueueUpload(
        key: String,
        name: String,
        type: WebDavTaskType,
        runningMessage: String,
        successMessage: String = appCtx.getString(R.string.success),
        block: suspend () -> Unit
    ): Boolean {
        return enqueue(
            key = key,
            bookName = name,
            type = type,
            pendingMessage = appCtx.getString(R.string.cache_manage_upload_queued),
            runningMessage = runningMessage,
            successMessage = successMessage,
            block = block
        )
    }

    fun snapshot(cacheKey: String): WebDavTaskState? = _states.value[cacheKey]

    fun cancelAll() {
        jobs.values.forEach {
            it.cancel(CancellationException(appCtx.getString(R.string.cache_manage_webdav_task_cancelled)))
        }
        jobs.clear()
        _states.update { states ->
            states.mapValues { (_, state) ->
                if (state.active) {
                    state.copy(
                        status = WebDavTaskStatus.CANCELLED,
                        active = false,
                        message = appCtx.getString(R.string.cache_manage_webdav_task_cancelled)
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun enqueue(
        key: String,
        bookName: String,
        type: WebDavTaskType,
        pendingMessage: String,
        runningMessage: String,
        successMessage: String,
        block: suspend () -> Unit
    ): Boolean {
        val existing = _states.value[key]
        if (existing?.active == true) return false
        updateState(
            key,
            WebDavTaskState(
                key = key,
                bookName = bookName,
                type = type,
                status = WebDavTaskStatus.PENDING,
                message = pendingMessage
            )
        )
        startService()
        val job = taskScope.launch(start = CoroutineStart.LAZY) {
            try {
                taskMutex.withLock {
                    updateState(key) {
                        it.copy(
                            status = WebDavTaskStatus.RUNNING,
                            message = runningMessage,
                            active = true
                        )
                    }
                    block()
                    updateState(key) {
                        it.copy(
                            status = WebDavTaskStatus.COMPLETED,
                            message = successMessage,
                            active = false
                        )
                    }
                }
            } catch (e: CancellationException) {
                updateState(key) {
                    it.copy(
                        status = WebDavTaskStatus.CANCELLED,
                        message = appCtx.getString(R.string.cache_manage_webdav_task_cancelled),
                        active = false
                    )
                }
            } catch (e: Throwable) {
                updateState(key) {
                    it.copy(
                        status = WebDavTaskStatus.FAILED,
                        message = e.localizedMessage ?: appCtx.getString(R.string.error),
                        active = false
                    )
                }
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = job
        job.start()
        return true
    }

    private fun updateState(key: String, transform: (WebDavTaskState) -> WebDavTaskState) {
        _states.update { states ->
            val current = states[key] ?: return@update states
            states.toMutableMap().apply {
                put(key, transform(current))
            }
        }
    }

    private fun updateState(key: String, state: WebDavTaskState) {
        _states.update { states ->
            states.toMutableMap().apply {
                put(key, state)
            }
        }
    }

    private fun startService() {
        val intent = Intent(appCtx, WebDavTaskService::class.java)
        appCtx.startForegroundServiceCompat(intent)
    }
}

enum class WebDavTaskType {
    CACHE_UPLOAD,
    CACHE_DOWNLOAD,
    THEME_PACKAGE_UPLOAD,
    NAVIGATION_BAR_PACKAGE_UPLOAD,
    TOP_BAR_PACKAGE_UPLOAD
}

enum class WebDavTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class WebDavTaskState(
    val key: String,
    val bookName: String,
    val type: WebDavTaskType,
    val status: WebDavTaskStatus,
    val message: String,
    val active: Boolean = true
)