package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference
import java.util.UUID

object AdvancedTitlePackageManager {

    const val BUILTIN_ID = "builtin_default"
    const val MAX_EDITABLE_JSON_BYTES = 2L * 1024L * 1024L
    const val MAX_JSON_BYTES = 16L * 1024L * 1024L
    const val MAX_PACKAGE_BYTES = 256L * 1024L * 1024L
    private const val MAX_PACKAGES = 64
    private const val MANIFEST_FILE = "package.json"
    private const val LOTTIE_FILE = "title.json"

    @Keep
    data class Config(
        val id: String,
        val name: String,
        val updatedAt: Long = System.currentTimeMillis(),
        val splitMode: Int? = null,
        val delimiter: String? = null,
        val regex: String? = null,
        val heightFactor: Int? = null,
        val formatVersion: Int = 1,
        val resources: List<PackageResource> = emptyList()
    ) {
        fun splitRuleOrNull(): AdvancedTitleConfig.SplitRule? {
            if (splitMode == null && delimiter == null && regex == null) return null
            return AdvancedTitleConfig.SplitRule(
                mode = if (splitMode == AdvancedTitleConfig.SPLIT_REGEX) {
                    AdvancedTitleConfig.SPLIT_REGEX
                } else {
                    AdvancedTitleConfig.SPLIT_DELIMITER
                },
                delimiter = delimiter ?: " ",
                regex = regex ?: AdvancedTitleConfig.DEFAULT_REGEX
            )
        }

        fun normalizedHeightFactorOrNull(): Int? = heightFactor?.coerceIn(30, 120)
    }

    data class Entry(
        val config: Config,
        val directory: File? = null,
        val isBuiltin: Boolean = false
    ) {
        val id: String get() = config.id
        val name: String get() = config.name
        val updatedAt: Long get() = config.updatedAt
    }

    data class ResourceContext(
        val packageId: String,
        val root: File,
        val resources: List<PackageResource>,
        val cacheKey: String
    )

    val rootDir: File
        get() = appCtx.externalFiles.getFile("advancedTitlePackages")

    private val tempDir: File
        get() = appCtx.externalCache.getFile("advancedTitlePackages").apply { mkdirs() }

    @Volatile
    private var cachedId: String? = null
    @Volatile
    private var cachedStamp: Long = Long.MIN_VALUE
    @Volatile
    private var cachedJson: String? = null
    @Volatile
    private var cachedLargeJson: SoftReference<String>? = null
    @Volatile
    private var builtinJsonCache: String? = null
    private val mutationLock = Any()

    fun builtinEntry(): Entry = Entry(
        config = Config(
            id = BUILTIN_ID,
            name = appCtx.getString(R.string.advanced_title_builtin),
            updatedAt = 0L,
            splitMode = AdvancedTitleConfig.SPLIT_DELIMITER,
            delimiter = " ",
            regex = AdvancedTitleConfig.DEFAULT_REGEX,
            heightFactor = AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
        ),
        isBuiltin = true
    )

    fun activeId(): String = appCtx.getPrefString(PreferKey.advancedTitlePackage)
        ?.takeIf(::isValidId)
        ?: BUILTIN_ID

    suspend fun loadEntries(): List<Entry> = withContext(IO) {
        synchronized(mutationLock) {
            rootDir.mkdirs()
            AdvancedTitlePackageStorage.cleanupStaleStagingDirectories(rootDir)
            migrateLegacyIfNeeded()
            var local = loadLocalEntries()
            val validIds = local.asSequence().map { it.id }.toSet() + BUILTIN_ID
            if (activeId() !in validIds) {
                val recovery = legacyTemplate()
                    ?.takeIf { runCatching { validateJson(it) }.isSuccess }
                    ?.let { addOrUpdate(appCtx.getString(R.string.advanced_title_migrated), it) }
                appCtx.putPrefString(
                    PreferKey.advancedTitlePackage,
                    recovery?.id ?: BUILTIN_ID
                )
                if (recovery != null) local = loadLocalEntries()
                invalidate()
            }
            listOf(builtinEntry()) + local.sortedWith(
                compareByDescending<Entry> { it.updatedAt }.thenBy { it.name }
            )
        }
    }

