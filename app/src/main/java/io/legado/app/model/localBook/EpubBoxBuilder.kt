package io.legado.app.model.localBook

import java.util.Locale

internal class EpubBoxBuilder {

    fun build(document: EpubDomDocument): EpubBoxDocument {
        val root = EpubBlockNode(
            tagName = document.body.tagName,
            attributes = document.body.attributes,
            style = document.body.style,
            children = document.body.children.mapNotNull { node -> buildNode(node, document.body.style.inheritedOnly()) },
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
        if (element.style["visibility"].equals("hidden", ignoreCase = true)) return null
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
                val isBlock = element.isBlock()
                val childParentStyle = if (isBlock) {
                    element.style.inheritedOnly()
                } else {
                    element.style
                }
                val children = arrayListOf<EpubBoxNode>()
                element.style.pageBackgroundColor()?.let { color ->
                    if (element.tagName == "body") {
                        children.add(
                            EpubPageColorNode(
                                colorValue = color,
                                style = element.style,
                                sourcePath = element.sourcePath
                            )
                        )
                    }
                }
                element.style.backgroundImageSrc()?.let { src ->
                    children.add(
                        EpubImageNode(
                            src = src,
                            attributes = element.attributes,
                            style = element.style,
                            isBackground = element.shouldPromoteBackgroundImage(),
                            sourcePath = element.sourcePath
                        )
                    )
                }
                children.addAll(element.children.mapNotNull { child ->
                    buildNode(child, childParentStyle)
                })
                if (isBlock) {
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
            if (display == "inline" || display == "inline-flex") return false
            if (display == "inline-block") return true
            if (display in blockDisplays) return true
        }
        return tagName in blockTags
    }

    private fun EpubDomElement.shouldPromoteBackgroundImage(): Boolean {
        if (tagName == "body" || tagName == "html") return true
        val attachment = style["background-attachment"]?.lowercase(Locale.ROOT)
        // 元素级背景（尤其标题模板）需要和文本同容器排版，不能因 no-repeat/cover 被提升成整页背景
        return attachment == "fixed"
    }

    private fun EpubComputedStyle.backgroundImageSrc(): String? {
        val background = this["background-image"]
            ?: this["background"]
            ?: return null
        return background.extractCssUrl()
    }

    private fun EpubComputedStyle.pageBackgroundColor(): String? {
        return this["background-color"]
            ?: this["background"]?.takeIf { it.extractCssUrl() == null }?.extractCssColor()
    }

    private fun String.extractCssUrl(): String? {
        val start = indexOf("url(", ignoreCase = true)
        if (start < 0) return null
        val valueStart = start + 4
        val end = findCssUrlEnd(valueStart)
        if (end < 0) return null
        return substring(valueStart, end).trim().trimMatchingQuote()
            .takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
    }

    private fun String.findCssUrlEnd(start: Int): Int {
        var quote: Char? = null
        for (index in start until length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') {
                    quote = null
                }
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                ')' -> return index
            }
        }
        return -1
    }

    private fun String.trimMatchingQuote(): String {
        val clean = trim()
        if (clean.length >= 2) {
            val first = clean.first()
            val last = clean.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return clean.substring(1, clean.lastIndex)
            }
        }
        return clean
    }

    private fun String.extractCssColor(): String? {
        val clean = trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        val parts = clean.split(' ', ',', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return parts.firstOrNull { part ->
            part.startsWith("#") || part.startsWith("rgb", true) || part.toNamedCssColor() != null
        }
    }

    private fun String.extractBackgroundRepeat(): String? {
        val clean = lowercase(Locale.ROOT)
        return when {
            clean.contains("no-repeat") -> "no-repeat"
            clean.contains("repeat-x") -> "repeat-x"
            clean.contains("repeat-y") -> "repeat-y"
            clean.contains("repeat") -> "repeat"
            else -> null
        }
    }

    private fun String.extractBackgroundSize(): String? {
        val slash = indexOf('/')
        if (slash < 0) return null
        return substring(slash + 1)
            .split(' ', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    private fun String.toNamedCssColor(): String? {
        return when (lowercase(Locale.ROOT)) {
            "black" -> "#000000"
            "white" -> "#FFFFFF"
            "red" -> "#FF0000"
            "green" -> "#008000"
            "blue" -> "#0000FF"
            "cyan", "aqua" -> "#00FFFF"
            "magenta", "fuchsia" -> "#FF00FF"
            "yellow" -> "#FFFF00"
            "gray", "grey" -> "#808080"
            "silver" -> "#C0C0C0"
            "maroon" -> "#800000"
            "purple" -> "#800080"
            "teal" -> "#008080"
            "navy" -> "#000080"
            "orange" -> "#FFA500"
            "transparent" -> "#00000000"
            else -> null
        }
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
