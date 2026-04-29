package io.legado.app.model.localBook

internal data class EpubDomDocument(
    val href: String,
    val title: String?,
    val body: EpubDomElement,
    val fontFaces: List<EpubFontFace> = emptyList()
)

internal data class EpubFontFace(
    val family: String,
    val src: String,
    val weight: String?,
    val style: String?
)

internal sealed class EpubDomNode {
    abstract val sourcePath: String
}

internal data class EpubDomElement(
    val tagName: String,
    val attributes: Map<String, String>,
    val style: EpubComputedStyle,
    val children: List<EpubDomNode>,
    override val sourcePath: String
) : EpubDomNode()

internal data class EpubDomText(
    val text: String,
    override val sourcePath: String
) : EpubDomNode()

internal data class EpubComputedStyle(
    val declarations: Map<String, EpubStyleValue>
) {
    operator fun get(name: String): String? = declarations[name]?.value

    fun withoutInheritedTextIndent(): EpubComputedStyle {
        if (!declarations.containsKey("text-indent")) return this
        return EpubComputedStyle(declarations - "text-indent")
    }

    fun inheritedOnly(): EpubComputedStyle {
        val inherited = declarations
            .filterKeys { it in inheritableProperties }
            .mapValues { (_, value) ->
                value.copy(sourceRank = -1, specificity = 0, ruleOrder = -1, declarationOrder = -1)
            }
        return EpubComputedStyle(inherited)
    }

    companion object {
        val empty = EpubComputedStyle(emptyMap())

        private val inheritableProperties = setOf(
            "color",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "font-variant",
            "font-variant-caps",
            "letter-spacing",
            "line-height",
            "text-align",
            "text-decoration",
            "text-decoration-color",
            "text-decoration-line",
            "text-decoration-style",
            "text-shadow",
            "text-transform",
            "visibility",
            "white-space",
            "word-break",
            "word-spacing",
            "writing-mode",
            "-epub-writing-mode",
            "-webkit-writing-mode",
            "direction"
        )
    }
}

internal data class EpubStyleValue(
    val value: String,
    val important: Boolean,
    val sourceRank: Int,
    val specificity: Int,
    val ruleOrder: Int,
    val declarationOrder: Int
) {
    fun hasHigherPriorityThan(other: EpubStyleValue): Boolean {
        return compareValuesBy(
            this,
            other,
            EpubStyleValue::sourceRank,
            EpubStyleValue::specificity,
            EpubStyleValue::ruleOrder,
            EpubStyleValue::declarationOrder
        ) > 0
    }
}
