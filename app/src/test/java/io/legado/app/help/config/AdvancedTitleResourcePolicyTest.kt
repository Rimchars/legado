package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedTitleResourcePolicyTest {

    @Test
    fun extractsLegacyDataAndVersionedResourceReferencesSeparately() {
        val json = """
            {
              "assets": [
                {"u":"assets/","p":"background.webp"},
                {"p":"asset://cover"},
                {"p":"data:image/png;base64,AAAA"}
              ],
              "fonts": {"list":[
                {"fFamily":"asset://titleFont","fName":"Title"},
                {"fFamily":"sans-serif","fName":"Roboto"}
              ]},
              "layers":[{}]
            }
        """.trimIndent()

        val references = AdvancedTitleResourcePolicy.references(json)

        assertEquals(setOf("assets/background.webp", "asset://cover"), references.images)
        assertEquals(setOf("asset://titleFont"), references.fonts)
    }
}
