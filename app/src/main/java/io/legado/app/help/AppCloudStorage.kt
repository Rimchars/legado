package io.legado.app.help

import android.net.Uri
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.CacheCloudIndex
import io.legado.app.help.book.CacheCloudIndexStore
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.cloud.CloudStorageBackend
import io.legado.app.lib.cloud.CloudStorageFile
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3BackupIndexItem
import io.legado.app.lib.cloud.S3CloudStorageBackend
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerManager
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.cloud.WebDavCloudStorageBackend
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

object AppCloudStorage {

    private const val BOOK_PROGRESS_DIR = "bookProgress/"
    private const val EXPORTS_DIR = "books/"
    private const val BG_DIR = "background/"
    private const val THEMES_DIR = "themes/"
    private const val NAVIGATION_BARS_DIR = "navigationBars/"
    private const val TOP_BARS_DIR = "topBars/"
    private const val COVER_COLLECTIONS_DIR = "coverCollections/"

    private val webDavBackend = WebDavCloudStorageBackend()
    private val s3Backend = S3CloudStorageBackend()
    private var backend: CloudStorageBackend = webDavBackend
    val type: CloudStorageType
        get() = CloudStorageType.from(appCtx.getPrefString(PreferKey.cloudStorageType))

    val isOk: Boolean
        get() = backend.isOk

    val isJianGuoYun: Boolean
        get() = backend.isJianGuoYun

    val defaultBookWebDav: RemoteBookWebDav?
        get() = webDavBackend.defaultBookWebDav

    init {
        runBlocking {
            upConfig()
        }
    }

    suspend fun upConfig() {
        if (type == CloudStorageType.S3) S3ContainerManager.containers()
        backend = when (type) {
            CloudStorageType.WEBDAV -> webDavBackend
            CloudStorageType.S3 -> s3Backend
        }
        backend.upConfig()
    }

    suspend fun getBackupNames(): ArrayList<String> {
        ensureNetwork()
        val files = backupFiles()
            .sortedWith { o1, o2 -> AlphanumComparator.compare(o1.displayName, o2.displayName) }
            .reversed()
        return ArrayList(files.map { it.displayName })
    }

    suspend fun restore(name: String) {
        val location = findBackupLocation(name)
        if (location != null && type == CloudStorageType.S3) {
            s3Backend.downloadTo(location.containerId, name, File(Backup.zipFilePath), true)
        } else {
            storage(S3ContainerScope.MAIN_BACKUP).downloadTo(name, File(Backup.zipFilePath), true)
        }
        FileUtils.delete(Backup.backupPath)
        ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
        Restore.restoreLocked(Backup.backupPath)
    }

    suspend fun hasBackup(name: String): Boolean {
        return if (type == CloudStorageType.S3) {
            findBackupLocation(name) != null
        } else {
            backend.exists(name)
        }
    }

    suspend fun lastBackup(): Result<CloudStorageFile?> {
        return kotlin.runCatching {
            backupFiles().maxByOrNull { it.lastModify }
        }
    }

    suspend fun backup(fileName: String) {
        ensureNetwork()
        storage(S3ContainerScope.MAIN_BACKUP).upload(fileName, Backup.zipFilePath)
    }

    suspend fun backupToWebDav(fileName: String) {
        ensureNetwork()
        webDavBackend.upConfig()
        webDavBackend.upload(fileName, Backup.zipFilePath)
    }

    fun listContainers(): List<S3Container> = S3ContainerManager.listContainers()

    fun saveContainers(containers: List<S3Container>) = S3ContainerManager.saveContainers(containers)

    fun addContainer(container: S3Container): S3Container = S3ContainerManager.addContainer(container)

    fun updateContainer(container: S3Container) = S3ContainerManager.updateContainer(container)

    fun deleteContainer(id: String) = S3ContainerManager.deleteContainer(id)

    suspend fun refreshUsage(containerId: String): S3Container = s3Backend.refreshUsage(containerId)

    fun selectContainer(scope: S3ContainerScope, id: String?) = S3ContainerManager.selectContainer(scope, id)

    fun selectContainer(scope: String, id: String?) = selectContainer(S3ContainerScope.from(scope), id)

    fun selectedContainer(scope: S3ContainerScope = S3ContainerScope.DEFAULT): S3Container? =
        S3ContainerManager.selectedContainer(scope)

    fun selectedContainer(scope: String): S3Container? = selectedContainer(S3ContainerScope.from(scope))

    fun containerDisplayLabel(container: S3Container?): String = S3ContainerManager.displayLabel(container)

