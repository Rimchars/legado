package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Server
import io.legado.app.exception.NoStackTraceException
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.analyzeRule.AnalyzeUrl

abstract class RemoteBookManager {

    open val rootBookUrl: String = ""
    open val serverID: Long? = null

    companion object {

        /**
         * 按服务器类型分发(SMB/WebDav)
         */
        fun fromPath(path: String): RemoteBookManager {
            val id = AnalyzeUrl(path).serverID ?: throw NoStackTraceException("没有serverID")
            val server = appDb.serverDao.get(id) ?: throw NoStackTraceException("服务器不存在")
            return when (server.type) {
                Server.TYPE.SMB -> {
                    val config = server.getSmbConfig()
                        ?: throw NoStackTraceException("SMB服务器配置错误")
                    RemoteBookSmb(server.getSmbRootUrl(), config, id)
                }

                Server.TYPE.WEBDAV -> {
                    val config = server.getWebDavConfig()
                        ?: throw NoStackTraceException("webDav配置错误")
                    RemoteBookWebDav(server.getWebDavRootUrl(), Authorization(config), id)
                }
            }
        }

    }

    /**
     * 获取书籍列表
     */
    @Throws(Exception::class)
    abstract suspend fun getRemoteBookList(path: String): MutableList<RemoteBook>

    /**
     * 根据书籍地址获取书籍信息
     */
    @Throws(Exception::class)
    abstract suspend fun getRemoteBook(path: String): RemoteBook?

    /**
     * @return Uri：下载到本地的路径
     */
    @Throws(Exception::class)
    abstract suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri

    /**
     * 上传书籍
     */
    @Throws(Exception::class)
    abstract suspend fun upload(book: Book)

    /**
     * 删除书籍
     */
    @Throws(Exception::class)
    abstract suspend fun delete(remoteBookUrl: String)

}