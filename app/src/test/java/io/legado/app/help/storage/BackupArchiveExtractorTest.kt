package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class BackupArchiveExtractorTest {

    @Test
    fun limitsRespectFreeSpaceReserve() {
        val policy = BackupArchivePolicy(
            maxArchiveBytes = 2_000,
            maxEntries = 20,
            maxEntryBytes = 400,
            maxTotalBytes = 1_000,
            reservedFreeBytes = 100,
            maxCompressionRatio = 500
        )

        val limits = policy.limitsFor(600)

        assertEquals(400, limits.maxEntryBytes)
        assertEquals(500, limits.maxTotalBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRestoreWhenReservedSpaceCannotBeKept() {
        BackupArchivePolicy(reservedFreeBytes = 100).limitsFor(100)
    }

    @Test
    fun oversizedStreamDoesNotLeavePartialArchive() {
        val root = createTempDirectory("backup_archive_").toFile()
        val target = File(root, "restore.zip")
        val policy = BackupArchivePolicy(maxArchiveBytes = 4)
        try {
            try {
                BackupArchiveExtractor.copyToTemporaryFile(
                    ByteArrayInputStream(ByteArray(5)),
                    target,
                    policy
                )
                throw AssertionError("expected oversized archive rejection")
            } catch (_: IOException) {
                assertFalse(target.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