    fun currentTemplate(): String? {
        val explicitId = appCtx.getPrefString(PreferKey.advancedTitlePackage)
            ?.takeIf(::isValidId)
        if (explicitId == null) {
            legacyTemplate()?.let { return it }
        }
        val id = explicitId ?: BUILTIN_ID
        return if (id == BUILTIN_ID) {
            builtinJson()
        } else {
            val file = lottieFile(localDir(id))
            readCached(id, file) ?: legacyTemplate() ?: builtinJson()
        }
    }

    fun readTemplate(entry: Entry): String {
        return if (entry.isBuiltin) {
            builtinJson()
        } else {
            val directory = requireNotNull(entry.directory) { "Missing advanced title directory" }
            readJsonFile(lottieFile(directory))
        }
    }

    fun resourceContext(entry: Entry): ResourceContext? {
        if (entry.isBuiltin) return null
        val directory = entry.directory ?: return null
        val resources = runCatching { PackageResourcePolicy.normalize(entry.config.resources) }
            .getOrDefault(emptyList())
        return ResourceContext(
            packageId = entry.id,
            root = directory,
            resources = resources,
            cacheKey = "${entry.id}:${entry.updatedAt}:${resources.hashCode()}"
        )
    }

    fun currentResourceContext(): ResourceContext? {
        val id = activeId().takeUnless { it == BUILTIN_ID } ?: return null
        return runCatching {
            val directory = localDir(id).canonicalFile
            val config = readManifest(directory, expectedId = id)
            resourceContext(Entry(config, directory))
        }.getOrNull()
    }

    fun templateSize(entry: Entry): Long {
        if (entry.isBuiltin) return 0L
        val directory = entry.directory ?: return 0L
        return lottieFile(directory).takeIf { it.isFile }?.length() ?: 0L
    }

    fun isEditable(entry: Entry): Boolean {
        if (entry.isBuiltin) return false
        return templateSize(entry) in 1..MAX_EDITABLE_JSON_BYTES
    }

    fun readTemplate(id: String): String {
        if (id == BUILTIN_ID) return builtinJson()
        require(isValidId(id)) { "Invalid advanced title id" }
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val directory = File(parent, id).canonicalFile
        require(directory.parentFile == parent) { "Advanced title directory escaped its root" }
        val config = verifyInstalledDirectory(directory, expectedId = id)
        return readTemplate(Entry(config, directory))
    }

    fun addOrUpdate(
        name: String,
        json: String,
        oldEntry: Entry? = null,
        splitRule: AdvancedTitleConfig.SplitRule? = oldEntry?.config?.splitRuleOrNull()
            ?: AdvancedTitleConfig.globalRule,
        heightFactor: Int? = oldEntry?.config?.normalizedHeightFactorOrNull()
            ?: AdvancedTitleConfig.heightFactor
    ): Entry =
        synchronized(mutationLock) {
        val normalizedName = normalizeName(name)
        validateJson(json)
        val editableOld = oldEntry?.takeUnless { it.isBuiltin }
        if (editableOld == null) {
            val packageCount = rootDir.listFiles().orEmpty().count {
                it.isDirectory && !it.name.startsWith('.')
            }
            require(packageCount < MAX_PACKAGES) {
                appCtx.getString(R.string.advanced_title_package_limit)
            }
        }
        val id = editableOld?.id ?: "title_${UUID.randomUUID().toString().replace("-", "")}".take(38)
        require(isValidId(id)) { "Invalid advanced title id" }
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val target = File(parent, id).canonicalFile
        require(target.parentFile == parent) { "Advanced title directory escaped its root" }
        val staging = File(parent, ".$id.staging-${UUID.randomUUID()}")
        val backup = File(parent, ".$id.backup-${UUID.randomUUID()}")
        val config = Config(
            id = id,
            name = normalizedName,
            updatedAt = System.currentTimeMillis(),
            splitMode = splitRule?.mode,
            delimiter = splitRule?.delimiter,
            regex = splitRule?.regex,
            heightFactor = heightFactor?.coerceIn(30, 120),
            formatVersion = editableOld?.config?.formatVersion ?: 1,
            resources = editableOld?.config?.resources.orEmpty()
        )
        try {
            staging.mkdirs()
            editableOld?.directory?.let { copyResourceDirectories(it, staging) }
            File(staging, MANIFEST_FILE).writeText(GSON.toJson(config))
            lottieFile(staging).writeText(json)
            verifyInstalledDirectory(
                directory = staging,
                expectedId = id,
                requireDirectoryIdMatch = false
            )
            val installed = BubbleDirectoryTransaction().install(
                target,
                staging,
                backup
            ) { installedDir ->
                val verified = verifyInstalledDirectory(installedDir, expectedId = id)
                Entry(verified, installedDir)
            }
            invalidate()
            installed
        } finally {
            AdvancedTitlePackageStorage.deleteStagingDirectory(parent, staging)
        }
    }

