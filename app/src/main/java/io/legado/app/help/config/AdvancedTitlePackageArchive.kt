package io.legado.app.help.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

internal data class ExtractedAdvancedTitlePackage(
    val packageRoot: File,
    val titleFile: File,
    val manifestFile: File?,
    val entryCount: Int,
    val extractedBytes: Long
)

internal object AdvancedTitlePackageArchive {
    const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MAX_ENTRIES = 2_048
    private const val MAX_SINGLE_FILE_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 250L
    private const val TITLE_FILE = "title.json"
    private const val PACKAGE_FILE = "package.json"

    fun isZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            file.inputStream().use { input ->
                input.read() == 0x50 && input.read() == 0x4b
            }
        }.getOrDefault(false)
    }

    fun extract(zipFile: File, destination: File): ExtractedAdvancedTitlePackage {
        require(zipFile.isFile) { "advanced title package does not exist" }
        if (zipFile.length() > MAX_ARCHIVE_BYTES) {
            throw IOException("advanced title package exceeds the 256 MiB safety limit")
        }
        require(!destination.exists()) { "advanced title extraction directory already exists" }
        check(destination.mkdirs()) { "failed to create advanced title extraction directory" }

        var entryCount = 0
        var totalBytes = 0L
        val paths = HashSet<String>()
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IOException("advanced title package has too many files")
                    val relativePath = validateEntryName(entry.name)
                    if (!paths.add(relativePath.lowercase(Locale.ROOT))) {
                        throw IOException("advanced title package contains duplicate paths")
                    }
                    val root = destination.canonicalFile
                    val target = File(root, relativePath).canonicalFile
                    if (target != root && !target.toPath().startsWith(root.toPath())) {
                        throw IOException("advanced title package entry escapes extraction directory")
                    }
                    if (entry.isDirectory) {
                        check(target.mkdirs() || target.isDirectory) { "failed to create package directory" }
                        continue
                    }
                    target.parentFile?.let { parent ->
                        check(parent.mkdirs() || parent.isDirectory) { "failed to create package directory" }
                    }
                    var fileBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                fileBytes += count.toLong()
                                totalBytes += count.toLong()
                                if (fileBytes > MAX_SINGLE_FILE_BYTES) {
                                    throw IOException("advanced title package file is too large")
                                }
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    throw IOException("advanced title package expands beyond the safety limit")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val compressedSize = entry.compressedSize
                    if (fileBytes > 0L && compressedSize >= 0L &&
                        fileBytes / compressedSize.coerceAtLeast(1L) > MAX_COMPRESSION_RATIO
                    ) {
                        throw IOException("advanced title package has an unsafe compression ratio")
                    }
                }
            }

            val preferred = destination.walkTopDown()
                .filter { it.isFile && it.name == TITLE_FILE }
                .toList()
            val titleFile = when {
                preferred.size == 1 -> preferred.single()
                preferred.size > 1 -> throw IOException("advanced title package contains multiple $TITLE_FILE files")
                else -> {
                    val candidates = destination.walkTopDown()
                        .filter {
                            it.isFile && it.extension.equals("json", true) &&
                                it.name != PACKAGE_FILE && it.name != "manifest.json"
                        }
                        .toList()
                    if (candidates.size != 1) {
                        throw IOException("advanced title package must contain exactly one animation JSON")
                    }
                    candidates.single()
                }
            }
            val packageRoot = requireNotNull(titleFile.parentFile).canonicalFile
            val manifest = File(packageRoot, PACKAGE_FILE).takeIf { it.isFile }
            return ExtractedAdvancedTitlePackage(
                packageRoot = packageRoot,
                titleFile = titleFile,
                manifestFile = manifest,
                entryCount = entryCount,
                extractedBytes = totalBytes
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun validateEntryName(rawName: String): String {
        if (rawName.isBlank() || '\u0000' in rawName || '\\' in rawName) {
            throw IOException("advanced title package contains an invalid path")
        }
        if (rawName.startsWith('/') || DRIVE_PREFIX.matches(rawName)) {
            throw IOException("advanced title package contains an absolute path")
        }
        val isDirectory = rawName.endsWith('/')
        val normalized = rawName.trimEnd('/')
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("advanced title package contains an unsafe path")
        }
        return if (isDirectory) "$normalized/" else normalized
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:.*")
}
