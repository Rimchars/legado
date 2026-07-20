package io.legado.app.help.config

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BubblePackageConfigCompatibilityTest {

    @Test
    fun legacyConfigWithoutVersionedResourcesUsesVersionOneDefaults() {
        val config = BubblePackageManager.normalizeStoredConfig(
            GSON.fromJsonObject<BubblePackageManager.Config>(
                """{"name":"Legacy","dirName":"legacy","svgTemplate":"<svg/>"}"""
            ).getOrThrow()
        )

        assertEquals(1, config.formatVersion)
        assertEquals(emptyList<PackageResource>(), config.resources)
    }
}
