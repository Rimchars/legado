package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isXml
import io.legado.app.utils.printOnDebug
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

class EpubFile(var book: Book) {

    companion object : BaseLocalBookParse {
        const val HTML_CONTENT_FLAG = "<usehtml data-epub-render=\"4\">"
        private var eFile: EpubFile? = null

        @Synchronized
        private fun getEFile(book: Book): EpubFile {
            if (eFile == null || eFile?.book?.bookUrl != book.bookUrl) {
                eFile = EpubFile(book)
                //对于Epub文件默认不启用替换
                //io.legado.app.data.entities.Book getUseReplaceRule
                return eFile!!
            }
            eFile?.book = book
            return eFile!!
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(
            book: Book,
            href: String
        ): InputStream? {
            return getEFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            return getEFile(book).upBookInfo()
        }

        fun clear() {
            eFile = null
        }
    }

    private var mCharset: Charset = Charset.defaultCharset()

    /**
     *持有引用，避免被回收
     */
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var epubBook: EpubBook? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = readEpub()
            }
            return field
        }
    private var epubBookContents: List<Resource>? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = epubBook?.contents
            }
            return field
        }

    init {
        upBookCover(true)
    }

    /**
     * 重写epub文件解析代码，直接读出压缩包文件生成Resources给epublib，这样的好处是可以逐一修改某些文件的格式错误
     */
    private fun readEpub(): EpubBook? {
        return kotlin.runCatching {
            //ContentScheme拷贝到私有文件夹采用懒加载防止OOM
            //val zipFile = BookHelp.getEpubFile(book)
            BookHelp.getBookPFD(book)?.let {
                fileDescriptor = it
                val zipFile = AndroidZipFile(it, book.originName)
                EpubReader().readEpubLazy(zipFile, "utf-8")
            }


        }.onFailure {
            AppLog.put("读取Epub文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrThrow()
    }

    private fun getContent(chapter: BookChapter): String? {
        if (chapter.isVolume && chapter.url.startsWith("skip:")) return ""
        /*获取当前章节文本*/
        val contents = epubBookContents ?: return null
        val nextChapterFirstResourceHref = chapter.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = chapter.startFragmentId
        val endFragmentId = chapter.endFragmentId
        val elements = Elements()
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        /*一些书籍依靠href索引的resource会包含多个章节，需要依靠fragmentId来截取到当前章节的内容*/
        /*注:这里较大增加了内容加载的时间，所以首次获取内容后可存储到本地cache，减少重复加载*/
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.href) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                elements.add(
                    getBody(res, startFragmentId, endFragmentId)
                )
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.href == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.href) {
                // 其余部分
                elements.add(getBody(res, null, null))
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    //有Fragment 则添加到上一章节
                    elements.add(getBody(res, null, endFragmentId))
                }
                break
            }
        }
        //title标签中的内容不需要显示在正文中，去除
        elements.select("title").remove()
        elements.select("[style*=display:none], [style*=display: none]").remove()
        elements.select("img[src=\"cover.jpeg\"]").forEachIndexed { i, it ->
            if (i > 0) it.remove()
        }
        val tag = Book.rubyTag
        if (book.getDelTag(tag)) {
            elements.select("rp, rt").remove()
        }
        val html = elements.joinToString("\n") { element ->
            element.html().trim()
        }.trim()
        if (html.isBlank()) {
            return HtmlFormatter.formatKeepImg(elements.outerHtml())
        }
        return "$HTML_CONTENT_FLAG${html.compactForUseHtml()}</usehtml>"
    }

    private fun getBody(res: Resource, startFragmentId: String?, endFragmentId: String?): Element {
        /**
         * <image width="1038" height="670" xlink:href="..."/>
         * ...titlepage.xhtml
         * 大多数epub文件的封面页都会带有cover，可以一定程度上解决封面读取问题
         */
        if (res.href.contains("titlepage.xhtml") ||
            res.href.contains("cover")
        ) {
            return Jsoup.parseBodyFragment("<img src=\"cover.jpeg\" />")
        }

        // Jsoup可能会修复不规范的xhtml文件 解析处理后再获取
        var doc = Jsoup.parse(String(res.data, mCharset))
        var bodyElement = doc.body()
        doc.select("script").remove()
        // 获取body对应的文本
        var bodyString = bodyElement.outerHtml()
        val originBodyString = bodyString
        /**
         * 某些xhtml文件 章节标题和内容不在一个节点或者不是兄弟节点
         * <div>
         *    <a class="mulu1>目录1</a>
         * </div>
         * <p>....</p>
         * <div>
         *    <a class="mulu2>目录2</a>
         * </div>
         * <p>....</p>
         * 先找到FragmentId对应的Element 然后直接截取之间的html
         */
        if (!startFragmentId.isNullOrBlank()) {
            bodyElement.getElementById(startFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = tagStart + bodyString.substringAfter(tagStart)
            }
        }
        if (!endFragmentId.isNullOrBlank() && endFragmentId != startFragmentId) {
            bodyElement.getElementById(endFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = bodyString.substringBefore(tagStart)
            }
        }
        //截取过再重新解析
        if (bodyString != originBodyString) {
            doc = Jsoup.parse(bodyString)
            bodyElement = doc.body()
        }
        // EPUB 的标题本身通常带有排版样式，原生阅读器不再删除 h1-h6 或插入统一标题。
        bodyElement.select("image").forEach {
            it.tagName("img", Parser.NamespaceHtml)
            it.attr("src", it.attr("xlink:href").ifBlank { it.attr("href") })
        }
        bodyElement.applyEpubCss(doc, res)
        bodyElement.propagateEpubInheritedStyles()
        bodyElement.select("[style]")
            .sortedByDescending { it.parents().size }
            .forEach { element ->
                element.applyEpubInlineStyle()
            }
        if (bodyElement.hasAttr("style")) {
            bodyElement.applyEpubInlineStyle()
        }
        bodyElement.materializeBackgroundImages(res)
        bodyElement.markSingleImagePage()
        bodyElement.select("img").forEach {
            val src = it.attr("src").trim()
            val resolvedHref = resolveEpubResourceHref(res.href, src)
            val alt = it.attr("alt")
            val options = it.epubImageOptions()
            val isBackground = it.attr("data-epub-background") == "true"
            it.clearAttributes()
            it.attr("src", resolvedHref)
            if (isBackground) {
                it.attr("data-epub-background", "true")
            }
            if (alt.isNotBlank()) {
                it.attr("alt", alt)
            }
            options["width"]?.let { width ->
                it.attr("data-legado-width", width)
            }
            options["style"]?.let { style ->
                it.attr("data-legado-style", style)
            }
        }
        bodyElement.select("a[href]").forEach {
            val href = it.attr("href").trim()
            if (href.isNotBlank() && !href.startsWith("#")) {
                val baseHref = res.href.encodeURI()
                val resolvedHref = URLDecoder.decode(URI(baseHref).resolve(href.encodeURI()).toString(), "UTF-8")
                it.attr("href", resolvedHref)
            }
        }
        return bodyElement
    }

    private fun Element.applyEpubCss(doc: Document, res: Resource) {
        val rules = runCatching {
            val parsedRules = arrayListOf<CssRule>()
            doc.head()?.select("style")?.forEach { styleElement ->
                parsedRules.addAll(parseCssRules(styleElement.data().ifBlank { styleElement.html() }))
            }
            doc.head()?.select("link[href][rel~=stylesheet]")?.forEach { link ->
                val href = link.attr("href").trim()
                if (href.isNotBlank()) {
                    parsedRules.addAll(parseCssRules(loadCss(res.href, href)))
                }
            }
            select("style").forEach { styleElement ->
                parsedRules.addAll(parseCssRules(styleElement.data().ifBlank { styleElement.html() }))
                styleElement.remove()
            }
            select("link[href][rel~=stylesheet]").forEach { link ->
                val href = link.attr("href").trim()
                if (href.isNotBlank()) {
                    parsedRules.addAll(parseCssRules(loadCss(res.href, href)))
                }
                link.remove()
            }
            parsedRules
        }.onFailure {
            AppLog.put("Epub CSS 解析失败, 已忽略样式\n${it.localizedMessage}", it)
        }.getOrDefault(emptyList())
        if (rules.isEmpty()) return
        rules.sortedWith(compareBy<CssRule> { it.specificity }.thenBy { it.order }).forEach { rule ->
            runCatching {
                if (this.`is`(rule.selector)) {
                    mergeInlineStyle(rule.style)
                }
                select(rule.selector).forEach { element ->
                    element.mergeInlineStyle(rule.style)
                }
            }
        }
    }

    private fun loadCss(baseHref: String, href: String): String {
        return runCatching {
            val resolvedHref = URLDecoder.decode(
                URI(baseHref.encodeURI()).resolve(href.encodeURI()).toString(),
                "UTF-8"
            )
            epubBook?.resources?.getByHref(resolvedHref)?.data?.let {
                String(it, mCharset).absolutizeCssUrls(resolvedHref)
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun String.absolutizeCssUrls(cssHref: String): String {
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
            val end = indexOf(')', valueStart)
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
                URLDecoder.decode(
                    URI(cssHref.encodeURI()).resolve(clean.encodeURI()).toString(),
                    "UTF-8"
                )
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

    private fun parseCssRules(css: String): List<CssRule> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .expandSupportedAtRules()
        val rules = arrayListOf<CssRule>()
        var order = 0
        var index = 0
        while (index < cleanCss.length) {
            val start = cleanCss.indexOf('{', index)
            if (start < 0) break
            val end = cleanCss.findMatchingCssBrace(start)
            if (end < 0) break
            val selectorText = cleanCss.substring(index, start)
            val style = normalizeSupportedCss(cleanCss.substring(start + 1, end))
            if (style.isNotBlank()) {
                selectorText.split(',')
                    .map { it.trim() }
                    .mapNotNull { it.toSupportedSelector() }
                    .forEach { selector ->
                        rules.add(CssRule(selector, style, selector.cssSpecificity(), order))
                    }
                order++
            }
            index = end + 1
        }
        return rules
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
                if (char == quote && this.getOrNull(index - 1) != '\\') {
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

    private fun normalizeSupportedCss(style: String): String {
        val supported = setOf(
            "text-align",
            "color",
            "font-weight",
            "font-style",
            "font-size",
            "font-family",
            "text-indent",
            "text-decoration",
            "line-height",
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
            "display",
            "background",
            "background-image",
            "background-color",
            "background-size",
            "background-position",
            "border",
            "border-left",
            "border-right",
            "border-top",
            "border-bottom",
            "border-radius",
            "width",
            "height",
            "max-width",
            "max-height"
        )
        return style.toCssDeclarationMap()
            .filterKeys { it in supported }
            .entries
            .joinToString(";") { (name, value) -> "$name:$value" }
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

    private fun Element.mergeInlineStyle(style: String) {
        if (style.isBlank()) return
        val merged = linkedMapOf<String, String>()
        merged.putAll(attr("style").toCssDeclarationMap())
        merged.putAll(style.toCssDeclarationMap())
        attr("style", merged.entries.joinToString(";") { (name, value) -> "$name:$value" })
    }

    private fun Element.propagateEpubInheritedStyles() {
        val inheritable = setOf(
            "color",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "line-height",
            "text-align",
            "text-decoration",
            "text-indent"
        )
        fun Element.walkWithInherited(parentStyle: Map<String, String>) {
            val ownStyle = attr("style").toCssDeclarationMap()
            val inherited = parentStyle.filterKeys { it in inheritable }
            var changed = false
            inherited.forEach { (name, value) ->
                if (!ownStyle.containsKey(name)) {
                    ownStyle[name] = value
                    changed = true
                }
            }
            if (changed) {
                attr("style", ownStyle.entries.joinToString(";") { (name, value) -> "$name:$value" })
            }
            val nextInherited = ownStyle.filterKeys { it in inheritable }
            children().forEach { child ->
                child.walkWithInherited(nextInherited)
            }
        }
        children().forEach { child ->
            child.walkWithInherited(attr("style").toCssDeclarationMap())
        }
    }

    private fun String.toCssDeclarationMap(): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        splitCssDeclarations().forEach { item ->
            val index = item.indexOf(':')
            if (index <= 0) return@forEach
            val name = item.substring(0, index).trim().lowercase(Locale.ROOT)
            val rawValue = item.substring(index + 1)
            val importantIndex = rawValue.indexOf("!important", ignoreCase = true)
            val value = (if (importantIndex >= 0) rawValue.substring(0, importantIndex) else rawValue)
                .trim()
                .replace("\"", "'")
            if (name.isNotBlank() && value.isNotBlank()) {
                map[name] = value
            }
        }
        return map
    }

    private fun String.splitCssDeclarations(): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in indices) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') {
                    quote = null
                }
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ';' -> if (parenDepth == 0) {
                    result.add(substring(start, index))
                    start = index + 1
                }
            }
        }
        if (start <= lastIndex) {
            result.add(substring(start))
        }
        return result
    }

    private fun String.compactForUseHtml(): String {
        return replace("\r", "")
            .replace("\n", " ")
            .replace(Regex(">\\s+<"), "><")
            .trim()
    }

    private fun Element.applyEpubInlineStyle() {
        val style = attr("style")
        if (style.isBlank()) return
        val declarations = style.split(';')
            .mapNotNull { item ->
                val index = item.indexOf(':')
                if (index <= 0) return@mapNotNull null
                item.substring(0, index).trim().lowercase(Locale.ROOT) to
                    item.substring(index + 1).trim()
            }.toMap()
        declarations["text-align"]?.let { align ->
            when (align.lowercase(Locale.ROOT)) {
                "center", "left", "right" -> attr("align", align.lowercase(Locale.ROOT))
            }
        }
        declarations["color"]?.let { color ->
            val normalizedColor = color.toHtmlColorAttr()
            if (normalName() == "font") {
                normalizedColor?.let { attr("color", it) }
            } else if (normalizedColor != null) {
                wrapInnerHtml("font", " color=\"$normalizedColor\"")
            }
        }
        declarations["font-weight"]?.let { weight ->
            val normalized = weight.lowercase(Locale.ROOT)
            if (normalized == "bold" || normalized.toIntOrNull()?.let { it >= 600 } == true) {
                wrapInnerHtml("b")
            }
        }
        declarations["font-style"]?.let { fontStyle ->
            if (fontStyle.equals("italic", ignoreCase = true) || fontStyle.equals("oblique", ignoreCase = true)) {
                wrapInnerHtml("i")
            }
        }
        declarations["text-decoration"]?.let { decoration ->
            val normalized = decoration.lowercase(Locale.ROOT)
            if (normalized.contains("underline")) {
                wrapInnerHtml("u")
            }
            if (normalized.contains("line-through")) {
                wrapInnerHtml("strike")
            }
        }
        declarations["display"]?.let { display ->
            if (display.equals("none", ignoreCase = true)) {
                remove()
            }
        }
        val backgroundColor = declarations["background-color"]?.toEpubColorTag()
            ?: declarations["background"]?.extractCssColor()?.toEpubColorTag()
        backgroundColor?.let { colorTag ->
            wrapInnerHtml("epubbg$colorTag")
        }
        declarations["border"]?.extractCssColor()?.toEpubColorTag()?.let { colorTag ->
            wrapInnerHtml("epubbg$colorTag")
        }
        declarations["font-size"]?.let { size ->
            val normalized = size.trim().lowercase(Locale.ROOT)
            when {
                normalized.contains("small") || normalized.endsWith("smaller") ||
                    normalized.removeSuffix("%").toFloatOrNull()?.let { it < 90f } == true ||
                    normalized.removeSuffix("em").toFloatOrNull()?.let { it < 0.9f } == true -> {
                    wrapInnerHtml("small")
                }
                normalized.contains("large") ||
                    normalized.removeSuffix("%").toFloatOrNull()?.let { it > 110f } == true ||
                    normalized.removeSuffix("em").toFloatOrNull()?.let { it > 1.1f } == true -> {
                    wrapInnerHtml("big")
                }
            }
        }
    }

    private fun Element.wrapInnerHtml(tag: String, attributes: String = "") {
        val name = normalName()
        if (name == tag || html().isBlank()) return
        html("<$tag$attributes>${html()}</$tag>")
    }

    private fun Element.materializeBackgroundImages(res: Resource) {
        val elements = linkedSetOf<Element>().apply {
            if (attr("style").contains("background", ignoreCase = true)) {
                add(this@materializeBackgroundImages)
            }
            addAll(select("[style*=background]"))
        }
        elements.forEach { element ->
            val imageHref = element.backgroundImageHref(res.href) ?: return@forEach
            if (element.normalName() != "body" && element.selectFirst("img") != null) return@forEach
            val img = Element("img")
            img.attr("src", imageHref)
            img.attr("data-legado-width", "100%")
            img.attr("data-legado-style", Book.imgStyleSingle)
            img.attr("data-epub-background", "true")
            if (element.normalName() == "body") {
                prependChild(img)
            } else {
                element.before(img)
            }
        }
    }

    private fun Element.backgroundImageHref(baseHref: String): String? {
        val style = attr("style")
        val declarations = style.split(';')
            .mapNotNull { item ->
                val index = item.indexOf(':')
                if (index <= 0) return@mapNotNull null
                item.substring(0, index).trim().lowercase(Locale.ROOT) to
                    item.substring(index + 1).trim()
            }.toMap()
        val background = declarations["background-image"]
            ?: declarations["background"]
            ?: attr("background").takeIf { it.isNotBlank() }
            ?: return null
        val url = background.extractCssUrl() ?: background.takeIf { attr("background").isNotBlank() }
        val clean = url?.trim()?.trimMatchingQuote()
            ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
            ?: return null
        return resolveEpubResourceHref(baseHref, clean)
    }

    private fun String.extractCssUrl(): String? {
        val start = indexOf("url(", ignoreCase = true)
        if (start < 0) return null
        val valueStart = start + 4
        val end = indexOf(')', valueStart)
        if (end < 0) return null
        return substring(valueStart, end).trim()
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

    private fun resolveEpubResourceHref(baseHref: String, href: String): String {
        val cleanHref = href.stripUrlOptions()
            .substringBefore("?")
            .trim()
            .trimMatchingQuote()
        if (cleanHref.startsWith("data:", true) ||
            cleanHref.startsWith("http://", true) ||
            cleanHref.startsWith("https://", true)
        ) {
            return cleanHref
        }
        findEpubResource(cleanHref)?.let { return it.href }
        val resolved = runCatching {
            URLDecoder.decode(
                URI(baseHref.encodeURI()).resolve(cleanHref.encodeURI()).toString(),
                "UTF-8"
            )
        }.getOrDefault(cleanHref)
        findEpubResource(resolved)?.let { return it.href }
        return resolved
    }

    private fun findEpubResource(href: String): Resource? {
        val clean = href.stripUrlOptions()
            .substringBefore("?")
            .trim()
            .trimMatchingQuote()
        if (clean.isBlank()) return null
        val candidates = linkedSetOf(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let { candidates.add(it) }
        candidates.toList().forEach { candidate ->
            candidates.add(candidate.trimStart('/'))
            candidates.add(candidate.encodeURI())
            runCatching { URLDecoder.decode(candidate.encodeURI(), "UTF-8") }.getOrNull()?.let {
                candidates.add(it)
            }
        }
        candidates.forEach { candidate ->
            epubBook?.resources?.getByHref(candidate)?.let { return it }
        }
        val normalized = candidates.map { it.trimStart('/').lowercase(Locale.ROOT) }.toSet()
        val fileName = clean.substringAfterLast('/').lowercase(Locale.ROOT)
        return epubBook?.resources?.all?.firstOrNull { resource ->
            val resourceHref = resource.href?.trimStart('/').orEmpty()
            val lower = resourceHref.lowercase(Locale.ROOT)
            lower in normalized || lower.endsWith("/$fileName") || lower == fileName
        }
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

    private fun String.toEpubColorTag(): String? {
        val color = toAndroidColor() ?: return null
        return "%08X".format(color)
    }

    private fun String.toHtmlColorAttr(): String? {
        val color = toAndroidColor() ?: return null
        val alpha = Color.alpha(color)
        return if (alpha == 255) {
            "#%06X".format(color and 0x00FFFFFF)
        } else {
            "#%08X".format(color)
        }
    }

    private fun String.toAndroidColor(): Int? {
        val clean = trim().trimMatchingQuote()
        return when {
            clean.startsWith("rgba", true) || clean.startsWith("rgb", true) -> clean.parseRgbCssColor()
            clean.startsWith("#") -> runCatching { Color.parseColor(clean.normalizeHexColor()) }.getOrNull()
            else -> clean.toNamedCssColor()?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        }
    }

    private fun String.normalizeHexColor(): String {
        val hex = trim().removePrefix("#")
        return when (hex.length) {
            3 -> "#" + hex.map { "$it$it" }.joinToString("")
            4 -> "#" + hex.map { "$it$it" }.joinToString("")
            else -> "#$hex"
        }
    }

    private fun String.parseRgbCssColor(): Int? {
        val start = indexOf('(')
        val end = lastIndexOf(')')
        if (start < 0 || end <= start) return null
        val parts = substring(start + 1, end)
            .split(',', ' ', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 3) return null
        fun component(value: String): Int {
            return if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f).toInt()
            } else {
                value.toFloatOrNull()?.toInt() ?: 0
            }.coerceIn(0, 255)
        }
        val alpha = parts.getOrNull(3)?.let { value ->
            if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 100f) * 2.55f).toInt()
            } else {
                ((value.toFloatOrNull() ?: 1f) * 255f).toInt()
            }
        } ?: 255
        return Color.argb(alpha.coerceIn(0, 255), component(parts[0]), component(parts[1]), component(parts[2]))
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

    private fun Element.markSingleImagePage() {
        val images = select("img")
        if (images.size != 1) return
        val text = text().trim()
        if (text.isNotBlank()) return
        images.first()?.attr("data-epub-single-page", "true")
    }

    private fun Element.epubImageOptions(): Map<String, String> {
        val options = linkedMapOf<String, String>()
        val style = attr("style")
        val declarations = style.split(';')
            .mapNotNull { item ->
                val index = item.indexOf(':')
                if (index <= 0) return@mapNotNull null
                item.substring(0, index).trim().lowercase(Locale.ROOT) to
                    item.substring(index + 1).trim()
            }.toMap()
        val width = attr("width").ifBlank { declarations["width"].orEmpty() }
        if (width.isNotBlank()) {
            options["width"] = normalizeImageWidth(width)
        }
        if (attr("data-epub-single-page") == "true") {
            options["style"] = Book.imgStyleSingle
            options.putIfAbsent("width", "100%")
        }
        return options
    }

    private fun normalizeImageWidth(width: String): String {
        val clean = width.trim().lowercase(Locale.ROOT)
        return when {
            clean.endsWith("%") -> clean
            clean.endsWith("px") -> clean.dropLast(2).substringBefore(".")
            clean.toIntOrNull() != null -> clean
            else -> ""
        }.ifBlank { "100%" }
    }

    private data class CssRule(
        val selector: String,
        val style: String,
        val specificity: Int,
        val order: Int
    )

    private fun getImage(href: String): InputStream? {
        if (href == "cover.jpeg") return epubBook?.coverImage?.inputStream
        return findEpubResource(href)?.inputStream
    }

    private fun String.stripUrlOptions(): String {
        val optionStart = indexOfUrlOptions()
        return if (optionStart != null) {
            substring(0, optionStart).trim()
        } else {
            trim()
        }
    }

    private fun String.indexOfUrlOptions(): Int? {
        for (index in indices) {
            if (this[index] != ',') continue
            var next = index + 1
            while (next < length && this[next].isWhitespace()) {
                next++
            }
            if (next < length && this[next] == '{') {
                return index
            }
        }
        return null
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            epubBook?.let {
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                /*部分书籍DRM处理后，封面获取异常，待优化*/
                it.coverImage?.inputStream?.use { input ->
                    val cover = BitmapFactory.decodeStream(input)
                    val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                    cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                } ?: AppLog.putDebug("Epub: 封面获取为空. path: ${book.bookUrl}")
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (epubBook == null) {
            eFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val metadata = epubBook!!.metadata
            book.name = metadata.firstTitle
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".epub", "")
            }

            if (metadata.authors.isNotEmpty()) {
                val author =
                    metadata.authors[0].toString().replace("^, |, $".toRegex(), "")
                book.author = author
            }
            if (metadata.descriptions.isNotEmpty()) {
                val desc = metadata.descriptions[0]
                book.intro = if (desc.isXml()) {
                    Jsoup.parse(metadata.descriptions[0]).text()
                } else {
                    desc
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        epubBook?.let { eBook ->
            val refs = eBook.tableOfContents.tocReferences
            if (refs == null || refs.isEmpty()) {
                AppLog.putDebug("Epub: NCX file parse error, check the file: ${book.bookUrl}")
                val spineReferences = eBook.spine.spineReferences
                var i = 0
                val size = spineReferences.size
                while (i < size) {
                    val resource = spineReferences[i].resource
                    var title = resource.title
                    if (TextUtils.isEmpty(title)) {
                        try {
                            val doc =
                                Jsoup.parse(String(resource.data, mCharset))
                            val elements = doc.getElementsByTag("title")
                            if (elements.isNotEmpty()) {
                                title = elements[0].text()
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    val chapter = BookChapter()
                    chapter.index = i
                    chapter.bookUrl = book.bookUrl
                    chapter.url = resource.href
                    if (i == 0 && title.isEmpty()) {
                        chapter.title = "封面"
                    } else {
                        chapter.title = title
                    }
                    chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                    chapterList.add(chapter)
                    i++
                }
            } else {
                parseFirstPage(chapterList, refs)
                parseMenu(chapterList, refs, 0)
            }
        }
        normalizeChapterList(chapterList)
        getWordCount(chapterList, book)
        return chapterList
    }

    /*获取书籍起始页内容。部分书籍第一章之前存在封面，引言，扉页等内容*/
    /*tile获取不同书籍风格杂乱，格式化处理待优化*/
    private var durIndex = 0
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?
    ) {
        val contents = epubBook?.contents
        if (epubBook == null || contents == null || refs == null) return
        val firstRef = refs.firstOrNull { it.resource != null } ?: return
        var i = 0
        durIndex = 0
        while (i < contents.size) {
            val content = contents[i]
            if (!content.mediaType.toString().contains("htm")) {
                i++
                continue
            }
            /**
             * 检索到第一章href停止
             * completeHref可能有fragment(#id) 必须去除
             * fix https://github.com/gedoor/legado/issues/1932
             */
            if (firstRef.completeHref.substringBeforeLast("#") == content.href) break
            val chapter = BookChapter()
            var title = content.title
            if (TextUtils.isEmpty(title)) {
                val elements = Jsoup.parse(
                    String(epubBook!!.resources.getByHref(content.href).data, mCharset)
                ).getElementsByTag("title")
                title =
                    if (elements.isNotEmpty() && elements[0].text().isNotBlank())
                        elements[0].text()
                    else
                        "--卷首--"
            }
            chapter.bookUrl = book.bookUrl
            chapter.title = title
            chapter.url = content.href
            chapter.startFragmentId =
                if (content.href.substringAfter("#") == content.href) null
                else content.href.substringAfter("#")

            chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
            chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            durIndex++
            i++
        }
    }

    private fun parseMenu(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?,
        level: Int
    ) {
        refs?.forEach { ref ->
            if (ref.resource != null) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title
                chapter.url = ref.completeHref
                chapter.startFragmentId = ref.fragmentId
                chapter.isVolume = ref.children != null && ref.children.isNotEmpty()
                chapterList.add(chapter)
                durIndex++
            } else if (!ref.title.isNullOrBlank()) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title
                chapter.url = "skip:${chapterList.size}:${ref.title}"
                chapter.isVolume = true
                chapterList.add(chapter)
            }
            if (ref.children != null && ref.children.isNotEmpty()) {
                chapterList.lastOrNull()?.isVolume = true
                parseMenu(chapterList, ref.children, level + 1)
            }
        }
    }

    private fun normalizeChapterList(chapterList: ArrayList<BookChapter>) {
        if (chapterList.isEmpty()) return
        for (index in chapterList.indices) {
            val chapter = chapterList[index]
            chapter.index = index
            val next = chapterList.getOrNull(index + 1)
            if (chapter.isVolume &&
                next != null &&
                !chapter.url.startsWith("skip:") &&
                chapter.url.substringBeforeLast("#") == next.url.substringBeforeLast("#")
            ) {
                chapter.url = "skip:${index}:${chapter.url}"
                chapter.startFragmentId = null
                chapter.endFragmentId = null
            }
        }
        for (index in chapterList.indices) {
            val chapter = chapterList[index]
            val next = chapterList.drop(index + 1)
                .firstOrNull { !(it.isVolume && it.url.startsWith("skip:")) }
            chapter.endFragmentId = next?.startFragmentId
            chapter.putVariable("nextUrl", next?.url)
        }
    }


    protected fun finalize() {
        fileDescriptor?.close()
    }

    private fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

}
