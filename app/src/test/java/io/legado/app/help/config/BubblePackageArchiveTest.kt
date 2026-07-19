package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class BubblePackageArchiveTest {

    @Test
    fun extractsSingleManifestAndAssets() {
        val root = createTempDirectory("bubble-archive-").toFile()
        try {
            val zip = File(root, "package.zip")
            writeZip(zip, mapOf("pack/bubble.json" to "{}", "pack/icon.svg" to "<svg/>"))
            val destination = File(root, "out")

            val result = BubblePackageArchive.extract(zip, destination)

            assertEquals("bubble.json", result.manifestFile.name)
            assertEquals(2, result.entryCount)
            assertTrue(File(destination, "pack/icon.svg").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlipAndDeletesPartialOutput() {
        val root = createTempDirectory("bubble-archive-").toFile()
        try {
            val zip = File(root, "package.zip")
            writeZip(zip, linkedMapOf("safe.txt" to "safe", "../outside.txt" to "bad"))
            val destination = File(root, "out")

            expectIOException { BubblePackageArchive.extract(zip, destination) }

            assertFalse(destination.exists())
            assertFalse(File(root, "outside.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateManifest() {
        val root = createTempDirectory("bubble-archive-").toFile()
        try {
            val zip = File(root, "package.zip")
            writeZip(zip, mapOf("one/bubble.json" to "{}", "two/bubble.json" to "{}"))

            expectIOException { BubblePackageArchive.extract(zip, File(root, "out")) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsHighlyCompressedEntry() {
        val root = createTempDirectory("bubble-archive-").toFile()
        try {
            val zip = File(root, "package.zip")
            writeZip(zip, mapOf("bubble.json" to "{}", "asset.txt" to "a".repeat(2_000_000)))

            expectIOException { BubblePackageArchive.extract(zip, File(root, "out")) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveBeyondHardLimitBeforeExtraction() {
        val root = createTempDirectory("bubble-archive-").toFile()
        try {
            val zip = File(root, "package.zip")
            RandomAccessFile(zip, "rw").use { it.setLength(256L * 1024L * 1024L + 1L) }

            expectIOException { BubblePackageArchive.extract(zip, File(root, "out")) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, content) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }
}