    fun importPackage(file: File, fallbackName: String): Entry = synchronized(mutationLock) {
        if (!AdvancedTitlePackageArchive.isZip(file)) {
            val json = readJsonFile(file)
            return@synchronized addOrUpdate(fallbackName, json)
        }
        val unzipDir = tempDir.getFile("import_${UUID.randomUUID()}")
        try {
            val extracted = AdvancedTitlePackageArchive.extract(file, unzipDir)
            val json = readJsonFile(extracted.titleFile)
            validateJson(json)
            val importedConfig = extracted.manifestFile?.let { manifest ->
                require(manifest.length() in 1..64L * 1024L) { "advanced title manifest is too large" }
                GSON.fromJsonObject<Config>(manifest.readText()).getOrThrow()
            }
            val resources = PackageResourcePolicy.validateFiles(
                extracted.packageRoot,
                importedConfig?.resources.orEmpty()
            )
            val normalizedName = normalizeName(importedConfig?.name ?: fallbackName)
            val id = "title_${UUID.randomUUID().toString().replace("-", "")}".take(38)
            val parent = rootDir.apply { mkdirs() }.canonicalFile
            val target = File(parent, id).canonicalFile
            val staging = File(parent, ".$id.staging-${UUID.randomUUID()}")
            val backup = File(parent, ".$id.backup-${UUID.randomUUID()}")
            val next = Config(
                id = id,
                name = normalizedName,
                updatedAt = System.currentTimeMillis(),
                splitMode = importedConfig?.splitMode,
                delimiter = importedConfig?.delimiter,
                regex = importedConfig?.regex,
                heightFactor = importedConfig?.heightFactor,
                formatVersion = if (resources.isEmpty() && !hasResourceDirectories(extracted.packageRoot)) 1 else 2,
                resources = resources
            )
            try {
                staging.mkdirs()
                copyResourceDirectories(extracted.packageRoot, staging)
                File(staging, MANIFEST_FILE).writeText(GSON.toJson(next))
                lottieFile(staging).writeText(json)
                verifyInstalledDirectory(staging, expectedId = id, requireDirectoryIdMatch = false)
                BubbleDirectoryTransaction().install(target, staging, backup) { installedDir ->
                    val verified = verifyInstalledDirectory(installedDir, expectedId = id)
                    Entry(verified, installedDir)
                }.also { invalidate() }
            } finally {
                AdvancedTitlePackageStorage.deleteStagingDirectory(parent, staging)
            }
        } finally {
            unzipDir.deleteRecursively()
        }
    }

    fun exportPackage(entry: Entry): File = synchronized(mutationLock) {
        require(!entry.isBuiltin) { "built-in advanced title cannot be exported as a package" }
        val directory = requireNotNull(entry.directory) { "Missing advanced title directory" }
        val output = tempDir.getFile("${entry.id}.zip")
        if (output.exists()) output.delete()
        check(ZipUtils.zipFile(directory, output) && output.isFile && output.length() > 0L) {
            "advanced title package export failed"
        }
        output
    }

    fun hasExternalResources(entry: Entry): Boolean {
        val directory = entry.directory ?: return false
        return entry.config.resources.isNotEmpty() || hasResourceDirectories(directory)
    }

