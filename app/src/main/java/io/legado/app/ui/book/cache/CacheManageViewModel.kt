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
import io.legado.app.help.CacheManager
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
    private val selectedSourceKeys = loadSelectedSourceKeys()
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
                val localItems = currentItems + manifestItems
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
        saveSelectedSourceKeys()
        load()
    }

    private fun mergeRemoteItems(
        localItems: List<CacheBookItem>,
        remoteIndex: List<CacheCloudIndexItem>
    ): List<CacheBookItem> {
        val flatLocalItems = flattenCacheItemsForGrouping(localItems)
        val localByKey = flatLocalItems.associateBy { it.cacheKey }
        val remoteByKey = remoteIndex
            .asSequence()
            .filter { it.mode == mode.name }
            .associateBy { it.cacheKey }
        val merged = arrayListOf<CacheBookItem>()
        flatLocalItems.forEach { item ->
            val remote = remoteByKey[item.cacheKey]
            merged += if (remote != null) {
                val remoteCount = remote.cachedChapterCount.coerceAtLeast(0)
                val totalCount = maxOf(item.totalChapterCount, remote.totalChapterCount, remoteCount)
                item.copy(
                    cachedCount = maxOf(item.localCachedCount, remoteCount).coerceAtMost(totalCount),
                    totalChapterCount = totalCount,
                    remoteAvailable = true,
                    remoteUpdatedAt = remote.updatedAt,
                    remoteZipFileName = remote.zipFileName,
                    remoteCachedCount = remoteCount
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
            cachedCount = remote.cachedChapterCount.coerceAtMost(remote.totalChapterCount.coerceAtLeast(remote.cachedChapterCount)),
            totalChapterCount = remote.totalChapterCount.coerceAtLeast(remote.cachedChapterCount),
            localCachedCount = 0,
            remoteCachedCount = remote.cachedChapterCount,
            inBookshelf = appDb.bookDao.has(remote.bookUrl),
            sourceAvailable = book.isLocal || book.getBookSource() != null,
            remoteAvailable = true,
            remoteUpdatedAt = remote.updatedAt,
            remoteZipFileName = remote.zipFileName
        )
    }

    fun deleteBookCache(item: CacheBookItem, target: CacheDeleteTarget, onDone: (CacheDeleteResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                deleteCacheItem(item, target)
            }.getOrElse {
                CacheDeleteResult(errorMessage = it.localizedMessage ?: context.getString(R.string.error))
            }
            withContext(Dispatchers.Main) {
                onDone(result)
            }
            load(mode)
        }
    }

    fun deleteBookCaches(items: List<CacheBookItem>, target: CacheDeleteTarget, onDone: (CacheDeleteResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                var deletedLocal = 0
                var deletedRemote = 0
                items.forEach { item ->
                    val result = deleteCacheItem(item, target, reloadRemoteIndex = false)
                    deletedLocal += result.deletedLocal
                    deletedRemote += result.deletedRemote
                }
                if (target.deleteRemote) {
                    refreshRemoteCacheIndexAfterDelete(items.map { it.cacheKey }.toSet())
                }
                CacheDeleteResult(deletedLocal, deletedRemote)
            }.getOrElse {
                CacheDeleteResult(errorMessage = it.localizedMessage ?: context.getString(R.string.error))
            }
            withContext(Dispatchers.Main) {
                onDone(result)
            }
            load(mode)
        }
    }

    private suspend fun deleteCacheItem(
        item: CacheBookItem,
        target: CacheDeleteTarget,
        reloadRemoteIndex: Boolean = true
    ): CacheDeleteResult {
        var deletedLocal = 0
        var deletedRemote = 0
        if (target.deleteLocal && item.localCachedCount > 0) {
            deleteLocalCache(item.book)
            deletedLocal = 1
        }
        if (target.deleteRemote && item.hasRemoteCache()) {
            item.remoteZipFileName?.takeIf { it.isNotBlank() }?.let { zipFileName ->
                runCatching { AppWebDav.deleteCachePackage(zipFileName) }
            }
            CacheCloudIndexStore.removeLocal(item.cacheKey)
            if (reloadRemoteIndex) {
                refreshRemoteCacheIndexAfterDelete(setOf(item.cacheKey))
            }
            deletedRemote = 1
        }
        return CacheDeleteResult(deletedLocal, deletedRemote)
    }

    private fun deleteLocalCache(book: Book) {
        deleteAudioMediaCache(book)
        BookHelp.clearCache(book)
        CacheManifestHelper.delete(book)
    }

    private suspend fun refreshRemoteCacheIndexAfterDelete(cacheKeys: Set<String>) {
        if (cacheKeys.isEmpty()) return
        val remoteItems = AppWebDav.downloadCacheIndex().items.filterNot { it.cacheKey in cacheKeys }
        AppWebDav.uploadCacheIndex(CacheCloudIndex(items = remoteItems))
        cacheKeys.forEach(CacheCloudIndexStore::removeLocal)
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
                replaceBookChapters(targetBook.bookUrl, chapters)
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
            val manifest = refreshManifest(book)
                ?: throw IllegalStateException(context.getString(R.string.cache_manage_manifest_missing))
            if (manifest.cachedChapterCount <= 0) {
                throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
            }
            if (!ZipUtils.zipFile(cacheDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
            }
            zipFile
        }
    }

    suspend fun uploadCacheItem(
        item: CacheBookItem,
        strategy: CacheSyncStrategy = CacheSyncStrategy.OVERWRITE
    ) {
        withContext(Dispatchers.IO) {
            val zipFile = if (strategy == CacheSyncStrategy.MERGE && item.hasRemoteCache()) {
                createMergedUploadPackage(item)
            } else {
                createCachePackage(item.book)
            }
            val freshItem = item.withFreshLocalManifest()
            val indexItem = createCacheIndexItem(
                freshItem,
                cachedCountOverride = if (strategy == CacheSyncStrategy.MERGE) readPackageCachedCount(zipFile, item.mode) else null
            )
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

    suspend fun downloadRemoteCache(
        item: CacheBookItem,
        strategy: CacheSyncStrategy = CacheSyncStrategy.OVERWRITE
    ): Boolean {
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
                    restoreAudioCachePackage(item.book, unzipDir, strategy)
                } else {
                    restoreChapterCachePackage(item.book, unzipDir, item, strategy)
                }
                hasRestoredLocalCache(item.book, item.mode)
            } finally {
                zipFile.delete()
                unzipDir.deleteRecursively()
            }
        }
    }

    private fun CacheBookItem.withFreshLocalManifest(): CacheBookItem {
        val manifest = CacheManifestHelper.read(book)
        val localCount = manifest?.cachedChapterCount?.takeIf { it > 0 } ?: localCachedCount
        val totalCount = manifest?.totalChapterNum?.takeIf { it > 0 } ?: totalChapterCount
        return copy(
            cachedCount = localCount.coerceAtMost(totalCount),
            totalChapterCount = totalCount,
            localCachedCount = localCount.coerceAtMost(totalCount),
            manifest = manifest ?: this.manifest
        )
    }

    private fun createCacheIndexItem(
        item: CacheBookItem,
        cachedCountOverride: Int? = null
    ): CacheCloudIndexItem {
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
            cachedChapterCount = cachedCountOverride ?: item.localCachedCount.coerceAtLeast(item.cachedCount),
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
        return flattenCacheItemsForGrouping(items)
            .groupBy { it.groupKey }
            .values
            .mapNotNull { group ->
                val variants = group
                    .sortedWith(
                        compareByDescending<CacheBookItem> { if (it.taskState.isVisibleAudioTask()) 1 else 0 }
                            .thenByDescending { maxOf(it.localCachedCount, it.remoteCachedCount) }
                            .thenByDescending { it.cachedCount }
                            .thenBy { it.sourceName }
                    )
                    .map { it.toSourceVariant() }
                val groupKey = group.firstOrNull()?.groupKey ?: return@mapNotNull null
                val selectedKey = selectedSourceKeys[groupKey]
                val selected = group.firstOrNull { it.sourceKey == selectedKey }
                    ?: group.firstOrNull { it.taskState.isVisibleAudioTask() }
                    ?: group.maxWithOrNull(
                        compareBy<CacheBookItem> { maxOf(it.localCachedCount, it.remoteCachedCount) }
                            .thenBy { it.cachedCount }
                            .thenBy { it.totalChapterCount }
                    )
                    ?: group.first()
                selected.copy(sourceVariants = variants)
            }
            .sortedWith(
                compareByDescending<CacheBookItem> { maxOf(it.localCachedCount, it.remoteCachedCount) }
                    .thenByDescending { it.cachedCount }
                    .thenBy { it.book.name }
                    .thenBy { it.sourceName }
            )
    }

    private fun flattenCacheItemsForGrouping(items: List<CacheBookItem>): List<CacheBookItem> {
        val flattened = linkedMapOf<String, CacheBookItem>()
        fun put(item: CacheBookItem) {
            flattened[item.cacheKey] = item.copy(sourceVariants = emptyList())
        }
        items.forEach { item ->
            put(item)
            item.sourceVariants.forEach { variant ->
                put(variant.toCacheBookItem(item.mode, item.groupKey))
            }
        }
        return flattened.values.toList()
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

    private fun getCacheFileNames(cacheDir: File): Set<String> {
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

    private fun refreshManifest(book: Book): CacheBookManifest? {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty()
        if (chapters.isEmpty()) {
            CacheManifestHelper.delete(book)
            return null
        }
        val cacheNames = getCacheFileNames(book)
        return CacheManifestHelper.write(book, chapters) {
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

    private suspend fun createMergedUploadPackage(item: CacheBookItem): File {
        val zipFileName = item.remoteZipFileName ?: return createCachePackage(item.book)
        return withContext(Dispatchers.IO) {
            val tempDir = File(appCtx.externalCache, "cache_merge_upload").apply { mkdirs() }
            val remoteZip = File(tempDir, "${zipFileName.removeSuffix(".zip")}_remote.zip").apply {
                if (exists()) delete()
            }
            val remoteDir = File(tempDir, "${zipFileName.removeSuffix(".zip")}_remote").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val mergeDir = File(tempDir, "${zipFileName.removeSuffix(".zip")}_merged_${System.currentTimeMillis()}").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val mergedZip = File(tempDir, "${zipFileName.removeSuffix(".zip")}_merged.zip").apply {
                if (exists()) delete()
            }
            try {
                AppWebDav.downloadCachePackage(zipFileName, remoteZip)
                ZipUtils.unZipToPath(remoteZip, remoteDir)
                if (item.mode == CacheManageMode.AUDIO) {
                    val remotePayload = resolveCachePayloadDir(remoteDir, "manifest.json")
                    copyDirectoryContents(remotePayload, mergeDir, overwrite = true)
                    mergeAudioLocalIntoPackage(item.book, mergeDir)
                } else {
                    val remotePayload = resolveCachePayloadDir(remoteDir, CacheManifestHelper.MANIFEST_FILE_NAME)
                    copyDirectoryContents(remotePayload, mergeDir, overwrite = true)
                    mergeChapterLocalIntoPackage(item.book, mergeDir)
                }
                if (!ZipUtils.zipFile(mergeDir, mergedZip) || !mergedZip.exists() || mergedZip.length() <= 0L) {
                    throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
                }
                mergedZip
            } finally {
                remoteZip.delete()
                remoteDir.deleteRecursively()
                mergeDir.deleteRecursively()
            }
        }
    }

    private fun readPackageCachedCount(zipFile: File, mode: CacheManageMode): Int? {
        val tempDir = File(appCtx.externalCache, "cache_package_count").apply { mkdirs() }
        val unzipDir = File(tempDir, "${zipFile.nameWithoutExtension}_${System.currentTimeMillis()}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            if (mode == CacheManageMode.AUDIO) {
                val manifestFile = File(resolveCachePayloadDir(unzipDir, "manifest.json"), "manifest.json")
                GSON.fromJsonObject<AudioCacheManifest>(manifestFile.readText()).getOrNull()?.chapters?.size
            } else {
                val manifest = CacheManifestHelper.read(File(resolveCachePayloadDir(unzipDir, CacheManifestHelper.MANIFEST_FILE_NAME), CacheManifestHelper.MANIFEST_FILE_NAME))
                manifest?.cachedChapterCount
            }
        } catch (_: Throwable) {
            null
        } finally {
            unzipDir.deleteRecursively()
        }
    }

    private fun mergeChapterLocalIntoPackage(book: Book, packageDir: File) {
        val localCacheDir = BookHelp.getCacheDir(book)
        if (!localCacheDir.exists()) return
        val remoteManifest = CacheManifestHelper.read(File(packageDir, CacheManifestHelper.MANIFEST_FILE_NAME))
        copyDirectoryContents(localCacheDir, packageDir, overwrite = true)
        val chapters = mergeChapterLists(
            remoteManifest?.let { CacheManifestHelper.toChapters(it, book.bookUrl) }.orEmpty(),
            CacheManifestHelper.read(book)?.let { CacheManifestHelper.toChapters(it, book.bookUrl) }.orEmpty(),
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        )
        if (chapters.isEmpty()) return
        writeChapterManifestToDir(book, chapters, packageDir, remoteManifest)
    }

    private fun mergeAudioLocalIntoPackage(book: Book, packageDir: File) {
        val localCacheDir = BookHelp.getCacheDir(book)
        if (localCacheDir.exists()) {
            copyDirectoryContents(localCacheDir, File(packageDir, "chapter_cache"), overwrite = true)
        }
        val manifestFile = File(packageDir, "manifest.json")
        val oldManifest = manifestFile.takeIf { it.isFile }
            ?.let { GSON.fromJsonObject<AudioCacheManifest>(it.readText()).getOrNull() }
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: oldManifest?.chapters?.map {
                BookChapter(
                    url = it.url,
                    title = it.title,
                    bookUrl = book.bookUrl,
                    index = it.index,
                    resourceUrl = it.resourceUrl
                )
            }
            ?: emptyList()
        if (chapters.isEmpty()) return
        manifestFile.writeText(
            GSON.toJson(
                AudioCacheManifest(
                    bookName = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    chapters = chapters.filterNot { it.isVolume }.map {
                        AudioCacheManifest.Chapter(
                            index = it.index,
                            title = it.title,
                            url = it.url,
                            resourceUrl = it.resourceUrl,
                            fileCount = 0
                        )
                    }
                )
            )
        )
    }

    private fun restoreChapterCachePackage(
        book: Book,
        unzipDir: File,
        item: CacheBookItem? = null,
        strategy: CacheSyncStrategy = CacheSyncStrategy.OVERWRITE
    ) {
        val payloadDir = resolveCachePayloadDir(unzipDir, CacheManifestHelper.MANIFEST_FILE_NAME)
        val remoteManifest = CacheManifestHelper.read(File(payloadDir, CacheManifestHelper.MANIFEST_FILE_NAME))
        val localManifest = CacheManifestHelper.read(book)
        val cacheDir = BookHelp.getCacheDir(book)
        val stagingDir = if (strategy == CacheSyncStrategy.MERGE) {
            copyMergedPayloadToStaging(
                baseDir = cacheDir,
                payloadDir = payloadDir,
                targetDir = cacheDir,
                remoteWins = true
            )
        } else {
            copyPayloadToStaging(payloadDir, cacheDir)
        }
        if (strategy == CacheSyncStrategy.MERGE) {
            val chapters = mergeChapterLists(
                remoteManifest?.let { CacheManifestHelper.toChapters(it, book.bookUrl) }.orEmpty(),
                localManifest?.let { CacheManifestHelper.toChapters(it, book.bookUrl) }.orEmpty(),
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            )
            writeChapterManifestToDir(book, chapters, stagingDir, remoteManifest ?: localManifest ?: item?.manifest)
        }
        replaceDirectory(cacheDir, stagingDir)
        val manifest = CacheManifestHelper.read(book) ?: rebuildManifestForRestoredCache(book, item)
        if (manifest == null) {
            throw IllegalStateException(context.getString(R.string.cache_manage_legacy_manifest_missing))
        }
        val chapters = CacheManifestHelper.toChapters(manifest, book.bookUrl)
        if (chapters.isNotEmpty() && appDb.bookDao.has(book.bookUrl)) {
            replaceBookChapters(book.bookUrl, chapters)
        }
    }

    private fun rebuildManifestForRestoredCache(book: Book, item: CacheBookItem?): CacheBookManifest? {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: item?.manifest?.let { CacheManifestHelper.toChapters(it, book.bookUrl) }.orEmpty()
        if (chapters.isEmpty()) return null
        val cacheNames = getCacheFileNames(book)
        return CacheManifestHelper.write(book, chapters) {
            isChapterCached(book, it, cacheNames, validateImageContent = false)
        }
    }

    private fun mergeChapterLists(vararg lists: List<BookChapter>): List<BookChapter> {
        val merged = linkedMapOf<Int, BookChapter>()
        lists.forEach { chapters ->
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .forEach { chapter ->
                    merged[chapter.index] = chapter
                }
        }
        return merged.values.sortedBy { it.index }
    }

    private fun writeChapterManifestToDir(
        book: Book,
        chapters: List<BookChapter>,
        packageDir: File,
        fallback: CacheBookManifest?
    ): CacheBookManifest? {
        val realChapters = chapters.filterNot { it.isVolume }
        if (realChapters.isEmpty()) return null
        val cacheNames = getCacheFileNames(packageDir)
        val cachedByIndex = realChapters.associate { chapter ->
            chapter.index to BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        }
        val cachedCount = cachedByIndex.values.count { it }
        if (cachedCount <= 0) return null
        val manifest = CacheBookManifest(
            bookUrl = book.bookUrl.ifBlank { fallback?.bookUrl.orEmpty() },
            tocUrl = book.tocUrl.ifBlank { fallback?.tocUrl.orEmpty() },
            origin = book.origin.ifBlank { fallback?.origin.orEmpty() },
            originName = book.originName.ifBlank { fallback?.originName.orEmpty() },
            name = book.name.ifBlank { fallback?.name.orEmpty() },
            author = book.author.ifBlank { fallback?.author.orEmpty() },
            kind = book.kind ?: fallback?.kind,
            coverUrl = book.coverUrl ?: fallback?.coverUrl,
            intro = book.intro ?: fallback?.intro,
            type = book.type.takeIf { it > 0 } ?: fallback?.type ?: 0,
            folderName = book.getFolderName(),
            latestChapterTitle = book.latestChapterTitle ?: fallback?.latestChapterTitle,
            totalChapterNum = realChapters.size.takeIf { it > 0 } ?: book.totalChapterNum,
            updatedAt = System.currentTimeMillis(),
            chapters = realChapters.map { chapter ->
                io.legado.app.help.book.CacheChapterManifest(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    baseUrl = chapter.baseUrl,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl,
                    cached = cachedByIndex[chapter.index] == true
                )
            }
        )
        File(packageDir, CacheManifestHelper.MANIFEST_FILE_NAME).writeText(GSON.toJson(manifest))
        return manifest
    }

    private fun loadSelectedSourceKeys(): HashMap<String, String> {
        return GSON.fromJsonObject<Map<String, String>>(CacheManager.get(SELECTED_SOURCE_CACHE_KEY))
            .getOrNull()
            ?.toMap()
            ?.let { HashMap(it) }
            ?: hashMapOf()
    }

    private fun saveSelectedSourceKeys() {
        if (selectedSourceKeys.isEmpty()) {
            CacheManager.delete(SELECTED_SOURCE_CACHE_KEY)
        } else {
            CacheManager.put(SELECTED_SOURCE_CACHE_KEY, GSON.toJson(selectedSourceKeys))
        }
    }

    private companion object {
        private const val SELECTED_SOURCE_CACHE_KEY = "cacheManageSelectedSourceKeys"
    }

    private fun restoreAudioCachePackage(
        book: Book,
        unzipDir: File,
        strategy: CacheSyncStrategy = CacheSyncStrategy.OVERWRITE
    ) {
        val payloadDir = resolveCachePayloadDir(unzipDir, "manifest.json")
        val chapterCacheDir = File(payloadDir, "chapter_cache")
        if (chapterCacheDir.exists()) {
            val cacheDir = BookHelp.getCacheDir(book)
            val stagingDir = if (strategy == CacheSyncStrategy.MERGE) {
                copyMergedPayloadToStaging(
                    baseDir = cacheDir,
                    payloadDir = chapterCacheDir,
                    targetDir = cacheDir,
                    remoteWins = true
                )
            } else {
                copyPayloadToStaging(chapterCacheDir, cacheDir)
            }
            replaceDirectory(cacheDir, stagingDir)
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
            if (!sourceDir.exists()) return@forEach
            ExoPlayerHelper.importMediaCache(resourceUrl, sourceDir)
        }
        if (chapters.isNotEmpty() && appDb.bookDao.has(book.bookUrl)) {
            replaceBookChapters(book.bookUrl, chapters)
        }
    }

    private fun replaceBookChapters(bookUrl: String, chapters: List<BookChapter>) {
        appDb.runInTransaction {
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
        }
    }

    private fun copyPayloadToStaging(payloadDir: File, targetDir: File): File {
        require(payloadDir.exists() && payloadDir.isDirectory) {
            context.getString(R.string.cache_manage_download_failed_simple)
        }
        val parent = targetDir.parentFile
            ?: throw IllegalStateException(context.getString(R.string.cache_manage_download_failed_simple))
        parent.mkdirs()
        val stagingDir = File(parent, "${targetDir.name}.restore_${System.currentTimeMillis()}")
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        stagingDir.mkdirs()
        payloadDir.listFiles()?.forEach { file ->
            val target = File(stagingDir, file.name)
            if (file.isDirectory) {
                file.copyRecursively(target, overwrite = true)
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
        if (stagingDir.listFiles().isNullOrEmpty()) {
            stagingDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
        }
        return stagingDir
    }

    private fun copyMergedPayloadToStaging(
        baseDir: File,
        payloadDir: File,
        targetDir: File,
        remoteWins: Boolean
    ): File {
        val parent = targetDir.parentFile
            ?: throw IllegalStateException(context.getString(R.string.cache_manage_download_failed_simple))
        parent.mkdirs()
        val stagingDir = File(parent, "${targetDir.name}.merge_${System.currentTimeMillis()}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        if (baseDir.exists()) {
            copyDirectoryContents(baseDir, stagingDir, overwrite = true)
        }
        copyDirectoryContents(payloadDir, stagingDir, overwrite = remoteWins)
        if (stagingDir.listFiles().isNullOrEmpty()) {
            stagingDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
        }
        return stagingDir
    }

    private fun copyDirectoryContents(sourceDir: File, targetDir: File, overwrite: Boolean) {
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        targetDir.mkdirs()
        sourceDir.listFiles()?.forEach { file ->
            val target = File(targetDir, file.name)
            if (file.isDirectory) {
                if (target.exists() && !target.isDirectory) {
                    if (overwrite) target.delete() else return@forEach
                }
                copyDirectoryContents(file, target, overwrite)
            } else if (overwrite || !target.exists()) {
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private fun replaceDirectory(targetDir: File, replacementDir: File) {
        val parent = targetDir.parentFile
            ?: throw IllegalStateException(context.getString(R.string.cache_manage_download_failed_simple))
        parent.mkdirs()
        val backupDir = File(parent, "${targetDir.name}.backup_${System.currentTimeMillis()}")
        val hadOld = targetDir.exists()
        if (hadOld && !targetDir.renameTo(backupDir)) {
            replacementDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_download_failed_simple))
        }
        try {
            if (!replacementDir.renameTo(targetDir)) {
                replacementDir.copyRecursively(targetDir, overwrite = true)
                replacementDir.deleteRecursively()
            }
            if (hadOld) {
                backupDir.deleteRecursively()
            }
        } catch (e: Throwable) {
            targetDir.deleteRecursively()
            if (hadOld && backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            throw e
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

    private fun CacheBookSourceVariant.toCacheBookItem(
        mode: CacheManageMode,
        groupKey: String
    ): CacheBookItem {
        return CacheBookItem(
            book = book,
            mode = mode,
            cacheKey = cacheKey,
            groupKey = groupKey,
            sourceKey = sourceKey,
            sourceName = sourceName,
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

enum class CacheDeleteTarget(
    @param:StringRes val labelRes: Int,
    val deleteLocal: Boolean,
    val deleteRemote: Boolean
) {
    LOCAL(R.string.cache_manage_delete_local, deleteLocal = true, deleteRemote = false),
    REMOTE(R.string.cache_manage_delete_remote, deleteLocal = false, deleteRemote = true),
    BOTH(R.string.cache_manage_delete_both, deleteLocal = true, deleteRemote = true)
}

enum class CacheSyncStrategy(@param:StringRes val labelRes: Int) {
    OVERWRITE(R.string.cache_manage_sync_overwrite),
    MERGE(R.string.cache_manage_sync_merge)
}

data class CacheDeleteResult(
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val errorMessage: String? = null
) {
    @get:StringRes
    val messageRes: Int?
        get() = if (deletedLocal > 0 || deletedRemote > 0) {
            R.string.delete_success
        } else if (errorMessage != null) {
            null
        } else {
            R.string.cache_manage_delete_none
        }
}

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

fun CacheBookItem.hasRemoteCache(): Boolean {
    return remoteAvailable && (remoteCachedCount > 0 || !remoteZipFileName.isNullOrBlank())
}

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
