package io.legado.app.lib.webdav

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.CustomUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 基于WebDAV Range请求的zip解析器,无需下载整个文件即可按需读取压缩包内条目
 * 仅支持zip格式(stored/deflate),依赖服务器支持HTTP Range
 * (移植自reader:com.x.reader.lib.webdav.WebDavZipReader)
 */
class WebDavZipReader(val url: String, val authorization: Authorization) : Closeable {

    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localHeaderOffset: Long
    )

    private val httpUrl: String? by lazy {
        val raw = CustomUrl(url).getUrl()
            .replace("davs://", "https://")
            .replace("dav://", "http://")
        kotlin.runCatching {
            raw.toHttpUrl().toString()
        }.getOrNull()
    }

    private val host: String? by lazy {
        httpUrl?.substringAfter("://")?.substringBefore("/")?.let {
            if (it.startsWith("[")) {
                //IPv6
                it.substring(1, it.indexOf(']').takeIf { x -> x > 0 } ?: it.lastIndex)
            } else {
                //去端口,与request.url.host(不含端口)比较
                it.substringBefore(':')
            }
        }
    }

    private val authInterceptor = Interceptor { chain ->
        var request = chain.request()
        val matchHost = host != null && request.url.host.equals(host, true)
        if (matchHost) {
            request = request
                .newBuilder()
                .header(authorization.name, authorization.data)
                .build()
        }
        //401时自动重试一次(某些服务器偶发对GET认证失效,重发可恢复)
        var response = chain.proceed(request)
        if (response.code == 401 && matchHost) {
            AppLog.put(
                "webdav zip 401诊断:用户=${authorization.username}, " +
                    "认证base64=${authorization.data}, " +
                    "方法=${request.method}, Range=${request.header("Range")}, " +
                    "实际认证头=${request.header("Authorization")}, " +
                    "Accept-Encoding=${request.header("Accept-Encoding")}, " +
                    "UA=${request.header("User-Agent")}\n$httpUrl"
            )
            response.close()
            response = chain.proceed(request)
        }
        response
    }

    private val client by lazy {
        okHttpClient.newBuilder().run {
            callTimeout(0, TimeUnit.SECONDS)
            //与WebDav主类一致:仅注册一次认证拦截器,避免双Authorization头导致部分服务器401
            interceptors().add(0, authInterceptor)
            build()
        }
    }

    private val entries: List<Entry> by lazy {
        parseCentralDirectory()
    }

    fun listEntries(): List<Entry> = entries

    fun findEntry(name: String): Entry? = entries.firstOrNull { it.name == name }

    /**
     * 打开条目数据流
     */
    fun openEntry(entry: Entry): InputStream {
        val header = readRange(entry.localHeaderOffset, 30)
        if (header.size < 30) {
            throw FileNotFoundException("zip条目头部读取失败 $url")
        }
        val nameLen = littleEndian(header, 26, 2).toInt()
        val extraLen = littleEndian(header, 28, 2).toInt()
        val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
        val method = entry.method
        //按块Range读取,减少网络往返
        val buffered = BufferedInputStream(
            BoundedRangeStream(dataOffset, entry.compressedSize),
            READ_BUFFER_SIZE
        )
        return if (method == 0) {
            buffered
        } else {
            InflaterInputStream(buffered, Inflater(true), READ_BUFFER_SIZE)
        }
    }

    /**
     * Range读取指定字节区间
     */
    private fun readRange(start: Long, length: Long): ByteArray {
        val url = httpUrl ?: throw WebDavException("WebDavZip读取失败\nurl为空")
        if (length <= 0) return ByteArray(0)
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw FileNotFoundException("WebDav zip读取失败 ${response.code} $url")
            }
            val body = response.body?.bytes()
                ?: throw FileNotFoundException("WebDav zip响应为空 $url")
            if (response.code == 206) {
                //服务器正确支持Range,body即请求区间(个别服务器范围可能偏大,做防御截取)
                return if (body.size <= length) body
                else body.copyOfRange(0, min(length, body.size.toLong()).toInt())
            }
            //部分服务器忽略Range返回200全文件,从start截取
            if (start >= body.size) return ByteArray(0)
            val from = start.toInt()
            val to = min(start + length, body.size.toLong()).toInt()
            return body.copyOfRange(from, to)
        }
    }

    private fun parseCentralDirectory(): List<Entry> {
        val url = httpUrl ?: run {
            AppLog.put("webdav zip解析失败:URL非法")
            return emptyList()
        }
        //HEAD获取文件大小,再以显式Range读取尾部(坚果云等服务器不支持后缀Range bytes=-N)
        val fileLen = client.newCall(
            Request.Builder().url(url).method("HEAD", null).build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.put("webdav zip读取失败:${response.code} $url")
                return emptyList()
            }
            response.header("Content-Length")?.toLongOrNull() ?: run {
                AppLog.put("webdav zip读取失败:HEAD无Content-Length $url")
                return emptyList()
            }
        }
        if (fileLen < 22) {
            AppLog.put("webdav zip解析失败:文件过小($fileLen) $url")
            return emptyList()
        }
        val tailLen = min(65536L + 22L, fileLen)
        val tailStart = fileLen - tailLen
        val tailRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=$tailStart-${fileLen - 1}")
            .build()
        val tail = client.newCall(tailRequest).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.put("webdav zip读取失败:${response.code} $url")
                return emptyList()
            }
            response.body?.bytes() ?: run {
                AppLog.put("webdav zip读取失败:tail响应为空 $url")
                return emptyList()
            }
        }
        if (tail.size < 22) {
            AppLog.put("webdav zip解析失败:tail过小(${tail.size}) $url")
            return emptyList()
        }
        var cdStart = -1L
        var cdSize = 0L
        var entryCount = 0
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            ) {
                entryCount = littleEndian(tail, i + 10, 2).toInt()
                cdSize = littleEndian(tail, i + 12, 4)
                cdStart = littleEndian(tail, i + 16, 4)
                break
            }
        }
        if (cdStart < 0 || cdSize <= 0 || entryCount <= 0) {
            AppLog.put("webdav zip解析失败:未找到中央目录 $url")
            return emptyList()
        }
        val cd = readRange(cdStart, cdSize)
        val result = arrayListOf<Entry>()
        var pos = 0
        repeat(entryCount) {
            if (pos + 46 > cd.size) return@repeat
            if (cd[pos] != 0x50.toByte() || cd[pos + 1] != 0x4B.toByte() ||
                cd[pos + 2] != 0x01.toByte() || cd[pos + 3] != 0x02.toByte()
            ) {
                return@repeat
            }
            val method = littleEndian(cd, pos + 10, 2).toInt()
            var compressedSize = littleEndian(cd, pos + 20, 4)
            var localHeaderOffset = littleEndian(cd, pos + 42, 4)
            val nameLen = littleEndian(cd, pos + 28, 2).toInt()
            val extraLen = littleEndian(cd, pos + 30, 2).toInt()
            val commentLen = littleEndian(cd, pos + 32, 2).toInt()
            val name = String(cd, pos + 46, nameLen, Charsets.UTF_8)
            if (compressedSize == ZIP64_MARKER || localHeaderOffset == ZIP64_MARKER) {
                parseZip64Extra(cd, pos + 46 + nameLen, extraLen) { cSize, offset ->
                    if (compressedSize == ZIP64_MARKER && cSize != null) compressedSize = cSize
                    if (localHeaderOffset == ZIP64_MARKER && offset != null) {
                        localHeaderOffset = offset
                    }
                }
            }
            result.add(Entry(name, method, compressedSize, localHeaderOffset))
            pos += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun parseZip64Extra(
        cd: ByteArray,
        start: Int,
        length: Int,
        consumer: (compressedSize: Long?, localHeaderOffset: Long?) -> Unit
    ) {
        var p = start
        val end = start + length
        while (p + 4 <= end) {
            val id = littleEndian(cd, p, 2)
            val size = littleEndian(cd, p + 2, 2).toInt()
            p += 4
            if (id == 0x0001L) {
                val fieldEnd = p + size
                var compressedSize: Long? = null
                var offset: Long? = null
                //uncompressedSize(8)
                p += 8
                if (p + 8 <= fieldEnd) {
                    compressedSize = littleEndian(cd, p, 8)
                    p += 8
                }
                if (p + 8 <= fieldEnd) {
                    offset = littleEndian(cd, p, 8)
                }
                consumer(compressedSize, offset)
                return
            }
            p += size
        }
    }

    override fun close() {
        //连接随响应流关闭,无需额外处理
    }

    /**
     * 按块Range读取的流,每次读取一整块减少请求次数
     */
    private inner class BoundedRangeStream(
        private val start: Long,
        private val length: Long
    ) : InputStream() {

        private var position = 0L
        private var block: ByteArray? = null
        private var blockOffset = 0
        private var blockLength = 0

        override fun read(): Int {
            if (position >= length) return -1
            if (!ensureBlock()) return -1
            val b = block!![blockOffset].toInt() and 0xFF
            blockOffset++
            position++
            return b
        }

        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            if (position >= length) return -1
            if (!ensureBlock()) return -1
            val n = min(len, blockLength - blockOffset)
            System.arraycopy(block!!, blockOffset, b!!, off, n)
            blockOffset += n
            position += n
            return n
        }

        private fun ensureBlock(): Boolean {
            if (block != null && blockOffset < blockLength) return true
            if (position >= length) return false
            val nextStart = start + position
            val readLen = min(READ_BUFFER_SIZE.toLong(), length - position).toInt()
            //块读取失败时重试,避免服务器偶发401/断流导致整张图失败显示重新加载
            var lastError: Exception? = null
            repeat(BLOCK_READ_RETRY) { attempt ->
                try {
                    block = readRange(nextStart, readLen.toLong())
                    blockOffset = 0
                    blockLength = block!!.size
                    return blockLength > 0
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(BLOCK_READ_RETRY_DELAY_MS * (attempt + 1))
                }
            }
            throw lastError ?: java.io.IOException("读取webdav数据块失败")
        }
    }

    private fun littleEndian(buf: ByteArray, offset: Int, len: Int): Long {
        var value = 0L
        for (i in 0 until len) {
            value = value or ((buf[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    companion object {

        private const val READ_BUFFER_SIZE = 1024 * 1024

        private const val ZIP64_MARKER = 0xFFFFFFFFL

        private const val BLOCK_READ_RETRY = 3

        private const val BLOCK_READ_RETRY_DELAY_MS = 500L

        private val indexCache = ConcurrentHashMap<String, List<Entry>>()

        /**
         * 获取条目索引(带缓存,空结果不缓存避免污染)
         */
        fun getEntries(url: String, authorization: Authorization): List<Entry> {
            val key = "$url|${authorization.data.hashCode()}"
            indexCache[key]?.let { return it }
            val entries = WebDavZipReader(url, authorization).listEntries()
            if (entries.isNotEmpty()) {
                indexCache[key] = entries
            }
            return entries
        }
    }
}
