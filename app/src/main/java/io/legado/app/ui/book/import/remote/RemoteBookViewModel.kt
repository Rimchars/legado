package io.legado.app.ui.book.import.remote

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.addType
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.smb.SmbZipReader
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDavZipReader
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookManager
import io.legado.app.model.remote.RemoteBookSmb
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Collections

class RemoteBookViewModel(application: Application) : BaseViewModel(application) {
    var sortKey = RemoteBookSort.Default
    var sortAscending = false
    val dirList = arrayListOf<RemoteBook>()
    val permissionDenialLiveData = MutableLiveData<Int>()

    var dataCallback: DataCallback? = null

    val dataFlow = callbackFlow<List<RemoteBook>> {

        val list = Collections.synchronizedList(ArrayList<RemoteBook>())

        dataCallback = object : DataCallback {

            override fun setItems(remoteFiles: List<RemoteBook>) {
                list.clear()
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun addItems(remoteFiles: List<RemoteBook>) {
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun screen(key: String?) {
                if (key.isNullOrBlank()) {
                    trySend(list)
                } else {
                    trySend(
                        list.filter { it.filename.contains(key) }
                    )
                }
            }
        }

        awaitClose {
            dataCallback = null
        }
    }.map { list ->
        if (sortAscending) when (sortKey) {
            RemoteBookSort.Name -> list.sortedWith(compareBy<RemoteBook> { !it.isDir }
                    then compareBy(AlphanumComparator) { it.filename })

            else -> list.sortedWith(compareBy({ !it.isDir }, { it.lastModify }))
        } else when (sortKey) {
            RemoteBookSort.Name -> list.sortedWith { o1, o2 ->
                val compare = -compareValues(o1.isDir, o2.isDir)
                if (compare == 0) {
                    return@sortedWith -AlphanumComparator.compare(o1.filename, o2.filename)
                }
                return@sortedWith compare
            }

            else -> list.sortedWith { o1, o2 ->
                val compare = -compareValues(o1.isDir, o2.isDir)
                if (compare == 0) {
                    return@sortedWith -compareValues(o1.lastModify, o2.lastModify)
                }
                return@sortedWith compare
            }
        }
    }.flowOn(Dispatchers.IO)

    private var remoteBookManager: RemoteBookManager? = null
    var isDefaultWebdav = false

    fun initData(onSuccess: () -> Unit) {
        execute {
            isDefaultWebdav = false
            val server = appDb.serverDao.get(AppConfig.remoteServerId)
            if (server != null) {
                //按类型分发(SMB/WebDav)
                remoteBookManager = when (server.type) {
                    Server.TYPE.SMB -> {
                        val config = server.getSmbConfig()
                            ?: throw NoStackTraceException("SMB服务器配置错误")
                        RemoteBookSmb(server.getSmbRootUrl(), config, server.id)
                    }

                    Server.TYPE.WEBDAV -> {
                        val config = server.getWebDavConfig()
                            ?: throw NoStackTraceException("webDav配置错误")
                        RemoteBookWebDav(
                            server.getWebDavRootUrl(), Authorization(config), server.id
                        )
                    }
                }
                return@execute
            }
            //没有配置服务器时静默处理,界面显示空列表,不弹出错误提示
        }.onSuccess {
            onSuccess.invoke()
        }
    }

    /**
     * 远程压缩包免下载加入书架:
     * 生成书籍记录(bookUrl=origin URL,首次打开时下载或webdav直读),zip标记archive,
     * 若压缩包内为图片则标记image(打开时getImageArchiveToc webdav直读)
     */
    private fun addArchiveToBookshelfDirect(
        remoteBook: RemoteBook,
        bookManager: RemoteBookManager
    ) {
        val nameAuthor = LocalBook.analyzeNameAuthor(remoteBook.filename)
        val origin = BookType.webDavTag + CustomUrl(remoteBook.path)
            .putAttribute("serverID", bookManager.serverID)
            .toString()
        //已存在的同名书籍时重新关联远程地址
        appDb.bookDao.getBookByFileName(remoteBook.filename)?.let { existing ->
            existing.origin = origin
            existing.addType(BookType.archive)
            existing.bookUrl = origin
            existing.save()
            return
        }
        //免下载检测压缩包内是否有图片,判断是否漫画(用origin的remoteUrl,带serverID选项)
        val remoteUrl = origin.substringAfter(BookType.webDavTag)
        val hasImages = kotlin.runCatching {
            when (bookManager) {
                is RemoteBookSmb -> {
                    SmbZipReader.getEntries(remoteUrl)
                        .any { it.name.matches(AppPattern.imageFileRegex) }
                }

                is RemoteBookWebDav -> {
                    WebDavZipReader.getEntries(remoteUrl, bookManager.authorization)
                        .any { it.name.matches(AppPattern.imageFileRegex) }
                }

                else -> false
            }
        }.getOrDefault(false)
        val book = Book(
            type = BookType.text or BookType.local or BookType.archive or
                if (hasImages) BookType.image else 0,
            //未下载书籍的占位符:避免被当作本地文件,首次打开webdav直读
            bookUrl = "/remote/" + MD5Utils.md5Encode16(remoteBook.path),
            name = nameAuthor.first,
            author = nameAuthor.second,
            originName = remoteBook.filename,
            latestChapterTime = remoteBook.lastModify ?: System.currentTimeMillis(),
            order = appDb.bookDao.minOrder - 1,
            origin = origin
        )
        appDb.bookDao.insert(book)
    }

    fun loadRemoteBookList(path: String?, loadCallback: (loading: Boolean) -> Unit) {
        executeLazy {
            val bookWebDav = remoteBookManager
                ?: run {
                    //没有配置服务器时显示空列表
                    dataCallback?.clear()
                    return@executeLazy
                }
            dataCallback?.clear()
            val url = path ?: bookWebDav.rootBookUrl
            val bookList = bookWebDav.getRemoteBookList(url)
            dataCallback?.setItems(bookList)
        }.onError {
            AppLog.put("获取webDav书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi("获取webDav书籍出错\n${it.localizedMessage}")
        }.onStart {
            loadCallback.invoke(true)
        }.onFinally {
            loadCallback.invoke(false)
        }.start()
    }

    fun addToBookshelf(remoteBooks: HashSet<RemoteBook>, finally: () -> Unit) {
        execute {
            val bookWebDav = remoteBookManager
                ?: throw NoStackTraceException("没有配置webDav")
            remoteBooks.forEach { remoteBook ->
                if (remoteBook.isDir) return@forEach
                if (ArchiveUtils.isArchive(remoteBook.filename)) {
                    //压缩包:免下载直读,生成书籍记录,首次打开阅读时webdav Range直读
                    addArchiveToBookshelfDirect(remoteBook, bookWebDav)
                } else {
                    //普通书籍:下载到本地再导入
                    val downloadBookUri = bookWebDav.downloadRemoteBook(remoteBook)
                    LocalBook.importFiles(downloadBookUri).forEach { book ->
                        book.origin = BookType.webDavTag + CustomUrl(remoteBook.path)
                            .putAttribute("serverID", bookWebDav.serverID)
                            .toString()
                        book.save()
                    }
                }
                remoteBook.isOnBookShelf = true
            }
        }.onError {
            AppLog.put("导入出错\n${it.localizedMessage}", it)
            context.toastOnUi("导入出错\n${it.localizedMessage}")
            if (it is SecurityException) {
                permissionDenialLiveData.postValue(1)
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun updateCallBackFlow(filterKey: String?) {
        dataCallback?.screen(filterKey)
    }

    interface DataCallback {

        fun setItems(remoteFiles: List<RemoteBook>)

        fun addItems(remoteFiles: List<RemoteBook>)

        fun clear()

        fun screen(key: String?)

    }
}