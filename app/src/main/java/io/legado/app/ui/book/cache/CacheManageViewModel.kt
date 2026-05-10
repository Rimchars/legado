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
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.CacheCloudIndex
import io.legado.app.help.book.CacheCloudIndexItem
import io.legado.app.help.book.CacheCloudIndexStore
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheBookManifest
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.cacheGroupKey
import io.legado.app.help.book.cacheRemoteKey
import io.legado.app.help.book.cacheSourceKey
import io.legado.app.help.book.cacheSourceName
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
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
    private val selectedSourceKeys = hashMapOf<String, String>()
    var mode: CacheManageMode = CacheManageMode.BOOK
        private set

    fun isLoading(): Boolean = loadJob?.isActive == true

    fun load(mode: CacheManageMode = this.mode) {
        this.mode = mode
        loadJob?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loadingLiveData.postValue(true)
            try {
                val currentBooks = getBooks(mode)
                val currentBookUrls = currentBooks.mapTo(hashSetOf()) { it.bookUrl }
                val cacheDirs = CacheManifestHelper.listCacheDirs()
                val cacheDirNames = cacheDirs.mapTo(hashSetOf()) { it.name }
                val manifests = CacheManifestHelper.listManifests(cacheDirs)
                val manifestByBookUrl = manifests.associateBy { it.bookUrl }
                val currentItems = currentBooks
                    .asSequence()
                    .mapNotNull { book ->
                        buildCacheBookItem(
                            book = book,
                            mode = mode,
                            knownManifest = manifestByBookUrl[book.bookUrl],
                            cacheDirNames = cacheDirNames
                        )
                    }
                    .toList()
                val manifestItems = manifests
                    .asSequence()
                    .filter { it.matches(mode) }
                    .filterNot { currentBookUrls.contains(it.bookUrl) }
                    .mapNotNull { manifest -> buildCacheBookItem(manifest, mode) }
                    .toList()
                val localItems = groupByBook(currentItems + manifestItems)
                ensureActive()
                val mirrorItems = mergeRemoteItems(localItems, CacheCloudIndexStore.readLocal())
                postItems(mirrorItems, mode)
                if (AppWebDav.isOk && NetworkUtils.isAvailable()) {
                    val remoteIndex = AppWebDav.downloadCacheIndex().items
                    val mergedIndex = CacheCloudIndexStore.mergeRemote(remoteIndex)
                    ensureActive()
                    postItems(mergeRemoteItems(localItems, mergedIndex), mode)
                }
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

    private fun postItems(items: List<CacheBookItem>, mode: CacheManageMode) {
        itemsLiveData.postValue(items)
        summaryLiveData.postValue(
            CacheSummary(
                bookCount = items.size,
                cachedChapterCount = items.sumOf { it.cachedCount },
                mode = mode
            )
        )
    }

    fun selectSource(groupKey: String, sourceKey: String) {
        selectedSourceKeys[groupKey] = sourceKey
        load()
    }

    private fun mergeRemoteItems(
        localItems: List<CacheBookItem>,
        remoteIndex: List<CacheCloudIndexItem>
    ): List<CacheBookItem> {
        val localByKey = localItems.associateBy { it.cacheKey }
        val merged = arrayListOf<CacheBookItem>()
        localItems.forEach { item ->
            val remote = remoteIndex.firstOrNull { it.cacheKey == item.cacheKey }
            merged += if (remote != null) {
                item.copy(
                    remoteAvailable = true,
                    remoteUpdatedAt = remote.updatedAt,
                    remoteZipFileName = remote.zipFileName,
                    remoteCachedCount = remote.cachedChapterCount
                )
            } else {
                item
            }
        }
        remoteIndex
            .asSequence()
            .filter { it.mode == mode.name }
            .filterNot { localByKey.containsKey(it.cacheKey) }
            .mapNotNull { buildRemoteCacheBookItem(it, mode) }
            .forEach { merged += it }
        return groupByBook(merged)
    }

    private fun buildRemoteCacheBookItem(
        remote: CacheCloudIndexItem,
        mode: CacheManageMode
    ): CacheBookItem? {
        if (remote.mode != mode.name) return null
        val book = Book(
            bookUrl = remote.bookUrl,
            origin = remote.origin,
            originName = remote.originName,
            name = remote.name,
            author = remote.author,
            coverUrl = remote.coverUrl,
            intro = remote.intro,
            type = remote.type,
            latestChapterTitle = remote.latestChapterTitle,
            totalChapterNum = remote.totalChapterCount,
            canUpdate = false
        )
        return CacheBookItem(
            book = book,
            mode = mode,
            cacheKey = remote.cacheKey,
            groupKey = remote.groupKey,
            sourceKey = remote.sourceKey,
            sourceName = book.cacheSourceName(),
            cachedCount = remote.cachedChapterCount.coerceAtMost(remote.totalChapterCount),
            totalChapterCount = remote.totalChapterCount,
            localCachedCount = 0,
            remoteCachedCount = remote.cachedChapterCount,
            inBookshelf = appDb.bookDao.has(remote.bookUrl),
            sourceAvailable = book.isLocal || book.getBookSource() != null,
            remoteAvailable = true,
            remoteUpdatedAt = remote.updatedAt,
            remoteZipFileName = remote.zipFileName
        )
    }

    fun deleteBookCache(book: Book, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteAudioMediaCache(book)
            BookHelp.clearCache(book)
            CacheManifestHelper.delete(book)
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
                CacheManifestHelper.delete(it)
            }
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    suspend fun getChapterItems(book: Book, key: String? = null): List<CacheChapterItem> {
        return getChapterItems(book, key, CacheChapterFilter.ALL)
    }

    suspend fun getChapterItems(
        book: Book,
        key: String? = null,
        filter: CacheChapterFilter = CacheChapterFilter.ALL
    ): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = if (book.isAudio) emptySet() else getCacheFileNames(book)
            val manifest = CacheManifestHelper.read(book)
            val dbChapters = if (key.isNullOrBlank()) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                appDb.bookChapterDao.search(book.bookUrl, key)
            }
            if (book.isAudio && CacheManifestHelper.mergeResourceUrls(dbChapters, manifest)) {
                appDb.bookChapterDao.update(*dbChapters.toTypedArray())
            }
            val chapters = dbChapters.takeIf { it.isNotEmpty() }
                ?: CacheManifestHelper.toChapters(manifest ?: return@withContext emptyList())
                    .filterByKey(key)
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
                    when (filter) {
                        CacheChapterFilter.CACHED -> if (!cached) return@mapNotNull null
                        CacheChapterFilter.UNCACHED -> if (cached) return@mapNotNull null
                        CacheChapterFilter.ALL -> Unit
                    }
                    CacheChapterItem(chapter = chapter, cached = cached)
                }
                .toList()
        }
    }

    suspend fun deleteChapterCache(book: Book, chapter: BookChapter) {
        deleteChapterCaches(book, listOf(chapter))
    }

    suspend fun deleteChapterCaches(book: Book, chapters: List<BookChapter>) {
        withContext(Dispatchers.IO) {
            if (chapters.isEmpty()) return@withContext
            chapters.forEach { chapter ->
                if (book.isAudio) {
                    ExoPlayerHelper.removeMediaCache(chapter.resourceUrl)
                }
                BookHelp.delChapterCache(book, chapter)
            }
            refreshManifest(book)
        }
    }

    fun cacheBookChapters(book: Book, chapters: List<BookChapter>): Int {
        if (book.isAudio || book.isLocal) return 0
        val indexes = chapters
            .asSequence()
            .filterNot { it.isVolume }
            .map { it.index }
            .distinct()
            .sorted()
            .toList()
        if (indexes.isEmpty()) return 0
        indexes.toRanges().forEach { (start, end) ->
            CacheBook.start(appCtx, book, start, end)
        }
        return indexes.size
    }

    suspend fun cacheAudioChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        if (!book.isAudio) return 0
        val targets = withContext(Dispatchers.IO) {
            val realChapters = chapters
                .asSequence()
                .filterNot { it.isVolume }
                .toList()
            if (CacheManifestHelper.mergeResourceUrls(realChapters, CacheManifestHelper.read(book))) {
                appDb.bookChapterDao.update(*realChapters.toTypedArray())
            }
            realChapters
                .asSequence()
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
            onFinished = {
                refreshManifest(book)
                if (reloadOnFinished && mode == CacheManageMode.AUDIO) {
                    load(mode)
                }
            }
        )
        if (started && mode == CacheManageMode.AUDIO) {
            load(mode)
        }
        return if (started) targets.size else 0
    }

    suspend fun restoreCacheToBookshelf(item: CacheBookItem): Boolean {
        return withContext(Dispatchers.IO) {
            val manifest = item.manifest ?: CacheManifestHelper.read(item.book) ?: return@withContext false
            val sameUrlBook = appDb.bookDao.getBook(manifest.bookUrl)
            val sameNameBook = appDb.bookDao.getBook(manifest.name, manifest.author)
            val targetBook = when {
                sameUrlBook != null -> sameUrlBook.apply {
                    tocUrl = manifest.tocUrl
                    origin = manifest.origin
                    originName = manifest.originName
                    name = manifest.name
                    author = manifest.author
                    kind = manifest.kind
                    coverUrl = manifest.coverUrl
                    intro = manifest.intro
                    type = manifest.type
                    latestChapterTitle = manifest.latestChapterTitle
                    totalChapterNum = manifest.totalChapterNum
                    canUpdate = false
                }.also {
                    appDb.bookDao.update(it)
                }
                sameNameBook != null -> CacheManifestHelper.toBook(manifest).apply {
                    group = sameNameBook.group
                    order = sameNameBook.order
                    durChapterIndex = sameNameBook.durChapterIndex
                    durChapterTitle = sameNameBook.durChapterTitle
                    durChapterPos = sameNameBook.durChapterPos
                    readConfig = sameNameBook.readConfig
                }.also {
                    appDb.bookDao.replace(sameNameBook, it)
                }
                else -> CacheManifestHelper.toBook(manifest).also {
                    appDb.bookDao.insert(it)
                }
            }
            val chapters = CacheManifestHelper.toChapters(manifest, targetBook.bookUrl)
            if (chapters.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(targetBook.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
            true
        }
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

    suspend fun uploadCacheItem(item: CacheBookItem) {
        withContext(Dispatchers.IO) {
            val zipFile = createCachePackage(item.book)
            val indexItem = createCacheIndexItem(item)
            AppWebDav.uploadCachePackage(indexItem.zipFileName, zipFile)
            val remoteIndex = AppWebDav.downloadCacheIndex().items
            val merged = linkedMapOf<String, CacheCloudIndexItem>()
            remoteIndex.forEach { merged[it.cacheKey] = it }
            merged[indexItem.cacheKey] = indexItem
            val finalItems = merged.values.sortedByDescending { it.updatedAt }
            AppWebDav.uploadCacheIndex(CacheCloudIndex(items = finalItems))
            CacheCloudIndexStore.upsertLocal(indexItem)
        }
    }

    suspend fun downloadRemoteCache(item: CacheBookItem): Boolean {
        val zipFileName = item.remoteZipFileName ?: return false
        return withContext(Dispatchers.IO) {
            val tempDir = File(appCtx.externalCache, "cache_remote_import").apply { mkdirs() }
            val zipFile = File(tempDir, "${zipFileName.removeSuffix(".zip")}.zip").apply {
                if (exists()) delete()
            }
            val unzipDir = File(tempDir, "${zipFileName.removeSuffix(".zip")}_unzipped").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            try {
                AppWebDav.downloadCachePackage(zipFileName, zipFile)
                ZipUtils.unZipToPath(zipFile, unzipDir)
                if (item.mode == CacheManageMode.AUDIO) {
                    restoreAudioCachePackage(item.book, unzipDir)
                } else {
                    restoreChapterCachePackage(item.book, unzipDir)
                }
                hasRestoredLocalCache(item.book, item.mode)
            } finally {
                zipFile.delete()
                unzipDir.deleteRecursively()
            }
        }
    }

    private fun createCacheIndexItem(item: CacheBookItem): CacheCloudIndexItem {
        val book = item.book
        val modeName = item.mode.name
        val remoteFileName = listOf(
            modeName.lowercase(),
            book.origin.ifBlank { book.originName },
            book.name,
            book.author
        ).joinToString("_").normalizeFileName().ifBlank { item.cacheKey.normalizeFileName() }
        return CacheCloudIndexItem(
            cacheKey = book.cacheRemoteKey(modeName),
            groupKey = book.cacheGroupKey(modeName),
            sourceKey = book.cacheSourceKey(),
            mode = modeName,
            bookUrl = book.bookUrl,
            origin = book.origin,
            originName = book.originName,
            name = book.name,
            author = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            latestChapterTitle = book.latestChapterTitle,
            type = book.type,
            totalChapterCount = item.totalChapterCount,
            cachedChapterCount = item.localCachedCount.coerceAtLeast(item.cachedCount),
            zipFileName = remoteFileName,
            updatedAt = System.currentTimeMillis()
        )
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
        val chapters = (appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty())
            .filterNot { it.isVolume }
            .mapNotNull { chapter ->
                val chapterDir = File(audioDir, chapter.index.toString())
                if (!ExoPlayerHelper.isMediaCached(chapter.resourceUrl)) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
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

    private fun groupByBook(items: List<CacheBookItem>): List<CacheBookItem> {
        return items
            .groupBy { it.groupKey }
            .values
            .mapNotNull { group ->
                val variants = group
                    .sortedWith(
                        compareByDescending<CacheBookItem> { if (it.taskState.isVisibleAudioTask()) 1 else 0 }
                            .thenByDescending { it.cachedCount }
                            .thenBy { it.sourceName }
                    )
                    .map { it.toSourceVariant() }
                val groupKey = group.firstOrNull()?.groupKey ?: return@mapNotNull null
                val selectedKey = selectedSourceKeys[groupKey]
                val selected = group.firstOrNull { it.sourceKey == selectedKey }
                    ?: group.firstOrNull { it.taskState.isVisibleAudioTask() }
                    ?: group.maxWithOrNull(
                        compareBy<CacheBookItem> { it.cachedCount }
                            .thenBy { it.totalChapterCount }
                    )
                    ?: group.first()
                selected.copy(sourceVariants = variants)
            }
            .sortedWith(
                compareByDescending<CacheBookItem> { it.cachedCount }
                    .thenBy { it.book.name }
                    .thenBy { it.sourceName }
            )
    }

    private fun buildCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        knownManifest: CacheBookManifest? = null,
        cacheDirNames: Set<String> = emptySet()
    ): CacheBookItem? {
        val taskState = AudioCacheTaskManager.snapshot(book.bookUrl)
        if (mode == CacheManageMode.AUDIO) {
            return buildAudioCacheBookItem(book, knownManifest, taskState)
        }
        if (knownManifest == null &&
            taskState?.active != true &&
            !cacheDirNames.contains(book.getFolderName())
        ) {
            return null
        }
        val cacheNames = getCacheFileNames(book)
        val needsChapterList = book.totalChapterNum <= 0
        val manifest = knownManifest ?: CacheManifestHelper.read(book)
        val dbChapters = if (needsChapterList) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            emptyList()
        }
        val chapters = dbChapters.takeIf { it.isNotEmpty() }
            ?: manifest?.let(CacheManifestHelper::toChapters)
            ?: emptyList()
        val rawCachedCount = getFastCachedCount(cacheNames)
        if (rawCachedCount <= 0 && taskState?.active != true) {
            CacheManifestHelper.delete(book)
            return null
        }
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            cacheKey = book.cacheRemoteKey(mode.name),
            groupKey = book.cacheGroupKey(mode.name),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            localCachedCount = cachedCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = true,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildAudioCacheBookItem(
        book: Book,
        manifest: CacheBookManifest?,
        taskState: AudioCacheTaskState?
    ): CacheBookItem? {
        val hasVisibleTask = taskState.isVisibleAudioTask()
        if (manifest == null && !hasVisibleTask) return null
        val candidateCachedIndexes = manifest.cachedIndexes()
        val manifestChapters = manifest?.let(CacheManifestHelper::toChapters).orEmpty()
        val realCachedCount = getAudioCachedCount(manifestChapters, candidateCachedIndexes)
        val taskCompletedCount = taskState?.completedChapters ?: 0
        val rawCachedCount = maxOf(realCachedCount, taskCompletedCount)
        if (rawCachedCount <= 0 && !hasVisibleTask) {
            CacheManifestHelper.delete(book)
            return null
        }
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: manifest?.totalChapterNum?.takeIf { it > 0 }
            ?: manifestChapters.size.takeIf { it > 0 }
            ?: taskState?.totalChapters?.takeIf { it > 0 }
            ?: rawCachedCount.coerceAtLeast(1)
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = CacheManageMode.AUDIO,
            cacheKey = book.cacheRemoteKey(CacheManageMode.AUDIO.name),
            groupKey = book.cacheGroupKey(CacheManageMode.AUDIO.name),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            localCachedCount = cachedCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = true,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildCacheBookItem(
        manifest: CacheBookManifest,
        mode: CacheManageMode
    ): CacheBookItem? {
        val book = CacheManifestHelper.toBook(manifest)
        val chapters = CacheManifestHelper.toChapters(manifest)
        val cacheNames = getCacheFileNames(book)
        val rawCachedCount = if (mode == CacheManageMode.AUDIO) {
            getAudioCachedCount(chapters, manifest.cachedIndexes())
        } else {
            chapters.count {
                isChapterCached(book, it, cacheNames, validateImageContent = false)
            }
        }
        if (rawCachedCount <= 0) {
            if (mode == CacheManageMode.AUDIO) {
                CacheManifestHelper.delete(manifest)
            }
            return null
        }
        val totalChapterCount = manifest.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        return CacheBookItem(
            book = book,
            mode = mode,
            cacheKey = book.cacheRemoteKey(mode.name),
            groupKey = book.cacheGroupKey(mode.name),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = rawCachedCount.coerceAtMost(totalChapterCount),
            totalChapterCount = totalChapterCount,
            localCachedCount = rawCachedCount.coerceAtMost(totalChapterCount),
            manifest = manifest,
            inBookshelf = false,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun getFastCachedCount(cacheNames: Set<String>): Int {
        return cacheNames.count { it.endsWith(".nb") }
    }

    private fun getAudioCachedCount(chapters: List<BookChapter>): Int {
        return getAudioCachedCount(chapters, cachedIndexes = null)
    }

    private fun getAudioCachedCount(
        chapters: List<BookChapter>,
        cachedIndexes: Set<Int>? = null
    ): Int {
        return chapters
            .asSequence()
            .filterNot { it.isVolume }
            .filter { cachedIndexes == null || it.index in cachedIndexes }
            .count { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
    }

    private fun CacheBookManifest?.cachedIndexes(): Set<Int>? {
        return this
            ?.chapters
            ?.asSequence()
            ?.filter { it.cached }
            ?.mapTo(hashSetOf()) { it.index }
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
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty()
        chapters
            .forEach { ExoPlayerHelper.removeMediaCache(it.resourceUrl) }
    }

    private fun refreshManifest(book: Book) {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty()
        if (chapters.isEmpty()) {
            CacheManifestHelper.delete(book)
            return
        }
        val cacheNames = getCacheFileNames(book)
        CacheManifestHelper.write(book, chapters) {
            isChapterCached(book, it, cacheNames, validateImageContent = false)
        }
    }

    private suspend fun resolveAudioMediaRequest(
        book: Book,
        chapter: BookChapter
    ): ExoPlayerHelper.MediaRequest {
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() }
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

    private fun restoreChapterCachePackage(book: Book, unzipDir: File) {
        val payloadDir = resolveCachePayloadDir(unzipDir, CacheManifestHelper.MANIFEST_FILE_NAME)
        val cacheDir = BookHelp.getCacheDir(book)
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
        cacheDir.mkdirs()
        payloadDir.listFiles()?.forEach { file ->
            val target = File(cacheDir, file.name)
            if (file.isDirectory) {
                file.copyRecursively(target, overwrite = true)
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
        CacheManifestHelper.read(book)?.let { manifest ->
            val chapters = CacheManifestHelper.toChapters(manifest, book.bookUrl)
            if (chapters.isNotEmpty() && appDb.bookDao.has(book.bookUrl)) {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
        }
    }

    private fun restoreAudioCachePackage(book: Book, unzipDir: File) {
        val payloadDir = resolveCachePayloadDir(unzipDir, "manifest.json")
        val chapterCacheDir = File(payloadDir, "chapter_cache")
        if (chapterCacheDir.exists()) {
            val cacheDir = BookHelp.getCacheDir(book)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            chapterCacheDir.copyRecursively(cacheDir, overwrite = true)
        }
        val manifestFile = File(payloadDir, "manifest.json")
        if (!manifestFile.isFile) return
        val audioManifest = GSON.fromJsonObject<AudioCacheManifest>(manifestFile.readText()).getOrNull()
            ?: return
        val chapters = audioManifest.chapters.map { chapter ->
            BookChapter(
                url = chapter.url,
                title = chapter.title,
                bookUrl = book.bookUrl,
                index = chapter.index,
                resourceUrl = chapter.resourceUrl
            )
        }
        val audioDir = File(payloadDir, "audio_cache")
        audioManifest.chapters.forEach { chapter ->
            val resourceUrl = chapter.resourceUrl ?: return@forEach
            val sourceDir = File(audioDir, chapter.index.toString())
            ExoPlayerHelper.importMediaCache(resourceUrl, sourceDir)
        }
        if (chapters.isNotEmpty() && appDb.bookDao.has(book.bookUrl)) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
        }
    }

    private fun resolveCachePayloadDir(unzipDir: File, markerName: String): File {
        if (File(unzipDir, markerName).exists()) return unzipDir
        val childDirs = unzipDir.listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()
        return childDirs.firstOrNull { File(it, markerName).exists() }
            ?: childDirs.singleOrNull()
            ?: unzipDir
    }

    private fun hasRestoredLocalCache(book: Book, mode: CacheManageMode): Boolean {
        val manifest = CacheManifestHelper.read(book) ?: return false
        val chapters = CacheManifestHelper.toChapters(manifest)
        if (mode == CacheManageMode.AUDIO) {
            return getAudioCachedCount(chapters, manifest.cachedIndexes()) > 0
        }
        val cacheNames = getCacheFileNames(book)
        return chapters.any { isChapterCached(book, it, cacheNames, validateImageContent = false) }
    }

    private fun CacheBookItem.toSourceVariant(): CacheBookSourceVariant {
        return CacheBookSourceVariant(
            cacheKey = cacheKey,
            sourceKey = sourceKey,
            sourceName = sourceName,
            book = book,
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            localCachedCount = localCachedCount,
            remoteCachedCount = remoteCachedCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = inBookshelf,
            sourceAvailable = sourceAvailable,
            remoteAvailable = remoteAvailable,
            remoteUpdatedAt = remoteUpdatedAt,
            remoteZipFileName = remoteZipFileName
        )
    }
}

enum class CacheManageMode(@StringRes val titleRes: Int, val bookType: Int) {
    BOOK(R.string.cache_manage_books, BookType.text),
    AUDIO(R.string.cache_manage_audio, BookType.audio),
    MANGA(R.string.cache_manage_manga, BookType.image)
}

enum class CacheChapterFilter {
    ALL,
    CACHED,
    UNCACHED
}

data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val cacheKey: String,
    val groupKey: String,
    val sourceKey: String,
    val sourceName: String,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val localCachedCount: Int,
    val remoteCachedCount: Int = 0,
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true,
    val remoteAvailable: Boolean = false,
    val remoteUpdatedAt: Long = 0L,
    val remoteZipFileName: String? = null,
    val sourceVariants: List<CacheBookSourceVariant> = emptyList()
)

data class CacheBookSourceVariant(
    val cacheKey: String,
    val sourceKey: String,
    val sourceName: String,
    val book: Book,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val localCachedCount: Int,
    val remoteCachedCount: Int = 0,
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true,
    val remoteAvailable: Boolean = false,
    val remoteUpdatedAt: Long = 0L,
    val remoteZipFileName: String? = null
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

private fun CacheBookManifest.matches(mode: CacheManageMode): Boolean {
    return type and mode.bookType > 0
}

private fun AudioCacheTaskState?.isVisibleAudioTask(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun List<BookChapter>.filterByKey(key: String?): List<BookChapter> {
    if (key.isNullOrBlank()) return this
    return filter { it.title.contains(key, ignoreCase = true) }
}

private fun List<Int>.toRanges(): List<Pair<Int, Int>> {
    if (isEmpty()) return emptyList()
    val ranges = arrayListOf<Pair<Int, Int>>()
    var start = first()
    var previous = first()
    drop(1).forEach { value ->
        if (value == previous + 1) {
            previous = value
        } else {
            ranges.add(start to previous)
            start = value
            previous = value
        }
    }
    ranges.add(start to previous)
    return ranges
}
