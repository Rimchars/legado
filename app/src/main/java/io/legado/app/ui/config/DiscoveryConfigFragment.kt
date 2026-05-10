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

class DiscoveryConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var targetKeyHandled = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_discovery)
        val modePref = findPreference<NameListPreference>("modernDiscoveryMode")
        val useModern = preferenceManager.sharedPreferences
            ?.getBoolean(PreferKey.modernDiscoveryPage, true) ?: true
        modePref?.value = if (useModern) "modern" else "legacy"
        updateModeSummary(modePref)
        modePref?.setOnPreferenceChangeListener { _, newValue ->
            val useModernMode = newValue == "modern"
            preferenceManager.sharedPreferences?.edit()
                ?.putBoolean(PreferKey.modernDiscoveryPage, useModernMode)
                ?.apply()
            updateModeSummary(modePref)
            postEvent(EventBus.NOTIFY_MAIN, false)
            true
        }
        DiscoveryPageLayoutDialog.bindSummary(
            requireContext(),
            findPreference(PreferKey.discoveryPageLayout)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.discovery_settings_title)
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
            PreferKey.showDiscovery -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.modernDiscoveryPage,
            PreferKey.discoveryPageLayout -> postEvent(EventBus.NOTIFY_MAIN, false)
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

    private fun updateModeSummary(modePref: NameListPreference?) {
        modePref ?: return
        val value = modePref.value
        val index = modePref.findIndexOfValue(value)
        modePref.summary = if (index >= 0) modePref.entries[index] else modePref.summary
    }

    private fun consumeTargetKey() {
        if (targetKeyHandled) return
        val targetKey = activity?.intent?.getStringExtra("targetKey")?.trim().orEmpty()
        if (targetKey.isBlank()) return
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
}

