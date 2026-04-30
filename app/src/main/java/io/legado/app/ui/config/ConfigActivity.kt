package io.legado.app.ui.config

import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SearchView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityConfigBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ConfigActivity : VMBaseActivity<ActivityConfigBinding, ConfigViewModel>() {

    override val binding by viewBinding(ActivityConfigBinding::inflate)
    override val viewModel by viewModels<ConfigViewModel>()
    private var currentSearchQuery: String = ""

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        applyHeaderColors()
        initSearchView()
        when (val configTag = intent.getStringExtra("configTag")) {
            ConfigTag.OTHER_CONFIG -> replaceFragment(configTag, OtherConfigFragment::class.java)
            ConfigTag.THEME_CONFIG -> replaceFragment(configTag, ThemeConfigFragment::class.java)
            ConfigTag.BACKUP_CONFIG -> replaceFragment(configTag, BackupConfigFragment::class.java)
            ConfigTag.AI_CONFIG -> replaceFragment(configTag, AiConfigFragment::class.java)
            ConfigTag.COVER_CONFIG -> replaceFragment(configTag, CoverConfigFragment::class.java)
            ConfigTag.WELCOME_CONFIG -> replaceFragment(configTag, WelcomeConfigFragment::class.java)
            else -> finish()
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        binding.titleBar.setTitle(resId)
        applyHeaderColors()
    }

    override fun onResume() {
        super.onResume()
        applyHeaderColors()
    }

    fun <T : Fragment> replaceFragment(configTag: String, fragmentClass: Class<T>) {
        intent.putExtra("configTag", configTag)
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: fragmentClass.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.configFrameLayout, configFragment, configTag)
            .commit()
        supportFragmentManager.executePendingTransactions()
        applySearchQuery(currentSearchQuery)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
    }

    private fun applyHeaderColors() {
        binding.titleBar.setTextColor(primaryTextColor)
        binding.titleBar.setColorFilter(primaryTextColor)
        binding.titleBar.setBackgroundColor(Color.TRANSPARENT)
        binding.titleBar.elevation = 0f
    }

    private fun initSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentSearchQuery = query.orEmpty()
                applySearchQuery(currentSearchQuery)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText.orEmpty()
                applySearchQuery(currentSearchQuery)
                return true
            }
        })
        binding.searchView.setOnCloseListener {
            currentSearchQuery = ""
            applySearchQuery("")
            false
        }
    }

    private fun applySearchQuery(query: String) {
        (supportFragmentManager.findFragmentById(R.id.configFrameLayout) as? io.legado.app.lib.prefs.fragment.PreferenceFragment)
            ?.filterPreferences(query)
    }

}
