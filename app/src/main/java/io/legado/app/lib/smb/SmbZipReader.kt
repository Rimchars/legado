package io.legado.app.lib.smb

import jcifs.smb.SmbRandomAccessFile
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 基于SMB随机访问的zip解析器,无需下载整个文件即可按需读取压缩包内条目
 * 仅支持zip格式(stored/deflate)
 */
class SmbZipReader(val url: String) : Closeable {

    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localHeaderOffset: Long
    )

    private val raf: SmbRandomAccessFile by lazy {
        Smb.fromPath(url).randomAccess()
    }

    /**
     * 共享连接读取锁:同一连接并发seek/read会互相干扰,必须串行
     */
    private val readLock = Object()

    private val entries: List<Entry> by lazy {
        parseCentralDirectory()
    }

    fun listEntries(): List<Entry> = entries

    fun findEntry(name: String): Entry? = entries.firstOrNull { it.name == name }

    /**
     * 打开条目数据流
     * 注意:此流关闭时仅关闭条目流本身,不关闭底层raf连接(连接由共享缓存管理)
     */
    fun openEntry(entry: Entry): InputStream {
        synchronized(readLock) {
            raf.seek(entry.localHeaderOffset)
            val header = ByteArray(30)
            raf.readFully(header)
            val nameLen = littleEndian(header, 26, 2).toInt()
            val extraLen = littleEndian(header, 28, 2).toInt()
            val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
            val method = entry.method
            //大文件优化:1MB缓冲批量读取,减少SMB网络往返次数
            val buffered = BufferedInputStream(
                BoundedRafStream(dataOffset, entry.compressedSize),
                READ_BUFFER_SIZE
            )
            val result = if (method == 0) {
                buffered
            } else {
                InflaterInputStream(buffered, Inflater(true), READ_BUFFER_SIZE)
            }
            return object : InputStream() {
                override fun read(): Int = result.read()

                override fun read(b: ByteArray?, off: Int, len: Int): Int = result.read(b, off, len)

                override fun close() {
                    try {
                        result.close()
                    } finally {
                        //不关闭raf,连接由共享缓存复用
                    }
                }
            }
        }
    }

    /**
     * 连接是否可用(探测读取失败即失效)
     */
    fun isConnectionAlive(): Boolean {
        return kotlin.runCatching {
            synchronized(readLock) { raf.length() >= 0 }
        }.getOrDefault(false)
    }

    private fun parseCentralDirectory(): List<Entry> {
        synchronized(readLock) {
            val fileLen = raf.length()
            if (fileLen < 22) return emptyList()
            val readLen = min(fileLen, 65536L + 22L)
            raf.seek(fileLen - readLen)
            val tail = ByteArray(readLen.toInt())
            raf.readFully(tail)
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
            if (cdStart < 0 || cdSize <= 0 || entryCount <= 0) return emptyList()
            raf.seek(cdStart)
            val cd = ByteArray(cdSize.toInt())
            raf.readFully(cd)
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
                //ZIP64:32位字段为0xFFFFFFFF时从extra字段读取真实值
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
    }

    /**
     * 解析central directory中的ZIP64 extra字段(0x0001)
     */
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
                var uncompressedSize: Long? = null
                var compressedSize: Long? = null
                var offset: Long? = null
                if (p + 8 <= fieldEnd) {
                    uncompressedSize = littleEndian(cd, p, 8)
                    p += 8
                }
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
        kotlin.runCatching { raf.close() }
    }

    private inner class BoundedRafStream(
        private val start: Long,
        private val length: Long
    ) : InputStream() {

        private var position = 0L

        override fun read(): Int {
            if (position >= length) return -1
            synchronized(readLock) {
                raf.seek(start + position)
                val b = raf.read()
                if (b >= 0) position++
                return b
            }
        }

        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            if (position >= length) return -1
            val toRead = min(len.toLong(), length - position).toInt()
            synchronized(readLock) {
                raf.seek(start + position)
                val n = raf.read(b, off, toRead)
                if (n > 0) position += n
                return n
            }
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

        private val indexCache = ConcurrentHashMap<String, List<Entry>>()

        /**
         * 复用的读取器(同URL共享连接,避免大漫画每张图新建SMB连接导致极慢/占满信号量)
         * 仅在流关闭时归还,长时间不用或连接失效时重建
         */
        private val readerCache = ConcurrentHashMap<String, SmbZipReader>()

        /**
         * 获取条目索引(带缓存,空结果不缓存避免污染)
         */
        fun getEntries(url: String): List<Entry> {
            indexCache[url]?.let { return it }
            //复用共享连接解析,不能use关闭(会关掉缓存里的连接)
            val reader = getSharedReader(url)
            val entries = try {
                reader.listEntries()
            } catch (e: Exception) {
                readerCache.remove(url, reader)
                kotlin.runCatching { reader.close() }
                throw e
            }
            if (entries.isNotEmpty()) {
                indexCache[url] = entries
            }
            return entries
        }

        /**
         * 获取或复用连接读取器
         */
        private fun getSharedReader(url: String): SmbZipReader {
            readerCache[url]?.let {
                if (it.isConnectionAlive()) return it
                readerCache.remove(url)
                kotlin.runCatching { it.close() }
            }
            return readerCache.computeIfAbsent(url) { SmbZipReader(it) }
        }

        /**
         * 打开条目数据流,使用共享连接,流关闭后归还连接
         * 读取失败时移除缓存连接,便于下次重试重建
         */
        fun openEntryShared(url: String, entry: Entry): InputStream {
            val reader = getSharedReader(url)
            return try {
                val raw = reader.openEntry(entry)
                object : java.io.FilterInputStream(raw) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            returnShared(url, reader)
                        }
                    }
                }
            } catch (e: Exception) {
                readerCache.remove(url, reader)
                kotlin.runCatching { reader.close() }
                throw e
            }
        }

        /**
         * 归还连接:若连接已失效则移除重建
         */
        private fun returnShared(url: String, reader: SmbZipReader) {
            if (!reader.isConnectionAlive()) {
                readerCache.remove(url, reader)
                kotlin.runCatching { reader.close() }
            }
        }
    }
}
