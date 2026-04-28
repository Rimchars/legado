package io.legado.app.model.localBook

internal data class EpubBoxDocument(
    val href: String,
    val title: String?,
    val root: EpubBlockNode
)

internal sealed class EpubBoxNode {
    abstract val style: EpubComputedStyle
    abstract val sourcePath: String
}

internal data class EpubBlockNode(
    val tagName: String,
    val attributes: Map<String, String>,
    override val style: EpubComputedStyle,
    val children: List<EpubBoxNode>,
    override val sourcePath: String
) : EpubBoxNode()

internal data class EpubInlineNode(
    val tagName: String,
    val attributes: Map<String, String>,
    override val style: EpubComputedStyle,
    val children: List<EpubBoxNode>,
    override val sourcePath: String
) : EpubBoxNode()

internal data class EpubTextNode(
    val text: String,
    override val style: EpubComputedStyle,
    override val sourcePath: String
) : EpubBoxNode()

internal data class EpubImageNode(
    val src: String,
    val attributes: Map<String, String>,
    override val style: EpubComputedStyle,
    val isBackground: Boolean,
    override val sourcePath: String
) : EpubBoxNode()

internal data class EpubBreakNode(
    override val style: EpubComputedStyle,
    override val sourcePath: String
) : EpubBoxNode()

internal data class EpubRuleNode(
    override val style: EpubComputedStyle,
    override val sourcePath: String
) : EpubBoxNode()

internal data class EpubPageColorNode(
    val colorValue: String,
    override val style: EpubComputedStyle,
    override val sourcePath: String
) : EpubBoxNode()
