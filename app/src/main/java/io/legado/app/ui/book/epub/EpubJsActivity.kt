package io.legado.app.ui.book.epub

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityEpubJsBinding
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
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
    private var fontScale: Int = 100
    private var scrollMode: Boolean = false
    private var currentCfi: String = ""
    private var lastPercentage: Double = 0.0
    private val fontScaleKey = "epubJsFontScale"
    private val scrollModeKey = "epubJsScrollMode"
    private val cfiKey = "epubJsCfi"

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        fontScale = getPrefInt(fontScaleKey, 100).coerceIn(70, 180)
        scrollMode = getPrefBoolean(scrollModeKey, false)
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
        binding.btnToc.setOnClickListener { showTocDialog() }
        binding.btnFontMinus.setOnClickListener {
            fontScale = (fontScale - 5).coerceAtLeast(70)
            putPrefInt(fontScaleKey, fontScale)
            evaluate("setStyle()")
        }
        binding.btnFontPlus.setOnClickListener {
            fontScale = (fontScale + 5).coerceAtMost(180)
            putPrefInt(fontScaleKey, fontScale)
            evaluate("setStyle()")
        }
        binding.btnScroll.setOnClickListener {
            scrollMode = !scrollMode
            putPrefBoolean(scrollModeKey, scrollMode)
            updateScrollButton()
            evaluate("setScrollMode()")
        }
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
        updateScrollButton()
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

    private fun showTocDialog() {
        if (tocItems.isEmpty()) {
            toastOnUi("目录还在加载")
            return
        }
        val labels = tocItems.map { item ->
            buildString {
                repeat(item.level) { append("  ") }
                append(item.label)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chapter_list)
            .setItems(labels) { dialog, which ->
                evaluate("display(${JSONObject.quote(tocItems[which].href)})")
                dialog.dismiss()
                hideMenu()
            }
            .show()
    }

    private fun parseToc(payload: String) {
        runCatching {
            val root = JSONObject(payload)
            val toc = root.optJSONArray("toc") ?: JSONArray()
            tocItems = flattenToc(toc)
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

    private fun toggleMenu() {
        if (menuVisible) hideMenu() else showMenu()
    }

    private fun showMenu() {
        menuVisible = true
        binding.menuLayer.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        menuVisible = false
        binding.menuLayer.visibility = View.GONE
    }

    private fun updateScrollButton() {
        binding.btnScroll.text = if (scrollMode) "滚动" else "分页"
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
        fun isScrollMode(): Boolean = scrollMode

        @JavascriptInterface
        fun getStyle(): String {
            val json = JSONObject()
            json.put("background", backgroundColorString())
            json.put("color", colorString(ReadBookConfig.textColor))
            json.put("fontSize", "$fontScale%")
            json.put("lineHeight", "1.65")
            return json.toString()
        }

        @JavascriptInterface
        fun onEvent(type: String, payload: String) {
            runOnUiThread {
                when (type) {
                    "ready" -> binding.progressBar.visibility = View.GONE
                    "toc" -> parseToc(payload)
                    "chapter" -> runCatching {
                        val title = JSONObject(payload).optString("title")
                        if (title.isNotBlank()) binding.tvTitle.text = title
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

    data class TocItem(
        val label: String,
        val href: String,
        val level: Int
    )
}
