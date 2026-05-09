package io.legado.app.ui.book.import.local

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.list
import io.legado.app.utils.mapParallel
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.max

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = context.getPrefInt(PreferKey.localBookImportSort)
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    var filterKey: String? = null
    val scanProgressFlow = MutableStateFlow<ScanProgress?>(null)
    val importProgressFlow = MutableStateFlow<ImportProgress?>(null)
    val dataFlow = callbackFlow<List<ImportBook>> {

        val list = Collections.synchronizedList(ArrayList<ImportBook>())

        dataCallback = object : DataCallback {

            override fun setItems(fileDocs: List<FileDoc>) {
                list.clear()
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun addItems(fileDocs: List<FileDoc>) {
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun upAdapter() {
                trySend(list)
            }

            override fun onScanStart() {
                scanProgressFlow.value = ScanProgress(
                    pendingDirs = 1,
                    scannedDirs = 0,
                    foundBooks = 0
                )
            }

            override fun onScanProgress(progress: ScanProgress) {
                scanProgressFlow.value = progress
            }

            override fun onScanFinish() {
                scanProgressFlow.value = null
            }
        }

        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        val docList = docList.toList()
        val filterKey = filterKey
        val skipFilter = filterKey.isNullOrBlank()
        val comparator = when (sort) {
            2 -> compareBy<ImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        docList.asSequence().filter {
            skipFilter || it.name.contains(filterKey)
        }.sortedWith(comparator).toList()
    }.flowOn(IO)

    fun addToBookshelf(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            val fileUris = bookList.map {
                it.file.uri
            }
            LocalBook.importFiles(fileUris)
        }.onError {
            context.toastOnUi("添加书架失败，请尝试重新选择文件夹")
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onSuccess {
            context.toastOnUi("添加书架成功")
        }.onFinally {
            finally.invoke()
        }
    }

    fun addToBookshelfWithProgress(
        bookList: HashSet<ImportBook>,
        onProgress: (ImportProgress) -> Unit,
        finally: () -> Unit
    ) {
        execute {
            val total = bookList.size.coerceAtLeast(1)
            var processed = 0
            var imported = 0
            var failed = 0
            val initialProgress = ImportProgress(total, processed, imported, failed)
            importProgressFlow.value = initialProgress
            onProgress(initialProgress)
            bookList.forEach { item ->
                kotlin.runCatching {
                    fun emitItemStage(message: String) {
                        val progress = ImportProgress(
                            total = total,
                            processed = processed,
                            imported = imported,
                            failed = failed,
                            message = message
                        )
                        importProgressFlow.value = progress
                        onProgress(progress)
                    }
                    emitItemStage("读取文件信息 ${item.name}")
                    val books = LocalBook.importFiles(item.file.uri) { stage ->
                        emitItemStage("$stage ${item.name}")
                    }
                    books.forEach { book ->
                        var lastEmitTime = 0L
                        LocalBook.prepareImportedBookCache(book) { stage, stageProcessed, stageTotal, title ->
                            val now = System.currentTimeMillis()
                            val shouldEmit = stageProcessed >= stageTotal || now - lastEmitTime >= 120L
                            if (shouldEmit) {
                                lastEmitTime = now
                                val stageName = when (stage) {
                                    "toc" -> "目录解析"
                                    "index" -> "索引构建"
                                    "layout" -> "排版缓存"
                                    else -> "正文解码"
                                }
                                val decodeProgress = ImportProgress(
                                    total = total,
                                    processed = processed,
                                    imported = imported,
                                    failed = failed,
                                    message = "$stageName ${book.name}: $title ($stageProcessed/${max(stageTotal, 1)})"
                                )
                                importProgressFlow.value = decodeProgress
                                onProgress(decodeProgress)
                            }
                        }
                    }
                }.onSuccess {
                    imported += 1
                }.onFailure {
                    failed += 1
                    AppLog.put("导入失败: ${item.name}\n${it.localizedMessage}", it)
                }
                processed += 1
                val progress = ImportProgress(
                    total = total,
                    processed = processed,
                    imported = imported,
                    failed = failed,
                    message = "导入 ${item.name}"
                )
                importProgressFlow.value = progress
                onProgress(progress)
            }
            if (processed == failed) {
                throw NoStackTraceException("ImportFiles Error:\nAll input files occur error")
            }
        }.onError {
            context.toastOnUi("添加书架失败，请尝试重新选择文件夹")
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onSuccess {
            context.toastOnUi("添加书架成功")
        }.onFinally {
            importProgressFlow.value = null
            finally.invoke()
        }
    }

    fun deleteDoc(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            bookList.forEach {
                it.file.delete()
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val docList = fileDoc.list { item ->
                item.isVisibleImportBookItem()
            }
            dataCallback?.setItems(docList!!)
        }.onError {
            context.toastOnUi("获取文件列表出错\n${it.localizedMessage}")
        }
    }

    suspend fun scanDoc(fileDoc: FileDoc) {
        dataCallback?.clear()
        dataCallback?.onScanStart()
        try {
            val channel = Channel<FileDoc>(UNLIMITED)
            var n = 1
            var scannedDirCount = 0
            var foundBookCount = 0
            channel.trySend(fileDoc)
            val list = arrayListOf<FileDoc>()
            channel.consumeAsFlow()
                .mapParallel(16) { fileDoc ->
                    fileDoc.list()!!
                }.onEach { fileDocs ->
                    n--
                    scannedDirCount += 1
                    list.clear()
                    fileDocs.forEach {
                        if (it.isDir) {
                            n++
                            channel.trySend(it)
                        } else if (it.isVisibleImportBookItem()) {
                            list.add(it)
                            foundBookCount += 1
                        }
                    }
                    dataCallback?.addItems(list)
                    dataCallback?.onScanProgress(
                        ScanProgress(
                            pendingDirs = n.coerceAtLeast(0),
                            scannedDirs = scannedDirCount,
                            foundBooks = foundBookCount
                        )
                    )
                }.takeWhile {
                    n > 0
                }.catch {
                    context.toastOnUi("扫描文件夹出错\n${it.localizedMessage}")
                }.collect()
        } finally {
            dataCallback?.onScanFinish()
        }
    }

    fun updateCallBackFlow(filterKey: String?) {
        this.filterKey = filterKey
        dataCallback?.upAdapter()
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>)

        fun addItems(fileDocs: List<FileDoc>)

        fun clear()

        fun upAdapter()

        fun onScanStart() {}

        fun onScanProgress(progress: ScanProgress) {}

        fun onScanFinish() {}

    }

    data class ScanProgress(
        val pendingDirs: Int,
        val scannedDirs: Int,
        val foundBooks: Int
    )

    data class ImportProgress(
        val total: Int,
        val processed: Int,
        val imported: Int,
        val failed: Int,
        val message: String = ""
    )

    private fun FileDoc.isVisibleImportBookItem(): Boolean {
        return when {
            name.startsWith(".") -> false
            isDir -> true
            else -> name.matches(bookFileRegex)
        }
    }

}
