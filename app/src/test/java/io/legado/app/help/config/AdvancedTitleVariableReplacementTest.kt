package io.legado.app.help.config

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AdvancedTitleVariableReplacementTest {

    @Test
    fun replacesBothPlaceholderStylesWithoutRemovingUnknownVariables() {
        val rendered = AdvancedTitleConfig.replaceTemplateVariables(
            """{"a":"${'$'}{title}","b":"{{title}}","c":"${'$'}{unknown}"}""",
            mapOf("title" to "Chapter")
        )

        val root = JsonParser.parseString(rendered).asJsonObject
        assertEquals("Chapter", root["a"].asString)
        assertEquals("Chapter", root["b"].asString)
        assertEquals("${'$'}{unknown}", root["c"].asString)
    }

    @Test
    fun returnsOriginalStringWhenTemplateHasNoVariables() {
        val source = """{"layers":[]}"""

        val rendered = AdvancedTitleConfig.replaceTemplateVariables(
            source,
            mapOf("title" to "Chapter")
        )

        assertSame(source, rendered)
    }

    @Test
    fun readsLottieMetadataWithoutBuildingJsonObjectTree() {
        val json = """{"v":"5.7","w":1080,"h":360,"assets":[{"p":"large"}],"layers":[{}]}"""

        assertEquals(1080.0 to 360.0, AdvancedTitleConfig.lottieDimensions(json))
        assertEquals(true, AdvancedTitleConfig.hasRenderableLayers(json))
    }
}
