package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.smb.Smb
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

/**
 * SMB远程书库管理(移植自reader:com.x.reader.model.remote.RemoteBookSmb)
 */
class RemoteBookSmb(
    override val rootBookUrl: String,
    val config: Server.SmbConfig,
    override val serverID: Long? = null
) : RemoteBookManager() {

    init {
        runBlocking {
            Smb(rootBookUrl, config).makeAsDir()
        }
    }

    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val remoteBooks = mutableListOf<RemoteBook>()
        //读取文件列表
        val smbFileList = Smb(path, config).listFiles()
        //转化远程文件信息到本地对象
        smbFileList.forEach { smbFile ->
            if (smbFile.isDir
                || bookFileRegex.matches(smbFile.displayName)
                || archiveFileRegex.matches(smbFile.displayName)
            ) {
                //扩展名符合阅读的格式则认为是书籍
                remoteBooks.add(RemoteBook(smbFile))
            }
        }
        return remoteBooks
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val smbFileInfo = Smb(path, config).getSmbFileInfo()
            ?: return null
        return RemoteBook(smbFileInfo)
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoStackTraceException("没有设置书籍保存位置!")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        return Smb(remoteBook.path, config).downloadInputStream().let { inputStream ->
            LocalBook.saveBookFile(inputStream, remoteBook.filename)
        }
    }

    override suspend fun upload(book: Book) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val localBookUri = Uri.parse(book.bookUrl)
        val smb = Smb(rootBookUrl, config)
        if (localBookUri.isContentScheme()) {
            smb.upload(localBookUri, book.originName)
        } else {
            smb.upload(File(localBookUri.path!!), book.originName)
        }
        val putUrl = rootBookUrl.trimEnd('/') + "/" + book.originName
        book.origin = BookType.webDavTag + CustomUrl(putUrl)
            .putAttribute("serverID", serverID)
            .toString()
        book.update()
    }

    override suspend fun delete(remoteBookUrl: String) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        Smb(remoteBookUrl, config).delete()
    }

}
