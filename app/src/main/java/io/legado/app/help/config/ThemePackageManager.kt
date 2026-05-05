package io.legado.app.help.config

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDav
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

object ThemePackageManager {

    private const val packageFileName = "theme.json"
    private const val mainBackgroundPrefix = "background"
    private const val bookInfoBackgroundPrefix = "book_info_background"

    val rootDir: File
        get() = appCtx.externalFiles.getFile("themePackages")

    suspend fun load(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        val local = loadLocal(isNightTheme).associateBy { it.dirName }
        val remote = if (AppConfig.syncThemePackages) {
            loadRemote(isNightTheme).associateBy { it.dirName }
        } else {
            emptyMap()
        }
        val keys = local.keys + remote.keys
        sortEntries(keys.mapNotNull { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )

                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> null
            }
        })
    }

    suspend fun loadLocalOnly(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        sortEntries(loadLocal(isNightTheme))
    }

    suspend fun localThemeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedDirName = themeName.trim().normalizeFileName()
        loadLocal(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun addFromCurrent(context: Context, name: String, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val normalizedName = name.trim().ifBlank { if (isNightTheme) "夜间主题" else "日间主题" }
            val config = ThemeConfig.getDurConfig(context).copy(
                themeName = normalizedName,
                isNightTheme = isNightTheme
            )
            saveConfig(config)
        }

    suspend fun addFromConfig(config: ThemeConfig.Config): Entry = withContext(IO) {
        saveConfig(config)
    }

    suspend fun themeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedDirName = themeName.trim().normalizeFileName()
        val localExists = loadLocal(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
        if (localExists) {
            return@withContext true
        }
        if (!AppConfig.syncThemePackages) {
            return@withContext false
        }
        loadRemote(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun upload(entry: Entry) = withContext(IO) {
        if (!AppConfig.syncThemePackages) return@withContext
        AppWebDav.uploadThemePackage(
            entry.packageInfo.isNightTheme,
            entry.dirName,
            exportZip(entry)
        )
    }

    suspend fun download(entry: Entry): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppWebDav.downloadThemePackage(entry.packageInfo.isNightTheme, entry.dirName, zipFile)
        importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        val pkg = peekPackage(zipFile)
        if (themeExists(pkg.isNightTheme, pkg.name)) {
            throw IllegalArgumentException("已存在同名主题")
        }
        importZipInternal(zipFile, 0L)
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.packageInfo.isNightTheme, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        zipFile
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        entry.localDir?.let { FileUtils.delete(it, deleteRootDir = true) }
    }

    suspend fun deleteRemote(entry: Entry) = withContext(IO) {
        AppWebDav.deleteThemePackage(entry.packageInfo.isNightTheme, entry.dirName)
    }

    fun apply(context: Context, entry: Entry, switchNightMode: Boolean = true) {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        val config = resolveConfigPaths(entry.packageInfo, dir)
        ThemeConfig.applyConfig(context, config, switchNightMode)
    }

    suspend fun reapplyRestoredAppliedThemes(context: Context) = withContext(IO) {
        val currentNight = AppConfig.isNightTheme
        reapplyRestoredAppliedTheme(context, !currentNight)
        reapplyRestoredAppliedTheme(context, currentNight)
    }

    fun getConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        return resolveConfigPaths(entry.packageInfo, dir)
    }

    suspend fun ensureLocalAppliedTheme(context: Context, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val currentConfig = ThemeConfig.getThemeConfig(context, isNightTheme)
            val config = currentConfig.copy(
                isNightTheme = isNightTheme,
                themeName = currentConfig.themeName.trim()
                    .ifBlank { if (isNightTheme) "夜间主题" else "日间主题" }
            )
            val dirName = config.themeName.normalizeFileName()
            val dir = localDir(isNightTheme, dirName)
            readPackage(dir)?.let { pkg ->
                return@withContext Entry(pkg, Source.LOCAL, localDir = dir)
            }
            saveConfig(config.copy(isNightTheme = isNightTheme))
        }

    private fun reapplyRestoredAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        val normalizedDirName = themeName.normalizeFileName()
        val directDir = localDir(isNightTheme, normalizedDirName)
        val entry = readPackage(directDir)?.let { pkg ->
            Entry(pkg, Source.LOCAL, localDir = directDir)
        } ?: loadLocal(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        } ?: return
        val dir = entry.localDir ?: localDir(isNightTheme, entry.dirName)
        val config = resolveConfigPaths(entry.packageInfo, dir)
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private fun saveConfig(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { if (config.isNightTheme) "夜间主题" else "日间主题" }
        val dirName = normalizedName.normalizeFileName()
        val dir = localDir(config.isNightTheme, dirName).apply {
            if (!exists()) mkdirs()
        }
        val namedConfig = config.copy(themeName = normalizedName)
        val packagedConfig = copyAssetsIntoPackage(namedConfig, dir, config.isNightTheme)
        val pkg = Package(
            name = normalizedName,
            dirName = dirName,
            isNightTheme = config.isNightTheme,
            updatedAt = System.currentTimeMillis(),
            config = packagedConfig
        )
        File(dir, packageFileName).writeText(GSON.toJson(pkg))
        ThemeConfig.addConfig(resolveConfigPaths(pkg, dir))
        return Entry(pkg, Source.LOCAL, localDir = dir)
    }

    private fun loadLocal(isNightTheme: Boolean): List<Entry> {
        val typeDir = typeDir(isNightTheme)
        return typeDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                readPackage(dir)?.let { pkg ->
                    Entry(pkg, Source.LOCAL, localDir = dir)
                }
            }.orEmpty()
    }

    private fun sortEntries(entries: List<Entry>): List<Entry> {
        return entries.sortedWith(
            compareByDescending<Entry> { maxOf(it.packageInfo.updatedAt, it.remoteUpdatedAt) }
                .thenByDescending { it.packageInfo.updatedAt }
                .thenBy { it.packageInfo.name }
                .thenBy { it.dirName }
        )
    }

    private suspend fun loadRemote(isNightTheme: Boolean): List<Entry> {
        return AppWebDav.listThemePackages(isNightTheme).map { remoteDir ->
            val dirName = remoteDir.displayName.trimEnd('/').removeSuffix(".zip")
            Entry(
                packageInfo = Package(
                    name = dirName,
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = remoteDir.lastModify,
                    config = null
                ),
                source = Source.REMOTE,
                remoteUpdatedAt = remoteDir.lastModify
            )
        }
    }

    private fun readPackage(dir: File): Package? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        return GSON.fromJsonObject<Package>(file.readText()).getOrNull()
    }

    private fun peekPackage(zipFile: File): Package {
        val unzipDir = tempDir.getFile("peek_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir) { it.endsWith(packageFileName) }
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException("未找到主题配置文件")
            GSON.fromJsonObject<Package>(packageFile.readText()).getOrThrow()
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        ZipUtils.unZipToPath(zipFile, unzipDir)
        val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
            ?: throw IllegalArgumentException("未找到主题配置文件")
        val pkg = GSON.fromJsonObject<Package>(packageFile.readText()).getOrThrow()
        val dirName = pkg.dirName.ifBlank { pkg.name.normalizeFileName() }
        val targetDir = localDir(pkg.isNightTheme, dirName)
        if (targetDir.exists()) {
            FileUtils.delete(targetDir, deleteRootDir = true)
        }
        targetDir.mkdirs()
        packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
        val restoredPackage = readPackage(targetDir) ?: pkg
        val targetPackage = if (remoteUpdatedAt == 0L) {
            restoredPackage.copy(updatedAt = System.currentTimeMillis())
        } else {
            restoredPackage
        }
        File(targetDir, packageFileName).writeText(GSON.toJson(targetPackage))
        ThemeConfig.addConfig(resolveConfigPaths(targetPackage, targetDir))
        return Entry(targetPackage, Source.LOCAL, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
    }

    private fun copyAssetsIntoPackage(
        config: ThemeConfig.Config,
        dir: File,
        isNightTheme: Boolean
    ): ThemeConfig.Config {
        val background = copyAsset(config.backgroundImgPath, dir, mainBackgroundPrefix)
        val bookInfo = copyAsset(
            config.bookInfoBackgroundImgPath
                ?: appCtx.getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            dir,
            bookInfoBackgroundPrefix
        )
        return config.copy(
            backgroundImgPath = background,
            bookInfoBackgroundImgPath = bookInfo
        )
    }

    private fun copyAsset(path: String?, dir: File, prefix: String): String? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) return path
        val source = File(path)
        if (!source.exists()) return path
        val suffix = source.name.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val target = File(dir, "$prefix$suffix")
        source.copyTo(target, overwrite = true)
        return target.name
    }

    private fun resolveConfigPaths(pkg: Package, dir: File): ThemeConfig.Config {
        val config = pkg.config ?: ThemeConfig.Config(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            primaryColor = "#795548",
            accentColor = "#E53935",
            backgroundColor = if (pkg.isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (pkg.isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = false,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
        return config.copy(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            backgroundImgPath = resolvePath(config.backgroundImgPath, dir),
            bookInfoBackgroundImgPath = resolvePath(config.bookInfoBackgroundImgPath, dir)
        )
    }

    private fun resolvePath(path: String?, dir: File): String? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) return path
        val file = File(path)
        if (file.isAbsolute) {
            if (file.exists()) return path
            findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
            return path
        }
        val packagedFile = File(dir, path)
        if (packagedFile.exists()) return packagedFile.absolutePath
        findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
        return packagedFile.absolutePath
    }

    private fun findPackagedAsset(dir: File, fileName: String): File? {
        if (fileName.isBlank()) return null
        val lowerName = fileName.lowercase()
        return dir.walkTopDown().firstOrNull { file ->
            file.isFile && file.name.lowercase() == lowerName
        }
    }

    fun localDir(isNightTheme: Boolean, dirName: String): File {
        return typeDir(isNightTheme).getFile(dirName)
    }

    private val tempDir: File
        get() = rootDir.getFile("temp").apply {
            if (!exists()) mkdirs()
        }

    private fun typeDir(isNightTheme: Boolean): File {
        return rootDir.getFile(if (isNightTheme) "night" else "day").apply {
            if (!exists()) mkdirs()
        }
    }

    data class Entry(
        val packageInfo: Package,
        val source: Source,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    ) {
        val dirName: String get() = packageInfo.dirName
    }

    @Keep
    data class Package(
        val name: String,
        val dirName: String,
        val isNightTheme: Boolean,
        val updatedAt: Long,
        val config: ThemeConfig.Config?
    )

    enum class Source {
        LOCAL,
        REMOTE,
        BOTH
    }
}
