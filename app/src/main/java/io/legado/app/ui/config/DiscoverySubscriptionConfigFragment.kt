package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.NameListPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor

class DiscoverySubscriptionConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var targetKeyHandled = false
    private var discoveryModePref: NameListPreference? = null
    private var rssModePref: NameListPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_discovery_subscription)
        discoveryModePref = findPreference(KEY_DISCOVERY_MODE)
        rssModePref = findPreference(KEY_RSS_MODE)
        bindModePreference(
            preference = discoveryModePref,
            booleanKey = PreferKey.modernDiscoveryPage,
            defaultValue = true
        )
        bindModePreference(
            preference = rssModePref,
            booleanKey = PreferKey.modernRssPage,
            defaultValue = true
        )
        DiscoveryPageLayoutDialog.bindSummary(
            requireContext(),
            findPreference(PreferKey.discoveryPageLayout)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.discovery_subscription_settings_title)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        consumeTargetKey()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.showDiscovery,
            PreferKey.showRss -> postEvent(EventBus.NOTIFY_MAIN, true)

            PreferKey.modernDiscoveryPage,
            PreferKey.modernRssPage,
            PreferKey.discoveryPageLayout,
            PreferKey.mergeDiscoveryRss -> postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == PreferKey.discoveryPageLayout) {
            DiscoveryPageLayoutDialog.show(requireContext()) {
                DiscoveryPageLayoutDialog.bindSummary(requireContext(), preference)
                postEvent(EventBus.NOTIFY_MAIN, false)
            }
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun bindModePreference(
        preference: NameListPreference?,
        booleanKey: String,
        defaultValue: Boolean
    ) {
        preference ?: return
        preference.isPersistent = false
        val useModern = preferenceManager.sharedPreferences
            ?.getBoolean(booleanKey, defaultValue) ?: defaultValue
        preference.value = if (useModern) PAGE_MODE_MODERN else PAGE_MODE_LEGACY
        preference.setOnPreferenceChangeListener { _, newValue ->
            val nextValue = newValue?.toString().orEmpty()
            val useModernMode = nextValue == PAGE_MODE_MODERN
            preference.value = nextValue
            preferenceManager.sharedPreferences?.edit()
                ?.putBoolean(booleanKey, useModernMode)
                ?.apply()
            postEvent(EventBus.NOTIFY_MAIN, false)
            true
        }
    }

    private fun consumeTargetKey() {
        if (targetKeyHandled) return
        val rawTargetKey = activity?.intent?.getStringExtra("targetKey")?.trim().orEmpty()
        if (rawTargetKey.isBlank()) return
        val targetKey = when (rawTargetKey) {
            KEY_MODERN_RSS_PAGE,
            KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
            KEY_SEARCH_JUMP_RSS_MODE -> KEY_RSS_MODE
            KEY_SEARCH_JUMP_DISCOVERY_MODE -> KEY_DISCOVERY_MODE
            else -> rawTargetKey
        }
        val preference = findPreference<Preference>(targetKey) ?: return
        targetKeyHandled = true
        listView.post {
            scrollToPreference(preference)
            if (preference is SwitchPreferenceCompat) {
                preference.isChecked = !preference.isChecked
            } else {
                onPreferenceTreeClick(preference)
            }
            activity?.intent?.removeExtra("targetKey")
        }
    }

    companion object {
        private const val PAGE_MODE_MODERN = "modern"
        private const val PAGE_MODE_LEGACY = "legacy"
        private const val KEY_DISCOVERY_MODE = "modernDiscoveryMode"
        private const val KEY_RSS_MODE = "modernRssMode"
        private const val KEY_MODERN_RSS_PAGE = "modernRssPage"
        private const val KEY_SEARCH_JUMP_MODERN_RSS_PAGE = "search_jump_modernRssPage"
        private const val KEY_SEARCH_JUMP_DISCOVERY_MODE = "search_jump_modernDiscoveryMode"
        private const val KEY_SEARCH_JUMP_RSS_MODE = "search_jump_modernRssMode"
    }
}
