package io.legado.app.model.localBook

import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.AppWebDav
import io.legado.app.help.glide.ArchiveImageLoader
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.smb.Smb
import io.legado.app.lib.smb.SmbZipReader
import io.legado.app.lib.webdav.WebDavZipReader
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.MD5Utils
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isUmd
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.epubcore.cache.EpubCoreDiskCache
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.regex.Pattern
import androidx.core.net.toUri
import kotlinx.coroutines.currentCoroutineContext

/**
 * 书籍文件导入 目录正文解析
 * 支持在线文件(txt epub umd 压缩文件 本地文件
 */
object LocalBook {

    /**
     * 远程书未下载占位符前缀(与RemoteBookViewModel.addArchiveToBookshelfDirect一致)
     */
    const val REMOTE_PLACEHOLDER_PREFIX = "/remote/"

    private const val LARGE_EPUB_FAST_IMPORT_BYTES = 100L * 1024L * 1024L

    private val nameAuthorPatterns = arrayOf(
        Pattern.compile("(.*?)《([^《》]+)》.*?作者：(.*)"),
        Pattern.compile("(.*?)《([^《》]+)》(.*)"),
        Pattern.compile("(^)(.+) 作者：(.+)$"),
        Pattern.compile("(^)(.+) by (.+)$")
    )

    @Throws(FileNotFoundException::class, SecurityException::class)
    fun getBookInputStream(book: Book): InputStream {
        val uri = book.getLocalUri()
        val inputStream = uri.inputStream(appCtx).getOrNull()
            ?: let {
                book.removeLocalUriCache()
                val localArchiveUri = book.getArchiveUri()
                val webDavUrl = book.getRemoteUrl()
                if (localArchiveUri != null) {
                    // 重新导入对应的压缩包
                    importArchiveFile(localArchiveUri, book.originName) {
                        it.contains(book.originName)
                    }.firstOrNull()?.let {
                        getBookInputStream(it)
                    }
                } else if (webDavUrl != null && downloadRemoteBook(book)) {
                    // 下载远程链接
                    getBookInputStream(book)
                } else {
                    null
                }
            }
        if (inputStream != null) return inputStream
        book.removeLocalUriCache()
        throw FileNotFoundException("${uri.path} 文件不存在")
    }

    fun getLastModified(book: Book): Result<Long> {
        return kotlin.runCatching {
            val uri = book.bookUrl.toUri()
            if (uri.isContentScheme()) {
                return@runCatching DocumentFile.fromSingleUri(appCtx, uri)!!.lastModified()
            }
            val file = File(uri.path!!)
            if (file.exists()) {
                return@runCatching file.lastModified()
            }
            throw FileNotFoundException("${uri.path} 文件不存在")
        }
    }

