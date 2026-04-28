package io.legado.app.ui.book.epub

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.utils.encodeURI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

class EpubWebViewActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJobCompat() + Dispatchers.Main.immediate)
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var topBar: View
    private var book: Book? = null
    private var epubBook: EpubBook? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var spine: List<Resource> = emptyList()
    private var chapterIndex = 0
    private val host = "legado-epub.local"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(250, 249, 246))
        }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = EpubClient()
            setOnClickListener { toggleTopBar() }
        }
        root.addView(webView)
        root.addView(createTopBar())
        setContentView(root)
        loadBook()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { fileDescriptor?.close() }
        webView.destroy()
        scope.cancel()
    }

    private fun createTopBar(): View {
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        fun action(text: String, click: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(56.dp(), ViewGroup.LayoutParams.MATCH_PARENT)
                setOnClickListener { click() }
            }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp(), 24.dp(), 4.dp(), 0)
            setBackgroundColor(0xB0000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                72.dp(),
                Gravity.TOP
            )
            addView(action("返回") { finish() })
            addView(action("上章") { openChapter(chapterIndex - 1) })
            addView(titleView)
            addView(action("下章") { openChapter(chapterIndex + 1) })
        }
        return topBar
    }

    private fun toggleTopBar() {
        topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun loadBook() {
        val bookUrl = intent.getStringExtra("bookUrl")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val loadedBook = bookUrl?.let { appDb.bookDao.getBook(it) } ?: appDb.bookDao.lastReadBook
                if (loadedBook == null || !loadedBook.isEpub) {
                    return@withContext Result.failure<LoadedEpub>(IllegalStateException("未找到 EPUB 书籍"))
                }
                runCatching {
                    val pfd = BookHelp.getBookPFD(loadedBook)
                        ?: error("无法打开 EPUB 文件")
                    val zipFile = AndroidZipFile(pfd, loadedBook.originName)
                    val epub = EpubReader().readEpubLazy(zipFile, "utf-8")
                    LoadedEpub(loadedBook, epub, pfd)
                }
            }
            result.onSuccess { loaded ->
                book = loaded.book
                epubBook = loaded.epub
                fileDescriptor = loaded.fileDescriptor
                spine = loaded.epub.spine.spineReferences.mapNotNull { it.resource }
                    .ifEmpty { loaded.epub.contents }
                chapterIndex = loaded.book.durChapterIndex.coerceIn(0, (spine.size - 1).coerceAtLeast(0))
                openChapter(chapterIndex)
            }.onFailure {
                Toast.makeText(this@EpubWebViewActivity, it.localizedMessage ?: "EPUB 加载失败", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun openChapter(index: Int) {
        if (spine.isEmpty()) return
        chapterIndex = index.coerceIn(0, spine.lastIndex)
        val resource = spine[chapterIndex]
        titleView.text = "${chapterIndex + 1}/${spine.size}  ${resource.title.orEmpty().ifBlank { book?.name.orEmpty() }}"
        scope.launch {
            val html = withContext(Dispatchers.IO) {
                runCatching {
                    resource.reader.use { it.readText() }
                }.getOrElse {
                    String(resource.data, Charset.forName("UTF-8"))
                }.toReadableEpubHtml(resource.href)
            }
            val baseUrl = "https://$host/${resource.href}"
            webView.loadDataWithBaseURL(baseUrl, html, "application/xhtml+xml", "UTF-8", null)
            book?.let { loadedBook ->
                loadedBook.durChapterIndex = chapterIndex
                loadedBook.durChapterPos = 0
                withContext(Dispatchers.IO) {
                    appDb.bookDao.update(loadedBook)
                }
            }
        }
    }

    private fun String.toReadableEpubHtml(href: String): String {
        val bodyPadding = "padding: max(22px, env(safe-area-inset-top)) 18px max(28px, env(safe-area-inset-bottom));"
        val injectedStyle = """
            <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
            <style>
              html { -webkit-text-size-adjust: 100%; background: #faf9f6; }
              body { $bodyPadding margin: 0; color: #1f1f1f; line-height: 1.72; word-break: break-word; overflow-wrap: anywhere; }
              img, svg, video { max-width: 100% !important; height: auto !important; object-fit: contain; }
              table { max-width: 100%; border-collapse: collapse; overflow-x: auto; display: block; }
              pre { white-space: pre-wrap; overflow-wrap: anywhere; }
              a { color: #2b6cb0; }
            </style>
        """.trimIndent()
        return if (contains("</head>", ignoreCase = true)) {
            replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$injectedStyle</head>")
        } else {
            """
            <!doctype html>
            <html>
            <head>$injectedStyle</head>
            <body data-href="$href">$this</body>
            </html>
            """.trimIndent()
        }
    }

    private inner class EpubClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val uri = request?.url ?: return null
            if (!uri.host.equals(host, ignoreCase = true)) return null
            val resource = findResource(uri) ?: return null
            return runCatching {
                val mime = resource.mediaType?.name ?: guessMime(resource.href)
                WebResourceResponse(mime, "UTF-8", resource.inputStream)
            }.getOrNull()
        }
    }

    private fun findResource(uri: Uri): Resource? {
        val rawPath = uri.encodedPath.orEmpty().trimStart('/')
        val decodedPath = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val clean = decodedPath.substringBefore('?').substringBefore('#').trim()
        val candidates = linkedSetOf(clean, clean.trimStart('/'))
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let {
            candidates.add(it)
            candidates.add(it.trimStart('/'))
        }
        candidates.toList().forEach { candidate ->
            candidates.add(candidate.encodeURI())
            runCatching { URLDecoder.decode(candidate.encodeURI(), "UTF-8") }.getOrNull()?.let {
                candidates.add(it)
            }
            val normalized = runCatching {
                URI(candidate.encodeURI()).normalize().toString()
            }.getOrNull()
            if (!normalized.isNullOrBlank()) candidates.add(normalized.trimStart('/'))
        }
        candidates.forEach { candidate ->
            epubBook?.resources?.getByHref(candidate)?.let { return it }
        }
        val lowerCandidates = candidates.map { it.lowercase(Locale.ROOT).trimStart('/') }.toSet()
        val fileName = clean.substringAfterLast('/').lowercase(Locale.ROOT)
        return epubBook?.resources?.all?.firstOrNull { resource ->
            val href = resource.href.orEmpty().trimStart('/')
            val lower = href.lowercase(Locale.ROOT)
            lower in lowerCandidates || lower.endsWith("/$fileName") || lower == fileName
        }
    }

    private fun guessMime(href: String?): String {
        return when (href?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
            "css" -> "text/css"
            "js" -> "application/javascript"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "xhtml", "html", "htm" -> "application/xhtml+xml"
            else -> "application/octet-stream"
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    private data class LoadedEpub(
        val book: Book,
        val epub: EpubBook,
        val fileDescriptor: ParcelFileDescriptor?
    )
}

private fun SupervisorJobCompat(): Job = kotlinx.coroutines.SupervisorJob()
