package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
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
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
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
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线�?
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"
    private val themesWebDavUrl get() = "${rootWebDavUrl}themes/"
    private val navigationBarsWebDavUrl get() = "${rootWebDavUrl}navigationBars/"
    private val topBarsWebDavUrl get() = "${rootWebDavUrl}topBars/"
    private val coverCollectionsWebDavUrl get() = "${rootWebDavUrl}coverCollections/"

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    init {
        runBlocking {
            upConfig()
        }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        kotlin.runCatching {
            authorization = null
            defaultBookWebDav = null
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val mAuthorization = Authorization(account, password)
                checkAuthorization(mAuthorization)
                WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                WebDav(themesWebDavUrl, mAuthorization).makeAsDir()
                WebDav(navigationBarsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(topBarsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(coverCollectionsWebDavUrl, mAuthorization).makeAsDir()
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            webDav.downloadTo(Backup.zipFilePath, true)
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
            Restore.restoreLocked(Backup.backupPath)
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * webDav备份
     * @param fileName 备份文件�?
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            WebDav(putUrl, it).upload(Backup.zipFilePath)
        }
    }

    suspend fun listThemePackages(isNightTheme: Boolean): List<WebDavFile> {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val dirUrl = getThemeTypeUrl(isNightTheme)
        WebDav(dirUrl, authorization).makeAsDir()
        return WebDav(dirUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    suspend fun uploadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        val typeUrl = getThemeTypeUrl(isNightTheme)
        WebDav(typeUrl, authorization).makeAsDir()
        WebDav(typeUrl + fileName, authorization).upload(zipFile)
    }

    suspend fun uploadCachePackage(fileName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val safeFileName = normalizeCachePackageFileName(fileName)
        WebDav(exportsWebDavUrl, authorization).makeAsDir()
        WebDav(exportsWebDavUrl + safeFileName, authorization)
            .upload(zipFile, "application/zip")
    }

    suspend fun downloadCachePackage(fileName: String, zipFile: File) {
        val currentAuthorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        zipFile.parentFile?.mkdirs()
        var lastError: Throwable? = null
        cachePackageFileNameCandidates(fileName).forEach { candidate ->
            runCatching {
                WebDav(exportsWebDavUrl + candidate, currentAuthorization)
                    .downloadTo(zipFile.absolutePath, true)
            }.onSuccess {
                return
            }.onFailure {
                lastError = it
            }
        }
        throw lastError ?: NoStackTraceException("Cache package not found")
    }


    suspend fun deleteCachePackage(fileName: String) {
        val currentAuthorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        WebDav(exportsWebDavUrl + normalizeCachePackageFileName(fileName), currentAuthorization).delete()
    }

    suspend fun downloadCacheIndex(): CacheCloudIndex {
        val authorization = authorization ?: return CacheCloudIndex()
        if (!NetworkUtils.isAvailable()) return CacheCloudIndex()
        return kotlin.runCatching {
            val bytes = WebDav(
                exportsWebDavUrl + CacheCloudIndexStore.remoteFileName(),
                authorization
            ).download()
            GSON.fromJsonObject<CacheCloudIndex>(String(bytes)).getOrNull() ?: CacheCloudIndex()
        }.getOrElse { CacheCloudIndex() }
    }

    suspend fun uploadCacheIndex(index: CacheCloudIndex) {
        val currentAuthorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        WebDav(exportsWebDavUrl, currentAuthorization).makeAsDir()
        WebDav(exportsWebDavUrl + CacheCloudIndexStore.remoteFileName(), currentAuthorization)
            .upload(GSON.toJson(index).toByteArray(), "application/json")
    }

    suspend fun downloadThemePackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        zipFile.parentFile?.mkdirs()
        WebDav(getThemeTypeUrl(isNightTheme) + fileName, authorization)
            .downloadTo(zipFile.absolutePath, true)
    }

    suspend fun deleteThemePackage(isNightTheme: Boolean, remoteDirName: String) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        WebDav(getThemeTypeUrl(isNightTheme) + fileName, authorization).delete()
    }

    suspend fun listNavigationBarPackages(isNightTheme: Boolean): List<WebDavFile> {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val dirUrl = getNavigationBarTypeUrl(isNightTheme)
        WebDav(dirUrl, authorization).makeAsDir()
        return WebDav(dirUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    suspend fun uploadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        val typeUrl = getNavigationBarTypeUrl(isNightTheme)
        WebDav(typeUrl, authorization).makeAsDir()
        WebDav(typeUrl + fileName, authorization).upload(zipFile)
    }

    suspend fun downloadNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        zipFile.parentFile?.mkdirs()
        WebDav(getNavigationBarTypeUrl(isNightTheme) + fileName, authorization)
            .downloadTo(zipFile.absolutePath, true)
    }

    suspend fun deleteNavigationBarPackage(isNightTheme: Boolean, remoteDirName: String) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        WebDav(getNavigationBarTypeUrl(isNightTheme) + fileName, authorization).delete()
    }

    suspend fun listTopBarPackages(isNightTheme: Boolean): List<WebDavFile> {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val dirUrl = getTopBarTypeUrl(isNightTheme)
        WebDav(dirUrl, authorization).makeAsDir()
        return WebDav(dirUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    suspend fun uploadTopBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        val typeUrl = getTopBarTypeUrl(isNightTheme)
        WebDav(typeUrl, authorization).makeAsDir()
        WebDav(typeUrl + fileName, authorization).upload(zipFile)
    }

    suspend fun downloadTopBarPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        zipFile.parentFile?.mkdirs()
        WebDav(getTopBarTypeUrl(isNightTheme) + fileName, authorization)
            .downloadTo(zipFile.absolutePath, true)
    }

    suspend fun deleteTopBarPackage(isNightTheme: Boolean, remoteDirName: String) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        WebDav(getTopBarTypeUrl(isNightTheme) + fileName, authorization).delete()
    }
    private fun getThemeTypeUrl(isNightTheme: Boolean): String {
        return themesWebDavUrl + if (isNightTheme) "night/" else "day/"
    }

    private fun getNavigationBarTypeUrl(isNightTheme: Boolean): String {
        return navigationBarsWebDavUrl + if (isNightTheme) "night/" else "day/"
    }

    private fun getTopBarTypeUrl(isNightTheme: Boolean): String {
        return topBarsWebDavUrl + if (isNightTheme) "night/" else "day/"
    }

    suspend fun listCoverCollectionPackages(isNightTheme: Boolean): List<WebDavFile> {
        val authorization = authorization ?: return emptyList()
        if (!NetworkUtils.isAvailable()) return emptyList()
        val dirUrl = getCoverCollectionTypeUrl(isNightTheme)
        WebDav(dirUrl, authorization).makeAsDir()
        return WebDav(dirUrl, authorization).listFiles()
            .filter { !it.isDir && it.displayName.endsWith(".zip", ignoreCase = true) }
    }

    suspend fun uploadCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        val typeUrl = getCoverCollectionTypeUrl(isNightTheme)
        WebDav(typeUrl, authorization).makeAsDir()
        WebDav(typeUrl + fileName, authorization).upload(zipFile)
    }

    suspend fun downloadCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String, zipFile: File) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        zipFile.parentFile?.mkdirs()
        WebDav(getCoverCollectionTypeUrl(isNightTheme) + fileName, authorization)
            .downloadTo(zipFile.absolutePath, true)
    }

    suspend fun deleteCoverCollectionPackage(isNightTheme: Boolean, remoteDirName: String) {
        val authorization = authorization ?: throw NoStackTraceException("WebDAV not configured")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("Network unavailable")
        val fileName = "${remoteDirName.trimEnd('/').removeSuffix(".zip")}.zip"
        WebDav(getCoverCollectionTypeUrl(isNightTheme) + fileName, authorization).delete()
    }

    private fun getCoverCollectionTypeUrl(isNightTheme: Boolean): String {
        return coverCollectionsWebDavUrl + if (isNightTheme) "night/" else "day/"
    }

    /**
     * 获取云端所有背景名�?
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("Network unavailable")
            authorization.let {
                it ?: throw NoStackTraceException("WebDAV not configured")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存�?开始上�?
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存�?开始上�?
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val url = getProgressUrl(book.name, book.author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName]
            webDavFile ?: return
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
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
}
