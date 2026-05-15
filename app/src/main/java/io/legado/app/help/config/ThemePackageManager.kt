package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDav
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ThemePackageManager {

    private const val packageFileName = "theme.json"
    private const val mainBackgroundPrefix = "background"
    private const val bookInfoBackgroundPrefix = "book_info_background"
    private const val panelBackgroundPrefix = "panel_background"
    private const val uiFontPrefix = "ui_font"
    private const val titleFontPrefix = "title_font"
    private const val builtinDayDirName = "builtin_day"
    private const val builtinNightDirName = "builtin_night"
    private const val builtinDayName = "\u5185\u7f6e\u65e5\u95f4\u4e3b\u9898"
    private const val builtinNightName = "\u5185\u7f6e\u591c\u95f4\u4e3b\u9898"

    val rootDir: File
        get() = appCtx.externalFiles.getFile("themePackages")

    suspend fun load(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        val local = loadLocal(isNightTheme).filterNot(::isBuiltinDuplicate).associateBy { it.dirName }
        val remote = loadRemoteOrCache(isNightTheme).associateBy { it.dirName }
        val keys = local.keys + remote.keys
        val mergedEntries = keys.mapNotNull { key ->
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
        }
        sortEntries(listOf(builtinEntry(isNightTheme)) + mergedEntries)
    }

    suspend fun loadLocalOnly(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        sortEntries(listOf(builtinEntry(isNightTheme)) + loadLocal(isNightTheme).filterNot(::isBuiltinDuplicate))
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
            val normalizedName = name.trim().ifBlank { builtinName(isNightTheme) }
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
        loadRemoteOrCache(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun upload(entry: Entry) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
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
        importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        val pkg = peekPackage(zipFile)
        if (themeExists(pkg.isNightTheme, pkg.name)) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_name_exists))
        }
        importZipInternal(zipFile, 0L)
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        if (entry.source == Source.BUILTIN) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_builtin_export_forbidden))
        }
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.packageInfo.isNightTheme, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        zipFile
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        entry.localDir?.let { FileUtils.delete(it, deleteRootDir = true) }
    }

    suspend fun deleteRemote(entry: Entry) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppWebDav.deleteThemePackage(entry.packageInfo.isNightTheme, entry.dirName)
    }

    suspend fun apply(context: Context, entry: Entry, switchNightMode: Boolean = true) {
        val config = withContext(IO) {
            validatedConfig(entry)
        }
        ThemeConfig.applyConfig(context, config, switchNightMode)
    }

    suspend fun reapplyRestoredAppliedThemes(context: Context) = withContext(IO) {
        val currentNight = AppConfig.isNightTheme
        reapplyRestoredAppliedTheme(context, !currentNight)
        reapplyRestoredAppliedTheme(context, currentNight)
    }

    suspend fun restoreAppliedThemes(context: Context) = withContext(IO) {
        restoreAppliedTheme(context, false)
        restoreAppliedTheme(context, true)
    }

    fun getConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        return resolveConfigPaths(entry.packageInfo, dir)
    }

    suspend fun ensureLocalAppliedTheme(context: Context, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val currentConfig = ThemeConfig.getThemeConfig(context, isNightTheme)
            val themeName = currentConfig.themeName.trim()
            if (isBuiltinTheme(isNightTheme, themeName)) {
                return@withContext builtinEntry(isNightTheme)
            }
            val config = currentConfig.copy(
                isNightTheme = isNightTheme,
                themeName = themeName.ifBlank { builtinName(isNightTheme) }
            )
            if (isBuiltinTheme(isNightTheme, config.themeName)) {
                return@withContext builtinEntry(isNightTheme)
            }
            val dirName = config.themeName.normalizeFileName()
            val dir = localDir(isNightTheme, dirName)
            readPackage(dir)?.let { pkg ->
                if (!isBuiltinPackage(pkg)) {
                    return@withContext Entry(pkg, Source.LOCAL, localDir = dir)
                }
            }
            saveConfig(config.copy(isNightTheme = isNightTheme))
        }

    private suspend fun reapplyRestoredAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        if (isBuiltinTheme(isNightTheme, themeName)) {
            val config = validatedConfig(builtinEntry(isNightTheme))
            ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
            return
        }
        val normalizedDirName = themeName.normalizeFileName()
        val directDir = localDir(isNightTheme, normalizedDirName)
        val entry = readPackage(directDir)?.let { pkg ->
            Entry(pkg, Source.LOCAL, localDir = directDir)
        } ?: loadLocal(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        } ?: return
        val config = validatedConfig(entry)
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private suspend fun restoreAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        if (isBuiltinTheme(isNightTheme, themeName)) {
            val config = validatedConfig(builtinEntry(isNightTheme))
            ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
            return
        }
        val normalizedDirName = themeName.normalizeFileName()
        val localEntry = loadLocal(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        }
        val localConfig = localEntry?.let { entry ->
            runCatching { validatedConfig(entry) }.getOrElse {
                AppLog.put("restore theme local package invalid: $themeName\n${it.localizedMessage}", it)
                null
            }
        }
        if (localConfig != null) {
            ThemeConfig.applyConfig(context, localConfig, switchNightMode = false, notify = false)
            return
        }
        val remoteEntry = loadRemoteOrCache(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        } ?: run {
            AppLog.put("restore theme package not found: $themeName")
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        val downloadedEntry = runCatching { download(remoteEntry) }.getOrElse {
            AppLog.put("restore theme package download failed: $themeName\n${it.localizedMessage}", it)
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        val remoteConfig = runCatching { validatedConfig(downloadedEntry) }.getOrElse {
            AppLog.put("restore theme package resource invalid: $themeName\n${it.localizedMessage}", it)
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        ThemeConfig.applyConfig(context, remoteConfig, switchNightMode = false, notify = false)
    }

    private suspend fun applyBuiltinTheme(context: Context, isNightTheme: Boolean) {
        val config = validatedConfig(builtinEntry(isNightTheme))
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private fun builtinEntry(isNightTheme: Boolean): Entry {
        val name = builtinName(isNightTheme)
        val config = ThemeConfig.Config(
            themeName = name,
            isNightTheme = isNightTheme,
            primaryColor = if (isNightTheme) "#455A64" else "#795548",
            accentColor = if (isNightTheme) "#FF8A65" else "#E53935",
            backgroundColor = if (isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0,
            bookInfoBackgroundImgPath = null,
            panelBackgroundImgPath = null
        )
        return Entry(
            Package(
                name = name,
                dirName = builtinDirName(isNightTheme),
                isNightTheme = isNightTheme,
                updatedAt = 0L,
                config = config
            ),
            Source.BUILTIN
        )
    }

    private fun saveConfig(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { builtinName(config.isNightTheme) }
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

    private fun isBuiltinDuplicate(entry: Entry): Boolean {
        return isBuiltinPackage(entry.packageInfo)
    }

    private fun isBuiltinPackage(pkg: Package): Boolean {
        return isBuiltinTheme(pkg.isNightTheme, pkg.name) || pkg.dirName == builtinDirName(pkg.isNightTheme)
    }

    private fun isBuiltinTheme(isNightTheme: Boolean, name: String): Boolean {
        return name == builtinName(isNightTheme) ||
            name == if (isNightTheme) "Built-in night" else "Built-in day"
    }

    private fun builtinName(isNightTheme: Boolean): String {
        return if (isNightTheme) builtinNightName else builtinDayName
    }

    private fun builtinDirName(isNightTheme: Boolean): String {
        return if (isNightTheme) builtinNightDirName else builtinDayDirName
    }

    private fun sortEntries(entries: List<Entry>): List<Entry> {
        return entries.sortedWith(
            compareBy<Entry> { it.source != Source.BUILTIN }
                .thenBy { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.packageInfo.updatedAt }
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

    private suspend fun loadRemoteOrCache(isNightTheme: Boolean): List<Entry> {
        val cached = readRemoteCache(isNightTheme)
        return runCatching {
            loadRemote(isNightTheme)
        }.onSuccess { remote ->
            if (remote.isNotEmpty() || cached.isEmpty()) {
                writeRemoteCache(isNightTheme, remote)
            }
        }.getOrElse {
            cached
        }
    }

    private fun remoteCacheFile(isNightTheme: Boolean): File {
        return remoteCacheDir.getFile(if (isNightTheme) "night.json" else "day.json")
    }

    private fun readRemoteCache(isNightTheme: Boolean): List<Entry> {
        val file = remoteCacheFile(isNightTheme)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<Package>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightTheme == isNightTheme }
            .map { pkg ->
                Entry(pkg.copy(config = null), Source.REMOTE, remoteUpdatedAt = pkg.updatedAt)
            }
    }

    private fun writeRemoteCache(isNightTheme: Boolean, entries: List<Entry>) {
        val packages = entries.map {
            it.packageInfo.copy(
                config = null,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.packageInfo.updatedAt
            )
        }
        remoteCacheFile(isNightTheme).writeTextIfChanged(GSON.toJson(packages))
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
                ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
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
            ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
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
        val panelBackground = copyAsset(
            config.panelBackgroundImgPath
                ?: appCtx.getPrefString(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage),
            dir,
            panelBackgroundPrefix
        )
        val uiFont = copyAsset(config.uiFontPath, dir, uiFontPrefix, keepOriginalName = true)
        val titleFont = copyAsset(config.titleFontPath, dir, titleFontPrefix, keepOriginalName = true)
        return config.copy(
            backgroundImgPath = background,
            bookInfoBackgroundImgPath = bookInfo,
            panelBackgroundImgPath = panelBackground,
            uiFontPath = uiFont,
            titleFontPath = titleFont
        )
    }

    private fun copyAsset(
        path: String?,
        dir: File,
        prefix: String,
        keepOriginalName: Boolean = false
    ): String? {
        if (path.isNullOrBlank()) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("http", ignoreCase = true)) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(path)
                val name = DocumentFile.fromSingleUri(appCtx, uri)?.name.orEmpty()
                val target = File(dir, packageAssetName(prefix, name, keepOriginalName))
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return path
                deletePackagedAssets(dir, prefix, target)
                target.name
            }.getOrDefault(path)
        }
        val source = File(path)
        if (!source.exists()) {
            return findPackagedAssetByPrefix(dir, prefix)?.name ?: path
        }
        if (source.parentFile?.canonicalFile == dir.canonicalFile && source.name.startsWith(prefix)) {
            deletePackagedAssets(dir, prefix, source)
            return source.name
        }
        val target = File(dir, packageAssetName(prefix, source.name, keepOriginalName))
        if (source.canonicalFile == target.canonicalFile) {
            deletePackagedAssets(dir, prefix, target)
            return target.name
        }
        source.copyTo(target, overwrite = true)
        deletePackagedAssets(dir, prefix, target)
        return target.name
    }

    private fun deletePackagedAssets(dir: File, prefix: String, keepFile: File? = null) {
        val keepCanonical = keepFile?.takeIf { it.exists() }?.canonicalFile
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.filter { keepCanonical == null || it.canonicalFile != keepCanonical }
            ?.forEach { it.delete() }
    }

    private fun findPackagedAssetByPrefix(dir: File, prefix: String): File? {
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    private fun packageAssetName(prefix: String, sourceName: String, keepOriginalName: Boolean): String {
        val suffix = sourceName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        if (!keepOriginalName) {
            return "$prefix$suffix"
        }
        val normalizedName = sourceName.normalizeFileName()
        if (normalizedName.startsWith("$prefix.")) {
            return "$prefix$suffix"
        }
        val cleanName = normalizedName.removePrefix("${prefix}_")
        return if (cleanName.isBlank()) {
            "$prefix$suffix"
        } else {
            "${prefix}_${cleanName}"
        }
    }

    private suspend fun validatedConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        if (entry.source != Source.BUILTIN && !File(dir, packageFileName).isFile) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
        }
        val config = resolveConfigPaths(entry.packageInfo, dir)
        return config.copy(
            backgroundImgPath = localResourcePath(config.backgroundImgPath, backgroundDir(config.isNightTheme)),
            bookInfoBackgroundImgPath = localResourcePath(
                config.bookInfoBackgroundImgPath,
                bookInfoBackgroundDir(config.isNightTheme)
            ),
            panelBackgroundImgPath = localResourcePath(config.panelBackgroundImgPath, panelBackgroundDir(config.isNightTheme)),
            uiFontPath = localResourcePath(config.uiFontPath),
            titleFontPath = localResourcePath(config.titleFontPath)
        )
    }

    private suspend fun localResourcePath(path: String?, remoteCacheDir: File? = null): String? {
        if (path.isNullOrBlank()) return path
        if (path.startsWith("http", ignoreCase = true)) {
            val cacheDir = remoteCacheDir ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_not_local))
            return downloadRemoteResource(path, cacheDir).absolutePath
        }
        val file = File(path)
        if (!isReadableOwnFile(file)) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_not_readable, file.name.ifBlank { path }))
        }
        return path
    }

    private suspend fun downloadRemoteResource(path: String, dir: File): File {
        dir.mkdirs()
        val target = File(dir, remoteResourceName(path))
        if (isReadableOwnFile(target)) return target
        withTimeout(60_000L) {
            okHttpClient.newCallResponse(0) {
                url(path)
            }.use { response ->
                if (!response.isSuccessful) {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_download_failed, response.code))
                }
                response.body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return target
    }

    private fun remoteResourceName(path: String): String {
        val cleanPath = path.substringBefore('?').substringBefore('#')
        val suffix = cleanPath.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 8 }
            ?.let { ".$it" }
            .orEmpty()
        return Integer.toHexString(path.hashCode()) + suffix
    }

    private fun resolveConfigPaths(pkg: Package, dir: File): ThemeConfig.Config {
        val config = pkg.config ?: ThemeConfig.Config(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            primaryColor = "#795548",
            accentColor = "#E53935",
            backgroundColor = if (pkg.isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (pkg.isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
        return config.copy(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            backgroundImgPath = resolvePath(config.backgroundImgPath, dir),
            bookInfoBackgroundImgPath = resolvePath(config.bookInfoBackgroundImgPath, dir),
            panelBackgroundImgPath = resolvePath(config.panelBackgroundImgPath, dir),
            uiFontPath = resolvePath(config.uiFontPath, dir),
            titleFontPath = resolvePath(config.titleFontPath, dir)
        )
    }

    private fun resolvePath(path: String?, dir: File): String? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) return path
        val file = File(path)
        if (file.isAbsolute) {
            if (isReadableOwnFile(file)) return path
            findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
            findPackagedAssetByPrefix(dir, file.name.substringBeforeLast('.', file.name))?.let {
                return it.absolutePath
            }
            return null
        }
        val packagedFile = File(dir, path)
        if (isReadableOwnFile(packagedFile)) return packagedFile.absolutePath
        findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
        return packagedFile.absolutePath
    }

    private fun isReadableOwnFile(file: File): Boolean {
        if (!file.isFile) return false
        if (isOtherAppExternalDataPath(file.absolutePath)) return false
        return runCatching {
            FileInputStream(file).use { true }
        }.getOrDefault(false)
    }

    private fun isOtherAppExternalDataPath(path: String): Boolean {
        val marker = "/Android/data/"
        val normalized = path.replace('\\', '/')
        val start = normalized.indexOf(marker, ignoreCase = true)
        if (start < 0) return false
        val packageStart = start + marker.length
        val packageEnd = normalized.indexOf('/', packageStart).takeIf { it >= 0 } ?: normalized.length
        val ownerPackage = normalized.substring(packageStart, packageEnd)
        return ownerPackage.isNotBlank() && ownerPackage != appCtx.packageName
    }
    private fun backgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage)
    }

    private fun bookInfoBackgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage)
    }

    private fun panelBackgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage)
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

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply {
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
        BUILTIN,
        LOCAL,
        REMOTE,
        BOTH
    }
}
