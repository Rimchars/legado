package io.legado.app.model

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object VideoPrepareManager {

    private const val CACHE_TTL = 10 * 60 * 1000L
    private val warmScope = CoroutineScope(SupervisorJob() + IO)
    private val warmSemaphore = Semaphore(2)

    data class PreparedVideo(
        val book: Book,
        val source: BookSource,
        val chapters: List<BookChapter>,
        val inBookshelf: Boolean,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val isFresh: Boolean
            get() = System.currentTimeMillis() - createdAt <= CACHE_TTL
    }

    private val cache = ConcurrentHashMap<String, PreparedVideo>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val warmingKeys = ConcurrentHashMap.newKeySet<String>()

    fun cacheKey(origin: String?, bookUrl: String?): String {
        return "${origin.orEmpty()}|${bookUrl.orEmpty()}"
    }

    fun take(origin: String?, bookUrl: String?): PreparedVideo? {
        val key = cacheKey(origin, bookUrl)
        return cache[key]?.takeIf { it.isFresh } ?: run {
            cache.remove(key)
            null
        }
    }

    fun warm(searchBook: SearchBook) {
        val key = cacheKey(searchBook.origin, searchBook.bookUrl)
        if (cache[key]?.isFresh == true) return
        if (!warmingKeys.add(key)) return
        warmScope.async(IO) {
            try {
                warmSemaphore.withPermit {
                    runCatching { prepare(this, searchBook) }
                }
            } finally {
                warmingKeys.remove(key)
            }
        }
    }

    fun warm(searchBooks: List<SearchBook>, limit: Int = 6) {
        searchBooks.asSequence()
            .filter { it.origin.isNotBlank() && it.bookUrl.isNotBlank() }
            .take(limit)
            .forEach { warm(it) }
    }

    fun cancelWarm() {
        warmScope.coroutineContext.cancelChildren()
    }

    suspend fun prepare(scope: CoroutineScope, searchBook: SearchBook): PreparedVideo = withContext(IO) {
        val key = cacheKey(searchBook.origin, searchBook.bookUrl)
        cache[key]?.takeIf { it.isFresh }?.let { return@withContext it }
        val lock = locks.getOrPut(key) { Mutex() }
        lock.withLock {
            cache[key]?.takeIf { it.isFresh }?.let { return@withLock it }
            val source = appDb.bookSourceDao.getBookSource(searchBook.origin)
                ?: error("未找到视频源")
            var book: Book = appDb.bookDao.getBook(searchBook.bookUrl) ?: searchBook.toBook()
            val inBookshelf = !book.isNotShelf && appDb.bookDao.has(book.bookUrl)
            if (book.tocUrl.isBlank()) {
                book = WebBook.getBookInfoAwait(source, book, canReName = true)
            }
            val chapters = if (book.tocUrl.isNotBlank()) {
                val oldChapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                oldChapters.takeIf { it.isNotEmpty() } ?: loadChapters(scope, source, book)
            } else {
                loadChapters(scope, source, book)
            }
            if (chapters.isEmpty()) {
                error("视频目录为空")
            }
            if (!inBookshelf) {
                book.addType(BookType.notShelf)
            }
            book.save()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            PreparedVideo(book, source, chapters, inBookshelf).also {
                cache[key] = it
            }
        }
    }

    private suspend fun loadChapters(
        scope: CoroutineScope,
        source: BookSource,
        book: Book
    ): List<BookChapter> {
        return scope.async(IO) {
            WebBook.getChapterListAwait(source, book, runPerJs = true, isFromBookInfo = true)
                .getOrThrow()
        }.await()
    }
}
