package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.util.UUID

object BubblePackageManager {

    const val BUILTIN_DIR_NAME = "builtin_default"
    const val DEFAULT_EMPHASIS_COLOR = "#FF0000"
    const val DEFAULT_NORMAL_COLOR = "#808080"
    const val DEFAULT_COLOR = DEFAULT_EMPHASIS_COLOR
    const val MIN_SIZE_SCALE = 0.5f
    const val MAX_SIZE_SCALE = 1.5f
    private const val packageFileName = "bubble.json"
    private const val defaultBubblePath =
        "M44 48 Q48 48 48 44 L48 20 Q48 16 44 16 L20 16 Q16 16 16 20 L16 24 S16 28 10 30 Q6 32 10 34 Q16 36 16 38 L16 44 Q16 48 20 48 Z"
    @Volatile
    private var cachedEntry: Entry? = null
    @Volatile
    private var cachedDirName: String? = null
    private val importMutex = Mutex()

    val rootDir: File
        get() = appCtx.externalFiles.getFile("bubblePackages")

    private val tempDir: File
        get() = rootDir.getFile("temp").apply { mkdirs() }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply { mkdirs() }

    @Keep
    private data class RemoteCache(
        val name: String,
        val dirName: String,
        val updatedAt: Long
    )

    @Keep
    data class Config(
        var name: String,
        var dirName: String = "",
        var svgTemplate: String = "",
        var sizeScale: Float = 1f,
        var dayNormalColor: String? = null,
        var dayEmphasisColor: String? = null,
        var nightNormalColor: String? = null,
        var nightEmphasisColor: String? = null,
        var updatedAt: Long = System.currentTimeMillis(),
        var formatVersion: Int = 1,
        var resources: List<PackageResource> = emptyList()
    )

    data class Entry(
        val config: Config,
        val source: Source,
        val dirName: String,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    )

    internal data class EntryLoadSnapshot(
        val localEntries: List<Entry>,
        val cachedRemoteEntries: List<Entry>
    ) {
        val mergedEntries: List<Entry>
            get() = BubblePackageManager.mergeEntries(localEntries, cachedRemoteEntries)
    }

    enum class Source { BUILTIN, LOCAL, REMOTE, BOTH }

    fun builtinConfig(): Config {
        return Config(
            name = "内置段评气泡",
            dirName = BUILTIN_DIR_NAME,
            svgTemplate = defaultSvgTemplate(),
            sizeScale = 1f,
            dayNormalColor = DEFAULT_NORMAL_COLOR,
            dayEmphasisColor = DEFAULT_EMPHASIS_COLOR,
            nightNormalColor = DEFAULT_NORMAL_COLOR,
            nightEmphasisColor = DEFAULT_EMPHASIS_COLOR,
            updatedAt = 0L
        )
    }

    fun builtinEntry(): Entry {
        return Entry(
            config = builtinConfig(),
            source = Source.BUILTIN,
            dirName = BUILTIN_DIR_NAME
        )
    }

    fun activeDirName(): String {
        return appCtx.getPrefString(PreferKey.paragraphBubblePackage, BUILTIN_DIR_NAME)
            ?.ifBlank { BUILTIN_DIR_NAME }
            ?: BUILTIN_DIR_NAME
    }

    fun apply(entry: Entry) {
        appCtx.putPrefString(PreferKey.paragraphBubblePackage, entry.dirName)
        invalidateCurrentEntry()
    }

    fun currentEntry(): Entry {
        val dirName = activeDirName()
        cachedEntry?.takeIf { cachedDirName == dirName }?.let { return it }
        val entry = if (dirName == BUILTIN_DIR_NAME) {
            builtinEntry()
        } else {
            runCatching { readEntry(localDir(dirName)) }.getOrNull() ?: builtinEntry()
        }
        cachedDirName = dirName
        cachedEntry = entry
        return entry
    }

    fun invalidateCurrentEntry() {
        cachedEntry = null
        cachedDirName = null
    }

    suspend fun loadEntries(containerId: String? = null, scope: String? = null): List<Entry> {
        val snapshot = loadEntrySnapshot(containerId)
        return refreshEntries(snapshot, containerId, scope)
    }

    internal suspend fun loadEntrySnapshot(containerId: String? = null): EntryLoadSnapshot =
        withContext(IO) {
            EntryLoadSnapshot(
                localEntries = loadLocal(),
                cachedRemoteEntries = readRemoteCache(containerId)
            )
        }

    internal suspend fun refreshEntries(
        snapshot: EntryLoadSnapshot,
        containerId: String? = null,
        scope: String? = null
    ): List<Entry> = withContext(IO) {
        mergeEntries(snapshot.localEntries, loadRemoteOrCache(containerId, scope))
    }