    /**
     * 图片压缩包生成漫画章节
     * webdav远程压缩包免下载直接读取,其余远程先下载
     * @return 章节列表和章节内容,非图片压缩包返回null
     */
    fun getImageArchiveToc(book: Book): Pair<ArrayList<BookChapter>, String>? {
        if (!book.isArchive) return null
        val remoteUrl = book.getRemoteUrl()
        val isSmb = remoteUrl?.startsWith("smb://", true) == true
        val isWebDav = remoteUrl?.startsWith("http://", true) == true ||
            remoteUrl?.startsWith("https://", true) == true ||
            remoteUrl?.startsWith("dav://", true) == true ||
            remoteUrl?.startsWith("davs://", true) == true
        //本地已有文件则用本地,否则远程免下载直读(安静检查,不产生日志)
        val localExists = kotlin.runCatching {
            when {
                book.bookUrl.startsWith(REMOTE_PLACEHOLDER_PREFIX) -> false
                book.bookUrl.isContentScheme() ->
                    DocumentFile.fromSingleUri(appCtx, book.bookUrl.toUri())?.exists() == true
                else -> File(book.bookUrl).exists()
            }
        }.getOrDefault(false)
        val (zipUri, images) = when {
            localExists -> {
                val list = ArchiveUtils.getArchiveFilesName(book.bookUrl.toUri()) {
                    it.matches(AppPattern.imageFileRegex)
                }
                book.bookUrl to list
            }

            isSmb -> {
                //SMB远程直读,失败直接抛出,避免降级为文本解析污染书籍
                val entries = SmbZipReader.getEntries(remoteUrl)
                val list = entries.map { it.name }
                    .filter { it.matches(AppPattern.imageFileRegex) }
                remoteUrl to list
            }

            isWebDav -> {
                //webdav远程直读(Range请求),失败直接抛出,避免降级为文本解析污染书籍
                val serverID = AnalyzeUrl(remoteUrl).serverID
                    ?: throw NoStackTraceException("webdav服务器不存在")
                val entries = kotlin.runCatching {
                    WebDavZipReader.getEntries(remoteUrl, Authorization(serverID))
                }.onFailure {
                    AppLog.put("webdav zip直读失败\n$remoteUrl\n${it.localizedMessage}", it)
                }.getOrThrow()
                if (entries.isEmpty()) {
                    AppLog.put("webdav zip直读为空(可能zip结构异常)\n$remoteUrl")
                }
                val list = entries.map { it.name }
                    .filter { it.matches(AppPattern.imageFileRegex) }
                remoteUrl to list
            }

            else -> {
                //webdav等远程:先下载到本地
                getBookInputStream(book).close()
                val list = ArchiveUtils.getArchiveFilesName(book.bookUrl.toUri()) {
                    it.matches(AppPattern.imageFileRegex)
                }
                book.bookUrl to list
            }
        }
        if (images.isEmpty()) {
            AppLog.put(
                "漫画检测未通过:${book.name}\n" +
                    "来源:${if (localExists) "本地文件" else if (isWebDav) "webdav直读" else "远程下载"},压缩包内无图片"
            )
            return null
        }
        val sortedImages = images.sortedWith(AlphanumComparator)
        val chapter = BookChapter(
            url = MD5Utils.md5Encode16(zipUri + "manga"),
            title = book.name,
            bookUrl = book.bookUrl,
            index = 0,
            baseUrl = zipUri
        )
        val content = sortedImages.joinToString("\n") { image ->
            "<img src=\"${ArchiveImageLoader.buildUrl(zipUri, image)}\">"
        }
        book.addType(BookType.image)
        book.tocUrl = zipUri
        book.totalChapterNum = 1
        book.latestChapterTitle = book.name
        book.save()
        BookHelp.saveText(book, chapter, content)
        return arrayListOf(chapter) to content
    }

    @Throws(TocEmptyException::class)
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        if (book.isArchive) {
            //图片压缩包一律按漫画章节处理,避免被按文本解析出乱码
            getImageArchiveToc(book)?.let { (toc, _) ->
                return toc
            }
        }
        val chapters = when {
            book.isEpub -> {
                EpubFile.getChapterList(book)
            }

            book.isUmd -> {
                UmdFile.getChapterList(book)
            }

            book.isPdf -> {
                PdfFile.getChapterList(book)
            }

            book.isMobi -> {
                MobiFile.getChapterList(book)
            }

            else -> {
                TextFile.getChapterList(book)
            }
        }
        if (chapters.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        val list = ArrayList(LinkedHashSet(chapters))
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            if (bookChapter.title.isEmpty()) {
                bookChapter.title = "无标题章节"
            }
        }
        val replaceRules = ContentProcessor.get(book).getTitleReplaceRules()
        val replaceBook = book.toReplaceBook()
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(
                replaceRules,
                book.getUseReplaceRule(),
                replaceBook = replaceBook
            )
        book.latestChapterTitle =
            list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
                .getDisplayTitle(
                    replaceRules,
                    book.getUseReplaceRule(),
                    replaceBook = replaceBook
                )
        book.totalChapterNum = list.size
        book.latestChapterTime = System.currentTimeMillis()
        return list
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        if (book.isImage) {
            //图片压缩包章节内容已由getImageArchiveToc经saveText缓存,丢失时重新生成
            return getImageArchiveToc(book)?.second
        }
        var content = try {
            when {
                book.isEpub -> {
                    EpubFile.getContent(book, chapter)
                }

                book.isUmd -> {
                    UmdFile.getContent(book, chapter)
                }

                book.isPdf -> {
                    PdfFile.getContent(book, chapter)
                }

                book.isMobi -> {
                    MobiFile.getContent(book, chapter)
                }

                else -> {
                    TextFile.getContent(book, chapter)
                }
            }
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("获取本地书籍内容失败\n${e.localizedMessage}", e)
            "获取本地书籍内容失败\n${e.localizedMessage}"
        }
        if (book.isEpub) {
            content ?: return null
            if (content.indexOf('&') > -1) {
                content = content.replace("&lt;img", "&lt; img", true)
                return StringEscapeUtils.unescapeHtml4(content)
            }
        }

