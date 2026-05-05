package io.legado.app.ui.book.cache

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class CacheManageViewModel(application: Application) : BaseViewModel(application) {

    val itemsLiveData = MutableLiveData<List<CacheBookItem>>()
    val summaryLiveData = MutableLiveData<CacheSummary>()
    val loadingLiveData = MutableLiveData<Boolean>()

    private var loadJob: Job? = null
    var mode: CacheManageMode = CacheManageMode.BOOK
        private set

    fun load(mode: CacheManageMode = this.mode) {
        this.mode = mode
        loadJob?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loadingLiveData.postValue(true)
            try {
                val items = getBooks(mode)
                    .asSequence()
                    .mapNotNull { book -> buildCacheBookItem(book, mode) }
                    .sortedWith(compareByDescending<CacheBookItem> { it.cachedCount }.thenBy { it.book.name })
                    .toList()
                ensureActive()
                itemsLiveData.postValue(items)
                summaryLiveData.postValue(
                    CacheSummary(
                        bookCount = items.size,
                        cachedChapterCount = items.sumOf { it.cachedCount },
                        mode = mode
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadJob === job) {
                    loadingLiveData.postValue(false)
                }
            }
        }
        loadJob = job
        job.start()
    }

    fun deleteBookCache(book: Book, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteAudioMediaCache(book)
            BookHelp.clearCache(book)
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    fun deleteBookCaches(books: List<Book>, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            books.forEach {
                deleteAudioMediaCache(it)
                BookHelp.clearCache(it)
            }
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    suspend fun getChapterItems(book: Book, key: String? = null): List<CacheChapterItem> {
        return getChapterItems(book, key, false)
    }

    suspend fun getChapterItems(
        book: Book,
        key: String? = null,
        cachedOnly: Boolean = false
    ): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = getCacheFileNames(book)
            if (cachedOnly && !book.isAudio && cacheNames.none { it.endsWith(".nb") }) {
                return@withContext emptyList()
            }
            val chapters = if (key.isNullOrBlank()) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                appDb.bookChapterDao.search(book.bookUrl, key)
            }
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .mapNotNull { chapter ->
                    val cached = isChapterCached(
                        book,
                        chapter,
                        cacheNames,
                        validateImageContent = false
                    )
                    if (cachedOnly && !cached) {
                        return@mapNotNull null
                    }
                    CacheChapterItem(chapter = chapter, cached = cached)
                }
                .toList()
        }
    }

    suspend fun deleteChapterCache(book: Book, chapter: BookChapter) {
        withContext(Dispatchers.IO) {
            if (book.isAudio) {
                ExoPlayerHelper.removeMediaCache(chapter.resourceUrl)
            }
            BookHelp.delChapterCache(book, chapter)
        }
    }

    suspend fun cacheAudioChapters(book: Book, chapters: List<BookChapter>): Int {
        if (!book.isAudio) return 0
        val targets = withContext(Dispatchers.IO) {
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .filterNot { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
                .toList()
        }
        if (targets.isEmpty()) return 0
        val started = AudioCacheTaskManager.start(
            book = book,
            chapters = targets,
            resolver = ::resolveAudioMediaRequest,
            onChapterResolved = { chapter, request ->
                if (chapter.resourceUrl != request.url) {
                    chapter.resourceUrl = request.url
                    appDb.bookChapterDao.update(chapter)
                }
            },
            onFinished = { load(mode) }
        )
        if (started) {
            load(mode)
        }
        return if (started) targets.size else 0
    }

    suspend fun createCachePackage(book: Book): File {
        return withContext(Dispatchers.IO) {
            val cacheDir = BookHelp.getCacheDir(book)
            val outDir = File(appCtx.externalCache, "cache_package").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${book.name}_${book.author}_${System.currentTimeMillis()}"
                .normalizeFileName()
                .ifBlank { "cache_${System.currentTimeMillis()}" }
            val zipFile = File(outDir, "$fileName.zip").apply {
                if (exists()) delete()
            }
            if (book.isAudio) {
                return@withContext createAudioCachePackage(book, cacheDir, outDir, fileName, zipFile)
            }
            if (!cacheDir.exists() || cacheDir.listFiles().isNullOrEmpty()) {
                throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
            }
            if (!ZipUtils.zipFile(cacheDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
            }
            zipFile
        }
    }

    private fun createAudioCachePackage(
        book: Book,
        cacheDir: File,
        outDir: File,
        fileName: String,
        zipFile: File
    ): File {
        val packageDir = File(outDir, "${fileName}_audio").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        var hasCache = false
        if (cacheDir.exists() && !cacheDir.listFiles().isNullOrEmpty()) {
            cacheDir.copyRecursively(File(packageDir, "chapter_cache"), overwrite = true)
            hasCache = true
        }
        val audioDir = File(packageDir, "audio_cache").apply { mkdirs() }
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .filterNot { it.isVolume }
            .mapNotNull { chapter ->
                val chapterDir = File(audioDir, chapter.index.toString())
                val fileCount = ExoPlayerHelper.copyMediaCache(chapter.resourceUrl, chapterDir)
                if (fileCount <= 0) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                hasCache = true
                AudioCacheManifest.Chapter(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    resourceUrl = chapter.resourceUrl,
                    fileCount = fileCount
                )
            }
        if (!hasCache) {
            packageDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
        }
        File(packageDir, "manifest.json").writeText(
            GSON.toJson(
                AudioCacheManifest(
                    bookName = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    chapters = chapters
                )
            )
        )
        val success = ZipUtils.zipFile(packageDir, zipFile)
        packageDir.deleteRecursively()
        if (!success || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
        }
        return zipFile
    }

    private fun buildCacheBookItem(book: Book, mode: CacheManageMode): CacheBookItem? {
        val taskState = AudioCacheTaskManager.snapshot(book.bookUrl)
        val rawCachedCount = if (mode == CacheManageMode.AUDIO) {
            getAudioCachedCount(book)
        } else {
            getFastCachedCount(book)
        }
        if (rawCachedCount <= 0 && taskState?.active != true) return null
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: appDb.bookChapterDao.getChapterCount(book.bookUrl).takeIf { it > 0 }
            ?: rawCachedCount
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            taskState = taskState
        )
    }

    private fun getFastCachedCount(book: Book): Int {
        return getCacheFileNames(book).count { it.endsWith(".nb") }
    }

    private fun getAudioCachedCount(book: Book): Int {
        return appDb.bookChapterDao.getChapterList(book.bookUrl)
            .asSequence()
            .filterNot { it.isVolume }
            .count { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
    }

    private fun getCacheFileNames(book: Book): Set<String> {
        val cacheDir = BookHelp.getCacheDir(book)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return emptySet()
        return cacheDir.list()?.toSet().orEmpty()
    }

    private fun isChapterCached(
        book: Book,
        chapter: BookChapter,
        cacheNames: Set<String> = getCacheFileNames(book),
        validateImageContent: Boolean = true
    ): Boolean {
        if (book.isLocal) return false
        if (book.isAudio) return ExoPlayerHelper.isMediaCached(chapter.resourceUrl)
        val hasContent = BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        return if (validateImageContent && book.isImage && hasContent) {
            BookHelp.hasImageContent(book, chapter)
        } else {
            hasContent
        }
    }

    private fun getBooks(mode: CacheManageMode): List<Book> {
        return when (mode) {
            CacheManageMode.BOOK -> appDb.bookDao.getByTypeOnLine(BookType.text)
            CacheManageMode.AUDIO -> appDb.bookDao.getByTypeOnLine(BookType.audio)
            CacheManageMode.MANGA -> appDb.bookDao.getByTypeOnLine(BookType.image)
        }
    }

    private fun deleteAudioMediaCache(book: Book) {
        if (!book.isAudio) return
        appDb.bookChapterDao.getChapterList(book.bookUrl)
            .forEach { ExoPlayerHelper.removeMediaCache(it.resourceUrl) }
    }

    private suspend fun cacheAudioChapter(book: Book, chapter: BookChapter) {
        val request = resolveAudioMediaRequest(book, chapter)
        ExoPlayerHelper.cacheMedia(request)
        if (chapter.resourceUrl != request.url) {
            chapter.resourceUrl = request.url
            appDb.bookChapterDao.update(chapter)
        }
    }

    private suspend fun resolveAudioMediaRequest(
        book: Book,
        chapter: BookChapter
    ): ExoPlayerHelper.MediaRequest {
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() && ExoPlayerHelper.isMediaCached(it) }
            ?.let { return ExoPlayerHelper.MediaRequest(it) }
        val source = book.getBookSource()
            ?: throw IllegalStateException(context.getString(R.string.book_source_not_found))
        val candidates = linkedSetOf<String>()
        BookHelp.getContent(book, chapter)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)
        WebBook.getContentAwait(source, book, chapter, needSave = true)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(candidates::add)
        var lastError: Throwable? = null
        for (content in candidates) {
            try {
                if (content.isJsonArray()) {
                    return ExoPlayerHelper.MediaRequest(content)
                }
                return AnalyzeUrl(
                    content,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = currentCoroutineContext()
                ).getMediaRequest()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            lastError?.localizedMessage ?: context.getString(R.string.cache_manage_audio_url_empty)
        )
    }
}

enum class CacheManageMode(@StringRes val titleRes: Int) {
    BOOK(R.string.cache_manage_books),
    AUDIO(R.string.cache_manage_audio),
    MANGA(R.string.cache_manage_manga)
}

data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val taskState: AudioCacheTaskState? = null
)

data class CacheChapterItem(
    val chapter: BookChapter,
    val cached: Boolean
)

data class CacheSummary(
    val bookCount: Int,
    val cachedChapterCount: Int,
    val mode: CacheManageMode
)

private data class AudioCacheManifest(
    val bookName: String,
    val author: String,
    val bookUrl: String,
    val chapters: List<Chapter>
) {
    data class Chapter(
        val index: Int,
        val title: String,
        val url: String,
        val resourceUrl: String?,
        val fileCount: Int
    )
}