    internal fun mergeEntries(localEntries: List<Entry>, remoteEntries: List<Entry>): List<Entry> {
        val local = localEntries.associateBy { it.dirName }
        val remote = remoteEntries.associateBy { it.dirName }
        val merged = (local.keys + remote.keys).mapNotNull { key ->
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
        return listOf(builtinEntry()) + merged.sortedWith(
            compareByDescending<Entry> { it.config.updatedAt }
                .thenByDescending { it.remoteUpdatedAt }
                .thenBy { it.config.name }
                .thenBy { it.dirName }
        )
    }

    fun addOrUpdate(config: Config, oldEntry: Entry? = null): Entry {
        val normalized = normalizeConfig(config)
        val name = normalized.name.trim().ifBlank { "段评气泡" }
        val editableOldEntry = oldEntry?.takeIf {
            it.dirName.isNotBlank() &&
                it.dirName != BUILTIN_DIR_NAME &&
                it.source != Source.REMOTE
        }
        val dirName = if (editableOldEntry != null) {
            editableOldEntry.dirName
        } else {
            val baseDirName = normalized.dirName.ifBlank { name.normalizeFileName() }
                .ifBlank { "bubble_${System.currentTimeMillis()}" }
            uniqueDirName(baseDirName)
        }
        val dir = localDir(dirName)
        val createdDirectory = !dir.exists()
        check(dir.mkdirs() || dir.isDirectory) { "failed to create bubble package directory" }
        val next = normalized.copy(
            name = name,
            dirName = dirName,
            updatedAt = System.currentTimeMillis()
        )
        return try {
            val validated = validateImportedConfig(next, dir)
            val manifest = File(dir, packageFileName)
            val json = GSON.toJson(validated)
            AtomicTextFileStore(manifest).writeVerified(json) { storedJson ->
                runCatching {
                    val stored = GSON.fromJsonObject<Config>(storedJson).getOrThrow()
                    validateImportedConfig(stored, dir).dirName == dirName
                }.getOrDefault(false)
            }
            invalidateCurrentEntry()
            Entry(validated, Source.LOCAL, dirName, localDir = dir)
        } catch (error: Throwable) {
            if (createdDirectory) deletePathBestEffort(dir)
            throw error
        }
    }

    fun deleteLocal(entry: Entry) {
        if (entry.source == Source.BUILTIN || entry.dirName == BUILTIN_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.dirName), deleteRootDir = true)
        if (activeDirName() == entry.dirName) {
            appCtx.putPrefString(PreferKey.paragraphBubblePackage, BUILTIN_DIR_NAME)
        }
        invalidateCurrentEntry()
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        if (entry.source == Source.BUILTIN) {
            throw IllegalArgumentException("内置气泡不可导出")
        }
        val dir = entry.localDir ?: localDir(entry.dirName)
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        if (!ZipUtils.zipFile(dir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException("气泡导出失败")
        }
        zipFile
    }

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.uploadBubblePackage(entry.dirName, exportZip(entry), containerId, scope)
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadBubblePackage(entry.dirName, zipFile, containerId, scope)
        importZipInternal(zipFile, overwrite = true, remoteUpdatedAt = entry.remoteUpdatedAt)
            .copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.deleteBubblePackage(entry.dirName, containerId, scope)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        importZipInternal(zipFile, overwrite = false, remoteUpdatedAt = 0L)
    }

