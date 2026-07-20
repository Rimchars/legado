package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AdvancedTitlePackageArchiveTest {

    @Test
    fun extractsVersionedPackageWithAssets() {
        val root = Files.createTempDirectory("advanced-title-archive").toFile()
        try {
            val zip = File(root, "title.zip")
            writeZip(zip, mapOf(
                "pack/package.json" to "{}",
                "pack/title.json" to "{\"w\":1,\"h\":1,\"layers\":[{}]}",
                "pack/assets/background.webp" to "image"
            ))

            val extracted = AdvancedTitlePackageArchive.extract(zip, File(root, "out"))

            assertEquals("title.json", extracted.titleFile.name)
            assertEquals("pack", extracted.packageRoot.name)
            assertTrue(File(extracted.packageRoot, "assets/background.webp").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndAmbiguousAnimations() {
        val root = Files.createTempDirectory("advanced-title-archive-invalid").toFile()
        try {
            val traversal = File(root, "traversal.zip")
            writeZip(traversal, mapOf("../title.json" to "{}"))
            assertTrue(runCatching {
                AdvancedTitlePackageArchive.extract(traversal, File(root, "traversal-out"))
            }.isFailure)

            val ambiguous = File(root, "ambiguous.zip")
            writeZip(ambiguous, mapOf("a.json" to "{}", "b.json" to "{}"))
            assertTrue(runCatching {
                AdvancedTitlePackageArchive.extract(ambiguous, File(root, "ambiguous-out"))
            }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }
}
