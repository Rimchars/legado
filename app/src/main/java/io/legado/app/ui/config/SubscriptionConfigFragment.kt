package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor

class SubscriptionConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var targetKeyHandled = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_subscription)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.subscription_settings_title)
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
            PreferKey.showRss -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.modernRssPage,
            PreferKey.mergeDiscoveryRss -> postEvent(EventBus.NOTIFY_MAIN, false)
        }
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