    private suspend fun importZipInternal(
        zipFile: File,
        overwrite: Boolean,
        remoteUpdatedAt: Long
    ): Entry = importMutex.withLock {
        val unzipDir = tempDir.getFile("import_${UUID.randomUUID()}")
        try {
            val extracted = BubblePackageArchive.extract(zipFile, unzipDir)
            val packageFile = extracted.manifestFile
            val config = readImportedConfig(packageFile, packageFile.parentFile)
            val baseDirName = safeImportedDirName(config)
            val dirName = if (overwrite) baseDirName else uniqueDirName(baseDirName)
            val parentDir = rootDir.apply { mkdirs() }.canonicalFile
            val targetDir = File(parentDir, dirName).canonicalFile
            require(targetDir.parentFile == parentDir) { "bubble directory escapes package root" }
            require(overwrite || !targetDir.exists()) { "bubble package target already exists" }
            val stagingDir = File(parentDir, ".$dirName.staging-${UUID.randomUUID()}")
            val backupDir = File(parentDir, ".$dirName.backup-${UUID.randomUUID()}")
            try {
                val sourceDir = requireNotNull(packageFile.parentFile)
                check(sourceDir.copyRecursively(stagingDir, overwrite = false)) {
                    "failed to stage imported bubble package"
                }
                val next = config.copy(
                    dirName = dirName,
                    updatedAt = if (remoteUpdatedAt > 0L) config.updatedAt else System.currentTimeMillis()
                )
                File(stagingDir, packageFileName).writeText(GSON.toJson(next))
                val verified = readImportedConfig(File(stagingDir, packageFileName), stagingDir)
                require(verified.dirName == dirName) { "staged bubble package identity mismatch" }

                val installed = BubbleDirectoryTransaction().install(
                    targetDir,
                    stagingDir,
                    backupDir
                ) { installedDir ->
                    val installedConfig = readImportedConfig(File(installedDir, packageFileName), installedDir)
                    require(installedConfig.dirName == dirName) { "installed bubble package identity mismatch" }
                    Entry(
                        installedConfig,
                        Source.LOCAL,
                        dirName,
                        localDir = installedDir,
                        remoteUpdatedAt = remoteUpdatedAt
                    )
                }
                invalidateCurrentEntry()
                installed
            } finally {
                deletePathBestEffort(stagingDir)
            }
        } finally {
            deletePathBestEffort(unzipDir)
        }
    }

    private fun readImportedConfig(file: File, resourceRoot: File? = null): Config {
        require(file.isFile) { "bubble package manifest is missing" }
        require(file.length() in 1..512L * 1024L) { "bubble package manifest is empty or too large" }
        val raw = GSON.fromJsonObject<Config>(file.readText()).getOrThrow()
        return validateImportedConfig(raw, resourceRoot)
    }

    private fun validateImportedConfig(config: Config, resourceRoot: File? = null): Config {
        val normalized = normalizeConfig(config)
        require(normalized.name.length <= 200) { "bubble package name is too long" }
        require(normalized.svgTemplate.length <= 512 * 1024) { "bubble SVG template is too large" }
        BubbleSvgPolicy.validate(normalized.svgTemplate)
        if (resourceRoot == null) return normalized
        val resources = PackageResourcePolicy.validateFiles(resourceRoot, normalized.resources)
        BubbleSvgPolicy.packageReferences(normalized.svgTemplate).forEach { reference ->
            val image = runCatching {
                PackageResourcePolicy.resolve(
                    resourceRoot,
                    resources,
                    reference,
                    PackageResourcePolicy.TYPE_IMAGE
                )
            }.getOrNull()
            val font = if (image == null) runCatching {
                PackageResourcePolicy.resolve(
                    resourceRoot,
                    resources,
                    reference,
                    PackageResourcePolicy.TYPE_FONT
                )
            }.getOrNull() else null
            val file = image ?: font
            requireNotNull(file) { "bubble package resource is missing: $reference" }
            PackageResourcePolicy.validateResolvedFile(
                file,
                if (image != null) PackageResourcePolicy.TYPE_IMAGE else PackageResourcePolicy.TYPE_FONT,
                reference
            )
        }
        val hasPackageResources = resources.isNotEmpty() ||
            BubbleSvgPolicy.packageReferences(normalized.svgTemplate).isNotEmpty()
        return normalized.copy(
            formatVersion = if (hasPackageResources) 2 else normalized.formatVersion.coerceIn(1, 2),
            resources = resources
        )
    }

    private fun safeImportedDirName(config: Config): String {
        val requested = config.dirName.trim()
        if (requested.isNotEmpty()) {
            require(requested != "." && requested != "..") { "invalid bubble directory" }
            require(!requested.startsWith('.')) { "bubble directory must not be hidden" }
            require(!File(requested).isAbsolute && '/' !in requested && '\\' !in requested) {
                "bubble directory must be a single path segment"
            }
        }
        return requested.ifBlank { config.name.normalizeFileName() }
            .ifBlank { "bubble_${System.currentTimeMillis()}" }
    }

    private fun deletePathBestEffort(file: File) {
        runCatching {
            if (file.exists() && !file.deleteRecursively()) {
                throw IOException("failed to delete ${file.absolutePath}")
            }
        }
    }

    private fun loadLocal(): List<Entry> {
        if (!rootDir.exists()) return emptyList()
        return rootDir.listFiles()
            ?.filter {
                it.isDirectory &&
                    !it.name.startsWith('.') &&
                    it.name !in setOf("temp", "remote_cache", BUILTIN_DIR_NAME)
            }
            ?.mapNotNull(::readEntry)
            .orEmpty()
    }

