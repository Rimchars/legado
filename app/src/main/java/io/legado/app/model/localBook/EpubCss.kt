package io.legado.app.model.localBook

import java.util.Locale

internal object EpubCss {

    private val supportedProperties = setOf(
        "text-align",
        "color",
        "font-weight",
        "font-style",
        "font-size",
        "font-family",
        "font",
        "font-variant",
        "font-variant-caps",
        "font-stretch",
        "font-size-adjust",
        "text-indent",
        "duokan-text-indent",
        "text-transform",
        "text-orientation",
        "text-decoration",
        "text-decoration-line",
        "text-decoration-color",
        "text-decoration-style",
        "line-height",
        "text-shadow",
        "box-shadow",
        "filter",
        "opacity",
        "letter-spacing",
        "word-spacing",
        "white-space",
        "word-break",
        "overflow-wrap",
        "word-wrap",
        "hyphens",
        "direction",
        "unicode-bidi",
        "writing-mode",
        "-epub-writing-mode",
        "-webkit-writing-mode",
        "vertical-align",
        "page-break-before",
        "page-break-after",
        "page-break-inside",
        "break-before",
        "break-after",
        "break-inside",
        "margin",
        "margin-left",
        "margin-right",
        "margin-top",
        "margin-bottom",
        "padding",
        "padding-left",
        "padding-right",
        "padding-top",
        "padding-bottom",
        "display",
        "visibility",
        "background",
        "background-image",
        "background-color",
        "background-size",
        "background-position",
        "background-position-x",
        "background-position-y",
        "background-repeat",
        "background-attachment",
        "background-clip",
        "background-origin",
        "background-blend-mode",
        "border",
        "border-left",
        "border-right",
        "border-top",
        "border-bottom",
        "border-color",
        "border-width",
        "border-style",
        "border-left-color",
        "border-left-width",
        "border-left-style",
        "border-right-color",
        "border-right-width",
        "border-right-style",
        "border-top-color",
        "border-top-width",
        "border-top-style",
        "border-bottom-color",
        "border-bottom-width",
        "border-bottom-style",
        "border-radius",
        "border-top-left-radius",
        "border-top-right-radius",
        "border-bottom-right-radius",
        "border-bottom-left-radius",
        "box-sizing",
        "overflow",
        "overflow-x",
        "overflow-y",
        "object-fit",
        "object-position",
        "float",
        "clear",
        "position",
        "left",
        "right",
        "top",
        "bottom",
        "z-index",
        "list-style",
        "list-style-type",
        "list-style-position",
        "list-style-image",
        "border-collapse",
        "border-spacing",
        "empty-cells",
        "table-layout",
        "caption-side",
        "display-inside",
        "display-outside",
        "width",
        "height",
        "min-width",
        "min-height",
        "max-width",
        "max-height",
        "orphans",
        "widows"
    )

    data class Rule(
        val selector: String,
        val style: String,
        val specificity: Int,
        val order: Int,
        val declarations: List<Declaration> = EpubCss.parseDeclarations(style)
    )

    data class Declaration(
        val name: String,
        val value: String,
        val important: Boolean,
        val order: Int
    )

    data class GeneratedContentRule(
        val selector: String,
        val before: Boolean,
        val declarations: List<Declaration>,
        val specificity: Int,
        val order: Int
    )

