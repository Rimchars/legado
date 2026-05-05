package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File

object CacheManifestHelper {

    const val MANIFEST_FILE_NAME = "cache_manifest.json"

    fun manifestFile(book: Book): File {
        return File(BookHelp.getCacheDir(book), MANIFEST_FILE_NAME)
    }

    fun hasManifest(cacheDir: File): Boolean {
        return File(cacheDir, MANIFEST_FILE_NAME).isFile
    }

    fun read(book: Book): CacheBookManifest? {
        return read(manifestFile(book))
    }

    fun read(file: File): CacheBookManifest? {
        if (!file.isFile) return null
        return GSON.fromJsonObject<CacheBookManifest>(file.readText()).getOrNull()
    }

    fun listManifests(): List<CacheBookManifest> {
        val root = File(BookHelp.cachePath)
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { read(File(it, MANIFEST_FILE_NAME)) }
            ?.toList()
            .orEmpty()
    }

    fun write(
        book: Book,
        chapters: List<BookChapter>,
        isChapterCached: (BookChapter) -> Boolean
    ): CacheBookManifest? {
        val realChapters = chapters.filterNot { it.isVolume }
        val cachedByIndex = realChapters.associate { it.index to isChapterCached(it) }
        val cachedCount = cachedByIndex.values.count { it }
        val file = manifestFile(book)
        if (cachedCount <= 0) {
            file.delete()
            return null
        }
        val cacheDir = file.parentFile ?: return null
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val now = System.currentTimeMillis()
        val manifest = CacheBookManifest(
            bookUrl = book.bookUrl,
            tocUrl = book.tocUrl,
            origin = book.origin,
            originName = book.originName,
            name = book.name,
            author = book.author,
            kind = book.kind,
            coverUrl = book.coverUrl,
            intro = book.intro,
            type = book.type,
            folderName = book.getFolderName(),
            latestChapterTitle = book.latestChapterTitle,
            totalChapterNum = realChapters.size.takeIf { it > 0 } ?: book.totalChapterNum,
            updatedAt = now,
            chapters = realChapters.map { chapter ->
                CacheChapterManifest(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    baseUrl = chapter.baseUrl,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl,
                    cached = cachedByIndex[chapter.index] == true
                )
            }
        )
        file.writeText(GSON.toJson(manifest))
        return manifest
    }

    fun delete(book: Book) {
        manifestFile(book).delete()
    }

    fun toBook(manifest: CacheBookManifest): Book {
        return Book(
            bookUrl = manifest.bookUrl,
            tocUrl = manifest.tocUrl,
            origin = manifest.origin,
            originName = manifest.originName,
            name = manifest.name,
            author = manifest.author,
            kind = manifest.kind,
            coverUrl = manifest.coverUrl,
            intro = manifest.intro,
            type = manifest.type,
            latestChapterTitle = manifest.latestChapterTitle,
            totalChapterNum = manifest.totalChapterNum,
            canUpdate = false
        )
    }

    fun toChapters(
        manifest: CacheBookManifest,
        targetBookUrl: String = manifest.bookUrl
    ): List<BookChapter> {
        return manifest.chapters
            .sortedBy { it.index }
            .map { chapter ->
                BookChapter(
                    url = chapter.url,
                    title = chapter.title,
                    isVolume = false,
                    baseUrl = chapter.baseUrl,
                    bookUrl = targetBookUrl,
                    index = chapter.index,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl
                )
            }
    }

    fun mergeResourceUrls(
        chapters: List<BookChapter>,
        manifest: CacheBookManifest?
    ): Boolean {
        if (manifest == null) return false
        val byIndex = manifest.chapters.associateBy { it.index }
        var changed = false
        chapters.forEach { chapter ->
            val resourceUrl = byIndex[chapter.index]?.resourceUrl
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            if (chapter.resourceUrl.isNullOrBlank()) {
                chapter.resourceUrl = resourceUrl
                changed = true
            }
        }
        return changed
    }
}

data class CacheBookManifest(
    val version: Int = 1,
    val bookUrl: String = "",
    val tocUrl: String = "",
    val origin: String = "",
    val originName: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val type: Int = 0,
    val folderName: String = "",
    val latestChapterTitle: String? = null,
    val totalChapterNum: Int = 0,
    val updatedAt: Long = 0L,
    val chapters: List<CacheChapterManifest> = emptyList()
) {
    val cachedChapterCount: Int
        get() = chapters.count { it.cached }
}

data class CacheChapterManifest(
    val index: Int = 0,
    val title: String = "",
    val url: String = "",
    val baseUrl: String = "",
    val isVip: Boolean = false,
    val isPay: Boolean = false,
    val resourceUrl: String? = null,
    val tag: String? = null,
    val wordCount: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    val startFragmentId: String? = null,
    val endFragmentId: String? = null,
    val variable: String? = null,
    val imgUrl: String? = null,
    val cached: Boolean = false
)
