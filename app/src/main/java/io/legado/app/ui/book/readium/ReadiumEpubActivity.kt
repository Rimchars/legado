package io.legado.app.ui.book.readium

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadiumEpubBinding
import io.legado.app.help.book.isEpub
import io.legado.app.utils.openUrl
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.HyperlinkNavigator
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
class ReadiumEpubActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {

    private lateinit var binding: ActivityReadiumEpubBinding
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var book: Book? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadiumEpubBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

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
        binding.toolbar.title = targetBook.name
        binding.toolbar.subtitle = targetBook.author.takeIf { it.isNotBlank() } ?: targetBook.originName

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
        observeProgress(targetBook)
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
                httpClient = httpClient
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
        publication?.close()
        publication = null
        super.onDestroy()
    }

    companion object {
        private const val NAVIGATOR_TAG = "readium_epub_navigator"
        private const val READIUM_LOCATOR_KEY = "readiumLocator"
    }
}