    fun apply(entry: Entry) = synchronized(mutationLock) {
        val json = readTemplate(entry)
        validateJson(json)
        if (entry.isBuiltin) {
            AdvancedTitleConfig.lottieJson = json
            AdvancedTitleConfig.lottiePath = null
        } else {
            // Local packages already have an atomic on-disk copy. Keeping their complete JSON in
            // SharedPreferences duplicates large templates, inflates the preferences XML and can
            // make every process start retain a multi-megabyte String.
            val directory = requireNotNull(entry.directory) { "Missing advanced title directory" }
            AdvancedTitleConfig.lottiePath = lottieFile(directory).absolutePath
            AdvancedTitleConfig.lottieJson = null
        }
        appCtx.putPrefString(PreferKey.advancedTitlePackage, entry.id)
        entry.config.splitRuleOrNull()?.let { AdvancedTitleConfig.globalRule = it }
        entry.config.normalizedHeightFactorOrNull()?.let { AdvancedTitleConfig.heightFactor = it }
        invalidate()
    }

    fun delete(entry: Entry) {
        synchronized(mutationLock) {
            if (entry.isBuiltin || entry.id == BUILTIN_ID) return@synchronized
            val parent = rootDir.canonicalFile
            val target = (entry.directory ?: localDir(entry.id)).canonicalFile
            require(target.parentFile == parent) { "Advanced title directory escaped its root" }
            if (target.exists() && !target.deleteRecursively() && target.exists()) {
                throw IOException("Unable to delete advanced title")
            }
            if (activeId() == entry.id) {
                appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
                AdvancedTitleConfig.lottieJson = builtinJson()
                AdvancedTitleConfig.lottiePath = null
                val builtin = builtinEntry().config
                builtin.splitRuleOrNull()?.let { AdvancedTitleConfig.globalRule = it }
                builtin.normalizedHeightFactorOrNull()?.let {
                    AdvancedTitleConfig.heightFactor = it
                }
            }
            invalidate()
        }
    }

