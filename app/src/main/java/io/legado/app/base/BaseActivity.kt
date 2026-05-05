package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Display
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize

abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity() {

    protected abstract val binding: VB
    private var lastThemeValuesChanged = 0L

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        if (AppConst.menuViewNames.contains(name) && parent?.parent is FrameLayout) {
            (parent.parent as View).setBackgroundResource(R.drawable.bg_popup_action_modern)
        }
        return super.onCreateView(parent, name, context, attrs)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        applyPreferredRefreshRate()
        setupSystemBar()
        setContentView(binding.root)
        upBackgroundImage()
        lastThemeValuesChanged = ThemeStore.valuesChanged(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()
        onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        applyPreferredRefreshRate()
        refreshThemeBackgroundIfChanged()
    }

    private fun refreshThemeBackgroundIfChanged() {
        val valuesChanged = ThemeStore.valuesChanged(this)
        if (valuesChanged != lastThemeValuesChanged) {
            lastThemeValuesChanged = valuesChanged
            setupSystemBar()
            upBackgroundImage()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreferredRefreshRate()
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        menu.applyTint(this, toolBarTheme)
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            supportFinishAfterTransition()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
                applyInitialWindowBackground()
            }

            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
                applyInitialWindowBackground()
            }

            else -> {
                if (AppConfig.isNightTheme) {
                    setTheme(R.style.AppTheme_Dark)
                } else {
                    setTheme(R.style.AppTheme_Light)
                }
                applyInitialWindowBackground()
            }
        }
    }

    private fun applyWindowBackgroundColor() {
        ViewCompat.setBackgroundTintList(window.decorView, null)
        window.decorView.setBackgroundColor(backgroundColor)
    }

    private fun applyInitialWindowBackground() {
        if (imageBg && ThemeConfig.hasUsableBgImage(this)) {
            ViewCompat.setBackgroundTintList(window.decorView, null)
            window.decorView.background = null
        } else {
            applyWindowBackgroundColor()
        }
    }

    open fun upBackgroundImage() {
        if (!imageBg) {
            applyWindowBackgroundColor()
            return
        }
        try {
            val drawable = ThemeConfig.getBgImage(this, windowManager.windowSize)
            if (drawable != null) {
                ViewCompat.setBackgroundTintList(window.decorView, null)
                window.decorView.background = drawable
            } else {
                applyWindowBackgroundColor()
            }
        } catch (_: OutOfMemoryError) {
            applyWindowBackgroundColor()
            toastOnUi(R.string.background_image_too_large)
        } catch (e: Exception) {
            applyWindowBackgroundColor()
            AppLog.put(getString(R.string.background_image_load_error, e.localizedMessage), e)
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    @SuppressLint("ObsoleteSdkInt")
    open fun applyPreferredRefreshRate() {
        val layoutParams = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = currentDisplay()
            val targetMode = resolvePreferredDisplayMode(display)
            layoutParams.preferredDisplayModeId = targetMode?.modeId ?: 0
            layoutParams.preferredRefreshRate = when {
                targetMode != null -> targetMode.refreshRate
                AppConfig.useHighRefreshRate -> 0f
                else -> 60f
            }
        } else {
            layoutParams.preferredRefreshRate = if (AppConfig.useHighRefreshRate) 0f else 60f
        }
        window.attributes = layoutParams
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun currentDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun resolvePreferredDisplayMode(display: Display?): Display.Mode? {
        display ?: return null
        val currentMode = display.mode
        val sameResolutionModes = display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        if (sameResolutionModes.isEmpty()) return null
        return if (AppConfig.useHighRefreshRate) {
            sameResolutionModes.maxByOrNull { it.refreshRate }
        } else {
            sameResolutionModes
                .filter { it.refreshRate <= 61f }
                .maxByOrNull { it.refreshRate }
                ?: sameResolutionModes.minByOrNull { it.refreshRate }
        }
    }

    open fun upNavigationBarColor() {
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }

    open fun observeLiveBus() {
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }
}
