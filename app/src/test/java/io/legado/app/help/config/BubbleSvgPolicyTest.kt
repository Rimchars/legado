package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleSvgPolicyTest {

    @Test
    fun allowsLocalFragmentReferences() {
        BubbleSvgPolicy.validate(
            """<svg><defs><linearGradient id="g"/></defs><path fill="url(#g)"/><use href="#g"/></svg>"""
        )
    }

    @Test
    fun allowsPackageAliasesAndRelativeAssets() {
        val svg = """<svg><image href="asset://background"/><image href="assets/icon.webp"/></svg>"""

        BubbleSvgPolicy.validate(svg)

        assertEquals(
            setOf("asset://background", "assets/icon.webp"),
            BubbleSvgPolicy.packageReferences(svg)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNetworkReferences() {
        BubbleSvgPolicy.validate("""<svg><image href="https://example.com/a.png"/></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScriptsAndEventHandlers() {
        BubbleSvgPolicy.validate("""<svg onload="run()"><script>run()</script></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsXmlEntities() {
        BubbleSvgPolicy.validate("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg>&x;</svg>""")
    }
}
