package io.legado.app.model.localBook

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.IdentityHashMap

internal class EpubDomBuilder(
    private val loadCss: (baseHref: String, href: String) -> String,
    private val resolveHref: (baseHref: String, href: String) -> String
) {

    fun build(
        doc: Document,
        body: Element,
        baseHref: String
    ): EpubDomDocument {
        val rules = collectRules(doc, body, baseHref).mapIndexed { index, rule ->
            rule.copy(order = index)
        }
        val fontFaces = collectFontFaces(doc, body, baseHref)
        val generatedContentRules = collectGeneratedContentRules(doc, body, baseHref)
        applyGeneratedContent(body, generatedContentRules)
        val matchedRules = matchRules(body, rules)
        val bodyElement = buildElement(
            element = body,
            baseHref = baseHref,
            parentStyle = EpubComputedStyle.empty,
            matchedRules = matchedRules,
            sourcePath = "body"
        )
        return EpubDomDocument(
            href = baseHref,
            title = doc.title().takeIf { it.isNotBlank() },
            body = bodyElement,
            fontFaces = fontFaces
        )
    }

    private fun collectFontFaces(doc: Document, body: Element, baseHref: String): List<EpubFontFace> {
        val faces = arrayListOf<EpubFontFace>()
        fun resolveCssUrl(cssHref: String, href: String): String {
            return resolveHref(cssHref, href)
        }
        doc.head()?.select("style")?.forEach { styleElement ->
            faces.addAll(
                EpubCss.parseFontFaces(styleElement.data().ifBlank { styleElement.html() }) { href ->
                    resolveCssUrl(baseHref, href)
                }
            )
        }
        doc.head()?.select("link[href][rel~=stylesheet]")?.forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                faces.addAll(EpubCss.parseFontFaces(loadCss(baseHref, href)))
            }
        }
        body.select("style").forEach { styleElement ->
            faces.addAll(
                EpubCss.parseFontFaces(styleElement.data().ifBlank { styleElement.html() }) { href ->
                    resolveCssUrl(baseHref, href)
                }
            )
        }
        body.select("link[href][rel~=stylesheet]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                faces.addAll(EpubCss.parseFontFaces(loadCss(baseHref, href)))
            }
        }
        return faces.distinctBy { face ->
            "${face.family.lowercase()}|${face.weight.orEmpty()}|${face.style.orEmpty()}|${face.src}"
        }
    }

    private fun collectRules(doc: Document, body: Element, baseHref: String): List<EpubCss.Rule> {
        val rules = arrayListOf<EpubCss.Rule>()
        doc.head()?.select("style")?.forEach { styleElement ->
            rules.addAll(EpubCss.parseRules(styleElement.data().ifBlank { styleElement.html() }, supportedOnly = false))
        }
        doc.head()?.select("link[href][rel~=stylesheet]")?.forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                rules.addAll(EpubCss.parseRules(loadCss(baseHref, href), supportedOnly = false))
            }
        }
        body.select("style").forEach { styleElement ->
            rules.addAll(EpubCss.parseRules(styleElement.data().ifBlank { styleElement.html() }, supportedOnly = false))
        }
        body.select("link[href][rel~=stylesheet]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                rules.addAll(EpubCss.parseRules(loadCss(baseHref, href), supportedOnly = false))
            }
        }
        return rules
    }

    private fun collectGeneratedContentRules(
        doc: Document,
        body: Element,
        baseHref: String
    ): List<EpubCss.GeneratedContentRule> {
        val rules = arrayListOf<EpubCss.GeneratedContentRule>()
        doc.head()?.select("style")?.forEach { styleElement ->
            rules.addAll(EpubCss.parseGeneratedContentRules(styleElement.data().ifBlank { styleElement.html() }))
        }
        doc.head()?.select("link[href][rel~=stylesheet]")?.forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                rules.addAll(EpubCss.parseGeneratedContentRules(loadCss(baseHref, href)))
            }
        }
        body.select("style").forEach { styleElement ->
            rules.addAll(EpubCss.parseGeneratedContentRules(styleElement.data().ifBlank { styleElement.html() }))
        }
        body.select("link[href][rel~=stylesheet]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                rules.addAll(EpubCss.parseGeneratedContentRules(loadCss(baseHref, href)))
            }
        }
        return rules
    }

    private fun applyGeneratedContent(body: Element, rules: List<EpubCss.GeneratedContentRule>) {
        if (rules.isEmpty()) return
        rules.forEach { rule ->
            val style = rule.declarations
                .filterNot { it.name == "content" }
                .joinToString(";") { declaration -> "${declaration.name}:${declaration.value}" }
            runCatching {
                if (body.`is`(rule.selector)) {
                    rule.declarations.generatedContentText(body)?.let { content ->
                        body.addGeneratedContent(content, style, rule.before)
                    }
                }
                body.select(rule.selector).forEach { element ->
                    rule.declarations.generatedContentText(element)?.let { content ->
                        element.addGeneratedContent(content, style, rule.before)
                    }
                }
            }
        }
    }

    private fun List<EpubCss.Declaration>.generatedContentText(element: Element): String? {
        val value = lastOrNull { it.name == "content" }?.value?.trim() ?: return null
        if (value.equals("none", ignoreCase = true) || value.equals("normal", ignoreCase = true)) return null
        val tokens = EpubCss.splitValueList(value)
        var counterFallback = 1
        return tokens
            .joinToString("") { token ->
                when {
                    token.startsWith("'") && token.endsWith("'") && token.length >= 2 -> token.substring(1, token.lastIndex)
                    token.startsWith("\"") && token.endsWith("\"") && token.length >= 2 -> token.substring(1, token.lastIndex)
                    token.equals("open-quote", ignoreCase = true) -> "“"
                    token.equals("close-quote", ignoreCase = true) -> "”"
                    token.equals("no-open-quote", ignoreCase = true) -> ""
                    token.equals("no-close-quote", ignoreCase = true) -> ""
                    token.startsWith("counter(", ignoreCase = true) -> counterFallback++.toString()
                    token.startsWith("counters(", ignoreCase = true) -> counterFallback++.toString()
                    token.startsWith("attr(", ignoreCase = true) && token.endsWith(")") -> {
                        val name = token.substringAfter('(').substringBeforeLast(')').trim()
                        element.attr(name)
                    }
                    else -> ""
                }
            }
            .replace("\\A", "\n")
            .replace("\\a", "\n")
            .takeIf { it.isNotEmpty() }
    }

    private fun Element.toRubyFallbackText(): String {
        val builder = StringBuilder()
        childNodes().forEach { child ->
            when (child) {
                is TextNode -> builder.append(child.wholeText)
                is Element -> when (child.normalName()) {
                    "rt" -> child.text().trim().takeIf { it.isNotBlank() }?.let {
                        builder.append('（').append(it).append('）')
                    }
                    "rp" -> Unit
                    else -> builder.append(child.text())
                }
            }
        }
        return builder.toString().ifBlank { text() }
    }

    private fun Element.addGeneratedContent(content: String, style: String, before: Boolean) {
        val styleAttr = style.takeIf { it.isNotBlank() }?.let { " style=\"$it\"" }.orEmpty()
        val html = "<span data-epub-generated=\"true\"$styleAttr>${content.escapeHtml()}</span>"
        if (before) {
            prepend(html)
        } else {
            append(html)
        }
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun matchRules(root: Element, rules: List<EpubCss.Rule>): IdentityHashMap<Element, MutableList<EpubCss.Rule>> {
        val matched = IdentityHashMap<Element, MutableList<EpubCss.Rule>>()
        rules.forEach { rule ->
            runCatching {
                if (root.`is`(rule.selector)) {
                    matched.getOrPut(root) { arrayListOf() }.add(rule)
                }
                root.select(rule.selector).forEach { element ->
                    matched.getOrPut(element) { arrayListOf() }.add(rule)
                }
            }
        }
        return matched
    }

    private fun buildNode(
        node: Node,
        baseHref: String,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<EpubCss.Rule>>,
        sourcePath: String
    ): EpubDomNode? {
        return when (node) {
            is TextNode -> EpubDomText(node.wholeText, sourcePath)
            is Element -> buildElement(node, baseHref, parentStyle, matchedRules, sourcePath)
            else -> null
        }
    }

    private fun buildElement(
        element: Element,
        baseHref: String,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<EpubCss.Rule>>,
        sourcePath: String
    ): EpubDomElement {
        val style = computeStyle(element, parentStyle, matchedRules[element].orEmpty(), baseHref)
        val attributes = element.attributes().associate { attr ->
            val value = when (attr.key.lowercase()) {
                "src", "href", "xlink:href" -> attr.value.takeIf { it.isNotBlank() }?.let {
                    resolveHref(baseHref, it)
                } ?: attr.value
                else -> attr.value
            }
            attr.key to value
        }
        if (element.normalName() == "ruby") {
            return EpubDomElement(
                tagName = "span",
                attributes = attributes,
                style = style,
                children = listOf(EpubDomText(element.toRubyFallbackText(), sourcePath)),
                sourcePath = sourcePath
            )
        }
        val children = element.childNodes().mapIndexedNotNull { index, child ->
            buildNode(
                node = child,
                baseHref = baseHref,
                parentStyle = style.inheritedOnly(),
                matchedRules = matchedRules,
                sourcePath = "$sourcePath/${element.normalName()}[$index]"
            )
        }
        return EpubDomElement(
            tagName = element.normalName(),
            attributes = attributes,
            style = style,
            children = children,
            sourcePath = sourcePath
        )
    }

    private fun computeStyle(
        element: Element,
        parentStyle: EpubComputedStyle,
        rules: List<EpubCss.Rule>,
        baseHref: String
    ): EpubComputedStyle {
        val merged = linkedMapOf<String, EpubStyleValue>()
        parentStyle.declarations.forEach { (name, value) ->
            merged[name] = value
        }
        fun putDeclaration(
            declaration: EpubCss.Declaration,
            sourceRank: Int,
            specificity: Int,
            ruleOrder: Int
        ) {
            val value = EpubStyleValue(
                value = declaration.value,
                important = declaration.important,
                sourceRank = sourceRank + if (declaration.important) 2 else 0,
                specificity = specificity,
                ruleOrder = ruleOrder,
                declarationOrder = declaration.order
            )
            val current = merged[declaration.name]
            if (current == null || value.hasHigherPriorityThan(current)) {
                merged[declaration.name] = value
            }
        }
        element.tagDefaultDeclarations().forEach { declaration ->
            putDeclaration(declaration, sourceRank = -1, specificity = 0, ruleOrder = -1)
        }
        element.attr("align")
            .trim()
            .lowercase()
            .normalizeTextAlign()
            ?.let { align ->
                putDeclaration(
                    declaration = EpubCss.Declaration(
                        name = "text-align",
                        value = align,
                        important = false,
                        order = -1
                    ),
                    sourceRank = -1,
                    specificity = 0,
                    ruleOrder = -1
                )
            }
        rules.forEach { rule ->
            rule.declarations.forEach { declaration ->
                putDeclaration(declaration, sourceRank = 0, specificity = rule.specificity, ruleOrder = rule.order)
            }
        }
        EpubCss.parseDeclarations(element.attr("style")).forEach { declaration ->
            putDeclaration(declaration, sourceRank = 1, specificity = 1000, ruleOrder = Int.MAX_VALUE)
        }
        merged.normalizeVerticalWritingFallback()
        merged.normalizeRelativeFontSize(parentStyle)
        return EpubComputedStyle(merged.resolveBackgroundUrls(baseHref))
    }

    private fun LinkedHashMap<String, EpubStyleValue>.normalizeVerticalWritingFallback() {
        val writingMode = this["writing-mode"]
            ?: this["-epub-writing-mode"]
            ?: this["-webkit-writing-mode"]
            ?: return
        if (!writingMode.value.lowercase().startsWith("vertical")) return
        putIfAbsent("text-align", writingMode.copy(value = "center"))
        putIfAbsent("line-height", writingMode.copy(value = "1.45"))
    }

    private fun LinkedHashMap<String, EpubStyleValue>.normalizeRelativeFontSize(parentStyle: EpubComputedStyle) {
        val current = this["font-size"] ?: return
        if (current.sourceRank < 0 && current.declarationOrder < 0) return
        val parentMultiplier = parentStyle["font-size"]?.fontSizeMultiplier(1f) ?: 1f
        val normalized = current.value.fontSizeMultiplier(parentMultiplier) ?: return
        this["font-size"] = current.copy(value = "${normalized * 100f}%")
    }

    private fun String.fontSizeMultiplier(parentMultiplier: Float): Float? {
        val clean = trim().lowercase()
        return when {
            clean == "xx-small" -> 0.58f
            clean == "x-small" -> 0.68f
            clean == "small" -> 0.82f
            clean == "medium" -> 1f
            clean == "large" -> 1.18f
            clean == "x-large" -> 1.36f
            clean == "xx-large" -> 1.55f
            clean == "smaller" -> parentMultiplier * 0.85f
            clean == "larger" -> parentMultiplier * 1.18f
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()?.let { parentMultiplier * it / 100f }
            clean.endsWith("em") -> clean.dropLast(2).toFloatOrNull()?.let { parentMultiplier * it }
            clean.endsWith("rem") -> clean.dropLast(3).toFloatOrNull()
            else -> null
        }
    }

    private fun Element.tagDefaultDeclarations(): List<EpubCss.Declaration> {
        val declarations = arrayListOf<EpubCss.Declaration>()
        fun add(name: String, value: String) {
            declarations.add(
                EpubCss.Declaration(
                    name = name,
                    value = value,
                    important = false,
                    order = declarations.size
                )
            )
        }
        when (normalName()) {
            "b", "strong" -> add("font-weight", "bold")
            "i", "em", "cite" -> add("font-style", "italic")
            "u" -> add("text-decoration", "underline")
            "strike", "s", "del" -> add("text-decoration", "line-through")
            "big" -> add("font-size", "larger")
            "small" -> add("font-size", "smaller")
            "sup" -> {
                add("font-size", "smaller")
                add("vertical-align", "super")
            }
            "sub" -> {
                add("font-size", "smaller")
                add("vertical-align", "sub")
            }
            "a" -> {
                add("text-decoration", "underline")
                add("color", "#3366CC")
            }
            "center" -> add("text-align", "center")
            "h1" -> {
                add("font-size", "2em")
                add("font-weight", "bold")
            }
            "h2" -> {
                add("font-size", "1.5em")
                add("font-weight", "bold")
            }
            "h3" -> {
                add("font-size", "1.17em")
                add("font-weight", "bold")
            }
            "h4", "h5", "h6" -> add("font-weight", "bold")
            "th" -> {
                add("font-weight", "bold")
                add("text-align", "center")
            }
            "caption" -> add("text-align", "center")
        }
        if (normalName() == "font") {
            attr("color").takeIf { it.isNotBlank() }?.let { add("color", it) }
            attr("face").takeIf { it.isNotBlank() }?.let { add("font-family", it) }
            attr("size").toHtmlFontSize()?.let { add("font-size", it) }
        }
        normalName().epubBackgroundColorTag()?.let { color ->
            add("background-color", color)
        }
        return declarations
    }

    private fun String.toHtmlFontSize(): String? {
        return when (trim().removePrefix("+")) {
            "1" -> "xx-small"
            "2" -> "small"
            "3" -> "medium"
            "4" -> "large"
            "5" -> "x-large"
            "6" -> "xx-large"
            "7" -> "2em"
            else -> null
        }
    }

    private fun String.epubBackgroundColorTag(): String? {
        if (!startsWith("epubbg", ignoreCase = true)) return null
        val hex = substringAfter("epubbg", "").takeIf { it.length == 8 || it.length == 6 } ?: return null
        return "#$hex"
    }

    private fun String.normalizeTextAlign(): String? {
        return when (this) {
            "center", "middle", "-webkit-center", "-moz-center" -> "center"
            "left", "start" -> "left"
            "right", "end" -> "right"
            "justify" -> "justify"
            else -> null
        }
    }

    private fun LinkedHashMap<String, EpubStyleValue>.resolveBackgroundUrls(
        baseHref: String
    ): LinkedHashMap<String, EpubStyleValue> {
        listOf("background", "background-image").forEach { name ->
            val value = this[name] ?: return@forEach
            val resolved = value.value.rewriteCssUrls { href ->
                resolveHref(baseHref, href)
            }
            if (resolved != value.value) {
                this[name] = value.copy(value = resolved)
            }
        }
        return this
    }

    private fun String.rewriteCssUrls(resolve: (String) -> String): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val start = indexOf("url(", index, ignoreCase = true)
            if (start < 0) {
                builder.append(substring(index))
                break
            }
            builder.append(substring(index, start))
            val valueStart = start + 4
            val end = findCssUrlEnd(valueStart)
            if (end < 0) {
                builder.append(substring(start))
                break
            }
            val raw = substring(valueStart, end).trim()
            val quote = raw.firstOrNull()?.takeIf { it == '\'' || it == '"' }
            val clean = raw.trimMatchingQuote()
            val resolved = if (clean.startsWith("data:", true) ||
                clean.startsWith("http://", true) ||
                clean.startsWith("https://", true)
            ) {
                clean
            } else {
                resolve(clean)
            }
            builder.append("url(")
            if (quote != null) {
                builder.append(quote).append(resolved).append(quote)
            } else {
                builder.append(resolved)
            }
            builder.append(")")
            index = end + 1
        }
        return builder.toString()
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
}
