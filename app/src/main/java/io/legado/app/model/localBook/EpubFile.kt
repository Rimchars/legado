package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Size
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isXml
import io.legado.app.utils.printOnDebug
import io.legado.app.ui.book.read.page.provider.ChapterProvider
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Executors
import splitties.init.appCtx

class EpubFile(var book: Book) {

    companion object : BaseLocalBookParse {
        const val NATIVE_CONTENT_FLAG = "<epub-native"
        const val NATIVE_LAYOUT_FLAG = "data-href="
        private const val ENABLE_EPUB_DEBUG_DUMP = false
        private const val MAX_NATIVE_DOM_CACHE = 600
        private const val MAX_NATIVE_LAYOUT_CACHE = 1200
        private var eFile: EpubFile? = null
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        private val preloadedNativeLayoutKeys = linkedSetOf<String>()
        private val globalNativeDomCache = object : LinkedHashMap<String, EpubDomDocument>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubDomDocument>?): Boolean {
                return size > MAX_NATIVE_DOM_CACHE
            }
        }
        private val globalNativeLayoutCache = object : LinkedHashMap<String, EpubLayoutDocument>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubLayoutDocument>?): Boolean {
                return size > MAX_NATIVE_LAYOUT_CACHE
            }
        }

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
        internal fun getNativeLayout(book: Book, href: String): EpubLayoutDocument? {
            return getEFile(book).getNativeLayout(href)
        }

        @Synchronized
        internal fun preloadNativeLayouts(book: Book, hrefs: List<String>) {
            if (hrefs.isEmpty()) return
            val file = getEFile(book)
            val styleKey = file.currentNativeLayoutStyleKey()
            val pendingHrefs = hrefs.distinct().filter { href ->
                val key = file.nativeLayoutCacheKey(href, ChapterProvider.visibleWidth, ChapterProvider.visibleHeight, styleKey)
                synchronized(preloadedNativeLayoutKeys) {
                    preloadedNativeLayoutKeys.add(key)
                }
            }
            if (pendingHrefs.isEmpty()) return
            preloadExecutor.execute {
                pendingHrefs.forEach { href ->
                    runCatching {
                        synchronized(file) {
                            file.getNativeLayout(href)
                        }
                    }
                }
            }
        }

        @Synchronized
        internal fun getFootnote(book: Book, href: String): EpubFootnote? {
            return getEFile(book).getFootnote(href)
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
            synchronized(preloadedNativeLayoutKeys) {
                preloadedNativeLayoutKeys.clear()
            }
        }
    }

    private var mCharset: Charset = Charset.defaultCharset()
    private val cssTextCache = linkedMapOf<String, String>()
    private val cssRuleCache = linkedMapOf<String, List<EpubCss.Rule>>()
    private val nativeDomCache = linkedMapOf<String, EpubDomDocument>()
    private val nativeLayoutCache = linkedMapOf<String, EpubLayoutDocument>()
    private val imageSizeCache = linkedMapOf<String, Size>()
    private val fontTypefaceCache = linkedMapOf<String, Typeface?>()
    private val footnoteCache = linkedMapOf<String, EpubFootnote?>()
    private val footnoteSourceCache = linkedMapOf<String, FootnoteSource?>()
    private val scheduledFullPreloadKeys = linkedSetOf<String>()
    private var nativeLayoutWidth = 0
    private var nativeLayoutHeight = 0
    private var nativeLayoutStyleKey = ""

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
    private var epubSpineContents: List<Resource>? = null
        get() {
            if (field == null || fileDescriptor == null) {
                val spineResources = epubBook?.spine?.spineReferences
                    ?.mapNotNull { it.resource }
                    ?.filter { it.href.isNotBlank() }
                    .orEmpty()
                field = spineResources.ifEmpty { epubBook?.contents.orEmpty() }
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
        val contents = epubSpineContents ?: epubBookContents ?: return null
        val nextChapterFirstResourceHref = chapter.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        findEpubResource(currentChapterFirstResourceHref)?.takeIf { it.isEpubBookInfoResource() }?.let {
            return ""
        }
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = chapter.startFragmentId
        val endFragmentId = chapter.endFragmentId
        val elements = Elements()
        val rawResources = linkedMapOf<String, String>()
        val nativeHrefs = arrayListOf<String>()
        fun collectRawResource(res: Resource) {
            nativeHrefs.add(res.href)
            if (ENABLE_EPUB_DEBUG_DUMP) {
                rawResources[res.href] = String(res.data, mCharset)
            }
        }
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        /*一些书籍依靠href索引的resource会包含多个章节，需要依靠fragmentId来截取到当前章节的内容*/
        /*注:这里较大增加了内容加载的时间，所以首次获取内容后可存储到本地cache，减少重复加载*/
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.href) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                collectRawResource(res)
                if (ENABLE_EPUB_DEBUG_DUMP) {
                    elements.add(getBody(res, startFragmentId, endFragmentId))
                }
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.href == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.href) {
                // 其余部分
                collectRawResource(res)
                if (ENABLE_EPUB_DEBUG_DUMP) {
                    elements.add(getBody(res, null, null))
                }
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    //有Fragment 则添加到上一章节
                    collectRawResource(res)
                    if (ENABLE_EPUB_DEBUG_DUMP) {
                        elements.add(getBody(res, null, endFragmentId))
                    }
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
        val html = if (ENABLE_EPUB_DEBUG_DUMP) {
            elements.joinToString("\n") { element ->
                element.html().trim()
            }.trim()
        } else {
            ""
        }
        if (ENABLE_EPUB_DEBUG_DUMP) {
            dumpEpubChapterDebug(chapter, rawResources, html)
        }
        if (nativeHrefs.isEmpty()) {
            AppLog.put("EPUB Native Content empty: chapter=${chapter.index}:${chapter.title}, href=$currentChapterFirstResourceHref")
        }
        val nativeHref = currentChapterFirstResourceHref.escapeXmlAttr()
        val nativeHrefList = nativeHrefs.distinct().joinToString("|") { it.escapeXmlAttr() }
        val title = chapter.title.escapeXmlAttr()
        preloadNativeLayouts(book, nativeHrefs)
        return """<epub-native data-href="$nativeHref" data-hrefs="$nativeHrefList" data-title="$title" />"""
    }

    private fun getBody(res: Resource, startFragmentId: String?, endFragmentId: String?): Element {
        /**
         * <image width="1038" height="670" xlink:href="..."/>
         * ...titlepage.xhtml
         * 大多数epub文件的封面页都会带有cover，可以一定程度上解决封面读取问题
         */
        // Jsoup可能会修复不规范的xhtml文件 解析处理后再获取
        var doc = Jsoup.parse(String(res.data, mCharset))
        var bodyElement = doc.body()
        doc.select("script").remove()
        doc.hideEpubFootnotes()
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
        bodyElement.materializeMediaElements(res)
        bodyElement.select("[style]")
            .sortedByDescending { it.parents().size }
            .forEach { element ->
                element.applyEpubInlineStyle()
            }
        if (bodyElement.hasAttr("style")) {
            bodyElement.applyEpubInlineStyle()
        }
        bodyElement.materializePageBackgroundColor()
        bodyElement.materializeBackgroundImages(res)
        bodyElement.markEpubOverlayImagePage()
        bodyElement.markEpubGalleryPage()
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
            if (!isBackground && options.isNotEmpty()) {
                it.attr("src", resolvedHref.withEpubImageOptions(options))
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
        buildNativeDom(doc, bodyElement, res)
        return bodyElement
    }

    private fun buildNativeDom(doc: Document, bodyElement: Element, res: Resource) {
        runCatching {
            val document = EpubDomBuilder(
                loadCss = ::loadCss,
                resolveHref = ::resolveEpubResourceHref
            ).build(
                doc = doc,
                body = bodyElement,
                baseHref = res.href
            )
            nativeDomCache[res.href] = document
            synchronized(globalNativeDomCache) {
                globalNativeDomCache[nativeDomCacheKey(res.href)] = document
            }
            AppLog.put(
                "EPUB Native DOM ready: href=${res.href}, " +
                    "children=${document.body.children.size}, title=${document.title.orEmpty()}"
            )
        }.onFailure {
            AppLog.putDebug("构建 EPUB 原生 DOM 失败: ${res.href}\n${it.localizedMessage}", it)
        }
    }

    private fun getFootnote(href: String): EpubFootnote? {
        val cleanHref = href.substringBeforeLast("#")
        val targetId = href.substringAfterLast("#", "")
            .decodeEpubFragment()
            .takeIf { it.isNotBlank() }
            ?: return null
        val cacheKey = "${cleanHref.ifBlank { "*" }}#$targetId"
        if (footnoteCache.containsKey(cacheKey)) {
            return footnoteCache[cacheKey]
        }
        val noteSource = findFootnoteSource(cleanHref, targetId) ?: run {
            footnoteCache[cacheKey] = null
            return null
        }
        val target = noteSource.document.getElementById(targetId)?.clone() ?: run {
            footnoteCache[cacheKey] = null
            return null
        }
        target.select("a[href]").forEach { link ->
            val linkHref = link.attr("href")
            val linkTarget = linkHref.substringAfterLast("#", "").decodeEpubFragment()
            if (linkTarget == targetId) {
                link.remove()
            } else if (linkHref.startsWith("#") || linkTarget.endsWith("-back")) {
                if (link.text().isBlank() && link.children().isEmpty()) {
                    link.remove()
                } else {
                    link.unwrap()
                }
            }
        }
        target.select("img").forEach { image ->
            val src = image.attr("src")
                .ifBlank { image.attr("data-src") }
                .ifBlank { image.attr("xlink:href") }
                .ifBlank { image.attr("href") }
                .trim()
            if (src.isNotBlank()) {
                image.attr("src", resolveEpubResourceHref(noteSource.href, src))
            }
        }
        val html = target.html().ifBlank { target.text() }.trim()
        val text = target.text().cleanEpubInfoText()
        val footnote = EpubFootnote(
            title = target.attr("title").ifBlank { "注解" },
            html = html.takeIf { it.isNotBlank() } ?: text
        ).takeIf { text.isNotBlank() || it.html.isNotBlank() }
        footnoteCache[cacheKey] = footnote
        return footnote
    }

    private fun findFootnoteSource(cleanHref: String, targetId: String): FootnoteSource? {
        val cacheKey = "${cleanHref.ifBlank { "*" }}#$targetId"
        if (footnoteSourceCache.containsKey(cacheKey)) {
            return footnoteSourceCache[cacheKey]
        }
        val primary = findEpubResource(cleanHref)?.let { resource ->
            val doc = runCatching { Jsoup.parse(String(resource.data, mCharset)) }.getOrNull()
            if (doc?.getElementById(targetId) != null) {
                FootnoteSource(resource.href, doc)
            } else {
                null
            }
        }
        if (primary != null) {
            footnoteSourceCache[cacheKey] = primary
            return primary
        }
        epubBook?.resources?.all.orEmpty().forEach { resource ->
            val href = resource.href ?: return@forEach
            val source = runCatching { String(resource.data, mCharset) }.getOrNull() ?: return@forEach
            if (!source.contains(targetId)) return@forEach
            val doc = runCatching { Jsoup.parse(source) }.getOrNull() ?: return@forEach
            if (doc.getElementById(targetId) != null) {
                return FootnoteSource(href, doc).also {
                    footnoteSourceCache[cacheKey] = it
                }
            }
        }
        footnoteSourceCache[cacheKey] = null
        return null
    }

    private fun String.decodeEpubFragment(): String {
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }

    private fun Document.hideEpubFootnotes() {
        select("aside[id], section[id], div[id], li[id]").forEach { element ->
            val type = element.attr("epub:type").ifBlank { element.attr("type") }.lowercase(Locale.ROOT)
            val role = element.attr("role").lowercase(Locale.ROOT)
            val clazz = element.className().lowercase(Locale.ROOT)
            val isNote = type.contains("footnote") ||
                type.contains("endnote") ||
                role == "doc-footnote" ||
                role == "doc-endnote" ||
                clazz.split(' ').any {
                    it == "footnote" ||
                        it == "endnote" ||
                        it == "note" ||
                        it == "duokan-footnote-content" ||
                        it == "duokan-footnote-item"
                }
            if (isNote) {
                element.attr("style", "${element.attr("style")};display:none")
            }
        }
    }

    private fun getNativeLayout(href: String): EpubLayoutDocument? {
        val width = ChapterProvider.visibleWidth
        val height = ChapterProvider.visibleHeight
        AppLog.put(
            "EPUB Native Layout enter: href=$href, view=${width}x$height, " +
                "domCache=${nativeDomCache.containsKey(href)}, layoutCache=${nativeLayoutCache.containsKey(href)}"
        )
        if (width <= 0 || height <= 0) {
            AppLog.put("EPUB Native Layout abort: 阅读区尺寸无效, href=$href, view=${width}x$height")
            return null
        }
        val styleKey = currentNativeLayoutStyleKey()
        scheduleFullNativeLayoutPreload(width, height, styleKey, href)
        if (nativeLayoutWidth != width || nativeLayoutHeight != height || nativeLayoutStyleKey != styleKey) {
            AppLog.put(
                "EPUB Native Layout cache clear: old=${nativeLayoutWidth}x$nativeLayoutHeight, " +
                    "new=${width}x$height, styleChanged=${nativeLayoutStyleKey != styleKey}"
            )
            nativeLayoutCache.clear()
            nativeLayoutWidth = width
            nativeLayoutHeight = height
            nativeLayoutStyleKey = styleKey
        }
        nativeLayoutCache[href]?.let {
            AppLog.put("EPUB Native Layout cache hit: href=$href, pages=${it.pages.size}")
            return it
        }
        val layoutCacheKey = nativeLayoutCacheKey(href, width, height, styleKey)
        synchronized(globalNativeLayoutCache) {
            globalNativeLayoutCache[layoutCacheKey]
        }?.let {
            nativeLayoutCache[href] = it
            AppLog.put("EPUB Native Layout global cache hit: href=$href, pages=${it.pages.size}")
            return it
        }
        val document = nativeDomCache[href] ?: rebuildNativeDom(href) ?: return null
        return runCatching {
            EpubLayoutEngine(
                imageSizeResolver = ::getEpubImageSize,
                fontResolver = ::getEpubTypeface,
                viewportWidth = width,
                viewportHeight = height
            ).layout(document)
        }.onSuccess {
            nativeLayoutCache[href] = it
            synchronized(globalNativeLayoutCache) {
                globalNativeLayoutCache[layoutCacheKey] = it
            }
            val linkAreas = it.pages.sumOf { page ->
                page.commands.count { command -> command is EpubLinkArea }
            }
            val linkedImages = it.pages.sumOf { page ->
                page.commands.count { command -> command is EpubImageBox && !command.linkHref.isNullOrBlank() }
            }
            val linkedText = it.pages.sumOf { page ->
                page.commands.count { command -> command is EpubTextRun && !command.linkHref.isNullOrBlank() }
            }
            AppLog.put(
                "EPUB Native Layout built: href=$href, pages=${it.pages.size}, " +
                    "commands=${it.pages.sumOf { page -> page.commands.size }}, " +
                    "linkAreas=$linkAreas, linkedImages=$linkedImages, linkedText=$linkedText"
            )
        }.onFailure {
            AppLog.putDebug("构建 EPUB 原生布局失败: $href\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun scheduleFullNativeLayoutPreload(width: Int, height: Int, styleKey: String, currentHref: String) {
        val preloadKey = "${book.bookUrl}|${width}x$height|$styleKey"
        synchronized(scheduledFullPreloadKeys) {
            if (!scheduledFullPreloadKeys.add(preloadKey)) return
        }
        val hrefs = epubSpineContents
            ?.asSequence()
            ?.filter { it.isReadableEpubResource() }
            ?.filterNot { it.isEpubBookInfoResource() }
            ?.map { it.href }
            ?.filter { it.isNotBlank() && it != currentHref }
            ?.distinct()
            ?.toList()
            .orEmpty()
        if (hrefs.isEmpty()) return
        AppLog.put("EPUB Native Layout preload all: count=${hrefs.size}, view=${width}x$height")
        preloadNativeLayouts(book, hrefs)
    }

    private fun nativeDomCacheKey(href: String): String {
        return "${book.bookUrl}|$href"
    }

    private fun nativeLayoutCacheKey(href: String, width: Int, height: Int, styleKey: String): String {
        return "${book.bookUrl}|$href|${width}x$height|$styleKey"
    }

    private fun currentNativeLayoutStyleKey(): String {
        val paint = ChapterProvider.contentPaint
        return buildString {
            append(paint.textSize)
            append('|').append(paint.color)
            append('|').append(paint.letterSpacing)
            append('|').append(paint.typeface?.hashCode() ?: 0)
            append('|').append(ChapterProvider.contentPaintTextHeight)
            append('|').append(ChapterProvider.lineSpacingExtra)
            append('|').append(ChapterProvider.paragraphSpacing)
        }
    }

    private fun rebuildNativeDom(href: String): EpubDomDocument? {
        AppLog.put("EPUB Native DOM rebuild start: href=$href")
        synchronized(globalNativeDomCache) {
            globalNativeDomCache[nativeDomCacheKey(href)]
        }?.let {
            nativeDomCache[href] = it
            AppLog.put("EPUB Native DOM global cache hit: href=$href, children=${it.body.children.size}")
            return it
        }
        val resource = findEpubResource(href) ?: run {
            AppLog.put("EPUB Native DOM rebuild failed: 找不到资源 href=$href")
            return null
        }
        return runCatching {
            getBody(resource, null, null)
            nativeDomCache[href].also { document ->
                AppLog.put(
                    "EPUB Native DOM rebuild result: href=$href, " +
                        "success=${document != null}, children=${document?.body?.children?.size ?: 0}"
                )
            }
        }.onFailure {
            AppLog.putDebug("重建 EPUB 原生 DOM 失败: $href\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun getEpubImageSize(href: String): Size? {
        val cleanHref = href.stripUrlOptions()
        imageSizeCache[cleanHref]?.let { return it }
        val data = if (cleanHref == "cover.jpeg") {
            epubBook?.coverImage?.data
        } else {
            findEpubResource(cleanHref)?.data
        } ?: return null
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val size = if (options.outWidth > 0 && options.outHeight > 0) {
            Size(options.outWidth, options.outHeight)
        } else {
            SvgUtils.getSize(ByteArrayInputStream(data))
        } ?: run {
            AppLog.put("EPUB Native Image size unknown: href=$href")
            return null
        }
        imageSizeCache[cleanHref] = size
        return size
    }

    private fun getEpubTypeface(
        family: String,
        bold: Boolean,
        italic: Boolean,
        fontFaces: List<EpubFontFace>
    ): Typeface? {
        if (fontFaces.isEmpty()) return null
        val normalizedFamily = family.trim().trim('\'', '"')
        if (normalizedFamily.isBlank()) return null
        val face = fontFaces
            .filter { it.family.equals(normalizedFamily, ignoreCase = true) }
            .minByOrNull { it.fontMatchScore(bold, italic) }
            ?: return null
        val cleanHref = face.src.stripUrlOptions()
        val cacheKey = "$cleanHref|$bold|$italic"
        return fontTypefaceCache.getOrPut(cacheKey) {
            runCatching {
                val data = findEpubResource(cleanHref)?.data ?: return@getOrPut null
                val dir = File(appCtx.cacheDir, "epub-fonts").apply { mkdirs() }
                val suffix = cleanHref.substringAfterLast('.', "ttf")
                    .takeIf { it.length in 2..5 }
                    ?: "ttf"
                val file = File(dir, "${book.bookUrl.hashCode()}_${cleanHref.hashCode()}.$suffix")
                if (!file.exists() || file.length() != data.size.toLong()) {
                    FileOutputStream(file).use { output -> output.write(data) }
                }
                val typeface = Typeface.createFromFile(file)
                val style = when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                Typeface.create(typeface, style)
            }.onFailure {
                AppLog.putDebug("加载 EPUB 内嵌字体失败: family=$family, href=$cleanHref\n${it.localizedMessage}", it)
            }.getOrNull()
        }
    }

    private fun EpubFontFace.fontMatchScore(bold: Boolean, italic: Boolean): Int {
        val weightValue = weight?.toIntOrNull()
            ?: when (weight?.trim()?.lowercase(Locale.ROOT)) {
                "bold", "bolder" -> 700
                "light", "lighter" -> 300
                else -> 400
            }
        val targetWeight = if (bold) 700 else 400
        val styleScore = if (italic == style.equals("italic", ignoreCase = true) ||
            italic == style.equals("oblique", ignoreCase = true)
        ) {
            0
        } else {
            1000
        }
        return kotlin.math.abs(weightValue - targetWeight) + styleScore
    }

    private fun dumpEpubChapterDebug(
        chapter: BookChapter,
        rawResources: Map<String, String>,
        renderedHtml: String
    ) {
        runCatching {
            val root = File(FileUtils.getSdCardPath(), "Download/阅读Archive/epub-debug")
            val bookDirName = book.name
                .ifBlank { book.originName }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(80)
            val chapterDirName = "${chapter.index}_${chapter.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(96)
            val dir = File(root, "$bookDirName/$chapterDirName")
            FileUtils.createFolderIfNotExist(dir.absolutePath)
            rawResources.forEach { (href, source) ->
                val fileName = href.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "chapter.xhtml" }
                    .takeLast(120)
                FileUtils.writeText(File(dir, "raw_$fileName").absolutePath, source)
            }
            FileUtils.writeText(File(dir, "rendered.html").absolutePath, renderedHtml)
            FileUtils.writeText(
                File(dir, "README.txt").absolutePath,
                "raw_*.xhtml/html 是 EPUB 解压后的原文；rendered.html 是应用解析 CSS 和资源路径后送入阅读器的内容。\n"
            )
        }.onFailure {
            AppLog.putDebug("写入 EPUB 调试原文失败\n${it.localizedMessage}", it)
        }
    }

    private fun Element.applyEpubCss(doc: Document, res: Resource) {
        val rules = runCatching {
            val parsedRules = arrayListOf<EpubCss.Rule>()
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
        val orderedRules = rules.mapIndexed { index, rule ->
            rule.copy(order = index)
        }
        val matchedRules = IdentityHashMap<Element, MutableList<EpubCss.Rule>>()
        orderedRules.forEach { rule ->
            runCatching {
                if (this.`is`(rule.selector)) {
                    matchedRules.getOrPut(this) { arrayListOf() }.add(rule)
                }
                select(rule.selector).forEach { element ->
                    matchedRules.getOrPut(element) { arrayListOf() }.add(rule)
                }
            }
        }
        matchedRules.forEach { (element, elementRules) ->
            element.applyCssRules(elementRules)
        }
    }

    private fun parseCssRules(css: String): List<EpubCss.Rule> {
        if (css.isBlank()) return emptyList()
        val cacheKey = "${css.length}:${css.hashCode()}"
        return cssRuleCache.getOrPut(cacheKey) {
            EpubCss.parseRules(css)
        }
    }

    private fun loadCss(baseHref: String, href: String): String {
        return runCatching {
            val resolvedHref = URLDecoder.decode(
                URI(baseHref.encodeURI()).resolve(href.encodeURI()).toString(),
                "UTF-8"
            )
            cssTextCache.getOrPut(resolvedHref) {
                epubBook?.resources?.getByHref(resolvedHref)?.data?.let {
                    String(it, mCharset).absolutizeCssUrls(resolvedHref)
                }.orEmpty()
            }
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

    private fun String.findCssUrlEnd(start: Int): Int {
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
                ')' -> return index
            }
            index++
        }
        return -1
    }

    private data class CascadedCssValue(
        val value: String,
        val important: Boolean,
        val sourceRank: Int,
        val specificity: Int,
        val ruleOrder: Int,
        val declarationOrder: Int
    )

    private fun Element.applyCssRules(rules: List<EpubCss.Rule>) {
        val merged = linkedMapOf<String, CascadedCssValue>()
        fun putDeclaration(
            declaration: EpubCss.Declaration,
            sourceRank: Int,
            specificity: Int,
            ruleOrder: Int
        ) {
            val value = CascadedCssValue(
                value = declaration.value,
                important = declaration.important,
                sourceRank = sourceRank + if (declaration.important) 2 else 0,
                specificity = specificity,
                ruleOrder = ruleOrder,
                declarationOrder = declaration.order
            )
            val current = merged[declaration.name]
            if (current == null || value.hasHigherCssPriorityThan(current)) {
                merged[declaration.name] = value
            }
        }
        rules.forEach { rule ->
            rule.declarations.forEach { declaration ->
                putDeclaration(declaration, sourceRank = 0, specificity = rule.specificity, ruleOrder = rule.order)
            }
        }
        EpubCss.parseDeclarations(attr("style")).forEach { declaration ->
            putDeclaration(declaration, sourceRank = 1, specificity = 1000, ruleOrder = Int.MAX_VALUE)
        }
        if (merged.isNotEmpty()) {
            attr("style", merged.entries.joinToString(";") { (name, value) ->
                buildString {
                    append(name)
                    append(':')
                    append(value.value)
                    if (value.important) {
                        append(" !important")
                    }
                }
            })
        }
    }

    private fun CascadedCssValue.hasHigherCssPriorityThan(other: CascadedCssValue): Boolean {
        return compareValuesBy(
            this,
            other,
            CascadedCssValue::sourceRank,
            CascadedCssValue::specificity,
            CascadedCssValue::ruleOrder,
            CascadedCssValue::declarationOrder
        ) > 0
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
            val ownStyle = EpubCss.declarations(attr("style"))
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
            child.walkWithInherited(EpubCss.declarations(attr("style")))
        }
    }


    private fun String.escapeXmlAttr(): String {
        return replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun Element.applyEpubInlineStyle() {
        val style = attr("style")
        if (style.isBlank()) return
        val declarations = EpubCss.declarations(style)
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
        val useBlockDecoration = isEpubDecoratedBlock(declarations)
        val backgroundColor = declarations["background-color"]?.toEpubColorTag()
            ?: declarations["background"]?.extractCssColor()?.toEpubColorTag()
        backgroundColor?.let { colorTag ->
            if (normalName() != "body" && !useBlockDecoration) {
                wrapInnerHtml("epubbg$colorTag")
            }
        }
        declarations["border"]?.extractCssColor()?.toEpubColorTag()?.let { colorTag ->
            if (normalName() != "body" && !useBlockDecoration) {
                wrapInnerHtml("epubbg$colorTag")
            }
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

    private fun Element.isEpubDecoratedBlock(declarations: Map<String, String>): Boolean {
        val name = normalName()
        val isBlock = name in setOf(
            "address", "article", "aside", "blockquote", "body", "dd", "div", "dl", "dt",
            "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "li", "main", "nav", "ol", "p", "pre",
            "section", "table", "td", "th", "tr", "ul"
        )
        if (!isBlock) return false
        return declarations.containsKey("border") ||
            declarations.containsKey("border-color") ||
            declarations.containsKey("border-radius") ||
            declarations.containsKey("padding") ||
            declarations.keys.any { it.startsWith("padding-") }
    }

    private fun Element.wrapInnerHtml(tag: String, attributes: String = "") {
        val name = normalName()
        if (name == tag || html().isBlank()) return
        html("<$tag$attributes>${html()}</$tag>")
    }

    private fun Element.materializePageBackgroundColor() {
        if (normalName() != "body") return
        val declarations = EpubCss.declarations(attr("style"))
        val colorTag = attr("bgcolor").takeIf { it.isNotBlank() }?.toEpubColorTag()
            ?: declarations["background-color"]?.toEpubColorTag()
            ?: declarations["background"]?.extractCssColor()?.toEpubColorTag()
            ?: return
        prepend("""<span data-epub-page-bg="$colorTag"></span>""")
    }

    private fun Element.materializeMediaElements(res: Resource) {
        select("video,audio,source,iframe,embed,object").forEach { media ->
            val src = media.attr("src")
                .ifBlank { media.attr("href") }
                .ifBlank { media.attr("data") }
                .ifBlank {
                    media.selectFirst("source[src]")?.attr("src").orEmpty()
                }
                .trim()
            val resolvedHref = src.takeIf { it.isNotBlank() }?.let {
                resolveEpubResourceHref(res.href, it)
            }.orEmpty()
            val label = when (media.normalName()) {
                "audio" -> "EPUB音频"
                else -> "EPUB视频"
            }
            val title = media.attr("title")
                .ifBlank { media.attr("alt") }
                .ifBlank { resolvedHref.substringAfterLast('/').ifBlank { label } }
            val href = if (resolvedHref.isBlank()) {
                "legado-epub-media:missing"
            } else {
                "legado-epub-media:${resolvedHref.encodeURI()}"
            }
            media.after(
                """<p class="epub-media-placeholder" style="margin:1em 5%;padding:0.8em;text-align:center;background:rgba(68,150,211,0.12);border:1px solid rgba(68,150,211,0.55);border-radius:8px;color:#225577"><a href="$href">[$label] $title</a></p>"""
            )
            media.remove()
        }
    }

    private fun Element.materializeBackgroundImages(res: Resource) {
        val elements = linkedSetOf<Element>().apply {
            if (attr("style").contains("background", ignoreCase = true) || attr("background").isNotBlank()) {
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
        val declarations = EpubCss.declarations(style)
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
            candidates.addAll(candidate.epubPathFallbacks())
            candidates.add(candidate.encodeURI())
            runCatching { URLDecoder.decode(candidate.encodeURI(), "UTF-8") }.getOrNull()?.let {
                candidates.add(it)
                candidates.addAll(it.epubPathFallbacks())
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

    private fun String.epubPathFallbacks(): List<String> {
        val clean = trimStart('/')
        val parts = clean.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val fallbacks = linkedSetOf<String>()
        val markerIndexes = parts.mapIndexedNotNull { index, part ->
            if (part.equals("OEBPS", ignoreCase = true) || part.equals("OPS", ignoreCase = true)) index else null
        }
        markerIndexes.forEach { index ->
            fallbacks.add(parts.drop(index).joinToString("/"))
        }
        val imageIndex = parts.indexOfLast { it.equals("Images", ignoreCase = true) || it.equals("Image", ignoreCase = true) }
        if (imageIndex >= 0) {
            fallbacks.add(parts.drop(imageIndex).joinToString("/"))
            fallbacks.add(parts.drop(imageIndex + 1).joinToString("/"))
        }
        fallbacks.add(parts.last())
        return fallbacks.filter { it.isNotBlank() && it != clean }
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

    private fun Element.markEpubOverlayImagePage() {
        val images = select("img")
        if (images.size != 1) return
        val image = images.first() ?: return
        if (image.attr("data-epub-background") == "true") return
        val text = text().trim()
        if (text.isBlank() || text.length > 80) return
        val firstElement = children().firstOrNull { child ->
            child.normalName() !in setOf("style", "link", "script")
        } ?: return
        if (firstElement != image && firstElement.selectFirst("img") != image) return
        val hasOverlayBlock = select("h1,h2,h3,h4,h5,h6,table,.vol-title").isNotEmpty()
        if (!hasOverlayBlock) return
        image.attr("data-epub-background", "true")
    }

    private fun Element.markEpubGalleryPage() {
        val images = select("img").filterNot { it.attr("data-epub-background") == "true" }
        if (images.size < 2) return
        val text = text().cleanEpubInfoText()
        if (text.length > 120) return
        attr(
            "style",
            "${attr("style")};margin:0;padding:0;text-indent:0;text-align:center;line-height:1"
        )
        images.forEach { image ->
            if (image.attr("data-legado-width").isBlank()) {
                image.attr("data-legado-width", "100%")
            }
            if (image.attr("data-legado-style").isBlank()) {
                image.attr("data-legado-style", Book.imgStyleSingle)
            }
            image.attr(
                "style",
                "${image.attr("style")};display:block;margin:0 auto;max-width:100%;height:auto"
            )
        }
    }

    private fun Element.epubImageOptions(): Map<String, String> {
        val options = linkedMapOf<String, String>()
        val style = attr("style")
        val declarations = EpubCss.declarations(style)
        val width = attr("width").ifBlank { declarations["width"].orEmpty() }
        if (width.isNotBlank()) {
            options["width"] = normalizeImageWidth(width)
        }
        val height = attr("height").ifBlank { declarations["height"].orEmpty() }
        if (height.isNotBlank()) {
            normalizeImageLength(height)?.let {
                options["height"] = it
            }
        }
        if (attr("data-legado-style").isNotBlank()) {
            options["style"] = attr("data-legado-style")
        }
        if (attr("data-epub-single-page") == "true") {
            options["style"] = Book.imgStyleSingle
            options.putIfAbsent("width", "100%")
        } else if (options["width"].isInlineEpubImageWidth()) {
            options["style"] = "text"
        }
        return options
    }

    private fun normalizeImageWidth(width: String): String {
        return normalizeImageLength(width) ?: "100%"
    }

    private fun normalizeImageLength(width: String): String? {
        val clean = width.trim().lowercase(Locale.ROOT)
        return when {
            clean.endsWith("%") -> clean
            clean.endsWith("em") || clean.endsWith("rem") -> clean
            clean.endsWith("px") -> clean.dropLast(2).substringBefore(".")
            clean.toIntOrNull() != null -> clean
            else -> null
        }
    }

    private fun String?.isInlineEpubImageWidth(): Boolean {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return false
        return when {
            clean.endsWith("em") -> (clean.dropLast(2).toFloatOrNull() ?: Float.MAX_VALUE) <= 3f
            clean.endsWith("rem") -> (clean.dropLast(3).toFloatOrNull() ?: Float.MAX_VALUE) <= 3f
            clean.endsWith("px") -> (clean.dropLast(2).toFloatOrNull() ?: Float.MAX_VALUE) <= 96f
            clean.endsWith("%") -> (clean.dropLast(1).toFloatOrNull() ?: Float.MAX_VALUE) <= 12f
            else -> (clean.toFloatOrNull() ?: Float.MAX_VALUE) <= 96f
        }
    }

    private fun String.withEpubImageOptions(options: Map<String, String>): String {
        if (options.isEmpty()) return this
        val json = options.entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
            """"${key.escapeJson()}":"${value.escapeJson()}""""
        }
        return "$this,$json"
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun getImage(href: String): InputStream? {
        val cleanHref = href.stripUrlOptions()
        if (cleanHref == "cover.jpeg") return epubBook?.coverImage?.inputStream
        return findEpubResource(cleanHref)?.inputStream
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
            findEpubBookInfo()?.let { info ->
                if (info.author.isNotBlank()) {
                    book.author = info.author
                }
                if (info.intro.isNotBlank()) {
                    book.intro = info.intro
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
                    if (resource.isEpubBookInfoResource()) {
                        i++
                        continue
                    }
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
        mergeMissingSpineChapters(chapterList)
        normalizeChapterList(chapterList)
        getWordCount(chapterList, book)
        return chapterList
    }

    /**
     * EPUB 的 TOC/NCX 只是导航目录，不等于完整阅读顺序。
     * 一些书会把卷首页、人物图、插图页放在 spine 中但不写进 TOC，
     * 如果只按 TOC 生成章节就会出现“缺页”。
     */
    private fun mergeMissingSpineChapters(chapterList: ArrayList<BookChapter>) {
        val spineContents = epubSpineContents
            ?.filter { it.isReadableEpubResource() }
            ?.filterNot { it.isEpubBookInfoResource() }
            .orEmpty()
        if (spineContents.isEmpty()) return

        val spineOrder = spineContents
            .mapIndexed { index, resource -> resource.href to index }
            .toMap()
        val existingHrefSet = chapterList.asSequence()
            .filterNot { it.url.startsWith("skip:") }
            .map { it.url.substringBeforeLast("#") }
            .toMutableSet()
        var insertedCount = 0

        spineContents.forEachIndexed { spineIndex, resource ->
            if (!existingHrefSet.add(resource.href)) return@forEachIndexed
            val chapter = BookChapter()
            chapter.bookUrl = book.bookUrl
            chapter.url = resource.href
            chapter.title = resource.readableTitle(spineIndex)

            val insertIndex = chapterList.indexOfFirst { exist ->
                val existOrder = if (exist.url.startsWith("skip:")) {
                    null
                } else {
                    spineOrder[exist.url.substringBeforeLast("#")]
                }
                existOrder != null && existOrder > spineIndex
            }.let { if (it < 0) chapterList.size else it }

            chapterList.add(insertIndex, chapter)
            insertedCount++
            AppLog.put("EPUB spine 补页: href=${resource.href}, title=${chapter.title}, index=$spineIndex")
        }

        if (insertedCount > 0) {
            AppLog.put(
                "EPUB spine 补页完成: spine=${spineContents.size}, " +
                    "inserted=$insertedCount, final=${chapterList.size}"
            )
        }
    }

    /*获取书籍起始页内容。部分书籍第一章之前存在封面，引言，扉页等内容*/
    /*tile获取不同书籍风格杂乱，格式化处理待优化*/
    private var durIndex = 0
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?
    ) {
        val contents = epubSpineContents
        if (epubBook == null || contents == null || refs == null) return
        val firstRef = refs.firstOrNull { it.resource != null } ?: return
        var i = 0
        durIndex = 0
        while (i < contents.size) {
            val content = contents[i]
            if (!content.isReadableEpubResource()) {
                i++
                continue
            }
            if (content.isEpubBookInfoResource()) {
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
                title = content.readableTitle(i)
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
                if (ref.resource.isEpubBookInfoResource()) {
                    if (ref.children != null && ref.children.isNotEmpty()) {
                        parseMenu(chapterList, ref.children, level + 1)
                    }
                    return@forEach
                }
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title.cleanEpubChapterTitle(ref.resource, chapterList.size)
                chapter.url = ref.completeHref
                chapter.startFragmentId = ref.fragmentId
                chapter.isVolume = ref.children != null && ref.children.isNotEmpty()
                chapterList.add(chapter)
                durIndex++
            } else if (!ref.title.isNullOrBlank()) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title.cleanEpubChapterTitle(null, chapterList.size)
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

    private fun Resource.isReadableEpubResource(): Boolean {
        val lowerHref = href.lowercase(Locale.ROOT)
        if (!mediaType.toString().contains("htm") &&
            !lowerHref.endsWith(".html") &&
            !lowerHref.endsWith(".xhtml") &&
            !lowerHref.endsWith(".htm")
        ) {
            return false
        }
        return true
    }

    private fun Resource.readableTitle(spineIndex: Int): String {
        if (!title.isNullOrBlank()) return title.cleanEpubChapterTitle(this, spineIndex)
        val doc = runCatching { Jsoup.parse(String(data, mCharset)) }.getOrNull()
        val titleText = doc?.selectFirst("h1,h2,h3,h4,h5,h6,[id^=toc_],.chapter,.chapter-title,.title,.head")
            ?.text()
            ?.trim()
            ?: doc?.selectFirst("title")?.text()?.trim()
        if (!titleText.isNullOrBlank()) return titleText.cleanEpubChapterTitle(this, spineIndex)
        val fileName = href.substringAfterLast('/').substringBeforeLast('.').trim()
        return fileName.cleanEpubChapterTitle(this, spineIndex).ifBlank { "EPUB 页面 ${spineIndex + 1}" }
    }

    private fun String?.cleanEpubChapterTitle(resource: Resource?, index: Int): String {
        val raw = orEmpty()
            .cleanEpubInfoText()
            .replace(Regex("\\s+"), " ")
            .trim('-', '—', '–', '_', ' ', '　')
            .trim()
        val lower = raw.lowercase(Locale.ROOT)
        val hrefName = resource?.href
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.cleanEpubInfoText()
            .orEmpty()
        if (raw.isBlank()) return hrefName.ifBlank { "EPUB 页面 ${index + 1}" }
        val generic = raw == "卷首" ||
            raw == "卷首页" ||
            raw == "chapter" ||
            raw == "untitled" ||
            lower.matches(Regex("chapter\\s*\\d+\\s*-\\s*\\d+")) ||
            lower.matches(Regex("section\\d+")) ||
            lower.matches(Regex("qynmn\\d+"))
        if (!generic) return raw
        return when {
            hrefName.contains("gallery", ignoreCase = true) -> "人物画廊"
            hrefName.contains("cover", ignoreCase = true) -> "封面"
            hrefName.contains("intro", ignoreCase = true) -> "简介"
            hrefName.contains("copyright", ignoreCase = true) -> "版权信息"
            hrefName.isNotBlank() -> hrefName
            else -> "卷首 ${index + 1}"
        }
    }

    private fun findEpubBookInfo(): EpubBookInfo? {
        return epubBook?.contents
            ?.asSequence()
            ?.filter { it.mediaType.toString().contains("htm") }
            ?.mapNotNull { it.extractEpubBookInfo() }
            ?.firstOrNull()
    }

    private fun Resource.isEpubBookInfoResource(): Boolean {
        return extractEpubBookInfo() != null
    }

    private fun Resource.extractEpubBookInfo(): EpubBookInfo? {
        val doc = runCatching { Jsoup.parse(String(data, mCharset)) }.getOrNull() ?: return null
        if (!doc.isEpubBookInfoDocument()) return null
        val lines = doc.body().select("h1,h2,h3,h4,p,div:not(:has(p)):not(:has(div))")
            .map { it.text().cleanEpubInfoText() }
            .filter { it.isNotBlank() }
            .distinct()
        val author = lines.mapNotNull { line -> line.substringAfterLabel("作者") }.firstOrNull().orEmpty()
        val introLines = arrayListOf<String>()
        var inIntro = false
        lines.forEach { line ->
            val intro = line.substringAfterLabel("简介")
            when {
                intro != null -> {
                    inIntro = true
                    if (intro.isNotBlank()) introLines.add(intro)
                }
                inIntro && !line.isEpubInfoMetaLine() -> introLines.add(line)
            }
        }
        val intro = introLines.joinToString("\n").trim()
        return EpubBookInfo(author = author, intro = intro)
    }

    private fun Document.isEpubBookInfoDocument(): Boolean {
        val title = select("[title*=书籍信息], [title*=版权信息], [title*=简介]").firstOrNull()
        if (title != null) return true
        val text = body().text().cleanEpubInfoText()
        val hasIntro = text.contains("简介")
        val hasBookMeta = text.contains("作者") || text.contains("首发") || text.contains("完本")
        val hasInfoClass = select(".sjmc,.jj01,.jj02,.copyright,.book-info").isNotEmpty()
        return hasIntro && hasBookMeta && hasInfoClass
    }

    private fun String.cleanEpubInfoText(): String {
        return replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('　')
            .trim()
    }

    private fun String.substringAfterLabel(label: String): String? {
        val regex = Regex("^\\s*$label\\s*[：:]\\s*(.*)$")
        return regex.find(this)?.groupValues?.getOrNull(1)?.cleanEpubInfoText()
    }

    private fun String.isEpubInfoMetaLine(): Boolean {
        return substringAfterLabel("作者") != null ||
            substringAfterLabel("首发") != null ||
            substringAfterLabel("完本") != null ||
            equals("简介", ignoreCase = true) ||
            equals("简介：", ignoreCase = true)
    }

    private data class EpubBookInfo(
        val author: String,
        val intro: String
    )

    internal data class EpubFootnote(
        val title: String,
        val html: String
    )

    private data class FootnoteSource(
        val href: String,
        val document: Document
    )

    private fun normalizeChapterList(chapterList: ArrayList<BookChapter>) {
        if (chapterList.isEmpty()) return
        val titleCounts = linkedMapOf<String, Int>()
        for (index in chapterList.indices) {
            val chapter = chapterList[index]
            chapter.index = index
            chapter.title = chapter.title.cleanEpubChapterTitle(
                findEpubResource(chapter.url.substringBeforeLast("#")),
                index
            )
            val count = titleCounts.getOrDefault(chapter.title, 0) + 1
            titleCounts[chapter.title] = count
            if (count > 1 && chapter.title.isGenericEpubTitle()) {
                chapter.title = "${chapter.title} $count"
            }
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

    private fun String.isGenericEpubTitle(): Boolean {
        val clean = cleanEpubInfoText().trim('-', '—', '–', '_', ' ', '　')
        return clean == "卷首" ||
            clean.startsWith("卷首 ") ||
            clean == "封面" ||
            clean == "插图" ||
            clean == "人物画廊" ||
            clean.startsWith("EPUB 页面")
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
