package io.legado.app.model.localBook

import java.util.Locale

internal class EpubBoxBuilder {

    fun build(document: EpubDomDocument): EpubBoxDocument {
        val root = EpubBlockNode(
            tagName = document.body.tagName,
            attributes = document.body.attributes,
            style = document.body.style,
            children = document.body.children.mapNotNull { node -> buildNode(node, document.body.style) },
            sourcePath = document.body.sourcePath
        )
        return EpubBoxDocument(
            href = document.href,
            title = document.title,
            root = root
        )
    }

    private fun buildNode(node: EpubDomNode, parentStyle: EpubComputedStyle): EpubBoxNode? {
        return when (node) {
            is EpubDomText -> EpubTextNode(
                text = node.text,
                style = parentStyle,
                sourcePath = node.sourcePath
            )
            is EpubDomElement -> buildElement(node)
        }
    }

    private fun buildElement(element: EpubDomElement): EpubBoxNode? {
        if (element.style["display"].equals("none", ignoreCase = true)) return null
        element.attributes["data-epub-page-bg"]?.let { color ->
            return EpubPageColorNode(
                colorValue = color,
                style = element.style,
                sourcePath = element.sourcePath
            )
        }
        return when (element.tagName) {
            "br" -> EpubBreakNode(element.style, element.sourcePath)
            "hr" -> EpubRuleNode(element.style, element.sourcePath)
            "img", "image" -> {
                val src = element.attributes["src"]
                    ?: element.attributes["xlink:href"]
                    ?: element.attributes["href"]
                    ?: return null
                EpubImageNode(
                    src = src,
                    attributes = element.attributes,
                    style = element.style,
                    isBackground = element.attributes["data-epub-background"] == "true",
                    sourcePath = element.sourcePath
                )
            }
            else -> {
                val children = element.children.mapNotNull { child ->
                    buildNode(child, element.style)
                }
                if (element.isBlock()) {
                    EpubBlockNode(
                        tagName = element.tagName,
                        attributes = element.attributes,
                        style = element.style,
                        children = children,
                        sourcePath = element.sourcePath
                    )
                } else {
                    EpubInlineNode(
                        tagName = element.tagName,
                        attributes = element.attributes,
                        style = element.style,
                        children = children,
                        sourcePath = element.sourcePath
                    )
                }
            }
        }
    }

    private fun EpubDomElement.isBlock(): Boolean {
        val display = style["display"]?.lowercase(Locale.ROOT)
        if (display != null) {
            if (display == "inline" || display == "inline-block" || display == "inline-flex") return false
            if (display in blockDisplays) return true
        }
        return tagName in blockTags
    }

    private companion object {
        private val blockDisplays = setOf(
            "block",
            "list-item",
            "table",
            "table-row",
            "table-cell",
            "flex",
            "grid",
            "flow-root"
        )

        private val blockTags = setOf(
            "address", "article", "aside", "blockquote", "body", "caption", "center",
            "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4",
            "h5", "h6", "header", "hgroup", "li", "main", "menu", "nav", "ol",
            "p", "pre", "section", "summary", "table", "tbody", "td", "tfoot",
            "th", "thead", "tr", "ul"
        )
    }
}
