package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

class BubblePackageEntryMergeTest {

    // Legacy constructors intentionally omit the optional versioned resource fields.

    @Test
    fun mergesLocalAndRemoteCopiesWithoutLosingLocalConfig() {
        val local = entry("shared", "Local name", 200L, BubblePackageManager.Source.LOCAL)
        val remote = entry(
            "shared",
            "Remote name",
            300L,
            BubblePackageManager.Source.REMOTE,
            remoteUpdatedAt = 300L
        )

        val merged = BubblePackageManager.mergeEntries(listOf(local), listOf(remote))
        val shared = merged.single { it.dirName == "shared" }

        assertEquals(BubblePackageManager.Source.BOTH, shared.source)
        assertEquals("Local name", shared.config.name)
        assertEquals(300L, shared.remoteUpdatedAt)
    }

    @Test
    fun sortsMergedEntriesByNewestTimestampAfterBuiltin() {
        val older = entry("older", "Older", 100L, BubblePackageManager.Source.LOCAL)
        val newer = entry("newer", "Newer", 300L, BubblePackageManager.Source.LOCAL)

        val merged = BubblePackageManager.mergeEntries(listOf(older, newer), emptyList())

        assertEquals(
            listOf(BubblePackageManager.BUILTIN_DIR_NAME, "newer", "older"),
            merged.map { it.dirName }
        )
    }

    private fun entry(
        dirName: String,
        name: String,
        updatedAt: Long,
        source: BubblePackageManager.Source,
        remoteUpdatedAt: Long = 0L
    ) = BubblePackageManager.Entry(
        config = BubblePackageManager.Config(
            name = name,
            dirName = dirName,
            svgTemplate = "<svg/>",
            updatedAt = updatedAt
        ),
        source = source,
        dirName = dirName,
        remoteUpdatedAt = remoteUpdatedAt
    )
}
