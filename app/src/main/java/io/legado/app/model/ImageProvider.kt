package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.collection.LruCache
import io.legado.app.R
import io.legado.app.constant.AppLog.putDebug
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ArchiveImageLoader
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.model.localBook.PdfFile
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.decodeBase64DataUrlBytes
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

object ImageProvider {

    private val imageCacheScope = CoroutineScope(SupervisorJob() + IO)
    private val inflightCacheKeys = ConcurrentHashMap.newKeySet<String>()

    private val errorBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.image_loading_error)
    }

    /**
     * 缓存bitmap LruCache实现
     * filePath bitmap
     */
    private const val M = 1024 * 1024
    private const val MIN_CACHE_SIZE = 8 * M
    private const val HARD_CACHE_SIZE = 96 * M
    val cacheSize: Int
        get() {
            if (AppConfig.bitmapCacheSize !in 1..1024) {
                AppConfig.bitmapCacheSize = 50
            }
            val userSize = AppConfig.bitmapCacheSize * M
            val heapBound = (Runtime.getRuntime().maxMemory() / 8)
                .coerceIn(MIN_CACHE_SIZE.toLong(), HARD_CACHE_SIZE.toLong())
                .toInt()
            return min(userSize, heapBound)
        }

    val bitmapLruCache = BitmapLruCache()

    class BitmapLruCache : LruCache<String, Bitmap>(cacheSize) {

        private var removeCount = 0

        val count get() = putCount() + createCount() - evictionCount() - removeCount

        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (!evicted) {
                synchronized(this) {
                    removeCount++
                }
            }
            //错误图片不能释放,占位用,防止一直重复获取图片
            if (oldValue != errorBitmap) {
                oldValue.recycle()
                //putDebug("ImageProvider: trigger bitmap recycle. URI: $filePath")
                //putDebug("ImageProvider : cacheUsage ${size()}bytes / ${maxSize()}bytes")
            }
        }

    }

    fun put(key: String, bitmap: Bitmap) {
        if (bitmap != errorBitmap && bitmap.byteCount > cacheSize) {
            return
        }
        ensureLruCacheSize()
        bitmapLruCache.put(key, bitmap)
    }

    fun get(key: String): Bitmap? {
        return bitmapLruCache[key]
    }

    fun remove(key: String): Bitmap? {
        return bitmapLruCache.remove(key)
    }

    private fun getNotRecycled(key: String): Bitmap? {
        val bitmap = bitmapLruCache[key] ?: return null
        if (bitmap.isRecycled) {
            bitmapLruCache.remove(key)
            return null
        }
        return bitmap
    }

    private fun ensureLruCacheSize() {
        val targetSize = cacheSize
        if (bitmapLruCache.maxSize() != targetSize) {
            bitmapLruCache.resize(targetSize)
        }
    }

    private fun handleDecodeFailure(key: String?, error: Throwable) {
        if (error is CancellationException) throw error
        if (error is OutOfMemoryError) {
            putDebug("ImageProvider: bitmap decode OOM, clear cache")
            clear()
        }
        key?.let {
            ensureLruCacheSize()
            bitmapLruCache.put(it, errorBitmap)
        }
    }

    /**
     *缓存网络图片和epub图片
     */
    suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File {
        return withContext(IO) {
            resolveLocalImageFile(src)?.let { file ->
                return@withContext file
            }
            val vFile = BookHelp.getImage(book, src)
            if (!BookHelp.isImageExist(book, src)) {
                val inputStream = when {
                    ArchiveImageLoader.isArchiveImage(src) -> kotlin.runCatching {
                        ArchiveImageLoader.openArchiveImage(src)
                    }.onFailure {
                        handleDecodeFailure(null, it)
                    }.getOrNull()
                    src.isDataUrl() -> kotlin.runCatching {
                        src.decodeBase64DataUrlBytes()?.let(::ByteArrayInputStream)
                    }.onFailure {
                        handleDecodeFailure(null, it)
                    }.getOrNull()
                    book.isEpub -> EpubFile.getImage(book, src)
                    book.isPdf -> PdfFile.getImage(book, src)
                    book.isMobi -> MobiFile.getImage(book, src)
                    else -> {
                        BookHelp.saveImage(bookSource, book, src)
                        null
                    }
                }
                inputStream?.use { input ->
                    val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
                    FileOutputStream(newFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return@withContext vFile
        }
    }

    fun cacheImageAsync(
        book: Book,
        src: String,
        bookSource: BookSource?,
        width: Int? = null,
        height: Int? = null,
        cacheKeySuffix: String? = null,
        onFinished: (() -> Unit)? = null
    ) {
        val key = "${book.bookUrl}|$src"
        if (!inflightCacheKeys.add(key)) return
        imageCacheScope.launch {
            try {
                cacheImage(book, src, bookSource)
                if (width != null && width > 0) {
                    getImage(book, src, width, height, cacheKeySuffix)
                }
            } catch (e: Exception) {
                putDebug("ImageProvider async cache failed: $src\n${e.localizedMessage}")
            } finally {
                inflightCacheKeys.remove(key)
                if (onFinished != null) {
                    withContext(Main) {
                        onFinished.invoke()
                    }
                }
            }
        }
    }

    /**
     *获取图片宽度高度信息
     */
    suspend fun getImageSize(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): Size {
        if (ParagraphBubbleRenderer.isBubbleSrc(src)) {
            return ParagraphBubbleRenderer.getSize(src)
        }
        return kotlin.runCatching {
            val file = cacheImage(book, src, bookSource)
            val op = BitmapFactory.Options()
        // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, op)
            if (op.outWidth < 1 && op.outHeight < 1) {
                if (src.isDataUrl()) {
                    src.decodeBase64DataUrlBytes()?.let { bytes ->
                        val dataOptions = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dataOptions)
                        if (dataOptions.outWidth > 0 && dataOptions.outHeight > 0) {
                            return Size(dataOptions.outWidth, dataOptions.outHeight)
                        }
                    }
                }
                //svg size
                val size = SvgUtils.getSize(file.absolutePath)
                if (size != null) return size
                putDebug("ImageProvider: $src Unsupported image type")
                //file.delete() 重复下载
                return Size(errorBitmap.width, errorBitmap.height)
            }
            Size(op.outWidth, op.outHeight)
        }.onFailure {
            handleDecodeFailure(null, it)
        }.getOrDefault(Size(errorBitmap.width, errorBitmap.height))
    }

    /**
     *获取bitmap 使用LruCache缓存
     */
    fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null
    ): Bitmap {
        return getImage(book, src, width, height, null)
    }

    fun getImageOrNull(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null,
        cacheKeySuffix: String? = null
    ): Bitmap? {
        val bitmap = getImage(book, src, width, height, cacheKeySuffix)
        return bitmap.takeUnless { it == errorBitmap }
    }

    fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null,
        cacheKeySuffix: String? = null
    ): Bitmap {
        if (ParagraphBubbleRenderer.isBubbleSrc(src)) {
            val cacheKey = ParagraphBubbleRenderer.cacheKey(src, width, height)
            getNotRecycled(cacheKey)?.let { return it }
            return kotlin.runCatching {
                ParagraphBubbleRenderer.render(src, width, height)
                    ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            }.onSuccess {
                put(cacheKey, it)
            }.onFailure {
                handleDecodeFailure(cacheKey, it)
            }.getOrDefault(errorBitmap)
        }
        //src为空白时 可能被净化替换掉了 或者规则失效
        if (book.getUseReplaceRule() && src.isBlank()) {
            book.setUseReplaceRule(false)
            appCtx.toastOnUi(R.string.error_image_url_empty)
        }
        val vFile = BookHelp.getImage(book, src)
        val directFile = resolveLocalImageFile(src)
        //epub文件提供图片链接是相对链接，同时阅读多个epub文件，缓存命中错误
        //bitmapLruCache的key同一改成缓存文件的路径
        val imageFile = directFile ?: vFile
        val cacheKey = if (cacheKeySuffix.isNullOrBlank()) {
            imageFile.absolutePath
        } else {
            "${imageFile.absolutePath}#$cacheKeySuffix"
        }
        val cacheBitmap = getNotRecycled(cacheKey)
        if (cacheBitmap != null) return cacheBitmap
        if (!imageFile.exists()) {
            return if (src.isDataUrl()) {
                decodeDataUrlImage(src, width, height, cacheKey)
            } else {
                errorBitmap
            }
        }
        return kotlin.runCatching {
            val bitmap = BitmapUtils.decodeBitmap(imageFile.absolutePath, width, height)
                ?: SvgUtils.createBitmap(imageFile.absolutePath, width, height)
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            put(cacheKey, bitmap)
            bitmap
        }.onFailure {
            //错误图片占位,防止重复获取
            handleDecodeFailure(cacheKey, it)
        }.getOrDefault(errorBitmap)
    }

    private fun resolveLocalImageFile(src: String): File? {
        AiImageGalleryManager.resolveImageFile(src)?.let { return it }
        val value = src.trim()
        if (value.startsWith("file://", ignoreCase = true)) {
            return runCatching { File(Uri.parse(value).path.orEmpty()) }
                .getOrNull()
                ?.takeIf { it.isFile }
        }
        return File(value).takeIf { it.isFile && value.startsWith("/") }
    }

    private fun decodeDataUrlImage(
        src: String,
        width: Int,
        height: Int?,
        cacheKey: String
    ): Bitmap {
        return kotlin.runCatching {
            val dataBytes = src.decodeBase64DataUrlBytes()
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(dataBytes, 0, dataBytes.size, options)
            options.inSampleSize = kotlin.run {
                val wRatio = if (width > 0) options.outWidth / width else -1
                val hRatio = height?.takeIf { it > 0 }?.let { options.outHeight / it } ?: -1
                when {
                    wRatio > 1 && hRatio > 1 -> maxOf(wRatio, hRatio)
                    wRatio > 1 -> wRatio
                    hRatio > 1 -> hRatio
                    else -> 1
                }
            }
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeByteArray(dataBytes, 0, dataBytes.size, options)
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            put(cacheKey, bitmap)
            bitmap
        }.onFailure {
            handleDecodeFailure(cacheKey, it)
        }.getOrDefault(errorBitmap)
    }

    fun trimMemory() {
        val trimSize = (cacheSize / 2).coerceAtLeast(MIN_CACHE_SIZE)
        if (bitmapLruCache.maxSize() > trimSize) {
            bitmapLruCache.resize(trimSize)
        }
    }

    fun clear() {
        bitmapLruCache.evictAll()
    }

}
