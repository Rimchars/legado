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
        "text-indent",
        "text-decoration",
        "line-height",
        "letter-spacing",
        "word-spacing",
        "white-space",
        "vertical-align",
        "page-break-before",
        "page-break-after",
        "break-before",
        "break-after",
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
        "background-repeat",
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
        "width",
        "height",
        "min-width",
        "min-height",
        "max-width",
        "max-height"
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

    fun parseRules(css: String): List<Rule> {
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
            val declarations = normalizeSupportedDeclarations(cleanCss.substring(start + 1, end))
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
                declarations.add(Declaration(name, value, important, declarations.size))
            }
        }
        return declarations
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
            .replace("|", "\\:")
        if (selector.isBlank()) return null
        if (selector.hasUnsupportedSelectorCombinator()) return null
        return selector.takeIf {
            it.matches(Regex("[a-zA-Z0-9_#.*%\\-\\s>\\[\\]=~\\^$|:'\",\\\\]+"))
        }
    }

    private fun String.hasUnsupportedSelectorCombinator(): Boolean {
        var bracketDepth = 0
        for (char in this) {
            when (char) {
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '+', '~' -> if (bracketDepth == 0) return true
            }
        }
        return false
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
}
