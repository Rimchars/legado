package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.XmlResourceParser
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.NameListPreference
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.NavigationBarManageActivity
import io.legado.app.ui.config.ThemeManageActivity
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import org.xmlpull.v1.XmlPullParser

class MyFragment() : BaseFragment(R.layout.fragment_my_config), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private val settingsSearchView by lazy(LazyThreadSafetyMode.NONE) {
        binding.root.findViewById<SearchView>(R.id.search_view)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        val fragmentTag = "prefFragment"
        var preferenceFragment = childFragmentManager.findFragmentByTag(fragmentTag)
        if (preferenceFragment == null) preferenceFragment = MyPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.pre_fragment, preferenceFragment, fragmentTag).commit()
        initSearchView()
        applySearchBarStyle()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_help -> showHelp("appHelp")
        }
    }

    private fun initSearchView() {
        settingsSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearchQuery(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchQuery(newText)
                return true
            }
        })
        settingsSearchView.setOnCloseListener {
            applySearchQuery("")
            false
        }
    }

    private fun applySearchQuery(query: String?) {
        (childFragmentManager.findFragmentByTag("prefFragment") as? MyPreferenceFragment)
            ?.filterMainPreferences(query)
    }

    private fun applySearchBarStyle() {
        settingsSearchView.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        val isNight = io.legado.app.help.config.AppConfig.isNightTheme
        val searchSurfaceColor = if (isNight) {
            ColorUtils.adjustAlpha(Color.rgb(52, 52, 56), 0.42f)
        } else {
            ColorUtils.adjustAlpha(Color.rgb(120, 120, 128), 0.18f)
        }
        val strokeColor = ColorUtils.adjustAlpha(primaryTextColor, if (isNight) 0.10f else 0.08f)
        settingsSearchView.background = GradientDrawable().apply {
            cornerRadius = UiCorner.searchRadius(18f)
            setColor(searchSurfaceColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    /**
     * 配置
     */
    class MyPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private data class SubSearchItem(
            val ownerKey: String,
            val title: String,
            val summary: String,
            val key: String,
            val ownerConfigTag: String? = null,
        ) {
            val searchText: String = listOf(title, summary, key).joinToString(" ").lowercase()
        }

        private val subSearchItems by lazy(LazyThreadSafetyMode.NONE) { buildSubSearchItems() }
        private val ownerMatchedSubItems = hashMapOf<String, List<SubSearchItem>>()
        private var activeSearchKeyword: String = ""
        private val originalSummaries = hashMapOf<String, CharSequence?>()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            putPrefBoolean(PreferKey.webService, WebService.isRun)
            addPreferencesFromResource(R.xml.pref_main)
            findPreference<SwitchPreference>("webService")?.onLongClick {
                if (!WebService.isRun) {
                    return@onLongClick false
                }
                context?.selector(arrayListOf("复制地址", "浏览器打开")) { _, i ->
                    when (i) {
                        0 -> context?.sendToClip(it.summary.toString())
                        1 -> context?.openUrl(it.summary.toString())
                    }
                }
                true
            }
            observeEventSticky<String>(EventBus.WEB_SERVICE) {
                findPreference<SwitchPreference>(PreferKey.webService)?.let {
                    it.isChecked = WebService.isRun
                    it.summary = if (WebService.isRun) {
                        WebService.hostAddress
                    } else {
                        getString(R.string.web_service_desc)
                    }
                }
            }
            findPreference<NameListPreference>(PreferKey.themeMode)?.let {
                it.setOnPreferenceChangeListener { _, _ ->
                    view?.post { ThemeConfig.applyDayNight(requireContext()) }
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.clipToPadding = false
            listView.applyMainBottomBarPadding()
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.webService -> {
                    if (requireContext().getPrefBoolean("webService")) {
                        WebService.start(requireContext())
                    } else {
                        WebService.stop(requireContext())
                    }
                }

                "recordLog" -> LogUtils.upLevel()
            }
        }

        fun filterMainPreferences(query: String?) {
            val keyword = query?.trim().orEmpty().lowercase()
            activeSearchKeyword = keyword
            ownerMatchedSubItems.clear()
            val root = preferenceScreen ?: return
            if (keyword.isBlank()) {
                restoreMainSummaries(root)
                resetVisibility(root)
                return
            }
            filterMainGroup(root, keyword)
        }

        private fun filterMainGroup(group: PreferenceGroup, keyword: String): Boolean {
            var anyVisible = false
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                val visible = when (preference) {
                    is PreferenceGroup -> filterMainGroup(preference, keyword) || matchesMainPreference(preference, keyword)
                    else -> matchesMainPreference(preference, keyword)
                }
                preference.isVisible = visible
                anyVisible = anyVisible || visible
            }
            group.isVisible = anyVisible || group == preferenceScreen
            return anyVisible
        }

        private fun resetVisibility(group: PreferenceGroup) {
            group.isVisible = true
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.isVisible = true
                if (preference is PreferenceGroup) {
                    resetVisibility(preference)
                }
            }
        }

        private fun matchesMainPreference(preference: Preference, keyword: String): Boolean {
            val key = preference.key.orEmpty()
            val titleText = preference.title?.toString().orEmpty().lowercase()
            val summaryText = preference.summary?.toString().orEmpty().lowercase()
            val keyText = key.lowercase()
            val matchedSubItems = subSearchItems
                .filter { it.ownerKey == key && it.searchText.contains(keyword) }
            if (matchedSubItems.isNotEmpty()) {
                ownerMatchedSubItems[key] = matchedSubItems
            }
            updateSearchSummary(preference, matchedSubItems)
            return titleText.contains(keyword)
                || summaryText.contains(keyword)
                || keyText.contains(keyword)
                || matchedSubItems.isNotEmpty()
        }

        private fun buildSubSearchItems(): List<SubSearchItem> {
            return listOf(
                Triple("theme_setting", R.xml.pref_config_theme, ConfigTag.THEME_CONFIG),
                Triple("web_dav_setting", R.xml.pref_config_backup, ConfigTag.BACKUP_CONFIG),
                Triple("coverConfig", R.xml.pref_config_cover, ConfigTag.COVER_CONFIG),
                Triple("ai_setting", R.xml.pref_config_ai, ConfigTag.AI_CONFIG),
                Triple("setting", R.xml.pref_config_other, ConfigTag.OTHER_CONFIG),
                Triple("setting", R.xml.pref_config_read, ConfigTag.OTHER_CONFIG),
                Triple(
                    "setting",
                    R.xml.pref_config_discovery_subscription,
                    ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG
                )
            ).flatMap { (ownerKey, xmlRes, ownerConfigTag) ->
                buildPreferenceXmlSearchItems(ownerKey, xmlRes, ownerConfigTag)
            }
        }

        private fun buildPreferenceXmlSearchItems(
            ownerKey: String,
            xmlRes: Int,
            ownerConfigTag: String
        ): List<SubSearchItem> {
            val items = ArrayList<SubSearchItem>()
            val parser: XmlResourceParser = resources.getXml(xmlRes)
            try {
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val title = collectPreferenceAttr(parser, "title").orEmpty()
                        val key = collectPreferenceAttr(parser, "key").orEmpty()
                        if (title.isNotBlank() && key.isNotBlank()) {
                            items.add(
                                SubSearchItem(
                                    ownerKey = ownerKey,
                                    title = title,
                                    summary = collectPreferenceAttr(parser, "summary").orEmpty(),
                                    key = key.removePrefix("search_jump_"),
                                    ownerConfigTag = ownerConfigTag
                                )
                            )
                        }
                    }
                    eventType = parser.next()
                }
            } finally {
                parser.close()
            }
            return items
        }

        private fun updateSearchSummary(preference: Preference, matchedItems: List<SubSearchItem>) {
            val key = preference.key ?: return
            if (!originalSummaries.containsKey(key)) {
                originalSummaries[key] = preference.summary
            }
            preference.summary = if (matchedItems.isEmpty()) {
                originalSummaries[key]
            } else {
                matchedItems.first().title
            }
        }

        private fun restoreMainSummaries(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.key?.let { key ->
                    if (originalSummaries.containsKey(key)) {
                        preference.summary = originalSummaries[key]
                    }
                }
                if (preference is PreferenceGroup) {
                    restoreMainSummaries(preference)
                }
            }
        }

        private fun collectPreferenceAttr(parser: XmlResourceParser, attrName: String): String? {
            val namespace = "http://schemas.android.com/apk/res/android"
            val attrValue = parser.getAttributeValue(namespace, attrName)?.trim().orEmpty()
            if (attrValue.isBlank()) return null
            val attrRes = parser.getAttributeResourceValue(namespace, attrName, 0)
            return if (attrRes != 0) {
                runCatching { getString(attrRes) }.getOrNull()?.trim().orEmpty().takeIf { it.isNotBlank() }
            } else {
                attrValue.removePrefix("@").takeIf { it.isNotBlank() }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "bookSourceManage" -> startActivity<BookSourceActivity>()
                "rssSourceManage" -> startActivity<RssSourceActivity>()
                "replaceManage" -> startActivity<ReplaceRuleActivity>()
                "dictRuleManage" -> startActivity<DictRuleActivity>()
                "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
                "bookmark" -> startActivity<AllBookmarkActivity>()
                "setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.OTHER_CONFIG)
                }

                "web_dav_setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                }

                "cacheManage" -> startActivity<CacheManageActivity>()

                "theme_setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.THEME_CONFIG)
                }

                "theme_manage" -> startActivity<ThemeManageActivity>()

                "navigation_bar_manage" -> startActivity<NavigationBarManageActivity>()

                "coverConfig" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.COVER_CONFIG)
                }

                "ai_setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.AI_CONFIG)
                }

                "fileManage" -> startActivity<FileManageActivity>()
                "readRecord" -> startActivity<ReadRecordActivity>()
                "about" -> startActivity<AboutActivity>()
                "exit" -> activity?.finish()
                else -> Unit
            }
            if (activeSearchKeyword.isNotBlank()) {
                ownerMatchedSubItems[preference.key.orEmpty()]
                    ?.firstOrNull()
                    ?.let { item ->
                        item.ownerConfigTag?.let { configTag ->
                            startActivity<ConfigActivity> {
                                putExtra("configTag", configTag)
                                putExtra("targetKey", item.key)
                            }
                            return true
                        }
                    }
            }
            return super.onPreferenceTreeClick(preference)
        }


    }
}
