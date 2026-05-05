package io.legado.app.ui.book.cache

import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.globalExecutor
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.utils.ConvertUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object AudioCacheTaskManager {

    private val executor: ExecutorService = globalExecutor
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val futures = ConcurrentHashMap<String, Future<*>>()
    private val _states = MutableStateFlow<Map<String, AudioCacheTaskState>>(emptyMap())
    val states: StateFlow<Map<String, AudioCacheTaskState>> = _states.asStateFlow()

    fun hasTask(bookUrl: String): Boolean = _states.value[bookUrl]?.active == true

    fun snapshot(bookUrl: String): AudioCacheTaskState? = _states.value[bookUrl]

    fun start(
        book: Book,
        chapters: List<BookChapter>,
        resolver: suspend (Book, BookChapter) -> ExoPlayerHelper.MediaRequest,
        onChapterResolved: ((BookChapter, ExoPlayerHelper.MediaRequest) -> Unit)? = null,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        if (chapters.isEmpty()) return false
        val existing = _states.value[book.bookUrl]
        if (existing?.active == true) return false
        val cancelFlag = AtomicBoolean(false)
        cancelFlags[book.bookUrl] = cancelFlag
        updateState(
            book.bookUrl,
            AudioCacheTaskState(
                bookUrl = book.bookUrl,
                bookName = book.name,
                totalChapters = chapters.size,
                status = CacheTaskStatus.PENDING,
                message = appCtx.getString(R.string.data_loading)
            )
        )
        val future = executor.submit {
            var completed = 0
            var downloadedBytes = 0L
            var knownTotalBytes = 0L
            var speedBytes = 0L
            var speedWindowStart = System.currentTimeMillis()
            try {
                chapters.forEachIndexed { index, chapter ->
                    if (cancelFlag.get()) throw CancellationException("cancelled")
                    updateState(
                        book.bookUrl
                    ) {
                        it.copy(
                            status = CacheTaskStatus.RESOLVING,
                            currentChapterTitle = chapter.title,
                            currentChapterIndex = index + 1,
                            completedChapters = completed,
                            active = true,
                            message = appCtx.getString(
                                R.string.cache_manage_resolving_chapter,
                                index + 1,
                                chapters.size
                            )
                        )
                    }
                    val request = runBlocking {
                        resolver(book, chapter)
                    }
                    onChapterResolved?.invoke(chapter, request)
                    var chapterKnownLength = 0L
                    ExoPlayerHelper.cacheMedia(
                        request = request,
                        progress = { requestLength, bytesCached, newBytesCached ->
                            if (cancelFlag.get()) throw CancellationException("cancelled")
                            if (requestLength > 0 && bytesCached <= requestLength) {
                                val previousKnown = chapterKnownLength
                                chapterKnownLength = max(chapterKnownLength, requestLength)
                                knownTotalBytes += (chapterKnownLength - previousKnown)
                            }
                            downloadedBytes += newBytesCached.coerceAtLeast(0L)
                            speedBytes += newBytesCached.coerceAtLeast(0L)
                            val now = System.currentTimeMillis()
                            val delta = (now - speedWindowStart).coerceAtLeast(1L)
                            val speed = if (delta >= 750L) {
                                val value = speedBytes * 1000L / delta
                                speedBytes = 0L
                                speedWindowStart = now
                                value
                            } else {
                                _states.value[book.bookUrl]?.speedBytesPerSecond ?: 0L
                            }
                            updateState(book.bookUrl) {
                                it.copy(
                                    status = CacheTaskStatus.CACHING,
                                    currentChapterTitle = chapter.title,
                                    currentChapterIndex = index + 1,
                                    completedChapters = completed,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = knownTotalBytes.takeIf { value -> value > 0L },
                                    speedBytesPerSecond = speed,
                                    active = true,
                                    message = buildProgressMessage(
                                        completed = completed,
                                        total = chapters.size,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = knownTotalBytes.takeIf { value -> value > 0L },
                                        speedBytes = speed
                                    )
                                )
                            }
                        },
                        shouldCancel = { cancelFlag.get() }
                    )
                    completed += 1
                    updateState(book.bookUrl) {
                        it.copy(
                            status = CacheTaskStatus.CACHING,
                            completedChapters = completed,
                            currentChapterTitle = chapter.title,
                            currentChapterIndex = index + 1,
                            active = true,
                            message = buildProgressMessage(
                                completed = completed,
                                total = chapters.size,
                                downloadedBytes = downloadedBytes,
                                totalBytes = knownTotalBytes.takeIf { value -> value > 0L },
                                speedBytes = _states.value[book.bookUrl]?.speedBytesPerSecond ?: 0L
                            )
                        )
                    }
                }
                updateState(book.bookUrl) {
                    it.copy(
                        status = CacheTaskStatus.COMPLETED,
                        completedChapters = completed,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = appCtx.getString(R.string.cache_manage_task_done, completed)
                    )
                }
            } catch (e: CancellationException) {
                updateState(book.bookUrl) {
                    it.copy(
                        status = CacheTaskStatus.CANCELLED,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = appCtx.getString(R.string.cache_manage_task_cancelled)
                    )
                }
            } catch (e: Exception) {
                updateState(book.bookUrl) {
                    it.copy(
                        status = CacheTaskStatus.FAILED,
                        active = false,
                        speedBytesPerSecond = 0L,
                        message = e.localizedMessage ?: appCtx.getString(R.string.error)
                    )
                }
            } finally {
                cancelFlags.remove(book.bookUrl)
                futures.remove(book.bookUrl)
                onFinished?.invoke()
            }
        }
        futures[book.bookUrl] = future
        return true
    }

    fun cancel(bookUrl: String) {
        cancelFlags[bookUrl]?.set(true)
        futures[bookUrl]?.cancel(true)
    }

    private fun buildProgressMessage(
        completed: Int,
        total: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        speedBytes: Long
    ): String {
        val downloadedText = ConvertUtils.formatFileSize(downloadedBytes)
        val totalText = totalBytes?.let(ConvertUtils::formatFileSize) ?: "?"
        val speedText = if (speedBytes > 0L) {
            ConvertUtils.formatFileSize(speedBytes) + "/s"
        } else {
            "--"
        }
        return appCtx.getString(
            R.string.cache_manage_task_progress,
            completed,
            total,
            downloadedText,
            totalText,
            speedText
        )
    }

    private fun updateState(bookUrl: String, transform: (AudioCacheTaskState) -> AudioCacheTaskState) {
        val current = _states.value[bookUrl] ?: return
        updateState(bookUrl, transform(current))
    }

    private fun updateState(bookUrl: String, state: AudioCacheTaskState) {
        _states.value = _states.value.toMutableMap().apply {
            put(bookUrl, state)
        }
    }
}

enum class CacheTaskStatus {
    PENDING,
    RESOLVING,
    CACHING,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class AudioCacheTaskState(
    val bookUrl: String,
    val bookName: String,
    val totalChapters: Int,
    val completedChapters: Int = 0,
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0L,
    val status: CacheTaskStatus = CacheTaskStatus.PENDING,
    val message: String = "",
    val active: Boolean = true
)
