package io.legado.app.ui.book.epub

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityEpubJsBinding
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubJsActivity : BaseActivity<ActivityEpubJsBinding>(imageBg = false) {

    override val binding by viewBinding(ActivityEpubJsBinding::inflate)

    private var book: Book? = null
    private var bookOpfUrl: String = ""
    private var tocItems: List<TocItem> = emptyList()
    private var menuVisible = false
    private var userSeekingChapter = false
    private val fontFamilyKey = "epubJsFontFamily"
    private val tocActivity = registerForActivityResult(TocActivityResult()) { result ->
        val chapterIndex = result?.getOrNull(0) as? Int ?: return@registerForActivityResult
        lifecycleScope.launch {
            val chapter = withContext(IO) {
                book?.bookUrl?.let { appDb.bookChapterDao.getChapter(it, chapterIndex) }
            } ?: return@launch
            displayChapter(chapter)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initWebView()
        initMenu()
        loadBook()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initWebView() {
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.allowFileAccess = true
        binding.webView.settings.allowContentAccess = true
        binding.webView.settings.allowFileAccessFromFileURLs = true
        binding.webView.settings.allowUniversalAccessFromFileURLs = true
        binding.webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        binding.webView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val width = view.width
                when {
                    event.x < width * 0.22f -> evaluate("scrollPage(-1)")
                    event.x > width * 0.78f -> evaluate("scrollPage(1)")
                    else -> toggleMenu()
                }
            }
            false
        }
    }

    private fun initMenu() {
        binding.btnPrev.setOnClickListener { openChapterByOffset(-1) }
        binding.btnNext.setOnClickListener { openChapterByOffset(1) }
        binding.btnToc.setOnClickListener { showTocPage() }
        binding.btnLayout.setOnClickListener { showLayoutDialog() }
        binding.btnPageAnim.visibility = View.GONE
        binding.btnSetting.setOnClickListener { showFontDialog() }
        binding.seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                userSeekingChapter = true
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val index = seekBar?.progress ?: return
                userSeekingChapter = false
                openChapter(index)
            }
        })
    }

    private fun loadBook() {
        lifecycleScope.launch {
            try {
                val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
                val loadedBook = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
                    ?: error("书籍不存在")
                if (!loadedBook.isEpub) {
                    error("不是 EPUB 文件")
                }
                book = loadedBook
                binding.tvTitle.text = loadedBook.name
                binding.tvSubTitle.text = loadedBook.originName
                bookOpfUrl = withContext(IO) { prepareEpubPackage(loadedBook) }
                binding.webView.loadUrl("file:///android_asset/epubjs/reader.html")
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage ?: "EPUB 打开失败")
                finish()
            }
        }
    }

    private fun prepareEpubPackage(book: Book): String {
        val root = getExternalFilesDir("epubjs") ?: File(filesDir, "epubjs")
        val bookDir = File(root, MD5Utils.md5Encode16(book.bookUrl))
        val marker = File(bookDir, ".source")
        val signature = "${book.bookUrl}\n${book.latestChapterTime}\n${book.originName}"
        if (!File(bookDir, "META-INF/container.xml").exists() || marker.readTextOrNull() != signature) {
            if (bookDir.exists()) {
                bookDir.deleteRecursively()
            }
            bookDir.mkdirs()
            LocalBook.getBookInputStream(book).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    val canonicalRoot = bookDir.canonicalFile
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (entry != null) {
                        val outFile = File(bookDir, entry.name).canonicalFile
                        if (!outFile.path.startsWith(canonicalRoot.path + File.separator)) {
                            error("EPUB 内部路径不安全: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output ->
                                while (true) {
                                    val len = zip.read(buffer)
                                    if (len <= 0) break
                                    output.write(buffer, 0, len)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            marker.writeText(signature)
        }
        val opf = findOpfFile(bookDir)
        return opf.toURI().toString()
    }

    private fun findOpfFile(bookDir: File): File {
        val container = File(bookDir, "META-INF/container.xml")
        if (container.exists()) {
            runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                FileInputStream(container).use { input ->
                    val doc = factory.newDocumentBuilder().parse(input)
                    val nodes = doc.getElementsByTagNameNS("*", "rootfile")
                    if (nodes.length > 0) {
                        val path = (nodes.item(0) as Element).getAttribute("full-path")
                        if (path.isNotBlank()) {
                            return File(bookDir, path)
                        }
                    }
                }
            }
        }
        return bookDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("opf", true) }
            ?: error("EPUB 未找到 OPF 文件")
    }

    private fun File.readTextOrNull(): String? {
        return runCatching { readText() }.getOrNull()
    }

    private fun showTocPage() {
        val targetBook = book ?: return
        lifecycleScope.launch {
            val count = withContext(IO) {
                appDb.bookChapterDao.getChapterCount(targetBook.bookUrl)
            }
            if (count <= 0) {
                toastOnUi("目录还在加载")
                return@launch
            }
            hideMenu()
            tocActivity.launch(targetBook.bookUrl)
        }
    }

    private fun parseToc(payload: String) {
        runCatching {
            val root = JSONObject(payload)
            val toc = root.optJSONArray("toc") ?: JSONArray()
            tocItems = flattenToc(toc)
            syncTocToDatabase()
        }
    }

    private fun flattenToc(array: JSONArray, level: Int = 0): List<TocItem> {
        val list = arrayListOf<TocItem>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val href = item.optString("href")
            val label = item.optString("label").ifBlank { href }
            if (href.isNotBlank()) {
                list.add(TocItem(label, href, level))
            }
            item.optJSONArray("subitems")?.let {
                list.addAll(flattenToc(it, level + 1))
            }
        }
        return list
    }

    private fun syncTocToDatabase() {
        val targetBook = book ?: return
        val chapters = tocItems.mapIndexed { index, item ->
            BookChapter(
                url = item.href,
                title = item.label,
                bookUrl = targetBook.bookUrl,
                index = index
            )
        }
        if (chapters.isEmpty()) return
        binding.seekProgress.max = (chapters.size - 1).coerceAtLeast(0)
        binding.seekProgress.progress = targetBook.durChapterIndex.coerceIn(0, binding.seekProgress.max)
        lifecycleScope.launch(IO) {
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            targetBook.totalChapterNum = chapters.size
            if (targetBook.durChapterTitle.isNullOrBlank()) {
                targetBook.durChapterTitle = chapters.first().title
            }
            targetBook.save()
        }
    }

    private fun toggleMenu() {
        if (menuVisible) hideMenu() else showMenu()
    }

    private fun showMenu() {
        menuVisible = true
        binding.topMenu.visibility = View.VISIBLE
        binding.menuLayer.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        menuVisible = false
        binding.topMenu.visibility = View.GONE
        binding.menuLayer.visibility = View.GONE
    }

    private fun evaluate(script: String) {
        binding.webView.evaluateJavascript(script, null)
    }

    private fun colorString(color: Int): String {
        return String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)
    }

    private fun backgroundColorString(): String {
        val config = ReadBookConfig.durConfig
        return if (config.curBgType() == 0) {
            config.curBgStr()
        } else {
            "#F7F3EA"
        }
    }

    private fun displayChapter(chapter: BookChapter) {
        binding.tvTitle.text = chapter.title
        if (binding.seekProgress.max >= chapter.index) {
            binding.seekProgress.progress = chapter.index
        }
        book?.let {
            it.durChapterIndex = chapter.index
            it.durChapterTitle = chapter.title
            lifecycleScope.launch(IO) { it.save() }
        }
        evaluate("display(${JSONObject.quote(chapter.url)})")
    }

    private fun openChapter(index: Int) {
        val targetBook = book ?: return
        val maxIndex = when {
            targetBook.totalChapterNum > 0 -> targetBook.totalChapterNum - 1
            tocItems.isNotEmpty() -> tocItems.lastIndex
            else -> index
        }
        val chapterIndex = index.coerceIn(0, maxIndex.coerceAtLeast(0))
        lifecycleScope.launch {
            val chapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(targetBook.bookUrl, chapterIndex)
            }
            if (chapter == null) {
                toastOnUi("章节不存在")
            } else {
                displayChapter(chapter)
            }
        }
    }

    private fun openChapterByOffset(offset: Int) {
        val targetBook = book ?: return
        val maxIndex = when {
            targetBook.totalChapterNum > 0 -> targetBook.totalChapterNum - 1
            tocItems.isNotEmpty() -> tocItems.lastIndex
            else -> 0
        }
        val nextIndex = (targetBook.durChapterIndex + offset)
            .coerceIn(0, maxIndex.coerceAtLeast(0))
        if (nextIndex == targetBook.durChapterIndex && offset != 0) {
            toastOnUi(if (offset < 0) "已经是第一章" else "已经是最后一章")
            return
        }
        openChapter(nextIndex)
    }

    private fun showFontDialog() {
        val values = arrayOf("", "sans-serif", "serif", "monospace")
        val labels = arrayOf(
            getString(R.string.default_font),
            "Sans",
            "Serif",
            "Mono"
        )
        val current = getPrefString(fontFamilyKey, "") ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.default_font)
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                putPrefString(fontFamilyKey, values[which])
                evaluate("setStyle()")
                dialog.dismiss()
            }
            .show()
    }

    private fun showLayoutDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 10.dpToPx(), 20.dpToPx(), 4.dpToPx())
        }
        addConfigSeek(root, getString(R.string.text_size), 10, 60, ReadBookConfig.textSize) {
            ReadBookConfig.textSize = it
        }
        addConfigSeek(root, getString(R.string.line_size), 0, 60, ReadBookConfig.lineSpacingExtra) {
            ReadBookConfig.lineSpacingExtra = it
        }
        addConfigSeek(root, getString(R.string.text_letter_spacing), -50, 150, (ReadBookConfig.letterSpacing * 100).toInt()) {
            ReadBookConfig.letterSpacing = it / 100f
        }
        addConfigSeek(root, getString(R.string.paragraph_size), 0, 40, ReadBookConfig.paragraphSpacing) {
            ReadBookConfig.paragraphSpacing = it
        }
        addConfigSeek(root, getString(R.string.padding_left), 0, 60, ReadBookConfig.paddingLeft) {
            ReadBookConfig.paddingLeft = it
        }
        addConfigSeek(root, getString(R.string.padding_right), 0, 60, ReadBookConfig.paddingRight) {
            ReadBookConfig.paddingRight = it
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.interface_setting)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                ReadBookConfig.save()
                evaluate("setStyle()")
                dialog.dismiss()
            }
            .show()
    }

    private fun addConfigSeek(
        root: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit
    ) {
        val label = TextView(this).apply {
            text = "$title  $value"
            textSize = 14f
            setPadding(0, 10.dpToPx(), 0, 0)
        }
        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val newValue = progress + min
                    label.text = "$title  $newValue"
                    onChange(newValue)
                    evaluate("setStyle()")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(label)
        root.addView(seekBar)
    }

    override fun onDestroy() {
        binding.webView.removeJavascriptInterface("AndroidBridge")
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    inner class Bridge {

        @JavascriptInterface
        fun getBookUrl(): String = bookOpfUrl

        @JavascriptInterface
        fun getSavedChapterIndex(): Int = book?.durChapterIndex ?: 0

        @JavascriptInterface
        fun getSavedScrollTop(): Int = book?.durChapterPos ?: 0

        @JavascriptInterface
        fun getStyle(): String {
            val lineHeight = 1.35f + ReadBookConfig.lineSpacingExtra / 50f
            val fontFamily = getPrefString(fontFamilyKey, "")?.ifBlank { null }
                ?: "sans-serif"
            val json = JSONObject()
            json.put("background", backgroundColorString())
            json.put("color", colorString(ReadBookConfig.textColor))
            json.put("fontSize", "${(ReadBookConfig.textSize * 5).coerceIn(60, 260)}%")
            json.put("lineHeight", lineHeight.toString())
            json.put("fontFamily", fontFamily)
            json.put("letterSpacing", "${ReadBookConfig.letterSpacing}em")
            json.put("paragraphSpacing", "${ReadBookConfig.paragraphSpacing / 10f}em")
            json.put(
                "padding",
                "0 ${ReadBookConfig.paddingRight.dpToPx()}px 0 ${ReadBookConfig.paddingLeft.dpToPx()}px"
            )
            return json.toString()
        }

        @JavascriptInterface
        fun onEvent(type: String, payload: String) {
            runOnUiThread {
                when (type) {
                    "ready" -> binding.progressBar.visibility = View.GONE
                    "toc" -> parseToc(payload)
                    "chapter" -> runCatching {
                        handleChapter(payload)
                    }

                    "location" -> handleLocation(payload)
                    "pageBoundary" -> handlePageBoundary(payload)
                    "error" -> toastOnUi(
                        runCatching { JSONObject(payload).optString("message") }.getOrNull()
                            ?: "EPUB 渲染失败"
                    )
                }
            }
        }
    }

    private fun handleLocation(payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val chapterIndex = json.optInt("chapterIndex", book?.durChapterIndex ?: 0)
            if (!userSeekingChapter && binding.seekProgress.max >= chapterIndex) {
                binding.seekProgress.progress = chapterIndex
            }
            book?.let {
                it.durChapterPos = json.optInt("scrollTop", 0)
                lifecycleScope.launch(IO) { it.save() }
            }
        }
    }

    private fun handlePageBoundary(payload: String) {
        runCatching {
            val direction = JSONObject(payload).optInt("direction", 0)
            if (direction != 0) {
                openChapterByOffset(direction)
            }
        }
    }

    private fun handleChapter(payload: String) {
        val json = JSONObject(payload)
        val href = json.optString("href")
        val title = json.optString("title").ifBlank {
            tocItems.firstOrNull { href.endsWith(it.href) || it.href.endsWith(href) }?.label.orEmpty()
        }
        if (title.isNotBlank()) binding.tvTitle.text = title
        val targetBook = book ?: return
        val index = tocItems.indexOfFirst { item ->
            href == item.href || href.endsWith(item.href) || item.href.endsWith(href)
        }
        if (index >= 0) {
            targetBook.durChapterIndex = index
            targetBook.durChapterTitle = tocItems[index].label
            if (!userSeekingChapter && binding.seekProgress.max >= index) {
                binding.seekProgress.progress = index
            }
            lifecycleScope.launch(IO) { targetBook.save() }
        }
    }

    data class TocItem(
        val label: String,
        val href: String,
        val level: Int
    )
}