    private suspend fun loadRemoteOrCache(containerId: String? = null, scope: String? = null): List<Entry> {
        val cached = readRemoteCache(containerId)
        if (!NetworkUtils.isAvailable()) return cached
        return try {
            val remote = AppCloudStorage.listBubblePackages(containerId, scope).map { remoteFile ->
                val dirName = remoteFile.displayName.trimEnd('/').removeSuffix(".zip")
                Entry(
                    config = Config(
                        name = dirName,
                        dirName = dirName,
                        updatedAt = remoteFile.lastModify,
                        svgTemplate = defaultSvgTemplate()
                    ),
                    source = Source.REMOTE,
                    dirName = dirName,
                    remoteUpdatedAt = remoteFile.lastModify
                )
            }
            if (remote.isNotEmpty() || cached.isEmpty()) {
                writeRemoteCache(remote, containerId)
            }
            remote
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            cached
        }
    }

    private fun readRemoteCache(containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(containerId)
        if (!file.exists()) return emptyList()
        return runCatching {
            GSON.fromJsonArray<RemoteCache>(file.readText()).getOrThrow().map {
                Entry(
                    config = Config(
                        name = it.name,
                        dirName = it.dirName,
                        updatedAt = it.updatedAt,
                        svgTemplate = defaultSvgTemplate()
                    ),
                    source = Source.REMOTE,
                    dirName = it.dirName,
                    remoteUpdatedAt = it.updatedAt
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRemoteCache(entries: List<Entry>, containerId: String? = null) {
        val cache = entries.map {
            RemoteCache(
                name = it.config.name,
                dirName = it.dirName,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.config.updatedAt
            )
        }
        remoteCacheFile(containerId).writeText(GSON.toJson(cache))
    }

    private fun remoteCacheFile(containerId: String? = null): File {
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("bubble$suffix.json")
    }

    private fun readEntry(dir: File): Entry? {
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val canonicalDir = dir.canonicalFile
        if (canonicalDir.parentFile != parent || canonicalDir.name.startsWith('.')) return null
        val file = File(canonicalDir, packageFileName)
        return runCatching {
            AtomicTextFileStore(file).recoverInterruptedCommit()
            if (!file.exists()) return@runCatching null
            val config = readImportedConfig(file, canonicalDir)
            val dirName = canonicalDir.name
            Entry(
                config.copy(dirName = dirName),
                Source.LOCAL,
                dirName,
                localDir = canonicalDir
            )
        }.getOrNull()
    }

    private fun normalizeConfig(config: Config): Config {
        val version = config.formatVersion.takeIf { it > 0 } ?: 1
        require(version in 1..2) { "unsupported bubble package version" }
        val size = config.sizeScale.takeIf { it.isFinite() } ?: 1f
        val resources = PackageResourcePolicy.normalize(config.resources.orEmpty())
        return config.copy(
            name = config.name.trim().ifBlank { "段评气泡" },
            svgTemplate = config.svgTemplate.ifBlank { defaultSvgTemplate() },
            formatVersion = version,
            resources = resources,
            sizeScale = size.coerceIn(MIN_SIZE_SCALE, MAX_SIZE_SCALE),
            dayNormalColor = normalizeColorOrBlank(config.dayNormalColor),
            dayEmphasisColor = normalizeColorOrBlank(config.dayEmphasisColor),
            nightNormalColor = normalizeColorOrBlank(config.nightNormalColor),
            nightEmphasisColor = normalizeColorOrBlank(config.nightEmphasisColor)
        )
    }

    internal fun normalizeStoredConfig(config: Config): Config = normalizeConfig(config)

    private fun normalizeColorOrBlank(value: String?): String? {
        val color = value?.trim().orEmpty()
        if (color.isBlank()) return null
        return if (color.startsWith("#")) color else "#$color"
    }

    private fun uniqueDirName(preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "bubble_${System.currentTimeMillis()}" }
        if (!localDir(clean).exists()) return clean
        for (index in 2..999) {
            val candidate = "${clean}_$index"
            if (!localDir(candidate).exists()) return candidate
        }
        return "${clean}_${System.currentTimeMillis()}"
    }

    private fun localDir(dirName: String): File {
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val directory = File(parent, dirName).canonicalFile
        require(directory.parentFile == parent && !directory.name.startsWith('.')) {
            "bubble directory escapes package root"
        }
        return directory
    }

    private fun defaultSvgTemplate(): String {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
              <path d="$defaultBubblePath" fill="none" stroke="${'$'}{color}" stroke-width="3.2" stroke-linejoin="round" stroke-linecap="round"/>
              <text x="32" y="32" dy=".35em" text-anchor="middle" font-family="sans-serif" font-size="15" font-weight="600" fill="${'$'}{color}">${'$'}{num}</text>
            </svg>
        """.trimIndent()
    }
}
