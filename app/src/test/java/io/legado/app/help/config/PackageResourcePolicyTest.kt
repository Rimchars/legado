package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackageResourcePolicyTest {

    @Test
    fun resolvesImageAndFontAliasesInsidePackageRoot() {
        val root = Files.createTempDirectory("package-resource").toFile()
        try {
            val image = root.resolve("assets/background.webp").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val font = root.resolve("assets/title.ttf").apply { writeBytes(byteArrayOf(2)) }
            val resources = listOf(
                PackageResource("background", "assets/background.webp", "image"),
                PackageResource("titleFont", "assets/title.ttf", "font")
            )

            assertEquals(image.canonicalFile, PackageResourcePolicy.resolve(
                root,
                resources,
                "asset://background",
                PackageResourcePolicy.TYPE_IMAGE
            ))
            assertEquals(font.canonicalFile, PackageResourcePolicy.resolve(
                root,
                resources,
                "asset://titleFont",
                PackageResourcePolicy.TYPE_FONT
            ))
            assertNull(PackageResourcePolicy.resolve(
                root,
                resources,
                "asset://titleFont",
                PackageResourcePolicy.TYPE_IMAGE
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAbsolutePathsAndDuplicateAliases() {
        listOf("../a.png", "/a.png", "C:/a.png", "assets/../a.png", "https://a.png").forEach {
            assertTrue(runCatching { PackageResourcePolicy.normalizePath(it) }.isFailure)
        }
        assertTrue(runCatching {
            PackageResourcePolicy.normalize(
                listOf(
                    PackageResource("Cover", "assets/a.png", "image"),
                    PackageResource("cover", "assets/b.png", "image")
                )
            )
        }.isFailure)
    }
}