        if (content.isNullOrEmpty() && !chapter.isVolume) {
            return null
        }

        return content
    }

    fun getCoverPath(book: Book): String {
        return getCoverPath(book.bookUrl, "jpg")
    }

    fun getCoverPath(book: Book, extension: String): String {
        return getCoverPath(book.bookUrl, extension)
    }

    fun findCoverPath(book: Book): String? {
        return listOf("png", "jpg", "webp")
            .asSequence()
            .map { getCoverPath(book.bookUrl, it) }
            .firstOrNull { File(it).exists() }
    }

    fun resolveCoverPath(book: Book, extension: String): String {
        val current = book.coverUrl
        if (!current.isNullOrBlank() && !isManagedCoverPath(book, current)) {
            return current
        }
        return getCoverPath(book.bookUrl, extension)
    }

    private fun isManagedCoverPath(book: Book, path: String): Boolean {
        return listOf("png", "jpg", "webp").any { path == getCoverPath(book.bookUrl, it) }
    }

    private fun getCoverPath(bookUrl: String, extension: String): String {
        val safeExtension = extension.substringAfterLast('.').ifBlank { "jpg" }.lowercase()
        return FileUtils.getPath(
            appCtx.externalFiles,
            "covers",
            "${MD5Utils.md5Encode16(bookUrl)}.$safeExtension"
        )
    }

    /**
     * 下载在线的文件并自动导入到阅读（txt umd epub)
     */
    suspend fun importFileOnLine(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Book {
        return importFile(saveBookFile(str, fileName, source))
    }

    /**
     * 导入本地文件
     */
    fun importFile(uri: Uri, onStage: ((String) -> Unit)? = null): Book {
        //updateTime变量不要修改,否则会导致读取不到缓存
        onStage?.invoke("读取文件信息")
        val fileDoc = FileDoc.fromUri(uri, false)
        if (fileDoc.size == 0L) throw EmptyFileException("Unexpected empty File")
        val fileName = fileDoc.name
        val updateTime = fileDoc.lastModified
        val bookUrl = fileDoc.toString()
        val fileSize = fileDoc.size
        var book = appDb.bookDao.getBook(bookUrl)
        if (book == null) {
            onStage?.invoke("解析书籍信息")
            val nameAuthor = analyzeNameAuthor(fileName)
            book = Book(
                type = BookType.text or BookType.local,
                bookUrl = bookUrl,
                name = nameAuthor.first,
                author = nameAuthor.second,
                originName = fileName,
                latestChapterTime = updateTime,
                order = appDb.bookDao.minOrder - 1
            )
            upBookInfoSafely(book, fileSize)
            onStage?.invoke("保存书籍信息")
            appDb.bookDao.insert(book)
        } else {
            onStage?.invoke("更新书籍信息")
            deleteBook(book, false)
            upBookInfoSafely(book, fileSize)
            // 触发 isLocalModified
            book.latestChapterTime = 0
            //已有书籍说明是更新,删除原有目录
            appDb.bookChapterDao.delByBook(bookUrl)
        }
        return book
    }

    fun upBookInfo(book: Book) {
        when {
            book.isEpub -> EpubFile.upBookInfo(book)
            book.isUmd -> UmdFile.upBookInfo(book)
            book.isPdf -> PdfFile.upBookInfo(book)
            book.isMobi -> MobiFile.upBookInfo(book)
        }
    }

    private fun upBookInfoSafely(book: Book, fileSize: Long) {
        if (book.isEpub && shouldDeferEpubBookInfo(fileSize)) {
            if (book.name.isBlank()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            if (book.intro.isNullOrBlank()) {
                book.intro = "大体积 EPUB 已快速导入，封面和简介将在阅读时按需加载。"
            }
            return
        }
        if (!book.isEpub) {
            upBookInfo(book)
            return
        }
        kotlin.runCatching {
            upBookInfo(book)
        }.onFailure {
            AppLog.put("EPUB 元数据解析失败，已先导入书籍\n${it.localizedMessage}", it)
            if (book.name.isBlank()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            if (book.intro.isNullOrBlank()) {
                book.intro = "EPUB 已导入，元数据将在阅读时按需加载。"
            }
        }
    }

    private fun shouldDeferEpubBookInfo(fileSize: Long): Boolean {
        return fileSize >= LARGE_EPUB_FAST_IMPORT_BYTES
    }

    /* 导入压缩包内的书籍 */
    fun importArchiveFile(
        archiveFileUri: Uri,
        saveFileName: String? = null,
        filter: ((String) -> Boolean)? = null
    ): List<Book> {
        val archiveFileDoc = FileDoc.fromUri(archiveFileUri, false)
        val files = ArchiveUtils.deCompress(archiveFileDoc, filter = filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
        }
        return files.map {
            saveBookFile(FileInputStream(it), saveFileName ?: it.name).let { uri ->
                importFile(uri).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    origin = "${BookType.localTag}::${archiveFileDoc.name}"
                    addType(BookType.archive)
                    save()
                }
            }
        }
    }

     /* 批量导入 支持自动导入压缩包的支持书籍 */
    fun importFiles(uri: Uri, onStage: ((String) -> Unit)? = null): List<Book> {
        val books = mutableListOf<Book>()
        onStage?.invoke("读取文件信息")
        val fileDoc = FileDoc.fromUri(uri, false)
        if (ArchiveUtils.isArchive(fileDoc.name)) {
            onStage?.invoke("解压压缩包")
            //压缩包内无匹配书籍文件(或没有支持的文件)时,尝试以图片漫画导入
            val innerBooks = kotlin.runCatching {
                importArchiveFile(uri) {
                    it.matches(AppPattern.bookFileRegex)
                }
            }.recoverCatching {
                if (it is NoStackTraceException) {
                    emptyList()
                } else {
                    throw it
                }
            }.getOrThrow()
            if (innerBooks.isEmpty()) {
                //压缩包内没有书籍文件,尝试以图片漫画导入
                kotlin.runCatching {
                    importImageArchive(fileDoc)
                }.onSuccess {
                    books.add(it)
                }.onFailure {
                    throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
                }
            } else {
                books.addAll(innerBooks)
            }
        } else {
            books.add(importFile(uri, onStage))
        }
        return books
    }

    /**
     * 图片压缩包(zip)以漫画方式导入,压缩包本身作为书籍
     * 首次打开阅读时生成漫画章节
     */
    fun importImageArchive(fileDoc: FileDoc): Book {
        val bookUrl = fileDoc.toString()
        val images = ArchiveUtils.getArchiveFilesName(fileDoc) {
            it.matches(AppPattern.imageFileRegex)
        }
        if (images.isEmpty()) {
            AppLog.put("漫画导入失败:压缩包内无图片 ${fileDoc.name}")
            throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
        }
        val nameAuthor = analyzeNameAuthor(fileDoc.name)
        return appDb.bookDao.getBook(bookUrl)?.let {
            it.origin = BookType.localTag
            it.addType(BookType.image)
            it.save()
            it
        } ?: Book(
            type = BookType.text or BookType.local or BookType.archive or BookType.image,
            bookUrl = bookUrl,
            name = nameAuthor.first,
            author = nameAuthor.second,
            originName = fileDoc.name,
            latestChapterTime = fileDoc.lastModified,
            order = appDb.bookDao.minOrder - 1,
            origin = BookType.localTag
        ).apply {
            appDb.bookDao.insert(this)
        }
    }

    fun importFiles(uris: List<Uri>) {
        var errorCount = 0
        uris.forEach { uri ->
            val fileDoc = FileDoc.fromUri(uri, false)
            kotlin.runCatching {
                if (ArchiveUtils.isArchive(fileDoc.name)) {
                    importArchiveFile(uri) {
                        it.matches(AppPattern.bookFileRegex)
                    }
                } else {
                    importFile(uri)
                }
            }.onFailure {
                AppLog.put("ImportFile Error:\nFile $fileDoc\n${it.localizedMessage}", it)
                errorCount += 1
            }
        }
        if (errorCount == uris.size) {
            throw NoStackTraceException("ImportFiles Error:\nAll input files occur error")
        }
    }

    fun prepareImportedBookCache(
        book: Book,
        onProgress: (stage: String, processed: Int, total: Int, title: String) -> Unit = { _, _, _, _ -> }
    ) {
        if (!book.isEpub) return
        onProgress("toc", 0, 1, book.name)
        val chapterList = getChapterList(book)
        if (chapterList.isEmpty()) return
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapterList.toTypedArray())
        appDb.bookDao.update(book)
        onProgress("toc", 1, 1, book.name)
        kotlin.runCatching {
            onProgress("index", 0, 1, book.name)
            EpubCoreDiskCache.writeChapterList(BookHelp.getCacheDir(book), EpubCoreDiskCache.bookSignature(book), chapterList)
            onProgress("index", 1, 1, book.name)
        }.onFailure {
            AppLog.putDebug("EPUB import structure cache failed: ${it.localizedMessage}", it)
        }
    }

    /**
     * 从文件分析书籍必要信息（书名 作者等）
     */
    fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        val tempFileName = fileName.substringBeforeLast(".")
        var name = ""
        var author = ""
        if (!AppConfig.bookImportFileName.isNullOrBlank()) {
            try {
                //在用户脚本后添加捕获author、name的代码，只要脚本中author、name有值就会被捕获
                val js =
                    AppConfig.bookImportFileName + "\nJSON.stringify({author:author,name:name})"
                //在脚本中定义如何分解文件名成书名、作者名
                val jsonStr = RhinoScriptEngine.run {
                    val bindings = ScriptBindings()
                    bindings["src"] = tempFileName
                    eval(js, bindings)
                }.toString()
                val bookMess = GSON.fromJsonObject<HashMap<String, String>>(jsonStr)
                    .getOrThrow()
                name = bookMess["name"] ?: ""
                author = bookMess["author"]?.takeIf { it.length != tempFileName.length } ?: ""
            } catch (e: Exception) {
                AppLog.put("执行导入文件名规则出错\n${e.localizedMessage}", e)
            }
        }
        if (name.isBlank()) {
            for (pattern in nameAuthorPatterns) {
                pattern.matcher(tempFileName).takeIf { it.find() }?.run {
                    name = group(2)!!
                    val group1 = group(1) ?: ""
                    val group3 = group(3) ?: ""
                    author = BookHelp.formatBookAuthor(group1 + group3)
                    return Pair(name, author)
                }
            }
            name = BookHelp.formatBookName(tempFileName)
            author = BookHelp.formatBookAuthor(tempFileName.replace(name, ""))
                .takeIf { it.length != tempFileName.length } ?: ""
        }
        return Pair(name, author)
    }

    fun deleteBook(book: Book, deleteOriginal: Boolean) {
        kotlin.runCatching {
            BookHelp.clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtils.delete(book.coverUrl!!)
            }
            if (deleteOriginal) {
                if (book.bookUrl.isContentScheme()) {
                    val uri = book.bookUrl.toUri()
                    DocumentFile.fromSingleUri(appCtx, uri)?.delete()
                } else {
                    FileUtils.delete(book.bookUrl)
                }
            }
        }
    }

    /**
     * 下载在线的文件
     */
    suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoBooksDirException()
        val inputStream = when {
            str.isAbsUrl() -> AnalyzeUrl(
                str, source = source, callTimeout = 0,
                coroutineContext = currentCoroutineContext()
            ).getInputStreamAwait()

            str.isDataUrl() -> ByteArrayInputStream(
                Base64.decode(
                    str.substringAfter("base64,"),
                    Base64.DEFAULT
                )
            )

            else -> throw NoStackTraceException("在线导入书籍支持http/https/DataURL")
        }
        return saveBookFile(inputStream, fileName)
    }

    @Throws(SecurityException::class)
    fun saveBookFile(
        inputStream: InputStream,
        fileName: String
    ): Uri {
        inputStream.use {
            val defaultBookTreeUri = AppConfig.defaultBookTreeUri
            if (defaultBookTreeUri.isNullOrBlank()) throw NoBooksDirException()
            val treeUri = defaultBookTreeUri.toUri()
            return if (treeUri.isContentScheme()) {
                val treeDoc = DocumentFile.fromTreeUri(appCtx, treeUri)
                var doc = treeDoc!!.findFile(fileName)
                if (doc == null) {
                    doc = treeDoc.createFile(FileUtils.getMimeType(fileName), fileName)
                        ?: throw SecurityException("请重新设置书籍保存位置\nPermission Denial")
                }
                appCtx.contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                    it.copyTo(oStream)
                }
                doc.uri
            } else {
                try {
                    val treeFile = File(treeUri.path!!)
                    val file = treeFile.getFile(fileName)
                    FileOutputStream(file).use { oStream ->
                        it.copyTo(oStream)
                    }
                    Uri.fromFile(file)
                } catch (e: FileNotFoundException) {
                    throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                        addSuppressed(e)
                    }
                }
            }
        }
    }

    fun isOnBookShelf(
        fileName: String
    ): Boolean {
        return appDb.bookDao.hasFile(fileName)
    }

    //文件类书源 合并在线书籍信息 在线 > 本地
    fun mergeBook(localBook: Book, onLineBook: Book?): Book {
        onLineBook ?: return localBook
        localBook.name = onLineBook.name.ifBlank { localBook.name }
        localBook.author = onLineBook.author.ifBlank { localBook.author }
        localBook.coverUrl = onLineBook.coverUrl
        localBook.intro =
            if (onLineBook.intro.isNullOrBlank()) localBook.intro else onLineBook.intro
        localBook.save()
        return localBook
    }

    //下载book对应的远程文件 并更新Book
    private fun downloadRemoteBook(localBook: Book): Boolean {
        val webDavUrl = localBook.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        try {
            AppConfig.defaultBookTreeUri
                ?: throw NoBooksDirException()
            val inputStream = runBlocking {
                if (webDavUrl.startsWith("smb://", true)) {
                    // SMB远程书籍
                    Smb.fromPath(webDavUrl).downloadInputStream()
                } else {
                    // 兼容旧版链接
                    val webdav: WebDav = kotlin.runCatching {
                        WebDav.fromPath(webDavUrl)
                    }.getOrElse {
                        AppWebDav.authorization?.let { WebDav(webDavUrl, it) }
                            ?: throw WebDavException("Unexpected defaultBookWebDav")
                    }
                    webdav.downloadInputStream()
                }
            }
            inputStream.use {
                if (localBook.isArchive) {
                    // 压缩包
                    val remoteUrl = localBook.getRemoteUrl()
                    val archiveName = remoteUrl?.let { CustomUrl(it).getUrl().substringAfterLast("/") }
                        ?: localBook.archiveName
                    val archiveUri = saveBookFile(it, archiveName)
                    //直接加入的压缩包(originName为压缩包本身)时,导入其中符合书籍格式的文件
                    val isArchiveItself = localBook.originName == archiveName
                    val innerBooks = kotlin.runCatching {
                        importArchiveFile(archiveUri, localBook.originName) { name ->
                            if (isArchiveItself) {
                                name.matches(AppPattern.bookFileRegex)
                            } else {
                                name.contains(localBook.originName)
                            }
                        }
                    }.getOrNull()
                    if (innerBooks != null && innerBooks.isNotEmpty()) {
                        val newBook = innerBooks.first()
                        localBook.origin = if (isArchiveItself) localBook.origin else newBook.origin
                        localBook.bookUrl = newBook.bookUrl
                    } else {
                        //压缩包内没有书籍文件,若为图片压缩包则标记为漫画保留压缩包本身
                        localBook.bookUrl = FileDoc.fromUri(archiveUri, false).toString()
                        val hasImages = ArchiveUtils.getArchiveFilesName(archiveUri) {
                            it.matches(AppPattern.imageFileRegex)
                        }.isNotEmpty()
                        if (hasImages) {
                            localBook.addType(BookType.image)
                        }
                    }
                    localBook.save()
                } else {
                    // txt epub pdf umd
                    val fileUri = saveBookFile(it, localBook.originName)
                    localBook.bookUrl = FileDoc.fromUri(fileUri, false).toString()
                    localBook.save()
                }
            }
            return true
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("自动下载webDav书籍失败", e)
            return false
        }
    }

}
