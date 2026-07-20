package io.legado.app.help.storage

import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

internal data class BackupArchivePolicy(
    val maxArchiveBytes: Long = 8L * 1024L * 1024L * 1024L,
    val maxEntries: Int = 100_000,
    val maxEntryBytes: Long = 512L * 1024L * 1024L,
    val maxTotalBytes: Long = 8L * 1024L * 1024L * 1024L,
    val reservedFreeBytes: Long = 256L * 1024L * 1024L,
    val maxCompressionRatio: Long = 10_000L
) {
    init {
        require(maxArchiveBytes > 0L)
        require(maxEntries > 0)
        require(maxEntryBytes > 0L)
        require(maxTotalBytes > 0L)
        require(reservedFreeBytes >= 0L)
        require(maxCompressionRatio > 0L)
    }

    fun limitsFor(usableSpace: Long): SafeZipLimits {
        val writableBudget = min(maxTotalBytes, usableSpace - reservedFreeBytes)
        require(writableBudget > 0L) { "insufficient storage space for backup restore" }
        return SafeZipLimits(
            maxEntries = maxEntries,
            maxEntryBytes = min(maxEntryBytes, writableBudget),
            maxTotalBytes = writableBudget,
            maxCompressionRatio = maxCompressionRatio
        )
    }
}

internal object BackupArchiveExtractor {

    private val defaultPolicy = BackupArchivePolicy()

    fun copyToTemporaryFile(
        input: InputStream,
        target: File,
        policy: BackupArchivePolicy = defaultPolicy
    ) {
        target.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) { "unable to create backup cache directory" }
        }
        var copied = 0L
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count.toLong()
                    if (copied > policy.maxArchiveBytes) {
                        throw IOException("backup archive exceeds the safety limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        require(copied > 0L) { "backup archive is empty" }
    }

    fun extract(
        zipFile: File,
        destination: File,
        policy: BackupArchivePolicy = defaultPolicy,
        usableSpace: Long = destination.parentFile?.usableSpace ?: destination.usableSpace
    ) {
        require(zipFile.isFile && zipFile.length() in 1..policy.maxArchiveBytes) {
            "backup archive is empty or too large"
        }
        resetDestination(destination)
        try {
            SafeZipExtractor.extract(
                zipFile = zipFile,
                destination = destination,
                limits = policy.limitsFor(usableSpace)
            )
        } catch (error: Throwable) {
            runCatching { resetDestination(destination) }
            throw error
        }
    }

    private fun resetDestination(destination: File) {
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("unable to clear backup restore directory")
        }
        check(destination.mkdirs() || destination.isDirectory) {
            "unable to create backup restore directory"
        }
    }
}
