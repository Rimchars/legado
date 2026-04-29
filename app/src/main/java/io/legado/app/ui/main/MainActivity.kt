@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.doOnPreDraw
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.qmdeve.liquidglass.widget.LiquidGlassView
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.ai.AiChatActivity
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.main.readrecord.ReadRecordFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.isCreated
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.ColorUtils as AppColorUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.coroutines.resume
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.dpToPx
import kotlin.time.Duration.Companion.hours

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    BottomNavigationView.OnNavigationItemSelectedListener,
    BottomNavigationView.OnNavigationItemReselectedListener,
    MainViewModel.CallBack {

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 1
    private val idRss = 2
    private val idReadRecord = 3
    private val idMy = 4
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private var pagePosition = 0
    private val fragmentMap = hashMapOf<Int, Fragment>()
    private var bottomMenuCount = 4
    private val EXIT_INTERVAL = 2000L
    private val realPositions = arrayOf(idBookshelf, idExplore, idRss, idReadRecord, idMy)
    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var onUpBooksBadgeView: BadgeView? = null
    private val bottomBarCornerRadius by lazy {
        resources.getDimension(R.dimen.main_bottom_bar_corner_radius)
    }
    private val searchButtonCornerRadius by lazy {
        resources.getDimension(R.dimen.main_bottom_bar_corner_radius)
    }
    private val bottomIndicatorCornerRadius by lazy {
        resources.getDimension(R.dimen.main_bottom_indicator_corner_radius)
    }
    private val bottomIndicatorWidth by lazy {
        resources.getDimensionPixelSize(R.dimen.main_bottom_indicator_width)
    }
    private val bottomIndicatorAnimator by lazy {
        ValueAnimator().apply {
            duration = 320L
            interpolator = OvershootInterpolator(0.55f)
        }
    }
    private val bottomGlassPulseInterpolator by lazy { AccelerateDecelerateInterpolator() }
    private var liquidGlassReady = false
    private val boundLiquidGlassViewIds = hashSetOf<Int>()
    private val hideBottomIndicatorRunnable = Runnable {
        binding.bottomNavigationIndicatorContainer.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(220L)
            .setInterpolator(bottomGlassPulseInterpolator)
            .start()
    }

    override fun setupSystemBar() {
        super.setupSystemBar()
        if (AppConfig.isMainTransparentStatusBar) {
            hideMainStatusBar()
        } else {
            showMainStatusBar()
        }
    }

    private fun hideMainStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun showMainStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        setStatusBarColorAuto(
            ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar),
            AppConfig.isTransparentStatusBar,
            fullScreen
        )
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                binding.viewPagerMain.currentItem = 0
                return@addCallback
            }
            (fragmentMap[getFragmentId(0)] as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            //隐私协议
            if (!privacyPolicy()) return@launch
            //版本更新
            upVersion()
            //设置本地密码
            setLocalPassword()
            notifyAppCrash()
            //备份同步
            backupSync()
            //设置回调
            viewModel.setActivityCallback(this@MainActivity)
            //自动更新书源
            binding.viewPagerMain.postDelayed(1000) {
                viewModel.ruleSubsUp()
            }
            //自动更新书籍
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                binding.viewPagerMain.postDelayed(2000) {
                    viewModel.upAllBookToc()
                }
            }
            binding.viewPagerMain.postDelayed(3000) {
                viewModel.postLoad()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scheduleLiquidGlassSetup()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_bookshelf ->
                viewPagerMain.setCurrentItem(0, false)

            R.id.menu_discovery ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)

            R.id.menu_rss ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)

            R.id.menu_read_record ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idReadRecord), false)

            R.id.menu_my_config ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
        return false
    }

    override fun onNavigationItemReselected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.gotoTop()
                }
            }

            R.id.menu_discovery -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[1] as? ExploreFragment)?.compressExplore()
                }
            }
        }
    }

    private fun initView() = binding.run {
        val initialPage = resolveHomePagePosition()
        pagePosition = initialPage
        viewPagerMain.setEdgeEffectColor(primaryColor)
        viewPagerMain.offscreenPageLimit = (bottomMenuCount - 1).coerceAtLeast(1)
        viewPagerMain.adapter = adapter
        viewPagerMain.setCurrentItem(initialPage, false)
        viewPagerMain.addOnPageChangeListener(PageChangeCallback())
        bottomNavigationView.setOnNavigationItemSelectedListener(this@MainActivity)
        bottomNavigationView.setOnNavigationItemReselectedListener(this@MainActivity)
        bottomNavigationView.menu.findItem(getBottomNavigationItemId(initialPage))?.isChecked = true
        searchButtonIcon.imageTintList = bottomNavigationView.itemIconTintList
        searchButton.setOnClickListener {
            startActivity(Intent(this@MainActivity, SearchActivity::class.java))
        }
        searchButton.setOnLongClickListener {
            if (AppConfig.aiAssistantEnabled) {
                startActivity(Intent(this@MainActivity, AiChatActivity::class.java))
            } else {
                toastOnUi(R.string.ai_enable_summary)
            }
            true
        }
        scheduleLiquidGlassSetup()
        contentContainer.doOnPreDraw {
            liquidGlassReady = true
            scheduleLiquidGlassSetup(delayMillis = 32L)
        }
        bottomNavigationView.doOnLayout {
            updateBottomNavigationIndicator(animate = false)
        }
        bottomControls.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val height = windowInsets.navigationBarHeight
            view.bottomPadding = height + 14.dpToPx()
            windowInsets.inset(0, 0, 0, height)
        }
    }

    private fun scheduleLiquidGlassSetup(delayMillis: Long = 0L) {
        val action = {
            if (!isFinishing) {
                setupLiquidGlass()
            }
        }
        if (delayMillis > 0L) {
            binding.bottomControls.postDelayed(delayMillis, action)
        } else {
            binding.bottomControls.post(action)
        }
    }

    private fun setupLiquidGlass() {
        binding.run {
            if (AppConfig.isEInkMode) {
                bottomNavigationGlassView.visibility = android.view.View.GONE
                bottomNavigationIndicatorContainer.visibility = android.view.View.GONE
                searchButtonGlassView.visibility = android.view.View.GONE
                bottomNavigationShellOverlay.visibility = android.view.View.GONE
                searchButtonShellOverlay.visibility = android.view.View.GONE
                bottomNavigationView.setBackgroundResource(R.drawable.bg_eink_border_top)
                searchButton.setBackgroundResource(R.drawable.bg_eink_circle_button)
                return
            }
            val effectMode = AppConfig.bottomBarEffectMode
            if (effectMode == "solid") {
                bottomNavigationGlassView.visibility = android.view.View.GONE
                bottomNavigationIndicatorGlassView.visibility = android.view.View.GONE
                searchButtonGlassView.visibility = android.view.View.GONE
                bottomNavigationShellOverlay.isVisible = true
                searchButtonShellOverlay.isVisible = true
                bottomNavigationIndicatorContainer.isVisible = true
                bottomNavigationIndicatorContainer.alpha = 1f
                bottomNavigationIndicatorContainer.scaleX = 1f
                bottomNavigationIndicatorContainer.scaleY = 1f
                bottomNavigationShellOverlay.background = createSolidBottomShellDrawable(
                    cornerRadius = bottomBarCornerRadius,
                    oval = false
                )
                searchButtonShellOverlay.background = createSolidBottomShellDrawable(
                    cornerRadius = searchButtonCornerRadius,
                    oval = true
                )
                bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
                searchButton.setBackgroundResource(R.drawable.bg_main_search_button)
                searchButtonIcon.imageTintList = bottomNavigationView.itemIconTintList
                bottomNavigationIndicatorOverlay.background = createSolidBottomIndicatorDrawable()
                updateBottomNavigationIndicator(animate = false)
                return
            }
            bottomNavigationIndicatorContainer.isVisible = true
            bottomNavigationIndicatorContainer.alpha = 0f
            bottomNavigationIndicatorContainer.scaleX = 0.82f
            bottomNavigationIndicatorContainer.scaleY = 0.82f
            bottomNavigationShellOverlay.isVisible = true
            searchButtonShellOverlay.isVisible = true
            bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
            searchButton.setBackgroundResource(R.drawable.bg_main_search_button)
            searchButtonIcon.imageTintList = bottomNavigationView.itemIconTintList
            bottomNavigationGlassView.visibility = android.view.View.VISIBLE
            bottomNavigationIndicatorGlassView.visibility = android.view.View.VISIBLE
            searchButtonGlassView.visibility = android.view.View.VISIBLE
            if (!liquidGlassReady || !contentContainer.isLaidOut || !bottomControls.isLaidOut) {
                contentContainer.doOnPreDraw {
                    liquidGlassReady = true
                    scheduleLiquidGlassSetup(delayMillis = 32L)
                }
                return
            }
            val glassLevel = when (effectMode) {
                "frosted" -> AppConfig.frostedGlassLevel / 100f
                else -> AppConfig.liquidGlassLevel / 100f
            }
            val frostedMode = effectMode == "frosted"
            val blurRadius = if (frostedMode) {
                (10f + glassLevel * 24f).dpToPx()
            } else {
                (5f + glassLevel * 14f).dpToPx()
            }
            val tintAlpha = if (frostedMode) {
                0.12f + glassLevel * 0.18f
            } else {
                0.05f + glassLevel * 0.10f
            }
            val dispersion = if (frostedMode) {
                (0.18f + glassLevel * 0.16f).coerceAtMost(0.42f)
            } else {
                0.46f + glassLevel * 0.32f
            }
            val refractionHeight = if (frostedMode) {
                (12f + glassLevel * 10f).dpToPx()
            } else {
                (18f + glassLevel * 14f).dpToPx()
            }
            val refractionOffset = if (frostedMode) {
                (36f + glassLevel * 18f).dpToPx()
            } else {
                (72f + glassLevel * 34f).dpToPx()
            }
            bottomNavigationShellOverlay.background = createLiquidGlassShellDrawable(
                glassLevel = glassLevel,
                cornerRadius = bottomBarCornerRadius,
                oval = false,
                selected = false
            )
            searchButtonShellOverlay.background = createLiquidGlassShellDrawable(
                glassLevel = glassLevel,
                cornerRadius = searchButtonCornerRadius,
                oval = true,
                selected = false
            )
            bottomNavigationIndicatorOverlay.background = createLiquidGlassShellDrawable(
                glassLevel = glassLevel,
                cornerRadius = bottomIndicatorCornerRadius,
                oval = false,
                selected = true
            )
            setupLiquidGlassView(
                liquidGlassView = bottomNavigationGlassView,
                cornerRadius = bottomBarCornerRadius,
                refractionHeight = refractionHeight,
                refractionOffset = refractionOffset,
                blurRadius = blurRadius,
                dispersion = dispersion,
                tintAlpha = tintAlpha,
                elasticEnabled = true,
                touchEffectEnabled = true
            )
            setupLiquidGlassView(
                liquidGlassView = searchButtonGlassView,
                cornerRadius = searchButtonCornerRadius,
                refractionHeight = refractionHeight,
                refractionOffset = refractionOffset,
                blurRadius = blurRadius,
                dispersion = (dispersion + 0.04f).coerceAtMost(1f),
                tintAlpha = tintAlpha,
                elasticEnabled = true,
                touchEffectEnabled = true
            )
            setupLiquidGlassView(
                liquidGlassView = bottomNavigationIndicatorGlassView,
                cornerRadius = bottomIndicatorCornerRadius,
                refractionHeight = (refractionHeight * 0.9f).coerceAtLeast(16f.dpToPx()),
                refractionOffset = (refractionOffset * 0.72f).coerceAtLeast(46f.dpToPx()),
                blurRadius = (blurRadius * 0.78f).coerceAtLeast(5f.dpToPx()),
                dispersion = (dispersion + 0.08f).coerceAtMost(1f),
                tintAlpha = (tintAlpha + 0.05f).coerceAtMost(0.28f),
                elasticEnabled = true,
                touchEffectEnabled = true
            )
        }
    }

    private fun createSolidBottomShellDrawable(cornerRadius: Float, oval: Boolean): GradientDrawable {
        val baseColor = bottomBackground
        val strokeColor = AppColorUtils.withAlpha(
            if (AppColorUtils.isColorLight(baseColor)) Color.BLACK else Color.WHITE,
            0.10f
        )
        return GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) {
                this.cornerRadius = cornerRadius
            }
            setColor(baseColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    private fun createSolidBottomIndicatorDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = bottomIndicatorCornerRadius
            setColor(primaryColor)
        }
    }

    private fun createLiquidGlassShellDrawable(
        glassLevel: Float,
        cornerRadius: Float,
        oval: Boolean,
        selected: Boolean
    ): GradientDrawable {
        val baseColor = bottomBackground
        val isLight = AppColorUtils.isColorLight(baseColor)
        val surfaceColor = if (isLight) {
            AppColorUtils.blendColors(baseColor, Color.WHITE, 0.72f)
        } else {
            AppColorUtils.blendColors(baseColor, Color.BLACK, 0.24f)
        }
        val startAlpha = (0.24f + glassLevel * 0.14f).coerceIn(0f, 0.46f)
        val centerAlpha = (0.14f + glassLevel * 0.12f).coerceIn(0f, 0.34f)
        val endAlpha = (0.10f + glassLevel * 0.10f).coerceIn(0f, 0.28f)
        val selectedBoost = if (selected) 0.08f else 0f
        val strokeAlpha = (0.22f + glassLevel * 0.22f + selectedBoost).coerceIn(0f, 0.58f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                AppColorUtils.withAlpha(surfaceColor, startAlpha + selectedBoost),
                AppColorUtils.withAlpha(surfaceColor, centerAlpha + selectedBoost),
                AppColorUtils.withAlpha(surfaceColor, endAlpha + selectedBoost)
            )
        ).apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) {
                setCornerRadius(cornerRadius)
            }
            setStroke(1.dpToPx(), AppColorUtils.withAlpha(surfaceColor, strokeAlpha))
        }
    }

    private fun setupLiquidGlassView(
        liquidGlassView: LiquidGlassView,
        cornerRadius: Float,
        refractionHeight: Float,
        refractionOffset: Float,
        blurRadius: Float,
        dispersion: Float,
        tintAlpha: Float,
        elasticEnabled: Boolean,
        touchEffectEnabled: Boolean,
    ) {
        if (boundLiquidGlassViewIds.add(liquidGlassView.id)) {
            liquidGlassView.bind(binding.contentContainer)
        }
        liquidGlassView.setCornerRadius(cornerRadius)
        liquidGlassView.setRefractionHeight(refractionHeight)
        liquidGlassView.setRefractionOffset(refractionOffset)
        liquidGlassView.setDispersion(dispersion)
        liquidGlassView.setBlurRadius(blurRadius)
        liquidGlassView.setTintAlpha(tintAlpha)
        liquidGlassView.setTintColorRed(0.70f)
        liquidGlassView.setTintColorGreen(0.79f)
        liquidGlassView.setTintColorBlue(0.86f)
        liquidGlassView.setDraggableEnabled(false)
        liquidGlassView.setElasticEnabled(elasticEnabled)
        liquidGlassView.setTouchEffectEnabled(touchEffectEnabled)
        liquidGlassView.isClickable = false
        liquidGlassView.isFocusable = false
        liquidGlassView.invalidate()
    }

    private fun updateBottomNavigationIndicator(animate: Boolean) {
        if (AppConfig.isEInkMode) return
        val menuView = binding.bottomNavigationView.getChildAt(0) as? ViewGroup ?: return
        val itemView = findBottomNavigationItemView(menuView, getBottomNavigationItemId(pagePosition))
            ?: return
        val indicator = binding.bottomNavigationIndicatorContainer
        val targetWidth = minOf(
            bottomIndicatorWidth,
            (itemView.width - 16.dpToPx()).coerceAtLeast(42.dpToPx())
        )
        indicator.layoutParams = indicator.layoutParams.apply {
            width = targetWidth
        }
        val baseX = binding.bottomNavigationView.x + menuView.x + itemView.x
        val targetX = baseX + (itemView.width - targetWidth) / 2f
        if (!animate || !indicator.isLaidOut) {
            indicator.x = targetX
            playBottomNavigationIndicatorAnimation(animate = false)
            return
        }
        val startX = indicator.x
        bottomIndicatorAnimator.cancel()
        bottomIndicatorAnimator.removeAllUpdateListeners()
        bottomIndicatorAnimator.setFloatValues(startX, targetX)
        bottomIndicatorAnimator.addUpdateListener { animator ->
            indicator.x = animator.animatedValue as Float
        }
        bottomIndicatorAnimator.start()
        playBottomNavigationIndicatorAnimation(animate = true)
    }

    private fun playBottomNavigationIndicatorAnimation(animate: Boolean) {
        if (AppConfig.isEInkMode) return
        val indicator = binding.bottomNavigationIndicatorContainer
        indicator.removeCallbacks(hideBottomIndicatorRunnable)
        indicator.animate().cancel()
        indicator.isVisible = true
        if (!animate) {
            indicator.alpha = 1f
            indicator.scaleX = 1f
            indicator.scaleY = 1f
        } else {
            indicator.alpha = 0.94f
            indicator.scaleX = 0.90f
            indicator.scaleY = 1.08f
            indicator.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280L)
                .setInterpolator(OvershootInterpolator(0.78f))
                .start()
            binding.bottomNavigationGlass.animate()
                .scaleX(1.01f)
                .scaleY(1.02f)
                .setDuration(120L)
                .withEndAction {
                    binding.bottomNavigationGlass.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220L)
                        .setInterpolator(bottomGlassPulseInterpolator)
                        .start()
                }
                .start()
        }
        indicator.postDelayed(hideBottomIndicatorRunnable, 780L)
    }

    private fun findBottomNavigationItemView(menuView: ViewGroup, itemId: Int): View? {
        for (index in 0 until menuView.childCount) {
            val child = menuView.getChildAt(index)
            if (child.id == itemId && child.visibility == View.VISIBLE) {
                return child
            }
        }
        var visibleIndex = 0
        for (index in 0 until menuView.childCount) {
            val child = menuView.getChildAt(index)
            if (child.visibility == View.VISIBLE) {
                if (visibleIndex == pagePosition) return child
                visibleIndex++
            }
        }
        return null
    }

    private fun getBottomNavigationItemId(position: Int): Int {
        return when (realPositions[position]) {
            idBookshelf -> R.id.menu_bookshelf
            idExplore -> R.id.menu_discovery
            idRss -> R.id.menu_rss
            idReadRecord -> R.id.menu_read_record
            else -> R.id.menu_my_config
        }
    }

    /**
     * 用户隐私与协议
     */
    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        val privacyPolicy = String(assets.open("privacyPolicy.md").readBytes())
        alert(getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(R.string.refuse) {
                finish()
                block.resume(false)
            }
        }
    }

    /**
     * 版本更新日志
     */
    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds < System.currentTimeMillis()) {
                    (AppUpdate.gitHubUpdate ?: AppUpdate.giteeUpdate).check(lifecycleScope)
                        .onSuccess {
                            showDialogFragment(
                                UpdateDialog(it)
                            )
                        }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            block.resume(null)
            return@sc
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(assets.open("web/help/md/appHelp.md").readBytes())
            val dialog = TextDialog(getString(R.string.help), help, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else if (!BuildConfig.DEBUG) {
            val log = String(assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else {
            block.resume(null)
        }
    }

    /**
     * 设置本地密码
     */
    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resume(null)
            return@sc
        }
        alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "password"
            }
            customView {
                editTextBinding.root
            }
            onDismiss {
                block.resume(null)
            }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    /**
     * 备份同步
     */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton()
                    okButton {
                        viewModel.restoreWebDav(lastBackupFile.displayName)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /**
     * 如果重启太快fragment不会重建,这里更新一下书架的排序
     */
    override fun recreate() {
        (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.run {
            upSort()
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            if (onUpBooksBadgeView == null) {
                onUpBooksBadgeView = binding.bottomNavigationView.addBadgeView(0)
            }
            onUpBooksBadgeView!!.setBadgeCount(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                if (it) {
                    viewPagerMain.setCurrentItem(bottomMenuCount - 1, false)
                }
            }
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
    }

    private fun upBottomMenu() {
        val showDiscovery = AppConfig.showDiscovery
        val showRss = AppConfig.showRSS && binding.bottomNavigationView.menu.findItem(R.id.menu_rss) != null
        binding.bottomNavigationView.menu.let { menu ->
            menu.findItem(R.id.menu_discovery).isVisible = showDiscovery
            menu.findItem(R.id.menu_rss)?.isVisible = showRss
        }
        var index = 0
        realPositions[index] = idBookshelf
        if (showDiscovery) {
            index++
            realPositions[index] = idExplore
        }
        if (showRss) {
            index++
            realPositions[index] = idRss
        }
        index++
        realPositions[index] = idReadRecord
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        adapter.notifyDataSetChanged()
        binding.bottomNavigationView.post {
            updateBottomNavigationIndicator(animate = false)
        }
    }

    private fun upHomePage() {
        binding.viewPagerMain.setCurrentItem(resolveHomePagePosition(), false)
    }

    fun selectAdjacentMainPage(direction: Int): Boolean {
        val target = (binding.viewPagerMain.currentItem + direction)
            .coerceIn(0, bottomMenuCount - 1)
        if (target == binding.viewPagerMain.currentItem) return false
        binding.viewPagerMain.setCurrentItem(target, true)
        return true
    }

    private fun resolveHomePagePosition(): Int {
        val visiblePositions = realPositions.take(bottomMenuCount)
        return when (AppConfig.defaultHomePage) {
            "explore" -> if (AppConfig.showDiscovery) visiblePositions.indexOf(idExplore) else 0
            "rss" -> visiblePositions.indexOf(idRss)
            "my" -> visiblePositions.indexOf(idMy)
            else -> 0
        }.takeIf { it >= 0 } ?: 0
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            pagePosition = position
            binding.bottomNavigationView.menu.findItem(getBottomNavigationItemId(position))?.isChecked = true
            updateBottomNavigationIndicator(animate = true)
        }

    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position
                ?: return POSITION_NONE
            val fragmentId = getId(position)
            if ((fragmentId == idBookshelf1 && any is BookshelfFragment1)
                || (fragmentId == idBookshelf2 && any is BookshelfFragment2)
                || (fragmentId == idExplore && any is ExploreFragment)
                || (fragmentId == idRss && any is RssFragment)
                || (fragmentId == idReadRecord && any is ReadRecordFragment)
                || (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                idRss -> RssFragment(position)
                idReadRecord -> ReadRecordFragment(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }

    }

    override fun openImportUi(type:Int, source: String) {
        when (type) {
            0 -> showDialogFragment(
                ImportBookSourceDialog(source)
            )
            1 -> showDialogFragment(
                ImportRssSourceDialog(source)
            )
            2 -> showDialogFragment(
                ImportReplaceRuleDialog(source)
            )
        }
    }

}
