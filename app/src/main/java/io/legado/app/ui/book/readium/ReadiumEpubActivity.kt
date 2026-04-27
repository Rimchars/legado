package io.legado.app.ui.book.readium

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadiumEpubBinding
import io.legado.app.help.book.isEpub
import io.legado.app.utils.openUrl
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.shared.util.asset.AssetRetriever

@OptIn(ExperimentalReadiumApi::class)
class ReadiumEpubActivity : BaseActivity<ActivityReadiumEpubBinding>(
    fullScreen = true,
    imageBg = false
), EpubNavigatorFragment.Listener {

    override val binding: ActivityReadiumEpubBinding by lazy {
        ActivityReadiumEpubBinding.inflate(layoutInflater)
    }
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var book: Book? = null
    private var currentLocator: Locator? = null
    private var ignoreSeekChange = false
    private val readiumInputListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val width = binding.readerContainer.width.takeIf { it > 0 } ?: return false
            val x = event.point.x
            if (x in width * 0.34f..width * 0.66f) {
                toggleMenu()
                return true
            }
            return false
        }

        override fun onDrag(event: DragEvent): Boolean = false

        override fun onKey(event: KeyEvent): Boolean = false
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initMenu()
        lifecycleScope.launch {
            openBook(savedInstanceState)
        }
    }

    private suspend fun openBook(savedInstanceState: Bundle?) {
        val bookUrl = intent.getStringExtra("bookUrl")
        val targetBook = withContext(Dispatchers.IO) {
            if (bookUrl.isNullOrBlank()) appDb.bookDao.lastReadBook else appDb.bookDao.getBook(bookUrl)
        }
        if (targetBook == null || !targetBook.isEpub) {
            toastOnUi("未找到 EPUB 书籍")
            finish()
            return
        }
        book = targetBook
        binding.tvBookName.text = targetBook.name

        val publication = withContext(Dispatchers.IO) {
            openPublication(targetBook)
        }
        this.publication = publication
        binding.progressBar.isVisible = false

        if (savedInstanceState == null) {
            val initialLocator = targetBook.getVariable(READIUM_LOCATOR_KEY)
                .takeIf { it.isNotBlank() }
                ?.let { Locator.fromJSON(JSONObject(it)) }
            supportFragmentManager.fragmentFactory = EpubNavigatorFactory(publication)
                .createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = EpubPreferences(),
                    listener = this,
                    configuration = EpubNavigatorFragment.Configuration(
                        servedAssets = listOf(".*")
                    )
                )
            supportFragmentManager.commitNow {
                replace(R.id.readerContainer, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            }
        }
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
        navigator?.addInputListener(readiumInputListener)
        observeProgress(targetBook)
    }

    private fun initMenu() {
        binding.menuLayer.setOnClickListener { hideMenu() }
        binding.topMenu.setOnClickListener { }
        binding.bottomMenu.setOnClickListener { }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { goRelativeChapter(-1) }
        binding.btnNext.setOnClickListener { goRelativeChapter(1) }
        binding.btnToc.setOnClickListener { showTocDialog() }
        binding.seekReadProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                ignoreSeekChange = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = (seekBar?.progress ?: return) / READIUM_PROGRESS_MAX.toDouble()
                goProgression(target.coerceIn(0.0, 1.0))
                ignoreSeekChange = false
            }
        })
    }

    private fun toggleMenu() {
        if (binding.menuLayer.isVisible) {
            hideMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        updateMenuState()
        binding.menuLayer.visible()
    }

    private fun hideMenu() {
        binding.menuLayer.visibility = View.GONE
    }

    private suspend fun openPublication(book: Book): Publication {
        val url = Uri.parse(book.bookUrl).toAbsoluteUrl()
            ?: error("EPUB 地址无效: ${book.bookUrl}")
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val asset = assetRetriever.retrieve(url).getOrElse {
            error(it.message)
        }
        return PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = this,
                assetRetriever = assetRetriever,
                httpClient = httpClient,
                pdfFactory = null
            )
        ).open(
            asset = asset,
            allowUserInteraction = true
        ).getOrElse {
            error(it.message)
        }
    }

    private fun observeProgress(book: Book) {
        val navigator = navigator ?: return
        lifecycleScope.launch {
            navigator.currentLocator.collectLatest { locator ->
                currentLocator = locator
                binding.tvChapterName.text = locator.title.orEmpty()
                updateMenuState()
                withContext(Dispatchers.IO) {
                    book.putVariable(READIUM_LOCATOR_KEY, locator.toJSON().toString())
                    book.durChapterTime = System.currentTimeMillis()
                    locator.locations.progression?.let { progression ->
                        book.durChapterPos = (progression * 10000).toInt()
                    }
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    private fun updateMenuState() {
        val publication = publication ?: return
        val index = currentReadingOrderIndex()
        binding.btnPrev.isEnabled = index > 0
        binding.btnNext.isEnabled = index >= 0 && index < publication.readingOrder.lastIndex
        if (!ignoreSeekChange) {
            val progress = currentLocator?.locations?.totalProgression
                ?: currentLocator?.locations?.progression
                ?: 0.0
            binding.seekReadProgress.progress = (progress * READIUM_PROGRESS_MAX)
                .toInt()
                .coerceIn(0, READIUM_PROGRESS_MAX)
        }
    }

    private fun currentReadingOrderIndex(): Int {
        val publication = publication ?: return -1
        val href = currentLocator?.href?.toString() ?: return -1
        return publication.readingOrder.indexOfFirst { link ->
            href.substringBefore("#") == link.href.toString().substringBefore("#")
        }
    }

    private fun goRelativeChapter(offset: Int) {
        val publication = publication ?: return
        val index = currentReadingOrderIndex()
        val target = publication.readingOrder.getOrNull(index + offset) ?: return
        navigator?.go(target, animated = true)
        hideMenu()
    }

    private fun goProgression(progression: Double) {
        val publication = publication ?: return
        val readingOrder = publication.readingOrder
        if (readingOrder.isEmpty()) return
        val targetIndex = (progression * readingOrder.lastIndex)
            .toInt()
            .coerceIn(0, readingOrder.lastIndex)
        navigator?.go(readingOrder[targetIndex], animated = true)
    }

    private fun showTocDialog() {
        val publication = publication ?: return
        val toc = publication.tableOfContents.ifEmpty { publication.readingOrder }
        if (toc.isEmpty()) {
            toastOnUi("目录为空")
            return
        }
        val titles = toc.mapIndexed { index, link ->
            link.title?.takeIf { it.isNotBlank() } ?: "章节 ${index + 1}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chapter_list)
            .setItems(titles) { dialog, which ->
                navigator?.go(toc[which], animated = true)
                dialog.dismiss()
                hideMenu()
            }
            .show()
    }

    override fun shouldFollowInternalLink(
        link: Link,
        context: HyperlinkNavigator.LinkContext?
    ): Boolean {
        return true
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        openUrl(url.toString())
    }

    override fun onDestroy() {
        navigator?.removeInputListener(readiumInputListener)
        publication?.close()
        publication = null
        super.onDestroy()
    }

    companion object {
        private const val NAVIGATOR_TAG = "readium_epub_navigator"
        private const val READIUM_LOCATOR_KEY = "readiumLocator"
        private const val READIUM_PROGRESS_MAX = 10000
    }
}
