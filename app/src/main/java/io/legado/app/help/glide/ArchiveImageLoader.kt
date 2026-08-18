package io.legado.app.help.glide

import android.net.Uri
import androidx.core.net.toUri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.constant.AppLog
import io.legado.app.lib.smb.SmbZipReader
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDavZipReader
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import me.ag2s.epublib.util.zip.AndroidZipFile
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 从本地压缩包(zip)中随机读取图片的Glide加载器
 * 格式: archive://{Uri.encode(zipUri)}|{Uri.encode(entryName)}
 * 图片按需从zip中央目录定位读取,无需整体解压
 * (移植自reader:com.x.reader.help.glide.ArchiveImageLoader)
 */
object ArchiveImageLoader {

    const val SCHEME = "archive://"

    /**
     * 限制远程压缩包并发读取连接数,避免服务器断开连接导致页面加载失败
     */
    private val fetchSemaphore = Semaphore(4)

    fun isArchiveImage(url: String?): Boolean = url?.startsWith(SCHEME) == true

    fun buildUrl(zipUri: String, entryName: String): String {
        return SCHEME + Uri.encode(zipUri) + "|" + Uri.encode(entryName)
    }

    class Loader : ModelLoader<String, InputStream> {

        override fun buildLoadData(
            model: String,
            width: Int,
            height: Int,
            options: com.bumptech.glide.load.Options
        ): ModelLoader.LoadData<InputStream>? {
            return ModelLoader.LoadData(ObjectKey(model), ArchiveImageFetcher(model))
        }

        override fun handles(model: String): Boolean {
            return isArchiveImage(model)
        }
    }

    /**
     * 打开archive://图片流(供Glide与网页服务共用)
     */
    fun openArchiveImage(url: String): InputStream {
        val encodeUrl = url.substring(SCHEME.length)
        val zipUri = Uri.decode(encodeUrl.substringBefore("|"))
        val entryName = Uri.decode(encodeUrl.substringAfter("|"))
        return if (zipUri.startsWith("smb://", true)) {
            //SMB远程压缩包免下载直读,信号量在整个读取期间持有,流关闭时释放
            runBlocking {
                fetchSemaphore.acquire()
                try {
                    val entry = SmbZipReader.getEntries(zipUri)
                        .firstOrNull { it.name == entryName }
                        ?: throw FileNotFoundException("压缩包内没有图片 $entryName")
                    //共享连接读取,避免大漫画每张图新建SMB连接
                    val raw = SmbZipReader.openEntryShared(zipUri, entry)
                    object : FilterInputStream(raw) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                fetchSemaphore.release()
                            }
                        }
                    }
                } catch (e: Exception) {
                    fetchSemaphore.release()
                    throw e
                }
            }
        } else if (zipUri.startsWith("http://", true) || zipUri.startsWith("https://", true)) {
            //webdav远程压缩包免下载直读(Range请求)
            runBlocking {
                fetchSemaphore.acquire()
                try {
                    val serverID = AnalyzeUrl(zipUri).serverID
                        ?: throw FileNotFoundException("压缩包内没有图片 $entryName")
                    val authorization = Authorization(serverID)
                    val entry = WebDavZipReader.getEntries(zipUri, authorization)
                        .firstOrNull { it.name == entryName }
                        ?: throw FileNotFoundException("压缩包内没有图片 $entryName")
                    val raw = WebDavZipReader(zipUri, authorization).openEntry(entry)
                    object : FilterInputStream(raw) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                fetchSemaphore.release()
                            }
                        }
                    }
                } catch (e: Exception) {
                    fetchSemaphore.release()
                    throw e
                }
            }
        } else {
            val zipFile = if (zipUri.isContentScheme()) {
                val pfd = appCtx.contentResolver.openFileDescriptor(zipUri.toUri(), "r")
                    ?: throw FileNotFoundException("zip不存在 $zipUri")
                AndroidZipFile(pfd, zipUri.substringAfterLast('/'))
            } else {
                AndroidZipFile(File(zipUri))
            }
            val entry = zipFile.getEntry(entryName)
                ?: throw FileNotFoundException("压缩包内没有图片 $entryName")
            object : FilterInputStream(zipFile.getInputStream(entry)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        kotlin.runCatching { zipFile.close() }
                    }
                }
            }
        }
    }

    class ArchiveImageFetcher(private val url: String) : DataFetcher<InputStream> {

        private var currentStream: InputStream? = null

        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in InputStream>
        ) {
            //磁盘缓存命中直接返回,避免重复网络Range读取(滑动过快/来回滑动时秒显)
            val cacheFile = cacheFile(url)
            if (cacheFile != null && cacheFile.exists()) {
                callback.onDataReady(FileInputStream(cacheFile))
                return
            }
            var lastError: Exception? = null
            //远程压缩包连接不稳定时自动重试,避免服务器限流导致图片显示重新加载
            repeat(3) { attempt ->
                try {
                    val stream = openArchiveImage(url)
                    synchronized(this) { currentStream = stream }
                    //缓存到本地磁盘(仅远程图片),滑动回来秒显
                    if (url.contains("http://", true) || url.contains("https://", true) ||
                        url.contains("smb://", true)
                    ) {
                        cacheStreamToFile(stream, cacheFile)
                    }
                    callback.onDataReady(stream)
                    return
                } catch (e: Exception) {
                    lastError = e
                    if (url.contains("http://", true) || url.contains("https://", true)) {
                        runBlocking { delay(500L * (attempt + 1)) }
                    }
                }
            }
            AppLog.put("漫画图片加载失败\n$url\n${lastError?.localizedMessage}", lastError)
            callback.onLoadFailed(lastError ?: Exception("加载失败"))
        }

        /**
         * 磁盘缓存文件(以url哈希命名)
         */
        private fun cacheFile(url: String): File? {
            return kotlin.runCatching {
                File(appCtx.cacheDir, "archiveImages").apply { mkdirs() }
                    .getFile(url.hashCode().toString() + ".img")
            }.getOrNull()
        }

        /**
         * 读取流并写入缓存(读取完成后流复位)
         */
        private fun cacheStreamToFile(stream: InputStream, cacheFile: File?) {
            if (cacheFile == null) return
            kotlin.runCatching {
                if (!stream.markSupported()) return
                stream.mark(Int.MAX_VALUE)
                val buffer = ByteArray(64 * 1024)
                FileOutputStream(cacheFile).use { out ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                stream.reset()
            }
        }

        override fun cleanup() {
            closeCurrentStream()
        }

        override fun cancel() {
            closeCurrentStream()
        }

        /**
         * 取消/清理时关闭流,释放连接与信号量,避免后台继续下载
         */
        private fun closeCurrentStream() {
            synchronized(this) {
                currentStream?.let {
                    kotlin.runCatching { it.close() }
                    currentStream = null
                }
            }
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }

    class Factory : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> {
            return Loader()
        }

        override fun teardown() {}
    }
}
