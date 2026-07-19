package io.legado.app.help.config

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedTitlePackageConfigTest {

    @Test
    fun legacyManifestWithoutRuleFieldsRemainsReadable() {
        val config = GSON.fromJsonObject<AdvancedTitlePackageManager.Config>(
            """{"id":"legacy","name":"Legacy","updatedAt":1}"""
        ).getOrThrow()

        assertNull(config.splitRuleOrNull())
        assertNull(config.normalizedHeightFactorOrNull())
    }

    @Test
    fun packageRuleRoundTripsThroughManifest() {
        val original = AdvancedTitlePackageManager.Config(
            id = "custom",
            name = "Custom",
            splitMode = AdvancedTitleConfig.SPLIT_REGEX,
            delimiter = " ",
            regex = "^(\\S+)\\s+(.+)$",
            heightFactor = 73
        )

        val restored = GSON.fromJsonObject<AdvancedTitlePackageManager.Config>(
            GSON.toJson(original)
        ).getOrThrow()

        assertEquals(original.splitRuleOrNull(), restored.splitRuleOrNull())
        assertEquals(73, restored.normalizedHeightFactorOrNull())
    }

    @Test
    fun malformedStoredValuesAreNormalized() {
        val config = AdvancedTitlePackageManager.Config(
            id = "custom",
            name = "Custom",
            splitMode = 99,
            delimiter = null,
            regex = null,
            heightFactor = 500
        )

        assertEquals(AdvancedTitleConfig.SPLIT_DELIMITER, config.splitRuleOrNull()?.mode)
        assertEquals(" ", config.splitRuleOrNull()?.delimiter)
        assertEquals(120, config.normalizedHeightFactorOrNull())
    }

    @Test
    fun largeTemplatesHaveSeparateEditableAndSafetyLimits() {
        assertEquals(2L * 1024L * 1024L, AdvancedTitlePackageManager.MAX_EDITABLE_JSON_BYTES)
        assertEquals(16L * 1024L * 1024L, AdvancedTitlePackageManager.MAX_JSON_BYTES)
    }

    @Test
    fun utf8SizeCountingStopsAtLimitWithoutAllocatingEncodedCopy() {
        assertEquals(8L, AdvancedTitlePackageManager.utf8SizeUpTo("a中😀", 10L))
        assertEquals(5L, AdvancedTitlePackageManager.utf8SizeUpTo("中文", 4L))
    }
}
