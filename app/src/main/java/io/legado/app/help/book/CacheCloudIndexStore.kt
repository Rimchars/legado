package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File

data class CacheCloudIndex(
    val version: Int = 1,
    val items: List<CacheCloudIndexItem> = emptyList()
)

data class CacheCloudIndexItem(
    val cacheKey: String = "",
    val groupKey: String = "",
    val sourceKey: String = "",
    val mode: String = "",
    val bookUrl: String = "",
    val origin: String = "",
    val originName: String = "",
    val name: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String? = null,
    val latestChapterTitle: String? = null,
    val type: Int = 0,
    val totalChapterCount: Int = 0,
    val cachedChapterCount: Int = 0,
    val zipFileName: String = "",
    val updatedAt: Long = 0L
)

object CacheCloudIndexStore {

    private const val FILE_NAME = "cache_index_local.json"
    private const val REMOTE_FILE_NAME = "cache_index.json"

    private val rootDir: File
        get() = appCtx.externalFiles.getFile("cachePackages")

    private val localFile: File
        get() = rootDir.getFile(FILE_NAME)

    fun remoteFileName(): String = REMOTE_FILE_NAME

    fun readLocal(): List<CacheCloudIndexItem> {
        if (!localFile.exists()) return emptyList()
        return GSON.fromJsonObject<CacheCloudIndex>(localFile.readText()).getOrNull()?.items.orEmpty()
    }

    fun writeLocal(items: List<CacheCloudIndexItem>) {
        rootDir.mkdirs()
        localFile.writeText(
            GSON.toJson(
                CacheCloudIndex(items = items.sortedByDescending { it.updatedAt })
            )
        )
    }

    fun mergeRemote(items: List<CacheCloudIndexItem>): List<CacheCloudIndexItem> {
        val merged = linkedMapOf<String, CacheCloudIndexItem>()
        readLocal().forEach { merged[it.cacheKey] = it }
        items.forEach { remote ->
            val current = merged[remote.cacheKey]
            if (current == null || remote.updatedAt >= current.updatedAt) {
                merged[remote.cacheKey] = remote
            }
        }
        return merged.values.sortedByDescending { it.updatedAt }.also(::writeLocal)
    }

    fun upsertLocal(item: CacheCloudIndexItem) {
        val merged = linkedMapOf<String, CacheCloudIndexItem>()
        readLocal().forEach { merged[it.cacheKey] = it }
        val current = merged[item.cacheKey]
        if (current == null || item.updatedAt >= current.updatedAt) {
            merged[item.cacheKey] = item
        }
        writeLocal(merged.values.toList())
    }

    fun removeLocal(cacheKey: String) {
        writeLocal(readLocal().filterNot { it.cacheKey == cacheKey })
    }
}

fun Book.cacheGroupKey(mode: String): String {
    return listOf(mode, name.trim(), getRealAuthor().trim()).joinToString(separator = "\u001F")
}

fun Book.cacheSourceKey(): String {
    return listOf(origin.ifBlank { originName }, bookUrl).joinToString(separator = "\u001F")
}

fun Book.cacheRemoteKey(mode: String): String {
    return listOf(mode, cacheSourceKey()).joinToString(separator = "\u001F")
}

fun Book.cacheSourceName(): String {
    return when {
        isLocal -> appCtx.getString(io.legado.app.R.string.local)
        originName.isNotBlank() -> originName
        origin.isNotBlank() -> origin
        else -> appCtx.getString(io.legado.app.R.string.unknown)
    }
}
