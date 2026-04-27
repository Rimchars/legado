package io.legado.app.ui.book.epub

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
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
import io.legado.app.constant.PageAnim
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
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class EpubJsActivity : BaseActivity<ActivityEpubJsBinding>(imageBg = false) {

    override val binding by viewBinding(ActivityEpubJsBinding::inflate)

    private var book: Book? = null
    private var bookBase64: String = ""
    private var tocItems: List<TocItem> = emptyList()
    private var menuVisible = false
    private var currentCfi: String = ""
    private var lastPercentage: Double = 0.0
    private var currentHref: String = ""
    private val fontFamilyKey = "epubJsFontFamily"
    private val cfiKey = "epubJsCfi"
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
        binding.webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        binding.webView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val width = view.width
                when {
                    event.x < width * 0.22f -> evaluate("prevPage()")
                    event.x > width * 0.78f -> evaluate("nextPage()")
                    else -> toggleMenu()
                }
            }
            false
        }
    }

    private fun initMenu() {
        binding.btnPrev.setOnClickListener { evaluate("prevPage()") }
        binding.btnNext.setOnClickListener { evaluate("nextPage()") }
        binding.btnToc.setOnClickListener { showTocPage() }
        binding.btnLayout.setOnClickListener { showLayoutDialog() }
        binding.btnPageAnim.setOnClickListener { showPageAnimDialog() }
        binding.btnSetting.setOnClickListener { showFontDialog() }
        binding.seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val percentage = progress / 10000.0
                    evaluate("displayByPercentage($percentage)")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
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
                currentCfi = loadedBook.getVariable(cfiKey)
                bookBase64 = withContext(IO) {
                    LocalBook.getBookInputStream(loadedBook).use {
                        Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
                    }
                }
                binding.webView.loadUrl("file:///android_asset/epubjs/reader.html")
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage ?: "EPUB 打开失败")
                finish()
            }
        }
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

    private fun currentPageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    private fun displayChapter(chapter: BookChapter) {
        currentHref = chapter.url
        binding.tvTitle.text = chapter.title
        book?.let {
            it.durChapterIndex = chapter.index
            it.durChapterTitle = chapter.title
            lifecycleScope.launch(IO) { it.save() }
        }
        evaluate("display(${JSONObject.quote(chapter.url)})")
    }

    private fun showPageAnimDialog() {
        val items = arrayOf(
            getString(R.string.page_anim_cover),
            getString(R.string.page_anim_slide),
            getString(R.string.page_anim_simulation),
            getString(R.string.page_anim_scroll),
            getString(R.string.page_anim_none)
        )
        val checked = currentPageAnim().coerceIn(0, items.lastIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.page_anim)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                book?.setPageAnim(which)
                lifecycleScope.launch(IO) { book?.save() }
                ReadBookConfig.pageAnim = which
                ReadBookConfig.save()
                evaluate("setScrollMode()")
                dialog.dismiss()
            }
            .show()
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
        fun getBookBase64(): String = bookBase64

        @JavascriptInterface
        fun getSavedCfi(): String = currentCfi

        @JavascriptInterface
        fun isScrollMode(): Boolean = currentPageAnim() == PageAnim.scrollPageAnim

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
            currentCfi = json.optString("cfi")
            lastPercentage = json.optDouble("percentage", lastPercentage)
            binding.seekProgress.progress = (lastPercentage * 10000).toInt().coerceIn(0, 10000)
            book?.let {
                it.putVariable(cfiKey, currentCfi)
                it.durChapterPos = binding.seekProgress.progress
                lifecycleScope.launch(IO) { it.save() }
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
        if (href.isNotBlank()) currentHref = href
        val targetBook = book ?: return
        val index = tocItems.indexOfFirst { item ->
            href == item.href || href.endsWith(item.href) || item.href.endsWith(href)
        }
        if (index >= 0) {
            targetBook.durChapterIndex = index
            targetBook.durChapterTitle = tocItems[index].label
            lifecycleScope.launch(IO) { targetBook.save() }
        }
    }

    data class TocItem(
        val label: String,
        val href: String,
        val level: Int
    )
}
