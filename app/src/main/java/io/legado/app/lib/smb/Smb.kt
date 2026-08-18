package io.legado.app.lib.smb

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.Properties
import splitties.init.appCtx

/**
 * SMB客户端,基于jcifs-ng
 */
class Smb(val url: String, val config: Server.SmbConfig) {

    companion object {

        fun fromPath(path: String): Smb {
            val id = AnalyzeUrl(path).serverID ?: throw SmbException("没有serverID")
            val server = appDb.serverDao.get(id) ?: throw SmbException("服务器不存在")
            val config = server.getSmbConfig() ?: throw SmbException("服务器配置错误")
            return Smb(CustomUrl(path).getUrl(), config)
        }

        private fun encodeName(name: String): String {
            return URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        }

    }

    private val context: CIFSContext by lazy {
        BaseContext(PropertyConfiguration(Properties().apply {
            //限制连接与读写超时,避免SMB服务器不可达时长时间挂起
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "30000")
        })).withCredentials(NtlmPasswordAuthenticator(null, config.username, config.password))
    }

    private fun getSmbFile(path: String): SmbFile {
        val smbUrl = if (path.startsWith("smb://", true)) path else "smb://$path"
        return SmbFile(smbUrl, context)
    }

    private fun getDirSmbFile(path: String): SmbFile {
        return getSmbFile(path.trimEnd('/') + "/")
    }

    private fun getFileInfo(smbFile: SmbFile): SmbFileInfo? {
        return if (smbFile.exists()) {
            SmbFileInfo(
                url = smbFile.getCanonicalPath(),
                displayName = smbFile.getName().trimEnd('/').substringAfterLast("/"),
                size = smbFile.length(),
                lastModify = smbFile.lastModified(),
                isDir = smbFile.isDirectory()
            )
        } else {
            null
        }
    }

    /**
     * 获取当前url文件信息
     */
    suspend fun getSmbFileInfo(): SmbFileInfo? {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                getFileInfo(getSmbFile(url))
            }.getOrNull()
        }
    }

    /**
     * 列出当前路径下的文件
     */
    suspend fun listFiles(): List<SmbFileInfo> {
        return withContext(Dispatchers.IO) {
            val fileList = kotlin.runCatching {
                getDirSmbFile(url).listFiles()
            }.getOrNull() ?: return@withContext emptyList()
            fileList.mapNotNull { child ->
                kotlin.runCatching {
                    getFileInfo(child)
                }.getOrNull()
            }
        }
    }

    /**
     * 根据自己的URL,在远程处创建对应的文件夹
     */
    suspend fun makeAsDir() {
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val smbFile = getDirSmbFile(url)
                if (!smbFile.exists()) {
                    smbFile.mkdirs()
                }
            }.onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put("SMB创建目录失败\n${it.localizedMessage}", it)
            }
        }
    }

    /**
     * 下载文件,返回输入流
     */
    suspend fun downloadInputStream(): InputStream {
        return withContext(Dispatchers.IO) {
            val smbFile = getSmbFile(url)
            if (!smbFile.exists()) throw SmbException("文件不存在\n${smbFile.getCanonicalPath()}")
            smbFile.getInputStream()
        }
    }

    /**
     * 打开远程文件随机访问(用于压缩包内按需读取)
     */
    fun randomAccess(): SmbRandomAccessFile {
        return SmbRandomAccessFile(getSmbFile(url), "r")
    }

    /**
     * 上传文件到当前目录
     */
    suspend fun upload(file: File, fileName: String) {
        withContext(Dispatchers.IO) {
            if (!file.exists()) throw SmbException("文件不存在")
            file.inputStream().use {
                uploadStream(it, fileName)
            }
        }
    }

    /**
     * 上传文件到当前目录
     */
    suspend fun upload(uri: Uri, fileName: String) {
        withContext(Dispatchers.IO) {
            val inputStream = kotlin.runCatching {
                appCtx.contentResolver.openInputStream(uri)
            }.getOrNull() ?: throw SmbException("无法读取本地文件")
            inputStream.use {
                uploadStream(it, fileName)
            }
        }
    }

    private fun uploadStream(inputStream: InputStream, fileName: String) {
        val dirUrl = url.trimEnd('/')
        val target = getSmbFile(dirUrl + "/" + encodeName(fileName))
        if (!target.exists()) {
            target.createNewFile()
        }
        target.getOutputStream().use { output ->
            inputStream.copyTo(output)
        }
    }

    /**
     * 删除文件/文件夹(支持非空文件夹)
     */
    suspend fun delete() {
        withContext(Dispatchers.IO) {
            deleteRecursive(getSmbFile(url))
        }
    }

    private fun deleteRecursive(smbFile: SmbFile) {
        if (smbFile.isDirectory() && smbFile.listFiles()?.isNotEmpty() == true) {
            smbFile.listFiles()?.forEach {
                deleteRecursive(it)
            }
        }
        smbFile.delete()
    }

}