    fun containerDisplayLabel(containerId: String?): String = containerDisplayLabel(S3ContainerManager.container(containerId))
    suspend fun listThemePackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getThemeTypePath(isNightTheme), scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun uploadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getThemeTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun downloadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getThemeTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun deleteThemePackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getThemeTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.THEME, containerId, scope))
    }

    suspend fun listNavigationBarPackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getNavigationBarTypePath(isNightTheme), scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun uploadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getNavigationBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun downloadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getNavigationBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun deleteNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getNavigationBarTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.NAVIGATION_BAR, containerId, scope))
    }

    suspend fun listTopBarPackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getTopBarTypePath(isNightTheme), scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun uploadTopBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getTopBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun downloadTopBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getTopBarTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun deleteTopBarPackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getTopBarTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.TOP_BAR, containerId, scope))
    }

    suspend fun listCoverCollectionPackages(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<CloudStorageFile> {
        return listZipFiles(getCoverCollectionTypePath(isNightTheme), scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope), emptyOnError = true)
    }

    suspend fun uploadCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        uploadZip(getCoverCollectionTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope))
    }

    suspend fun downloadCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File, containerId: String? = null, scope: String? = null) {
        downloadZip(getCoverCollectionTypePath(isNightTheme), remoteDirName, zipFile, scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope))
    }

    suspend fun deleteCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, containerId: String? = null, scope: String? = null) {
        deleteZip(getCoverCollectionTypePath(isNightTheme), remoteDirName, scopedBackend(S3ContainerScope.COVER_COLLECTION, containerId, scope))
    }
    suspend fun uploadCachePackage(fileName: String, zipFile: File) {
        ensureNetwork()
        val target = storage(S3ContainerScope.CACHE)
        target.makeDir(EXPORTS_DIR)
        target.upload(EXPORTS_DIR + normalizeCachePackageFileName(fileName), zipFile, "application/zip")
    }

    suspend fun downloadCachePackage(fileName: String, zipFile: File) {
        ensureNetwork()
        var lastError: Throwable? = null
        cachePackageFileNameCandidates(fileName).forEach { candidate ->
            runCatching {
                storage(S3ContainerScope.CACHE).downloadTo(EXPORTS_DIR + candidate, zipFile, true)
            }.onSuccess {
                return
            }.onFailure {
                lastError = it
            }
        }
        throw lastError ?: NoStackTraceException("Cache package not found")
    }

    suspend fun deleteCachePackage(fileName: String) {
        ensureNetwork()
        storage(S3ContainerScope.CACHE).delete(EXPORTS_DIR + normalizeCachePackageFileName(fileName))
    }

    suspend fun downloadCacheIndex(): CacheCloudIndex {
        if (!NetworkUtils.isAvailable() || !storage(S3ContainerScope.CACHE).isOk) return CacheCloudIndex()
        return kotlin.runCatching {
            val bytes = storage(S3ContainerScope.CACHE).download(EXPORTS_DIR + CacheCloudIndexStore.remoteFileName())
            GSON.fromJsonObject<CacheCloudIndex>(String(bytes)).getOrNull() ?: CacheCloudIndex()
        }.getOrElse { CacheCloudIndex() }
    }

    suspend fun uploadCacheIndex(index: CacheCloudIndex) {
        ensureNetwork()
        val target = storage(S3ContainerScope.CACHE)
        target.makeDir(EXPORTS_DIR)
        target.upload(EXPORTS_DIR + CacheCloudIndexStore.remoteFileName(), GSON.toJson(index).toByteArray(), "application/json")
    }

    suspend fun upBgs(files: Array<File>) {
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        val remoteNames = runCatching { target.listFiles(BG_DIR).map { it.displayName }.toSet() }.getOrDefault(emptySet())
        files.forEach {
            if (!remoteNames.contains(it.name) && it.exists()) {
                target.upload(BG_DIR + it.name, it)
            }
        }
    }

    suspend fun export(byteArray: ByteArray, fileName: String) {
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        target.upload(EXPORTS_DIR + fileName, byteArray, "text/plain")
    }

    suspend fun export(uri: Uri, fileName: String) {
        val target = storage(S3ContainerScope.DEFAULT)
        if (!NetworkUtils.isAvailable() || !target.isOk) return
        target.upload(EXPORTS_DIR + fileName, uri, "text/plain")
    }

    suspend fun uploadBookProgress(book: Book, toast: Boolean = false, onSuccess: (() -> Unit)? = null) {
        if (!AppConfig.syncBookProgress || !NetworkUtils.isAvailable() || !backend.isOk) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            storage(S3ContainerScope.DEFAULT).upload(getProgressPath(book.name, book.author), json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            io.legado.app.constant.AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        if (!AppConfig.syncBookProgress || !NetworkUtils.isAvailable() || !backend.isOk) return
        val json = GSON.toJson(bookProgress)
        storage(S3ContainerScope.DEFAULT).upload(getProgressPath(bookProgress.name, bookProgress.author), json.toByteArray(), "application/json")
        onSuccess?.invoke()
    }

    suspend fun getBookProgress(book: Book): BookProgress? {
        val path = getProgressPath(book.name, book.author)
        kotlin.runCatching {
            val byteArray = storage(S3ContainerScope.DEFAULT).download(path)
            val json = String(byteArray)
            if (json.isJson()) {
                return GSON.fromJsonObject<BookProgress>(json).getOrNull()
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            io.legado.app.constant.AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        if (!NetworkUtils.isAvailable() || !backend.isOk) return
        val files = storage(S3ContainerScope.DEFAULT).listFiles(BOOK_PROGRESS_DIR)
        val map = files.associateBy { it.displayName }
        appDb.bookDao.all.forEach { book ->
            val file = map[getProgressFileName(book.name, book.author)] ?: return@forEach
            if (file.lastModify <= book.syncTime) return@forEach
            getBookProgress(book)?.let { progress ->
                if (progress.durChapterIndex > book.durChapterIndex ||
                    (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = progress.durChapterIndex
                    book.durChapterPos = progress.durChapterPos
                    book.durChapterTitle = progress.durChapterTitle
                    book.durChapterTime = progress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    private suspend fun listZipFiles(
        path: String,
        backend: CloudStorageBackend,
        emptyOnError: Boolean = false
    ): List<CloudStorageFile> {
        if (!NetworkUtils.isAvailable()) {
            if (emptyOnError) return emptyList()
            throw NoStackTraceException("Network unavailable")
        }
        backend.makeDir(path)
        return backend.listFiles(path).filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    private suspend fun uploadZip(path: String, remoteDirName: String, zipFile: File, backend: CloudStorageBackend) {
        ensureNetwork()
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        backend.makeDir(path)
        backend.upload(path + fileName, zipFile)
    }

    private suspend fun downloadZip(path: String, remoteDirName: String, zipFile: File, backend: CloudStorageBackend) {
        ensureNetwork()
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        backend.downloadTo(path + fileName, zipFile, true)
    }

    private suspend fun deleteZip(path: String, remoteDirName: String, backend: CloudStorageBackend) {
        ensureNetwork()
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        backend.delete(path + fileName)
    }

    private fun storage(scope: S3ContainerScope): CloudStorageBackend {
        return if (type == CloudStorageType.S3) {
            S3CloudStorageBackend(scope)
        } else {
            backend
        }
    }

    private fun scopedBackend(defaultScope: S3ContainerScope, containerId: String?, scope: String?): CloudStorageBackend {
        if (type != CloudStorageType.S3) return backend
        val targetScope = scope?.let { S3ContainerScope.from(it) } ?: defaultScope
        return S3CloudStorageBackend(targetScope, containerId)
    }

    private suspend fun backupFiles(): List<CloudStorageFile> {
        return if (type == CloudStorageType.S3) {
            scanBackupFiles()
        } else {
            storage(S3ContainerScope.MAIN_BACKUP).listFiles("")
                .filter { it.displayName.startsWith("backup") }
        }
    }

    private suspend fun findBackupLocation(fileName: String): S3BackupIndexItem? {
        return scanBackupIndexItems().firstOrNull { it.fileName == fileName }
    }

    private suspend fun scanBackupFiles(): List<CloudStorageFile> {
        return scanBackupIndexItems().map {
            CloudStorageFile(it.fileName, it.fileName, it.size, it.time, false)
        }
    }

    private suspend fun scanBackupIndexItems(): List<S3BackupIndexItem> {
        if (type != CloudStorageType.S3) return emptyList()
        return S3ContainerManager.listContainers().filter { it.enabled }.flatMap { container ->
            runCatching {
                s3Backend.listFiles(container.id, "")
                    .filter { it.displayName.startsWith("backup") }
                    .map { file ->
                        S3BackupIndexItem(file.displayName, file.lastModify, file.size, container.id)
                    }
            }.getOrDefault(emptyList())
        }
    }
    private fun getThemeTypePath(isNightTheme: Boolean): String = THEMES_DIR + if (isNightTheme) "night/" else "day/"
    private fun getNavigationBarTypePath(isNightTheme: Boolean): String = NAVIGATION_BARS_DIR + if (isNightTheme) "night/" else "day/"
    private fun getTopBarTypePath(isNightTheme: Boolean): String = TOP_BARS_DIR + if (isNightTheme) "night/" else "day/"
    private fun getCoverCollectionTypePath(isNightTheme: Boolean): String = COVER_COLLECTIONS_DIR + if (isNightTheme) "night/" else "day/"

    private fun getProgressPath(name: String, author: String): String = BOOK_PROGRESS_DIR + getProgressFileName(name, author)

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    private fun cachePackageFileNameCandidates(fileName: String): List<String> {
        val raw = fileName.trimEnd('/')
        val withZip = if (raw.endsWith(".zip", ignoreCase = true)) raw else "$raw.zip"
        val normalized = normalizeCachePackageFileName(fileName)
        val normalizedWithZip = normalizeCachePackageFileName(withZip)
        return linkedSetOf(withZip, raw, normalized, normalizedWithZip)
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun normalizeCachePackageFileName(fileName: String): String {
        val safeFileName = UrlUtil.replaceReservedChar(
            fileName.trimEnd('/').removeSuffix(".zip").normalizeFileName()
        ).ifBlank { "cache_${System.currentTimeMillis()}" }
        return "$safeFileName.zip"
    }

    private fun ensureNetwork() {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
    }
}

