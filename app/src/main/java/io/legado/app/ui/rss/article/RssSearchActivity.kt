package io.legado.app.ui.rss.article

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.commit
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssSearchBinding
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class RssSearchActivity : VMBaseActivity<ActivityRssSearchBinding, RssSortViewModel>() {

    override val binding by viewBinding(ActivityRssSearchBinding::inflate)
    override val viewModel by viewModels<RssSortViewModel>()

    override fun onActivityCreated(savedInstanceState: android.os.Bundle?) {
        binding.titleBar.title = getString(R.string.rss_search_hint)
        setupSearchView()
        initData()
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        binding.searchView.setQuery("", false)
        initData()
    }

    private fun initData() {
        viewModel.initData(intent) {
            val source = viewModel.rssSource
            if (source == null || source.searchUrl.isNullOrBlank()) {
                toastOnUi(R.string.rss_source_empty)
                finish()
                return@initData
            }
            val key = intent.getStringExtra("key").orEmpty()
            if (key.isBlank()) {
                binding.searchView.post {
                    binding.searchView.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showSoftInput(binding.searchView.findFocus(), InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                binding.searchView.setQuery(key, false)
                submitSearch(key)
            }
        }
    }

    private fun setupSearchView() {
        binding.searchView.applyUiBodyTypefaceDeep(uiTypeface())
        TopBarSearchStyle.apply(binding.searchView)
        binding.searchView.queryHint = getString(R.string.rss_search_hint)
        binding.searchView.isSubmitButtonEnabled = true
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val key = query?.trim().orEmpty()
                if (key.isNotBlank()) {
                    binding.searchView.clearFocus()
                    submitSearch(key)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = false
        })
    }

    private fun submitSearch(key: String) {
        val source = viewModel.rssSource ?: return
        val searchUrl = source.searchUrl ?: return
        viewModel.searchKey = key
        binding.titleBar.title = key
        supportFragmentManager.commit {
            replace(
                R.id.fragment_container,
                RssArticlesFragment(getString(R.string.search), searchUrl, key),
                "rss_search_result"
            )
        }
    }

    companion object {
        fun start(context: Context, sourceUrl: String, key: String? = null) {
            context.startActivity<RssSearchActivity> {
                putExtra("sourceUrl", sourceUrl)
                putExtra("key", key)
            }
        }
    }
}