    fun parseGeneratedContentRules(css: String): List<GeneratedContentRule> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .expandSupportedAtRules()
        val rules = arrayListOf<GeneratedContentRule>()
        var order = 0
        var index = 0
        while (index < cleanCss.length) {
            val start = cleanCss.indexOf('{', index)
            if (start < 0) break
            val end = cleanCss.findMatchingCssBrace(start)
            if (end < 0) break
            val selectorText = cleanCss.substring(index, start)
            val declarations = parseDeclarations(cleanCss.substring(start + 1, end))
            selectorText.split(',')
                .map { it.trim() }
                .forEach { selector ->
                    val lower = selector.lowercase(Locale.ROOT)
                    val before = lower.endsWith("::before") || lower.endsWith(":before")
                    val after = lower.endsWith("::after") || lower.endsWith(":after")
                    if (!before && !after) return@forEach
                    val baseSelector = selector
                        .replace(Regex(":{1,2}(before|after)\\s*$", RegexOption.IGNORE_CASE), "")
                        .toSupportedSelector()
                        ?: return@forEach
                    rules.add(
                        GeneratedContentRule(
                            selector = baseSelector,
                            before = before,
                            declarations = declarations,
                            specificity = baseSelector.cssSpecificity() + 1,
                            order = order
                        )
                    )
                }
            order++
            index = end + 1
        }
        return rules
    }

    fun parseFontFaces(css: String, resolveUrl: (String) -> String = { it }): List<EpubFontFace> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        val faces = arrayListOf<EpubFontFace>()
        var index = 0
        while (index < cleanCss.length) {
            val at = cleanCss.indexOf("@font-face", index, ignoreCase = true)
            if (at < 0) break
            val start = cleanCss.indexOf('{', at)
            if (start < 0) break
            val end = cleanCss.findMatchingCssBrace(start)
            if (end < 0) break
            val declarations = declarations(cleanCss.substring(start + 1, end))
            val family = declarations["font-family"]?.cleanCssFontFamily()
            val src = declarations["src"]?.extractCssUrl()?.let(resolveUrl)
            if (!family.isNullOrBlank() && !src.isNullOrBlank()) {
                faces.add(
                    EpubFontFace(
                        family = family,
                        src = src,
                        weight = declarations["font-weight"],
                        style = declarations["font-style"]
                    )
                )
            }
            index = end + 1
        }
        return faces
    }

    fun parseRules(css: String, supportedOnly: Boolean = true): List<Rule> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .expandSupportedAtRules()
        val rules = arrayListOf<Rule>()
        var order = 0
        var index = 0
        while (index < cleanCss.length) {
            val start = cleanCss.indexOf('{', index)
            if (start < 0) break
            val end = cleanCss.findMatchingCssBrace(start)
            if (end < 0) break
            val selectorText = cleanCss.substring(index, start)
            val declarations = if (supportedOnly) {
                normalizeSupportedDeclarations(cleanCss.substring(start + 1, end))
            } else {
                parseDeclarations(cleanCss.substring(start + 1, end))
            }
            val style = declarations.toStyleString()
            if (style.isNotBlank()) {
                selectorText.split(',')
                    .map { it.trim() }
                    .mapNotNull { it.toSupportedSelector() }
                    .forEach { selector ->
                        rules.add(Rule(selector, style, selector.cssSpecificity(), order, declarations))
                    }
                order++
            }
            index = end + 1
        }
        return rules
    }

    fun normalizeSupportedStyle(style: String): String {
        return normalizeSupportedDeclarations(style).toStyleString()
    }

    fun normalizeSupportedDeclarations(style: String): List<Declaration> {
        return parseDeclarations(style)
            .expandBoxShorthand()
            .filter { it.name in supportedProperties }
    }

    fun declarations(style: String): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        parseDeclarations(style).forEach { declaration ->
            map[declaration.name] = declaration.value
        }
        return map
    }

    fun parseDeclarations(style: String): List<Declaration> {
        val declarations = arrayListOf<Declaration>()
        splitDeclarations(style).forEach { item ->
            val index = item.indexOf(':')
            if (index <= 0) return@forEach
            val name = item.substring(0, index).trim().lowercase(Locale.ROOT)
            val rawValue = item.substring(index + 1)
            val importantIndex = rawValue.indexOf("!important", ignoreCase = true)
            val important = importantIndex >= 0
            val value = (if (importantIndex >= 0) rawValue.substring(0, importantIndex) else rawValue)
                .trim()
                .replace("\"", "'")
            if (name.isNotBlank() && value.isNotBlank()) {
                val normalizedName = if (name == "duokan-text-indent") "text-indent" else name
                val normalizedValue = if (name == "duokan-text-indent") {
                    splitValueList(value).lastOrNull().orEmpty().ifBlank { value }
                } else {
                    value
                }
                declarations.add(Declaration(normalizedName, normalizedValue, important, declarations.size))
            }
        }
        return declarations
            .expandFontShorthand()
            .expandTextDecorationShorthand()
            .expandBorderShorthand()
            .expandBorderRadiusShorthand()
            .expandBackgroundShorthand()
            .expandListStyleShorthand()
    }

    fun splitDeclarations(style: String): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in style.indices) {
            val char = style[index]
            if (quote != null) {
                if (char == quote && style.getOrNull(index - 1) != '\\') {
                    quote = null
                }
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ';' -> if (parenDepth == 0) {
                    result.add(style.substring(start, index))
                    start = index + 1
                }
            }
        }
        if (start <= style.lastIndex) {
            result.add(style.substring(start))
        }
        return result
    }

    fun splitValueList(value: String): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in value.indices) {
            val char = value[index]
            if (quote != null) {
                if (char == quote && value.getOrNull(index - 1) != '\\') {
                    quote = null
                }
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ' ', '\t', '\r', '\n' -> if (parenDepth == 0) {
                    val item = value.substring(start, index).trim()
                    if (item.isNotBlank()) result.add(item)
                    start = index + 1
                }
            }
        }
        val last = value.substring(start).trim()
        if (last.isNotBlank()) result.add(last)
        return result
    }

    fun LinkedHashMap<String, String>.expandBoxShorthand(): LinkedHashMap<String, String> {
        expandBoxShorthand("margin")
        expandBoxShorthand("padding")
        return this
    }

    private fun List<Declaration>.expandBoxShorthand(): List<Declaration> {
        val expanded = linkedMapOf<String, Declaration>()
        forEach { declaration ->
            expanded[declaration.name] = declaration
            if (declaration.name == "margin" || declaration.name == "padding") {
                val values = splitValueList(declaration.value).takeIf { it.isNotEmpty() } ?: return@forEach
                val top = values.getOrNull(0).orEmpty()
                val right = values.getOrNull(1) ?: top
                val bottom = values.getOrNull(2) ?: top
                val left = values.getOrNull(3) ?: right
                listOf(
                    "${declaration.name}-top" to top,
                    "${declaration.name}-right" to right,
                    "${declaration.name}-bottom" to bottom,
                    "${declaration.name}-left" to left
                ).forEach { (name, value) ->
                    expanded.putIfAbsent(
                        name,
                        declaration.copy(name = name, value = value, order = expanded.size)
                    )
                }
            }
        }
        return expanded.values.toList()
    }

    private fun List<Declaration>.expandBorderShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded.add(declaration)
            val sides = when (declaration.name) {
                "border" -> listOf("top", "right", "bottom", "left")
                "border-top" -> listOf("top")
                "border-right" -> listOf("right")
                "border-bottom" -> listOf("bottom")
                "border-left" -> listOf("left")
                else -> emptyList()
            }
            if (sides.isEmpty()) return@forEach
            val tokens = splitValueList(declaration.value)
            val width = tokens.firstOrNull { it.isCssBorderWidthToken() }
            val style = tokens.firstOrNull { it.lowercase(Locale.ROOT) in borderStyles }
            val color = tokens.firstOrNull { it.isCssColorToken() }
            sides.forEach { side ->
                width?.let {
                    expanded.add(declaration.copy(name = "border-$side-width", value = it, order = expanded.size))
                }
                style?.let {
                    expanded.add(declaration.copy(name = "border-$side-style", value = it, order = expanded.size))
                }
                color?.let {
                    expanded.add(declaration.copy(name = "border-$side-color", value = it, order = expanded.size))
                }
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandBorderRadiusShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded.add(declaration)
            if (declaration.name != "border-radius") return@forEach
            val horizontal = declaration.value.substringBefore('/').trim()
            val values = splitValueList(horizontal).takeIf { it.isNotEmpty() } ?: return@forEach
            val topLeft = values.getOrNull(0).orEmpty()
            val topRight = values.getOrNull(1) ?: topLeft
            val bottomRight = values.getOrNull(2) ?: topLeft
            val bottomLeft = values.getOrNull(3) ?: topRight
            listOf(
                "border-top-left-radius" to topLeft,
                "border-top-right-radius" to topRight,
                "border-bottom-right-radius" to bottomRight,
                "border-bottom-left-radius" to bottomLeft
            ).forEach { (name, value) ->
                expanded.add(declaration.copy(name = name, value = value, order = expanded.size))
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandTextDecorationShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded.add(declaration)
            if (declaration.name != "text-decoration") return@forEach
            val tokens = splitValueList(declaration.value)
            val line = tokens.filter { it.lowercase(Locale.ROOT) in textDecorationLines }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            val style = tokens.firstOrNull { it.lowercase(Locale.ROOT) in textDecorationStyles }
            val color = tokens.firstOrNull { it.isCssColorToken() }
            line?.let {
                expanded.add(declaration.copy(name = "text-decoration-line", value = it, order = expanded.size))
            }
            style?.let {
                expanded.add(declaration.copy(name = "text-decoration-style", value = it, order = expanded.size))
            }
            color?.let {
                expanded.add(declaration.copy(name = "text-decoration-color", value = it, order = expanded.size))
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandBackgroundShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded.add(declaration)
            if (declaration.name != "background") return@forEach
            val value = declaration.value
            value.extractCssUrl()?.let { url ->
                expanded.add(declaration.copy(name = "background-image", value = "url('$url')", order = expanded.size))
            }
            value.extractCssColor()?.let { color ->
                expanded.add(declaration.copy(name = "background-color", value = color, order = expanded.size))
            }
            value.extractBackgroundRepeat()?.let { repeat ->
                expanded.add(declaration.copy(name = "background-repeat", value = repeat, order = expanded.size))
            }
            value.extractBackgroundPosition()?.let { position ->
                expanded.add(declaration.copy(name = "background-position", value = position, order = expanded.size))
            }
            value.extractBackgroundSize()?.let { size ->
                expanded.add(declaration.copy(name = "background-size", value = size, order = expanded.size))
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandListStyleShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded.add(declaration)
            if (declaration.name != "list-style") return@forEach
            val tokens = splitValueList(declaration.value)
            val type = tokens.firstOrNull { it.lowercase(Locale.ROOT) in listStyleTypes }
            val position = tokens.firstOrNull {
                val lower = it.lowercase(Locale.ROOT)
                lower == "inside" || lower == "outside"
            }
            val image = declaration.value.extractCssUrl()
            type?.let {
                expanded.add(declaration.copy(name = "list-style-type", value = it, order = expanded.size))
            }
            position?.let {
                expanded.add(declaration.copy(name = "list-style-position", value = it, order = expanded.size))
            }
            image?.let {
                expanded.add(declaration.copy(name = "list-style-image", value = "url('$it')", order = expanded.size))
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandFontShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded.add(declaration)
            if (declaration.name != "font") return@forEach
            val parsed = declaration.value.parseFontShorthand()
            parsed.forEach { (name, value) ->
                expanded.add(
                    declaration.copy(
                        name = name,
                        value = value,
                        order = expanded.size
                    )
                )
            }
        }
        return expanded
    }

    private fun String.parseFontShorthand(): List<Pair<String, String>> {
        val tokens = splitValueList(this)
        if (tokens.isEmpty()) return emptyList()
        val result = arrayListOf<Pair<String, String>>()
        var sizeTokenIndex = -1
        tokens.forEachIndexed { index, token ->
            val lower = token.lowercase(Locale.ROOT)
            when {
                lower == "italic" || lower == "oblique" -> result.add("font-style" to lower)
                lower == "bold" || lower == "bolder" || lower == "lighter" ||
                    lower.toIntOrNull()?.let { it in 100..1000 } == true -> {
                    result.add("font-weight" to lower)
                }
                sizeTokenIndex < 0 && lower.containsFontSizeToken() -> {
                    sizeTokenIndex = index
                    val parts = lower.split('/', limit = 2)
                    result.add("font-size" to parts[0])
                    parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { lineHeight ->
                        result.add("line-height" to lineHeight)
                    }
                }
            }
        }
        if (sizeTokenIndex >= 0 && sizeTokenIndex + 1 < tokens.size) {
            val family = tokens.drop(sizeTokenIndex + 1)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            family?.let { result.add("font-family" to it) }
        }
        return result
    }

    private fun String.containsFontSizeToken(): Boolean {
        val sizePart = substringBefore('/')
        return sizePart.endsWith("em") ||
            sizePart.endsWith("rem") ||
            sizePart.endsWith("px") ||
            sizePart.endsWith("pt") ||
            sizePart.endsWith("%") ||
            sizePart in setOf(
                "xx-small", "x-small", "small", "medium", "large",
                "x-large", "xx-large", "smaller", "larger"
            ) ||
            sizePart.toFloatOrNull() != null
    }

    private fun String.isCssBorderWidthToken(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower in borderWidthKeywords ||
            lower.endsWith("px") ||
            lower.endsWith("em") ||
            lower.endsWith("rem") ||
            lower.endsWith("pt") ||
            lower.endsWith("pc") ||
            lower.endsWith("in") ||
            lower.endsWith("cm") ||
            lower.endsWith("mm") ||
            lower.endsWith("vw") ||
            lower.endsWith("vh") ||
            lower.endsWith("vmin") ||
            lower.endsWith("vmax") ||
            lower.endsWith("%") ||
            lower.toFloatOrNull() != null
    }

    private fun String.isCssColorToken(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.startsWith("#") ||
            lower.startsWith("rgb") ||
            lower.startsWith("hsl") ||
            lower == "transparent" ||
            lower in namedColorTokens
    }

    fun String.extractCssUrl(): String? {
        val start = indexOf("url(", ignoreCase = true)
        if (start < 0) return null
        val end = indexOf(')', start + 4)
        if (end < 0) return null
        return substring(start + 4, end)
            .trim()
            .trim('\'', '"')
            .takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
    }

    fun String.cleanCssFontFamily(): String {
        return split(',')
            .firstOrNull()
            .orEmpty()
            .trim()
            .trim('\'', '"')
    }

    private fun String.extractCssColor(): String? {
        val clean = trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        clean.extractGradientColor()?.let { return it }
        val parts = splitValueList(clean)
        return parts.firstOrNull { it.isCssColorToken() }
    }

    private fun String.extractGradientColor(): String? {
        if (!contains("gradient(", ignoreCase = true)) return null
        val colorPattern = Regex(
            "(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|hsla?\\([^)]*\\)|\\b(?:black|white|red|green|blue|cyan|aqua|magenta|fuchsia|yellow|gray|grey|silver|maroon|purple|teal|navy|orange|transparent)\\b)",
            RegexOption.IGNORE_CASE
        )
        return colorPattern.find(this)?.value
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
        return splitValueList(substring(slash + 1))
            .takeWhile { token ->
                val lower = token.lowercase(Locale.ROOT)
                lower !in backgroundRepeatTokens &&
                    lower !in backgroundAttachmentTokens &&
                    !token.isCssColorToken()
            }
            .take(2)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    private fun String.extractBackgroundPosition(): String? {
        val beforeSlash = substringBefore('/')
        val tokens = splitValueList(beforeSlash)
            .filterNot { token ->
                val lower = token.lowercase(Locale.ROOT)
                token.extractCssUrl() != null ||
                    token.isCssColorToken() ||
                    lower in backgroundRepeatTokens ||
                    lower in backgroundAttachmentTokens
            }
        val positionTokens = tokens.filter { token ->
            val lower = token.lowercase(Locale.ROOT)
            lower in backgroundPositionTokens ||
                token.endsWith("%") ||
                lower.endsWith("px") ||
                lower.endsWith("em") ||
                lower.endsWith("rem") ||
                lower.endsWith("pt") ||
                lower.endsWith("pc") ||
                lower.endsWith("in") ||
                lower.endsWith("cm") ||
                lower.endsWith("mm") ||
                lower.endsWith("vw") ||
                lower.endsWith("vh") ||
                lower.endsWith("vmin") ||
                lower.endsWith("vmax") ||
                token.toFloatOrNull() != null
        }
        return positionTokens.take(2).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun List<Declaration>.toStyleString(): String {
        return joinToString(";") { declaration ->
            buildString {
                append(declaration.name)
                append(':')
                append(declaration.value)
                if (declaration.important) {
                    append(" !important")
                }
            }
        }
    }

    private fun LinkedHashMap<String, String>.expandBoxShorthand(name: String) {
        val shorthand = this[name] ?: return
        val values = splitValueList(shorthand).takeIf { it.isNotEmpty() } ?: return
        val top = values.getOrNull(0).orEmpty()
        val right = values.getOrNull(1) ?: top
        val bottom = values.getOrNull(2) ?: top
        val left = values.getOrNull(3) ?: right
        putIfAbsent("$name-top", top)
        putIfAbsent("$name-right", right)
        putIfAbsent("$name-bottom", bottom)
        putIfAbsent("$name-left", left)
    }

    private fun String.expandSupportedAtRules(): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val at = indexOf('@', index)
            if (at < 0) {
                builder.append(substring(index))
                break
            }
            builder.append(substring(index, at))
            val nameEnd = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '{', ';'), at + 1)
                .takeIf { it >= 0 } ?: length
            val name = substring(at + 1, nameEnd).trim().lowercase(Locale.ROOT)
            val blockStart = indexOf('{', nameEnd)
            val semicolon = indexOf(';', nameEnd).takeIf { it >= 0 }
            if (blockStart < 0 || (semicolon != null && semicolon < blockStart)) {
                index = (semicolon ?: nameEnd) + 1
                continue
            }
            val blockEnd = findMatchingCssBrace(blockStart)
            if (blockEnd < 0) {
                break
            }
            if (name == "media" || name == "supports") {
                builder.append(substring(blockStart + 1, blockEnd))
            }
            index = blockEnd + 1
        }
        return builder.toString()
    }

    private fun String.findMatchingCssBrace(start: Int): Int {
        var depth = 0
        var quote: Char? = null
        var index = start
        while (index < length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') {
                    quote = null
                }
                index++
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun String.toSupportedSelector(): String? {
        val selector = trim()
            .dropUnsupportedSelectorPseudo()
            .replace(Regex("\\s+>\\s+"), " > ")
            .replace(Regex("\\s*\\+\\s*"), " + ")
            .replace(Regex("\\s*~\\s*"), " ~ ")
            .replace("|", "\\:")
        if (selector.isBlank()) return null
        return selector.takeIf {
            it.indexOfAny(charArrayOf('{', '}', ';')) < 0
        }
    }

    private fun String.dropUnsupportedSelectorPseudo(): String {
        val builder = StringBuilder(length)
        var index = 0
        var bracketDepth = 0
        while (index < length) {
            val char = this[index]
            when {
                char == '[' -> {
                    bracketDepth++
                    builder.append(char)
                    index++
                }
                char == ']' -> {
                    if (bracketDepth > 0) bracketDepth--
                    builder.append(char)
                    index++
                }
                char == ':' && bracketDepth == 0 -> {
                    index++
                    while (index < length && (this[index].isLetterOrDigit() || this[index] == '-' || this[index] == '_')) {
                        index++
                    }
                    if (index < length && this[index] == '(') {
                        val end = findMatchingParenthesis(index)
                        index = if (end >= 0) end + 1 else length
                    }
                }
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return builder.toString().trim()
    }

    private fun String.findMatchingParenthesis(start: Int): Int {
        var depth = 0
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
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun String.cssSpecificity(): Int {
        val ids = count { it == '#' }
        val classes = count { it == '.' } + count { it == '[' }
        val tags = split(Regex("[\\s>]+")).count { part ->
            part.isNotBlank() && !part.startsWith(".") && !part.startsWith("#") && part != "*"
        }
        return ids * 100 + classes * 10 + tags
    }

    private val borderStyles = setOf(
        "none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset"
    )

    private val borderWidthKeywords = setOf("thin", "medium", "thick")

    private val textDecorationLines = setOf("none", "underline", "overline", "line-through", "blink")

    private val textDecorationStyles = setOf("solid", "double", "dotted", "dashed", "wavy")

    private val backgroundRepeatTokens = setOf("repeat", "repeat-x", "repeat-y", "no-repeat", "space", "round")

    private val backgroundAttachmentTokens = setOf("scroll", "fixed", "local")

    private val backgroundPositionTokens = setOf("left", "center", "right", "top", "bottom")

    private val listStyleTypes = setOf(
        "none", "disc", "circle", "square", "decimal", "decimal-leading-zero",
        "lower-roman", "upper-roman", "lower-alpha", "upper-alpha", "lower-latin", "upper-latin",
        "cjk-ideographic", "simp-chinese-informal", "simp-chinese-formal"
    )

    private val namedColorTokens = setOf(
        "black", "white", "red", "green", "blue", "cyan", "aqua", "magenta", "fuchsia",
        "yellow", "gray", "grey", "silver", "maroon", "purple", "teal", "navy", "orange"
    )
}
