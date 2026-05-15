package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDav
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.File

object TopBarConfig {

    const val DEFAULT_DIR_NAME = "default"
    const val STYLE_DEFAULT = "default"
    const val STYLE_REGULAR = "regular"
    private const val packageFileName = "top_bar.json"
    private const val activeDayKey = PreferKey.topBarPackageDay
    private const val activeNightKey = PreferKey.topBarPackageNight

    val rootDir: File
        get() = appCtx.externalFiles.getFile("topBarPackages")

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("topBarTemp").apply { mkdirs() }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply { mkdirs() }

    @Keep
    data class Config(
        var name: String,
        var isNightMode: Boolean,
        var style: String = STYLE_DEFAULT,
        var tagBarColor: Int? = null,
        var tagBarAlpha: Int = 100,
        var tagSelectedColor: Int? = null,
        var tagSelectedAlpha: Int = 100,
        var wallpaperPath: String? = null,
        var wallpaperAlpha: Int = 100,
        var backgroundColor: Int? = null,
        var cornerScale: Float? = null,
        var expandFiltersByDefault: Boolean = false,
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class Entry(
        val config: Config,
        val source: Source,
        val dirName: String,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    )

    enum class Source { BUILTIN, LOCAL, REMOTE, BOTH }

    @Keep
    private data class RemoteCache(
        val name: String,
        val dirName: String,
        val isNightMode: Boolean,
        val updatedAt: Long
    )

    fun defaultConfig(context: Context, isNight: Boolean): Config {
        return Config(
            name = defaultName(isNight),
            isNightMode = isNight,
            tagBarColor = ContextCompat.getColor(context, R.color.background_menu),
            tagSelectedColor = ContextCompat.getColor(context, R.color.background_card),
            backgroundColor = defaultBackgroundColor(isNight),
            cornerScale = 1f,
            updatedAt = 0L
        )
    }

    fun currentConfig(context: Context, isNight: Boolean = AppConfig.isNightTheme): Config {
        return currentEntry(context, isNight).config
    }

    fun activeDirName(isNight: Boolean): String {
        return appCtx.getPrefString(if (isNight) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
            ?.ifBlank { DEFAULT_DIR_NAME }
            ?: DEFAULT_DIR_NAME
    }

    fun currentSignature(isNight: Boolean): String {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return "$isNight|$DEFAULT_DIR_NAME"
        val configFile = File(localDir(isNight, dirName), packageFileName)
        return "$isNight|$dirName|${configFile.lastModified()}"
    }

    fun currentEntry(context: Context, isNight: Boolean): Entry {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return defaultEntry(context, isNight)
        return readEntry(localDir(isNight, dirName)) ?: defaultEntry(context, isNight)
    }

    fun currentWallpaperFile(context: Context, isNight: Boolean): File? {
        val entry = currentEntry(context, isNight)
        val path = entry.config.wallpaperPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        val resolved = if (file.isAbsolute) {
            file
        } else {
            File(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), path)
        }
        return resolved.takeIf { it.exists() && it.isFile }
    }

    suspend fun loadEntries(context: Context, isNight: Boolean, includeRemote: Boolean): List<Entry> {
        val local = loadLocal(isNight).associateBy { it.dirName }
        val remote = if (includeRemote) {
            loadRemoteOrCache(isNight).associateBy { it.dirName }
        } else {
            emptyMap()
        }
        val entries = linkedMapOf<String, Entry>()
        entries[DEFAULT_DIR_NAME] = defaultEntry(context, isNight)
        (local.keys + remote.keys).forEach { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            entries[key] = when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )
                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> return@forEach
            }
        }
        return entries.values.sortedWith(
            compareBy<Entry> { it.dirName != DEFAULT_DIR_NAME }
                .thenBy { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.config.updatedAt }
                .thenBy { it.config.name }
                .thenBy { it.dirName }
        )
    }

    fun addOrUpdate(config: Config, oldEntry: Entry? = null): Entry {
        val normalized = normalizeConfig(config)
        val name = normalized.name.trim().ifBlank { defaultName(normalized.isNightMode) }
        val keepOldDir = oldEntry != null &&
            oldEntry.dirName.isNotBlank() &&
            oldEntry.dirName != DEFAULT_DIR_NAME &&
            oldEntry.source != Source.REMOTE
        val dirName = if (keepOldDir) {
            oldEntry!!.dirName
        } else {
            name.normalizeFileName().ifBlank { "top_bar_${System.currentTimeMillis()}" }
        }
        if (!keepOldDir && readEntry(localDir(normalized.isNightMode, dirName)) != null) {
            throw IllegalArgumentException(appCtx.getString(R.string.top_bar_name_exists))
        }
        val dir = localDir(normalized.isNightMode, dirName).apply { mkdirs() }
        val next = normalized.copy(
            name = name,
            wallpaperPath = normalizeWallpaperPath(normalized.wallpaperPath, dir),
            updatedAt = System.currentTimeMillis()
        )
        File(dir, packageFileName).writeText(GSON.toJson(next))
        return Entry(next, Source.LOCAL, dirName, localDir = dir)
    }

    fun apply(entry: Entry) {
        val key = if (entry.config.isNightMode) activeNightKey else activeDayKey
        appCtx.putPrefString(key, entry.dirName)
    }

    suspend fun restoreApplied(isNight: Boolean): Entry? {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return defaultEntry(appCtx, isNight)
        readEntry(localDir(isNight, dirName))?.let { return it }
        val remoteEntries = loadRemoteOrCache(isNight)
        val remote = remoteEntries.firstOrNull { it.dirName == dirName }
            ?: remoteEntries.firstOrNull { it.config.name.normalizeFileName() == dirName }
            ?: run {
                io.legado.app.constant.AppLog.put("恢复顶栏包未找到: $dirName")
                return null
            }
        return runCatching { download(remote) }.onSuccess {
            if (it.dirName != dirName) apply(it)
        }.getOrElse {
            io.legado.app.constant.AppLog.put("恢复顶栏包下载失败: $dirName\n${it.localizedMessage}", it)
            null
        }
    }

    fun deleteLocal(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), deleteRootDir = true)
        resetActiveIfNeeded(entry)
    }

    suspend fun deleteRemote(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        AppWebDav.deleteTopBarPackage(entry.config.isNightMode, entry.dirName)
    }

    suspend fun delete(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        when (entry.source) {
            Source.REMOTE -> deleteRemote(entry)
            Source.BOTH -> {
                val remoteResult = runCatching { deleteRemote(entry) }
                deleteLocal(entry)
                remoteResult.getOrThrow()
            }
            Source.LOCAL -> deleteLocal(entry)
            Source.BUILTIN -> return
        }
        resetActiveIfNeeded(entry)
    }

    suspend fun exportZip(entry: Entry): File {
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.config.isNightMode, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        return zipFile
    }

    fun importZip(zipFile: File): Entry = importZipInternal(zipFile)

    suspend fun upload(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        AppWebDav.uploadTopBarPackage(entry.config.isNightMode, entry.dirName, exportZip(entry))
    }

    suspend fun download(entry: Entry): Entry {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppWebDav.downloadTopBarPackage(entry.config.isNightMode, entry.dirName, zipFile)
        return importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    fun withOpacity(color: Int, opacity: Int): Int {
        val alpha = (opacity.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun defaultBackgroundColor(isNight: Boolean): Int {
        return if (isNight) Color.BLACK else Color.WHITE
    }

    fun resolveBackgroundColor(config: Config): Int {
        return config.backgroundColor ?: defaultBackgroundColor(config.isNightMode)
    }

    fun resolveCornerScale(config: Config): Float {
        return config.cornerScale ?: 1f
    }

    fun cornerRadius(context: Context, config: Config): Float {
        return context.resources.getDimension(R.dimen.ui_panel_radius) * resolveCornerScale(config).coerceIn(0f, 3f)
    }

    private fun defaultEntry(context: Context, isNight: Boolean): Entry {
        return Entry(defaultConfig(context, isNight), Source.BUILTIN, DEFAULT_DIR_NAME)
    }

    private fun loadLocal(isNight: Boolean): List<Entry> {
        return typeDir(isNight).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readEntry(it) }
            .orEmpty()
    }

    private suspend fun loadRemote(isNight: Boolean): List<Entry> {
        return AppWebDav.listTopBarPackages(isNight).mapNotNull { file ->
            val name = file.displayName.removeSuffix(".zip")
            val dirName = name.normalizeFileName().ifBlank { return@mapNotNull null }
            Entry(
                Config(name = name, isNightMode = isNight, updatedAt = file.lastModify),
                Source.REMOTE,
                dirName,
                remoteUpdatedAt = file.lastModify
            )
        }
    }

    private suspend fun loadRemoteOrCache(isNight: Boolean): List<Entry> {
        val cached = readRemoteCache(isNight)
        return runCatching {
            loadRemote(isNight)
        }.onSuccess { remote ->
            writeRemoteCache(isNight, remote)
        }.getOrElse {
            cached
        }
    }

    private fun readRemoteCache(isNight: Boolean): List<Entry> {
        val file = remoteCacheFile(isNight)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<RemoteCache>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightMode == isNight }
            .mapNotNull { cache ->
                val dirName = cache.dirName.ifBlank { cache.name.normalizeFileName() }
                    .ifBlank { return@mapNotNull null }
                Entry(
                    Config(name = cache.name.ifBlank { dirName }, isNightMode = isNight, updatedAt = cache.updatedAt),
                    Source.REMOTE,
                    dirName,
                    remoteUpdatedAt = cache.updatedAt
                )
            }
    }

    private fun writeRemoteCache(isNight: Boolean, entries: List<Entry>) {
        val cache = entries.map {
            RemoteCache(
                name = it.config.name,
                dirName = it.dirName,
                isNightMode = it.config.isNightMode,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.config.updatedAt
            )
        }
        remoteCacheFile(isNight).writeTextIfChanged(GSON.toJson(cache))
    }

    private fun remoteCacheFile(isNight: Boolean): File {
        return remoteCacheDir.getFile(if (isNight) "night.json" else "day.json")
    }

    private fun readEntry(dir: File): Entry? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        val config = GSON.fromJsonObject<Config>(file.readText()).getOrNull()?.let(::normalizeConfig)
            ?: return null
        return Entry(config, Source.LOCAL, dir.name, localDir = dir)
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long = 0L): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.top_bar_config_missing))
            val config = normalizeConfig(GSON.fromJsonObject<Config>(packageFile.readText()).getOrThrow())
            if (remoteUpdatedAt == 0L) {
                config.updatedAt = System.currentTimeMillis()
            }
            val dirName = config.name.normalizeFileName().ifBlank { "top_bar_${System.currentTimeMillis()}" }
            val targetDir = localDir(config.isNightMode, dirName)
            if (targetDir.exists()) FileUtils.delete(targetDir, deleteRootDir = true)
            targetDir.mkdirs()
            packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
            val finalConfig = config.copy(
                wallpaperPath = normalizeWallpaperPath(config.wallpaperPath, targetDir)
            )
            File(targetDir, packageFileName).writeText(GSON.toJson(finalConfig))
            Entry(finalConfig, Source.LOCAL, dirName, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun normalizeConfig(config: Config): Config {
        config.style = when (config.style) {
            STYLE_DEFAULT, STYLE_REGULAR -> config.style
            "immersive", "flow" -> STYLE_REGULAR
            else -> STYLE_DEFAULT
        }
        config.tagBarAlpha = config.tagBarAlpha.coerceIn(0, 100)
        config.tagSelectedAlpha = config.tagSelectedAlpha.coerceIn(0, 100)
        config.wallpaperAlpha = config.wallpaperAlpha.coerceIn(0, 100)
        config.wallpaperPath = config.wallpaperPath?.takeIf { it.isNotBlank() }
        config.cornerScale = config.cornerScale?.coerceIn(0f, 3f)
        return config
    }

    private fun normalizeWallpaperPath(path: String?, dir: File): String? {
        val value = path?.takeIf { it.isNotBlank() } ?: return null
        val source = File(value)
        if (!source.isAbsolute) {
            return value
        }
        if (!source.exists() || !source.isFile) {
            return null
        }
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("top_bar_wallpaper.") }
            ?.forEach { it.delete() }
        val suffix = source.extension.takeIf { it.isNotBlank() } ?: "jpg"
        val target = File(dir, "top_bar_wallpaper.$suffix")
        if (source.absolutePath != target.absolutePath) {
            source.copyTo(target, overwrite = true)
        }
        return target.name
    }

    private fun resetActiveIfNeeded(entry: Entry) {
        if (activeDirName(entry.config.isNightMode) == entry.dirName) {
            appCtx.putPrefString(if (entry.config.isNightMode) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
        }
    }

    private fun localDir(isNight: Boolean, dirName: String): File = typeDir(isNight).getFile(dirName)

    private fun typeDir(isNight: Boolean): File {
        return rootDir.getFile(if (isNight) "night" else "day").apply { mkdirs() }
    }

    private fun defaultName(isNight: Boolean): String {
        return appCtx.getString(if (isNight) R.string.top_bar_night_default_name else R.string.top_bar_day_default_name)
    }
}