    fun validateJson(json: String) {
        require(json.isNotEmpty()) { appCtx.getString(R.string.advanced_title_invalid_json) }
        require(utf8SizeUpTo(json, MAX_JSON_BYTES) <= MAX_JSON_BYTES) {
            appCtx.getString(R.string.advanced_title_too_large)
        }
        require(AdvancedTitleConfig.isValidLottieJson(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
    }

    fun validateEditableJson(json: String) {
        require(json.isNotEmpty()) { appCtx.getString(R.string.advanced_title_invalid_json) }
        require(utf8SizeUpTo(json, MAX_EDITABLE_JSON_BYTES) <= MAX_EDITABLE_JSON_BYTES) {
            appCtx.getString(R.string.large_config_read_only)
        }
        require(AdvancedTitleConfig.isValidLottieJson(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
    }

    fun invalidate() {
        cachedId = null
        cachedStamp = Long.MIN_VALUE
        cachedJson = null
        cachedLargeJson = null
    }

    private fun loadLocalEntries(): List<Entry> {
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        return parent.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .take(MAX_PACKAGES * 2)
            .mapNotNull { directory ->
                runCatching {
                    val canonical = directory.canonicalFile
                    require(canonical.parentFile == parent)
                    val config = verifyInstalledDirectory(canonical)
                    Entry(config, canonical)
                }.getOrNull()
            }
            .take(MAX_PACKAGES)
            .toList()
    }

    private fun verifyInstalledDirectory(
        directory: File,
        expectedId: String? = null,
        requireDirectoryIdMatch: Boolean = true
    ): Config {
        val config = readManifest(directory, expectedId)
        AdvancedTitlePackageStorage.requireDirectoryMatchesId(
            directoryName = directory.name,
            configId = config.id,
            requireMatch = requireDirectoryIdMatch
        )
        require(config.name.isNotBlank() && config.name.length <= 100) { "Advanced title name is invalid" }
        require(config.formatVersion in 1..2) { "Unsupported advanced title package version" }
        val resources = PackageResourcePolicy.validateFiles(directory, config.resources)
        val json = readJsonFile(lottieFile(directory))
        require(AdvancedTitleConfig.hasRenderableLayers(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
        return config.copy(name = config.name.trim(), resources = resources)
    }

    private fun readManifest(directory: File, expectedId: String? = null): Config {
        val manifest = File(directory, MANIFEST_FILE)
        require(manifest.isFile && manifest.length() in 1..64L * 1024L) {
            "Advanced title manifest is invalid"
        }
        val config = GSON.fromJsonObject<Config>(manifest.readText()).getOrThrow()
        require(isValidId(config.id)) { "Advanced title id is invalid" }
        require(expectedId == null || config.id == expectedId) { "Advanced title id changed" }
        return config
    }

    private fun copyResourceDirectories(source: File, target: File) {
        listOf("assets", "images").forEach { name ->
            val sourceDir = File(source, name)
            if (!sourceDir.isDirectory) return@forEach
            val targetDir = File(target, name)
            check(sourceDir.copyRecursively(targetDir, overwrite = false)) {
                "failed to copy advanced title resources"
            }
        }
    }

    private fun hasResourceDirectories(directory: File): Boolean {
        return listOf("assets", "images").any { name ->
            File(directory, name).walkTopDown().any { it.isFile }
        }
    }

    private fun migrateLegacyIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.advancedTitlePackage).isNullOrBlank()) return
        val legacy = legacyTemplate()
            ?.takeIf { runCatching { validateJson(it) }.isSuccess }
        if (legacy == null) {
            appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
            return
        }
        val builtin = builtinJson()
        if (legacy == builtin) {
            appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
            AdvancedTitleConfig.lottieJson = builtin
            AdvancedTitleConfig.lottiePath = null
        } else {
            val migrated = addOrUpdate(appCtx.getString(R.string.advanced_title_migrated), legacy)
            AdvancedTitleConfig.lottiePath = lottieFile(
                requireNotNull(migrated.directory) { "Missing migrated advanced title directory" }
            ).absolutePath
            AdvancedTitleConfig.lottieJson = null
            appCtx.putPrefString(PreferKey.advancedTitlePackage, migrated.id)
        }
        invalidate()
    }

    private fun legacyTemplate(): String? {
        AdvancedTitleConfig.lottieJson?.takeIf { it.isNotBlank() }?.let { return it }
        val path = AdvancedTitleConfig.lottiePath?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { readJsonFile(File(path)) }.getOrNull()
    }

    private fun readCached(id: String, file: File): String? {
        if (!file.isFile) return null
        val stamp = file.lastModified() xor file.length()
        if (cachedId == id && cachedStamp == stamp) {
            cachedJson?.let { return it }
            cachedLargeJson?.get()?.let { return it }
        }
        return runCatching { readJsonFile(file) }.getOrNull()?.also { json ->
            if (file.length() <= MAX_EDITABLE_JSON_BYTES) {
                cachedJson = json
                cachedLargeJson = null
            } else {
                cachedJson = null
                cachedLargeJson = SoftReference(json)
            }
            cachedStamp = stamp
            cachedId = id
        }
    }

    private fun builtinJson(): String {
        builtinJsonCache?.let { return it }
        return appCtx.resources.openRawResource(R.raw.advanced_title_lottie)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also { builtinJsonCache = it }
    }

    private fun readJsonFile(file: File): String {
        require(file.isFile) { "Advanced title file is missing" }
        require(file.length() in 1..MAX_JSON_BYTES) {
            appCtx.getString(R.string.advanced_title_too_large)
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun localDir(id: String): File = rootDir.getFile(id)

    private fun lottieFile(directory: File): File = directory.getFile(LOTTIE_FILE)

    private fun normalizeName(value: String): String {
        return value.trim().replace(Regex("[\\r\\n\\t]+"), " ")
            .take(100)
            .ifBlank { appCtx.getString(R.string.advanced_title_unnamed) }
    }

    internal fun utf8SizeUpTo(value: String, limit: Long): Long {
        var size = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            size += when {
                char.code <= 0x7f -> 1L
                char.code <= 0x7ff -> 2L
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4L
                }
                else -> 3L
            }
            if (size > limit) return limit + 1L
            index++
        }
        return size
    }

    private fun isValidId(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))
}
