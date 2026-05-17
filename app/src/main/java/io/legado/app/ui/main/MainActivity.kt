@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
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
import com.google.android.material.navigation.NavigationBarView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.about.loadReadRecordAvatar
import io.legado.app.ui.about.loadReadRecordCover
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
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.isCreated
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setHuaweiDisplayCutoutShortEdgesCompat
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.ColorUtils as AppColorUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.coroutines.resume
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.dpToPx
import kotlin.math.abs
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
    private var sidebarDownX = 0f
    private var sidebarDownY = 0f
    private var sidebarGestureHandled = false
    private var sidebarGestureAllowed = false
    private var sideNavigationGravity = AppConfig.bottomBarSidebarGravity
    private var sideNavigationLockedGravity: String? = null
    private var sideBookshelfGroupsExpanded = false
    private var sideBookGroups: List<BookGroup> = emptyList()
    private var sideNavigationBackgroundJob: Job? = null
    private var sideNavigationBackgroundLoadingKey: String? = null
    private var sideNavigationBackgroundKey: String? = null
    private var sideNavigationBackgroundBitmap: Bitmap? = null
    private var aiFloatingBall: FrameLayout? = null
    private var aiFloatingBallDragged = false
    private val aiFloatingBallAttachRunnable = Runnable {
        aiFloatingBall?.let { placeAiFloatingBall(it, animate = true, attached = true) }
    }
    private var bottomNavigationConfigSignature: String? = null
    private var bottomNavigationInset = 0
    private val sidebarTouchSlop by lazy {
        ViewConfiguration.get(this).scaledTouchSlop
    }
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
    private val liquidGlassSetupRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            setupLiquidGlass()
        }
    }
    private var mergedDiscoveryLongClickView: View? = null
    private var sideNavigationOpen = false
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
        setHuaweiDisplayCutoutShortEdgesCompat(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun showMainStatusBar() {
        setHuaweiDisplayCutoutShortEdgesCompat(false)
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
            if (isSidebarMode() && sideNavigationOpen) {
                closeSideNavigation()
                return@addCallback
            }
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
        refreshBottomNavigationConfig()
        if (isSidebarMode()) {
            updateSideGoalHeader()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        return handleNavigationItemSelected(item, closeSidebar = true)
    }

    private fun handleNavigationItemSelected(item: MenuItem, closeSidebar: Boolean): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_bookshelf ->
                viewPagerMain.setCurrentItem(0, false)

            R.id.menu_discovery ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(resolveDiscoveryNavTarget()), true)

            R.id.menu_rss ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)

            R.id.menu_read_record ->
                realPositions.indexOf(idReadRecord).takeIf { it >= 0 }?.let {
                    viewPagerMain.setCurrentItem(it, false)
                }

            R.id.menu_my_config ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
        if (closeSidebar && isSidebarMode()) {
            closeSideNavigation()
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
                    when (resolveDiscoveryNavTarget()) {
                        idExplore -> (fragmentMap[idExplore] as? ExploreFragment)?.compressExplore()
                        idRss -> (fragmentMap[idRss] as? RssFragment)?.gotoTop()
                    }
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
        bindSideNavigationButtons()
        bottomNavigationView.menu.findItem(getBottomNavigationItemId(initialPage))?.isChecked = true
        applyBottomNavigationIcons()
        searchButton.setOnClickListener {
            startActivity(Intent(this@MainActivity, SearchActivity::class.java))
        }
        sideSearchButton.setOnClickListener {
            closeSideNavigation()
            startActivity(Intent(this@MainActivity, SearchActivity::class.java))
        }
        sideSearchButton.setOnLongClickListener {
            closeSideNavigation()
            if (AppConfig.aiAssistantEnabled) {
                startActivity(Intent(this@MainActivity, AiChatActivity::class.java))
            } else {
                toastOnUi(R.string.ai_enable_summary)
            }
            true
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
        sideNavigationPanel.doOnLayout {
            placeSideNavigation(animate = false)
        }
        appDb.bookGroupDao.show.observe(this@MainActivity) {
            sideBookGroups = it
            renderSideBookshelfGroups()
        }
        bottomControls.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val height = windowInsets.navigationBarHeight
            bottomNavigationInset = height
            if (isStandardBottomMode()) {
                view.bottomPadding = 0
                applyBottomNavigationShape(standardMode = true)
            } else {
                view.bottomPadding = height + 14.dpToPx()
                applyBottomNavigationShape(standardMode = false)
            }
            windowInsets.inset(0, 0, 0, height)
        }
        sideNavigationPanel.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            view.bottomPadding = 0
            val statusTop = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBottom = windowInsets.navigationBarHeight
            sideNavigationContent.setPadding(
                resources.getDimensionPixelSize(R.dimen.main_sidebar_panel_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.main_sidebar_header_padding_top) +
                        statusTop + 8.dpToPx(),
                resources.getDimensionPixelSize(R.dimen.main_sidebar_panel_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.main_sidebar_panel_padding_vertical) +
                        navigationBottom + 8.dpToPx()
            )
            windowInsets
        }
        bindMergedDiscoveryLongClick()
        applyBottomLayoutMode()
    }

    private fun scheduleLiquidGlassSetup(delayMillis: Long = 0L) {
        binding.bottomControls.removeCallbacks(liquidGlassSetupRunnable)
        if (delayMillis > 0L) {
            binding.bottomControls.postDelayed(liquidGlassSetupRunnable, delayMillis)
        } else {
            binding.bottomControls.post(liquidGlassSetupRunnable)
        }
    }

    private fun refreshBottomNavigationConfig() {
        val signature = NavigationBarIconConfig.currentSignature(AppConfig.isNightTheme)
        if (bottomNavigationConfigSignature == signature) {
            return
        }
        bottomNavigationConfigSignature = signature
        NavigationBarIconConfig.applyCurrentBottomConfig(AppConfig.isNightTheme)
        applyBottomNavigationIcons()
        applyBottomLayoutMode()
        scheduleLiquidGlassSetup()
        binding.bottomNavigationView.doOnLayout {
            updateBottomNavigationIndicator(animate = false)
        }
    }

    private fun refreshMainTopBars(view: View) {
        if (view is MainTopBarView) {
            view.refreshStyle()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                refreshMainTopBars(view.getChildAt(index))
            }
        }
    }

    private fun isSidebarMode(): Boolean {
        return AppConfig.bottomBarLayoutMode == "sidebar"
    }

    private fun isStandardBottomMode(): Boolean {
        return AppConfig.bottomBarLayoutMode == "standard"
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleSidebarSwipe(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleSidebarSwipe(ev: MotionEvent): Boolean {
        if (!isSidebarMode()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sidebarDownX = ev.rawX
                sidebarDownY = ev.rawY
                sidebarGestureHandled = false
                val edgeGuard = 28.dpToPx()
                sidebarGestureAllowed = ev.rawX > edgeGuard && ev.rawX < binding.root.width - edgeGuard
            }

            MotionEvent.ACTION_MOVE -> {
                if (!sidebarGestureAllowed) return false
                if (sidebarGestureHandled) return true
                val dx = ev.rawX - sidebarDownX
                val dy = ev.rawY - sidebarDownY
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (absDx < sidebarTouchSlop * 3 || absDx < absDy * 1.35f) return false
                val handled = if (sideNavigationOpen) {
                    if (isSidebarCloseGesture(dx)) {
                        closeSideNavigation()
                        true
                    } else {
                        false
                    }
                } else if (dx != 0f) {
                    openSideNavigation(if (dx < 0f) "end" else "start")
                    true
                } else {
                    false
                }
                if (handled) {
                    cancelChildTouch(ev)
                    sidebarGestureHandled = true
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sidebarGestureAllowed = false
                if (sidebarGestureHandled) {
                    sidebarGestureHandled = false
                    return true
                }
            }
        }
        return false
    }

    private fun isSidebarCloseGesture(dx: Float): Boolean {
        val gravity = sideNavigationLockedGravity ?: sideNavigationGravity
        return if (gravity == "end") {
            dx > 0f
        } else {
            dx < 0f
        }
    }

    private fun cancelChildTouch(ev: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(ev).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        runCatching {
            binding.contentContainer.dispatchTouchEvent(cancelEvent)
        }
        cancelEvent.recycle()
    }

    private fun applyBottomLayoutMode() = binding.run {
        val sidebarMode = isSidebarMode()
        val standardMode = isStandardBottomMode()
        viewPagerMain.swipeEnabled = !sidebarMode
        bottomControls.isVisible = !sidebarMode
        sideNavigationPanel.isVisible = sidebarMode
        applyBottomNavigationShape(standardMode)
        updateAiFloatingBall()
        if (sidebarMode) {
            if (!sideNavigationOpen) {
                sideNavigationGravity = AppConfig.bottomBarSidebarGravity
                sideNavigationLockedGravity = null
            }
            bottomIndicatorAnimator.cancel()
            bottomNavigationIndicatorContainer.isVisible = false
            sideNavigationScrim.background = createSideNavigationScrimDrawable()
            sideNavigationPanel.background = createSideNavigationPanelDrawable()
            sideNavigationHeader.background = createSideNavigationHeaderDrawable()
            sideSearchRow.background = createSideNavigationSearchDrawable()
            sideNavAiRow.background = createSideNavigationRowDrawable(false)
            applySideNavigationBackground()
            updateSideGoalHeader()
            updateSideNavigationItems()
            placeSideNavigation(animate = false)
        } else {
            sideNavigationOpen = false
            sideNavigationLockedGravity = null
            sideNavigationPanel.animate().cancel()
            sideNavigationScrim.animate().cancel()
            sideNavigationScrim.visibility = View.GONE
            sideNavigationPanel.visibility = View.GONE
            bottomNavigationView.menu.findItem(getBottomNavigationItemId(pagePosition))?.isChecked = true
        }
    }

    private fun applyBottomNavigationShape(standardMode: Boolean) = binding.run {
        searchButtonContainer.isVisible = !standardMode
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.main_bottom_nav_horizontal_padding)
        val standardContentHeight = resources.getDimensionPixelSize(R.dimen.main_bottom_standard_height)
        val floatingContentHeight = resources.getDimensionPixelSize(R.dimen.main_bottom_bar_height)
        bottomControls.setPadding(
            if (standardMode) 0 else resources.getDimensionPixelSize(R.dimen.main_bottom_controls_horizontal_padding),
            bottomControls.paddingTop,
            if (standardMode) 0 else resources.getDimensionPixelSize(R.dimen.main_bottom_controls_horizontal_padding),
            if (standardMode) 0 else bottomControls.paddingBottom
        )
        bottomNavigationView.labelVisibilityMode = if (standardMode) {
            NavigationBarView.LABEL_VISIBILITY_UNLABELED
        } else {
            NavigationBarView.LABEL_VISIBILITY_UNLABELED
        }
        bottomNavigationView.itemIconSize = if (standardMode) {
            resources.getDimensionPixelSize(R.dimen.main_bottom_standard_icon_size)
        } else {
            resources.getDimensionPixelSize(R.dimen.main_bottom_nav_icon_size)
        }
        bottomNavigationView.setPadding(
            horizontalPadding,
            0,
            horizontalPadding,
            if (standardMode) bottomNavigationInset else 0
        )
        bottomNavigationView.minimumHeight = if (standardMode) {
            standardContentHeight + bottomNavigationInset
        } else {
            floatingContentHeight
        }
        bottomNavigationGlass.layoutParams = bottomNavigationGlass.layoutParams.apply {
            height = if (standardMode) standardContentHeight + bottomNavigationInset else floatingContentHeight
        }
        ConstraintSet().apply {
            clone(bottomControls)
            clear(R.id.bottom_navigation_glass, ConstraintSet.END)
            clear(R.id.bottom_navigation_glass, ConstraintSet.BOTTOM)
            clear(R.id.bottom_navigation_glass, ConstraintSet.TOP)
            if (standardMode) {
                connect(R.id.bottom_navigation_glass, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                setMargin(R.id.bottom_navigation_glass, ConstraintSet.END, 0)
                connect(R.id.bottom_navigation_glass, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                connect(R.id.bottom_navigation_glass, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            } else {
                connect(R.id.bottom_navigation_glass, ConstraintSet.END, R.id.search_button_container, ConstraintSet.START)
                setMargin(R.id.bottom_navigation_glass, ConstraintSet.END, resources.getDimensionPixelSize(R.dimen.main_bottom_bar_gap))
                connect(R.id.bottom_navigation_glass, ConstraintSet.BOTTOM, R.id.search_button_container, ConstraintSet.BOTTOM)
                connect(R.id.bottom_navigation_glass, ConstraintSet.TOP, R.id.search_button_container, ConstraintSet.TOP)
            }
            applyTo(bottomControls)
        }
    }

    private fun updateAiFloatingBall(): Unit = binding.run {
        val shouldShow = isStandardBottomMode() && AppConfig.aiAssistantEnabled && !isSidebarMode()
        if (!shouldShow) {
            aiFloatingBall?.removeCallbacks(aiFloatingBallAttachRunnable)
            aiFloatingBall?.isVisible = false
            return
        }
        val ball = aiFloatingBall ?: createAiFloatingBall().also {
            aiFloatingBall = it
            root.addView(it)
        }
        ball.isVisible = true
        ball.bringToFront()
        ball.post {
            placeAiFloatingBall(ball, animate = false, attached = true)
            scheduleAiFloatingBallAttach()
        }
    }

    private fun createAiFloatingBall(): FrameLayout {
        val size = resources.getDimensionPixelSize(R.dimen.main_ai_floating_ball_size)
        val iconPadding = resources.getDimensionPixelSize(R.dimen.main_ai_floating_ball_icon_padding)
        return FrameLayout(this).apply {
            elevation = resources.getDimension(R.dimen.main_search_button_elevation)
            background = createSolidBottomShellDrawable(bottomBarCornerRadius, oval = true)
            layoutParams = ConstraintLayout.LayoutParams(size, size)
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(
                    NavigationBarIconConfig.currentDrawable(this@MainActivity, "ai", false)
                        ?: ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_bottom_ai_assistant)
                )
                imageTintList = binding.bottomNavigationView.createThemeColorStateList()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                if (!aiFloatingBallDragged) {
                    startActivity(Intent(this@MainActivity, AiChatActivity::class.java))
                }
            }
            setOnTouchListener(AiFloatingBallTouchListener(this))
        }
    }

    private fun scheduleAiFloatingBallAttach() {
        aiFloatingBall?.removeCallbacks(aiFloatingBallAttachRunnable)
        aiFloatingBall?.postDelayed(aiFloatingBallAttachRunnable, 3000L)
    }

    private fun placeAiFloatingBall(ball: View, animate: Boolean, attached: Boolean) = binding.run {
        val parentWidth = root.width
        val parentHeight = root.height
        if (parentWidth <= 0 || parentHeight <= 0 || ball.width <= 0 || ball.height <= 0) return@run
        val side = getPrefInt(PreferKey.aiFloatingBallSide, 1).coerceIn(0, 1)
        val yPercent = getPrefInt(PreferKey.aiFloatingBallYPercent, 50).coerceIn(8, 92)
        val hiddenOffset = if (attached) (ball.width * 0.5f) else 0f
        val targetX = if (side == 0) -hiddenOffset else parentWidth - ball.width + hiddenOffset
        val safeMargin = resources.getDimensionPixelSize(R.dimen.main_ai_floating_ball_safe_margin)
        val targetY = ((parentHeight - ball.height) * yPercent / 100f)
            .coerceIn(safeMargin.toFloat(), (parentHeight - ball.height - safeMargin).toFloat())
        if (animate) {
            ball.animate()
                .x(targetX)
                .y(targetY)
                .setDuration(180L)
                .setInterpolator(bottomGlassPulseInterpolator)
                .start()
        } else {
            ball.x = targetX
            ball.y = targetY
        }
    }

    private inner class AiFloatingBallTouchListener(private val target: View) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.removeCallbacks(aiFloatingBallAttachRunnable)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = target.x
                    downY = target.y
                    aiFloatingBallDragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!aiFloatingBallDragged && (abs(dx) > sidebarTouchSlop || abs(dy) > sidebarTouchSlop)) {
                        aiFloatingBallDragged = true
                    }
                    if (aiFloatingBallDragged) {
                        val parent = binding.root
                        target.x = (downX + dx).coerceIn(-target.width * 0.45f, parent.width - target.width * 0.55f)
                        target.y = (downY + dy).coerceIn(12.dpToPx().toFloat(), (parent.height - target.height - 12.dpToPx()).toFloat())
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (aiFloatingBallDragged) {
                        val side = if (target.x + target.width / 2f < binding.root.width / 2f) 0 else 1
                        val yPercent = ((target.y / (binding.root.height - target.height).coerceAtLeast(1)) * 100).toInt()
                            .coerceIn(8, 92)
                        putPrefInt(PreferKey.aiFloatingBallSide, side)
                        putPrefInt(PreferKey.aiFloatingBallYPercent, yPercent)
                        placeAiFloatingBall(target, animate = true, attached = false)
                        scheduleAiFloatingBallAttach()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        target.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun bindSideNavigationButtons() = binding.run {
        sideNavigationScrim.setOnClickListener {
            closeSideNavigation()
        }
        sideSearchRow.setOnClickListener {
            closeSideNavigation()
            startActivity(Intent(this@MainActivity, SearchActivity::class.java))
        }
        sideNavAiRow.setOnClickListener {
            closeSideNavigation()
            startActivity(Intent(this@MainActivity, AiChatActivity::class.java))
        }
        sideNavAi.setOnClickListener {
            sideNavAiRow.performClick()
        }
        sideNavigationRowMap().forEach { (itemId, row) ->
            row.setOnClickListener {
                if (itemId == R.id.menu_bookshelf) {
                    sideBookshelfGroupsExpanded = !sideBookshelfGroupsExpanded
                    renderSideBookshelfGroups()
                    return@setOnClickListener
                }
                val menuItem = bottomNavigationView.menu.findItem(itemId) ?: return@setOnClickListener
                if (menuItem.itemId == getBottomNavigationItemId(pagePosition)) {
                    onNavigationItemReselected(menuItem)
                    closeSideNavigation()
                } else {
                    handleNavigationItemSelected(menuItem, closeSidebar = true)
                }
            }
        }
        sideNavigationButtonMap().forEach { (itemId, button) ->
            button.setOnClickListener {
                sideNavigationRowMap()[itemId]?.performClick()
            }
        }
    }

    private fun sideNavigationButtonMap(): Map<Int, AppCompatImageButton> = binding.run {
        linkedMapOf(
            R.id.menu_bookshelf to sideNavBookshelf,
            R.id.menu_discovery to sideNavDiscovery,
            R.id.menu_rss to sideNavRss,
            R.id.menu_read_record to sideNavReadRecord,
            R.id.menu_my_config to sideNavMyConfig
        )
    }

    private fun sideNavigationRowMap(): Map<Int, View> = binding.run {
        linkedMapOf(
            R.id.menu_bookshelf to sideNavBookshelfRow,
            R.id.menu_discovery to sideNavDiscoveryRow,
            R.id.menu_rss to sideNavRssRow,
            R.id.menu_read_record to sideNavReadRecordRow,
            R.id.menu_my_config to sideNavMyConfigRow
        )
    }

    private fun sideNavigationTextMap(): Map<Int, TextView> = binding.run {
        linkedMapOf(
            R.id.menu_bookshelf to sideNavBookshelfText,
            R.id.menu_discovery to sideNavDiscoveryText,
            R.id.menu_rss to sideNavRssText,
            R.id.menu_read_record to sideNavReadRecordText,
            R.id.menu_my_config to sideNavMyConfigText
        )
    }

    private fun updateSideNavigationItems() = binding.run {
        val selectedItemId = getBottomNavigationItemId(pagePosition)
        val mergedDiscovery = AppConfig.mergeDiscoveryRss && AppConfig.showDiscovery && AppConfig.showRSS
        sideNavigationButtonMap().forEach { (itemId, button) ->
            val menuItem = bottomNavigationView.menu.findItem(itemId)
            val visible = menuItem?.isVisible == true && !(mergedDiscovery && itemId == R.id.menu_rss)
            sideNavigationRowMap()[itemId]?.isVisible = visible
            button.isVisible = visible
            button.isSelected = itemId == selectedItemId
            val title = sideNavigationTitle(itemId, menuItem?.title)
            button.contentDescription = title
            button.setImageDrawable(menuItem?.icon?.constantState?.newDrawable()?.mutate() ?: menuItem?.icon)
            button.imageTintList = null
            sideNavigationTextMap()[itemId]?.let {
                it.text = title
                it.applyUiTitleTypeface(this@MainActivity)
            }
            sideNavigationRowMap()[itemId]?.background = createSideNavigationRowDrawable(itemId == selectedItemId)
        }
        sideNavBookshelfGroups.isVisible = sideBookshelfGroupsExpanded &&
                bottomNavigationView.menu.findItem(R.id.menu_bookshelf)?.isVisible == true
        sideNavAiRow.isVisible = AppConfig.aiAssistantEnabled
        sideNavAi.setImageDrawable(NavigationBarIconConfig.currentDrawable(this@MainActivity, "ai", false))
        sideNavAi.imageTintList = bottomNavigationView.createThemeColorStateList()
        sideNavAi.contentDescription = getString(R.string.side_nav_assistant)
        sideNavAiText.text = getString(R.string.side_nav_assistant)
        sideNavAiText.applyUiTitleTypeface(this@MainActivity)
    }

    private fun sideNavigationTitle(itemId: Int, fallback: CharSequence?): CharSequence {
        return when (itemId) {
            R.id.menu_read_record -> getString(R.string.side_nav_stats)
            else -> fallback ?: ""
        }
    }

    private fun renderSideBookshelfGroups() {
        binding.run {
        sideNavBookshelfGroups.removeAllViews()
        val visible = sideBookshelfGroupsExpanded &&
                isSidebarMode() &&
                bottomNavigationView.menu.findItem(R.id.menu_bookshelf)?.isVisible == true &&
                sideBookGroups.isNotEmpty()
        sideNavBookshelfGroups.isVisible = visible
        if (!visible) return@run
        val savedIndex = AppConfig.saveTabPosition.coerceAtLeast(0)
        sideBookGroups.forEachIndexed { index, group ->
            sideNavBookshelfGroups.addView(createSideBookshelfGroupRow(group, index == savedIndex))
        }
        }
    }

    private fun createSideBookshelfGroupRow(group: BookGroup, selected: Boolean): View {
        return TextView(this).apply {
            text = group.groupName
            textSize = 15f
            applyUiTitleTypeface(this@MainActivity)
            setTextColor(
                if (selected) {
                    ContextCompat.getColor(this@MainActivity, R.color.primaryText)
                } else {
                    ContextCompat.getColor(this@MainActivity, R.color.secondaryText)
                }
            )
            maxLines = 1
            includeFontPadding = false
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
            background = createSideNavigationGroupDrawable(selected)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                44.dpToPx()
            ).apply {
                bottomMargin = 6.dpToPx()
            }
            setOnClickListener {
                switchToBookshelfGroup(group)
                closeSideNavigation()
            }
        }
    }

    private fun switchToBookshelfGroup(group: BookGroup) {
        val index = sideBookGroups.indexOfFirst { it.groupId == group.groupId }
        if (index >= 0) {
            AppConfig.saveTabPosition = index
        }
        binding.viewPagerMain.setCurrentItem(0, false)
        binding.root.post {
            when (val fragment = fragmentMap[getFragmentId(0)]) {
                is BookshelfFragment1 -> fragment.switchToGroupId(group.groupId)
                is BookshelfFragment2 -> fragment.switchToGroupId(group.groupId)
            }
        }
    }

    private fun placeSideNavigation(animate: Boolean) {
        if (!isSidebarMode()) return
        binding.run {
            sideNavigationPanel.animate().cancel()
            updateSideNavigationPanelWidth()
            if (sideNavigationPanel.width == 0) {
                sideNavigationPanel.doOnLayout { placeSideNavigation(animate) }
                return@run
            }
            applySideNavigationEdge(sideNavigationGravity)
            val closedOffset = sideNavigationClosedOffset()
            val target = if (sideNavigationOpen) 0f else closedOffset.toFloat()
            if (animate) {
                animateSideNavigationScrim(sideNavigationOpen)
                sideNavigationPanel.animate()
                    .translationX(target)
                    .setDuration(220L)
                    .setInterpolator(bottomGlassPulseInterpolator)
                    .start()
            } else {
                sideNavigationPanel.translationX = target
                sideNavigationScrim.alpha = if (sideNavigationOpen) 1f else 0f
                sideNavigationScrim.isVisible = sideNavigationOpen
            }
        }
    }

    private fun applySideNavigationEdge(gravity: String) = binding.run {
        val fromEnd = gravity == "end"
        (sideNavigationPanel.layoutParams as? ConstraintLayout.LayoutParams)?.let {
            it.startToStart = ConstraintSet.PARENT_ID
            it.endToEnd = ConstraintSet.PARENT_ID
            it.startToEnd = ConstraintLayout.LayoutParams.UNSET
            it.endToStart = ConstraintLayout.LayoutParams.UNSET
            it.horizontalBias = if (fromEnd) 1f else 0f
            sideNavigationPanel.layoutParams = it
        }
    }

    private fun sideNavigationClosedOffset(): Int = binding.run {
        if (sideNavigationGravity == "end") {
            sideNavigationPanel.width + 14.dpToPx()
        } else {
            -sideNavigationPanel.width - 14.dpToPx()
        }
    }

    private fun updateSideNavigationPanelWidth() = binding.run {
        val rootWidth = root.width
        val rootHeight = root.height
        if (rootWidth <= 0 || rootHeight <= 0) return@run
        val percent = if (rootWidth > rootHeight) 0.33f else 0.66f
        (sideNavigationPanel.layoutParams as? ConstraintLayout.LayoutParams)?.let {
            if (it.matchConstraintPercentWidth != percent) {
                it.matchConstraintPercentWidth = percent
                sideNavigationPanel.layoutParams = it
            }
        }
        applySideNavigationBackground()
    }

    private fun applySideNavigationSurface() = binding.run {
        sideNavigationPanel.background = createSideNavigationPanelDrawable()
        sideNavigationHeader.background = createSideNavigationHeaderDrawable()
        sideSearchRow.background = createSideNavigationSearchDrawable()
        updateSideNavigationItems()
    }

    private fun clearSideNavigationBackground(cancelLoading: Boolean = true) = binding.run {
        if (cancelLoading) {
            sideNavigationBackgroundJob?.cancel()
            sideNavigationBackgroundJob = null
            sideNavigationBackgroundLoadingKey = null
        }
        sideNavigationBackgroundKey = null
        sideNavigationBackground.setImageDrawable(null)
        sideNavigationBackground.isVisible = false
        sideNavigationBackgroundBitmap?.takeIf { !it.isRecycled }?.recycle()
        sideNavigationBackgroundBitmap = null
        applySideNavigationSurface()
    }

    private fun applySideNavigationBackground() = binding.run {
        val path = NavigationBarIconConfig.currentSidebarBackgroundPath(AppConfig.isNightTheme)
        if (path.isNullOrBlank()) {
            clearSideNavigationBackground()
            return@run
        }
        val targetWidth = sideNavigationPanel.width.takeIf { it > 0 } ?: root.width
        val targetHeight = sideNavigationPanel.height.takeIf { it > 0 } ?: root.height
        if (targetWidth <= 0 || targetHeight <= 0) {
            applySideNavigationSurface()
            return@run
        }
        val cacheKey = "$path@$targetWidth@$targetHeight"
        sideNavigationBackgroundBitmap?.takeIf {
            sideNavigationBackgroundKey == cacheKey && !it.isRecycled
        }?.let { bitmap ->
            sideNavigationBackground.setImageBitmap(bitmap)
            sideNavigationBackground.isVisible = true
            applySideNavigationSurface()
            return@run
        }
        if (sideNavigationBackgroundLoadingKey == cacheKey) {
            applySideNavigationSurface()
            return@run
        }
        sideNavigationBackgroundJob?.cancel()
        sideNavigationBackgroundLoadingKey = cacheKey
        if (sideNavigationBackgroundKey != cacheKey) {
            sideNavigationBackground.setImageDrawable(null)
            sideNavigationBackground.isVisible = false
        }
        applySideNavigationSurface()
        sideNavigationBackgroundJob = lifecycleScope.launch {
            val bitmap = withContext(IO) {
                kotlin.runCatching {
                    BitmapUtils.decodeBitmap(path, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
                }.getOrNull()
            }
            if (sideNavigationBackgroundLoadingKey != cacheKey ||
                NavigationBarIconConfig.currentSidebarBackgroundPath(AppConfig.isNightTheme) != path ||
                isFinishing ||
                isDestroyed
            ) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                return@launch
            }
            sideNavigationBackgroundLoadingKey = null
            sideNavigationBackgroundKey = cacheKey
            sideNavigationBackgroundBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            sideNavigationBackgroundBitmap = bitmap
            if (bitmap == null) {
                sideNavigationBackground.setImageDrawable(null)
                sideNavigationBackground.isVisible = false
            } else {
                sideNavigationBackground.setImageBitmap(bitmap)
                sideNavigationBackground.isVisible = true
            }
            applySideNavigationSurface()
        }
    }

    private fun updateSideGoalHeader() {
        lifecycleScope.launch {
            val goalConfig = ReadRecordWidgetStore.loadGoalConfig()
            val todayTime = withContext(IO) {
                appDb.readRecordDailyDao.get(java.time.LocalDate.now().toString())?.readTime ?: 0L
            }
            val todayText = formatSideReadDuration(todayTime)
            binding.sideGoalAvatar.loadReadRecordAvatar(goalConfig.avatar)
            binding.sideGoalUserName.text = goalConfig.userName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.read_record_goal_card)
            binding.sideGoalToday.text = getString(R.string.read_record_goal_today, todayText)
            binding.sideGoalUserName.applyUiTitleTypeface(this@MainActivity)
            binding.sideGoalToday.typeface = this@MainActivity.uiTypeface()
        }
    }

    private fun formatSideReadDuration(duration: Long): String {
        val totalMinutes = (duration / DateUtils.MINUTE_IN_MILLIS).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> {
                "${getString(R.string.duration_hour, hours)} ${getString(R.string.duration_minute, minutes)}"
            }

            hours > 0 -> getString(R.string.duration_hour, hours)
            else -> getString(R.string.duration_minute, minutes)
        }
    }

    private fun animateSideNavigationScrim(show: Boolean) = binding.run {
        sideNavigationScrim.animate().cancel()
        if (show) {
            sideNavigationScrim.alpha = 0f
            sideNavigationScrim.isVisible = true
            sideNavigationScrim.animate()
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(bottomGlassPulseInterpolator)
                .start()
        } else {
            sideNavigationScrim.animate()
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(bottomGlassPulseInterpolator)
                .withEndAction {
                    if (!sideNavigationOpen) {
                        sideNavigationScrim.isVisible = false
                    }
                }
                .start()
        }
    }

    private fun openSideNavigation(gravity: String) {
        if (!isSidebarMode()) return
        if (sideNavigationOpen) return
        sideNavigationGravity = gravity.takeIf { it == "end" } ?: "start"
        sideNavigationLockedGravity = sideNavigationGravity
        binding.sideNavigationPanel.background = createSideNavigationPanelDrawable()
        binding.sideNavigationPanel.animate().cancel()
        updateSideNavigationPanelWidth()
        applySideNavigationEdge(sideNavigationGravity)
        sideNavigationOpen = false
        binding.sideNavigationPanel.isVisible = true
        binding.sideNavigationPanel.doOnLayout {
            applySideNavigationEdge(sideNavigationGravity)
            binding.sideNavigationPanel.translationX = sideNavigationClosedOffset().toFloat()
            sideNavigationOpen = true
            placeSideNavigation(animate = true)
        }
    }

    private fun closeSideNavigation() {
        if (!sideNavigationOpen) return
        sideNavigationOpen = false
        placeSideNavigation(animate = true)
        sideNavigationLockedGravity = null
    }

    private fun setupLiquidGlass() {
        binding.run {
            val standardMode = isStandardBottomMode()
            if (AppConfig.isEInkMode) {
                bottomNavigationGlassView.visibility = android.view.View.GONE
                bottomNavigationIndicatorContainer.visibility = android.view.View.GONE
                bottomNavigationIndicatorGlassView.visibility = android.view.View.GONE
                searchButtonGlassView.visibility = android.view.View.GONE
                bottomNavigationShellOverlay.visibility = android.view.View.VISIBLE
                searchButtonShellOverlay.visibility = if (standardMode) android.view.View.GONE else android.view.View.VISIBLE
                bottomNavigationShellOverlay.background = if (standardMode) {
                    createStandardBottomShellDrawable()
                } else {
                    createEInkBottomShellDrawable(
                        cornerRadius = bottomBarCornerRadius,
                        oval = false
                    )
                }
                searchButtonShellOverlay.background = createEInkBottomShellDrawable(
                    cornerRadius = searchButtonCornerRadius,
                    oval = true
                )
                bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
                searchButton.setBackgroundColor(Color.TRANSPARENT)
                syncSearchButtonTint()
                return
            }
            val effectMode = AppConfig.bottomBarEffectMode
            if (standardMode) {
                bottomNavigationGlassView.visibility = android.view.View.GONE
                bottomNavigationIndicatorGlassView.visibility = android.view.View.GONE
                searchButtonGlassView.visibility = android.view.View.GONE
                searchButtonShellOverlay.visibility = android.view.View.GONE
                bottomNavigationShellOverlay.isVisible = true
                bottomNavigationIndicatorContainer.isVisible = true
                bottomNavigationIndicatorContainer.alpha = 1f
                bottomNavigationIndicatorContainer.scaleX = 1f
                bottomNavigationIndicatorContainer.scaleY = 1f
                bottomNavigationShellOverlay.background = createStandardBottomShellDrawable()
                bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
                syncSearchButtonTint()
                bottomNavigationIndicatorOverlay.background = createSolidBottomIndicatorDrawable()
                updateBottomNavigationIndicator(animate = false)
                return
            }
            if (effectMode == "solid") {
                bottomNavigationGlassView.visibility = android.view.View.GONE
                bottomNavigationIndicatorGlassView.visibility = android.view.View.GONE
                searchButtonGlassView.visibility = android.view.View.GONE
                bottomNavigationShellOverlay.isVisible = true
                searchButtonShellOverlay.isVisible = !standardMode
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
                if (!standardMode) searchButton.setBackgroundResource(R.drawable.bg_main_search_button)
                syncSearchButtonTint()
                bottomNavigationIndicatorOverlay.background = createSolidBottomIndicatorDrawable()
                updateBottomNavigationIndicator(animate = false)
                return
            }
            bottomNavigationIndicatorContainer.isVisible = true
            bottomNavigationIndicatorContainer.alpha = 0f
            bottomNavigationIndicatorContainer.scaleX = 0.82f
            bottomNavigationIndicatorContainer.scaleY = 0.82f
            bottomNavigationShellOverlay.isVisible = true
            searchButtonShellOverlay.isVisible = !standardMode
            bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
            if (!standardMode) searchButton.setBackgroundResource(R.drawable.bg_main_search_button)
            syncSearchButtonTint()
            bottomNavigationGlassView.visibility = android.view.View.VISIBLE
            bottomNavigationIndicatorGlassView.visibility = android.view.View.VISIBLE
            searchButtonGlassView.visibility = if (standardMode) android.view.View.GONE else android.view.View.VISIBLE
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
            if (!liquidGlassReady || !contentContainer.isLaidOut || !bottomControls.isLaidOut) {
                contentContainer.doOnPreDraw {
                    liquidGlassReady = true
                    scheduleLiquidGlassSetup(delayMillis = 32L)
                }
                return
            }
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
            if (!standardMode) {
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
            }
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

    private fun applyBottomNavigationIcons() = binding.run {
        val hasCustom = NavigationBarIconConfig.applyTo(
            bottomNavigationView.menu,
            this@MainActivity,
            AppConfig.isNightTheme
        )
        if (hasCustom) {
            bottomNavigationView.itemIconTintList = null
        } else {
            bottomNavigationView.restoreThemeIconTint()
        }
        updateSideNavigationItems()
        syncSearchButtonTint()
    }

    private fun syncSearchButtonTint() = binding.run {
        searchButtonIcon.imageTintList = bottomNavigationView.createThemeColorStateList()
        sideSearchButton.imageTintList = bottomNavigationView.createThemeColorStateList()
    }

    private fun createSolidBottomShellDrawable(cornerRadius: Float, oval: Boolean): GradientDrawable {
        val baseColor = bottomBackground
        val alpha = (AppConfig.liquidGlassLevel / 100f).coerceIn(0f, 1f)
        return GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) {
                this.cornerRadius = cornerRadius
            }
            setColor(AppColorUtils.withAlpha(baseColor, alpha))
            bottomBarBorderColor()?.let { setStroke(1.dpToPx(), it) }
        }
    }

    private fun createStandardBottomShellDrawable(): GradientDrawable {
        val baseColor = bottomBackground
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(baseColor)
            bottomBarBorderColor()?.let { setStroke(1.dpToPx(), it) }
        }
    }

    private fun createEInkBottomShellDrawable(cornerRadius: Float, oval: Boolean): GradientDrawable {
        val baseColor = bottomBackground
        return GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) {
                this.cornerRadius = cornerRadius
            }
            setColor(baseColor)
            setStroke(1.dpToPx(), AppColorUtils.withAlpha(Color.BLACK, 0.42f))
        }
    }

    private fun createSolidBottomIndicatorDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = bottomIndicatorCornerRadius
            setColor(primaryColor)
        }
    }

    private fun createSideNavigationScrimDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(AppColorUtils.withAlpha(Color.BLACK, 0.42f))
        }
    }

    private fun createSideNavigationHeaderDrawable(): GradientDrawable {
        val baseColor = bottomBackground
        val isLight = AppColorUtils.isColorLight(baseColor)
        val surface = if (binding.sideNavigationBackground.isVisible) {
            AppColorUtils.withAlpha(
                if (AppConfig.isNightTheme) Color.BLACK else Color.WHITE,
                if (AppConfig.isNightTheme) 0.20f else 0.42f
            )
        } else {
            AppColorUtils.blendColors(
                baseColor,
                if (isLight) Color.WHITE else Color.BLACK,
                if (isLight) 0.34f else 0.16f
            )
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.panelRadius(this@MainActivity)
            setColor(surface)
            setStroke(
                1.dpToPx(),
                AppColorUtils.withAlpha(
                    if (isLight) Color.BLACK else Color.WHITE,
                    if (binding.sideNavigationBackground.isVisible) 0.06f else 0.10f
                )
            )
        }
    }

    private fun createSideNavigationSearchDrawable(): GradientDrawable {
        val searchSurfaceColor = if (AppConfig.isNightTheme) {
            AppColorUtils.withAlpha(Color.rgb(52, 52, 56), 0.42f)
        } else {
            AppColorUtils.withAlpha(Color.rgb(120, 120, 128), 0.22f)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.searchRadius(18f)
            setColor(searchSurfaceColor)
            setStroke(0, Color.TRANSPARENT)
        }
    }

    private fun createSideNavigationPanelDrawable(): GradientDrawable {
        val baseColor = bottomBackground
        val hasWallpaper = binding.sideNavigationBackground.isVisible
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(if (hasWallpaper) Color.TRANSPARENT else baseColor)
            if (hasWallpaper) {
                setStroke(0, Color.TRANSPARENT)
            } else {
                setStroke(
                    1.dpToPx(),
                    AppColorUtils.withAlpha(
                        if (AppColorUtils.isColorLight(baseColor)) Color.BLACK else Color.WHITE,
                        0.12f
                    )
                )
            }
        }
    }

    private fun createSideNavigationRowDrawable(selected: Boolean): Drawable {
        val baseColor = bottomBackground
        val isLight = AppColorUtils.isColorLight(baseColor)
        val fill = if (selected) {
            if (binding.sideNavigationBackground.isVisible) {
                AppColorUtils.withAlpha(
                    if (AppConfig.isNightTheme) Color.BLACK else Color.WHITE,
                    if (AppConfig.isNightTheme) 0.18f else 0.34f
                )
            } else {
                if (AppConfig.isNightTheme) {
                    AppColorUtils.withAlpha(Color.rgb(52, 52, 56), 0.46f)
                } else {
                    AppColorUtils.withAlpha(Color.rgb(120, 120, 128), 0.20f)
                }
            }
        } else {
            Color.TRANSPARENT
        }
        return InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiCorner.actionRadius(this@MainActivity)
                setColor(fill)
                setStroke(0, Color.TRANSPARENT)
            },
            4.dpToPx(),
            5.dpToPx(),
            4.dpToPx(),
            5.dpToPx()
        )
    }

    private fun createSideNavigationGroupDrawable(selected: Boolean): Drawable {
        val fill = if (selected) {
            if (AppConfig.isNightTheme) {
                AppColorUtils.withAlpha(Color.rgb(52, 52, 56), 0.42f)
            } else {
                AppColorUtils.withAlpha(Color.rgb(120, 120, 128), 0.18f)
            }
        } else {
            Color.TRANSPARENT
        }
        return InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiCorner.actionRadius(this@MainActivity)
                setColor(fill)
                setStroke(0, Color.TRANSPARENT)
            },
            12.dpToPx(),
            0,
            12.dpToPx(),
            0
        )
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
        val startAlpha = (0.32f + glassLevel * 0.44f).coerceIn(0f, 0.86f)
        val centerAlpha = (0.24f + glassLevel * 0.38f).coerceIn(0f, 0.74f)
        val endAlpha = (0.18f + glassLevel * 0.32f).coerceIn(0f, 0.66f)
        val selectedBoost = if (selected) 0.08f else 0f
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
            bottomBarBorderColor()?.let { setStroke(1.dpToPx(), it) }
        }
    }

    private fun bottomBarBorderColor(): Int? {
        return NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config.borderColor
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
        if (isSidebarMode()) return
        if (AppConfig.isEInkMode) return
        val menuView = binding.bottomNavigationView.getChildAt(0) as? ViewGroup ?: return
        val itemView = findBottomNavigationItemView(menuView, getBottomNavigationItemId(pagePosition))
            ?: return
        val indicator = binding.bottomNavigationIndicatorContainer
        val standardMode = isStandardBottomMode()
        val targetWidth = if (standardMode) {
            bottomIndicatorWidth
        } else {
            minOf(
                bottomIndicatorWidth,
                (itemView.width - 16.dpToPx()).coerceAtLeast(42.dpToPx())
            )
        }
        val targetHeight = if (standardMode) {
            resources.getDimensionPixelSize(R.dimen.main_bottom_indicator_height)
        } else {
            resources.getDimensionPixelSize(R.dimen.main_bottom_indicator_height)
        }
        indicator.layoutParams = indicator.layoutParams.apply {
            width = targetWidth
            height = targetHeight
        }
        val baseX = binding.bottomNavigationView.x + menuView.x + itemView.x
        val targetX = baseX + (itemView.width - targetWidth) / 2f
        val targetY = if (standardMode) {
            binding.bottomNavigationView.y + menuView.y + itemView.y + (itemView.height - targetHeight) / 2f
        } else {
            (binding.bottomNavigationGlass.height - targetHeight).coerceAtLeast(0) / 2f
        }
        indicator.y = targetY
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
        if (isSidebarMode()) return
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
            idRss -> if (AppConfig.mergeDiscoveryRss && AppConfig.showDiscovery && AppConfig.showRSS) {
                R.id.menu_discovery
            } else {
                R.id.menu_rss
            }
            idReadRecord -> R.id.menu_read_record
            else -> R.id.menu_my_config
        }
    }

    private fun resolveDiscoveryNavTarget(): Int {
        val showDiscovery = AppConfig.showDiscovery
        val showRss = AppConfig.showRSS
        if (!(AppConfig.mergeDiscoveryRss && showDiscovery && showRss)) {
            return when {
                showDiscovery -> idExplore
                showRss -> idRss
                else -> idExplore
            }
        }
        return if (AppConfig.mergedDiscoveryRssTarget == "rss") idRss else idExplore
    }

    private fun toggleMergedDiscoveryNavTarget() {
        if (!(AppConfig.mergeDiscoveryRss && AppConfig.showDiscovery && AppConfig.showRSS)) return
        AppConfig.mergedDiscoveryRssTarget =
            if (resolveDiscoveryNavTarget() == idRss) "explore" else "rss"
        upBottomMenu()
        val targetPosition = realPositions.indexOf(resolveDiscoveryNavTarget())
        if (targetPosition >= 0) {
            binding.viewPagerMain.setCurrentItem(targetPosition, true)
        }
    }

    private fun bindMergedDiscoveryLongClick() {
        val menuView = binding.bottomNavigationView.getChildAt(0) as? ViewGroup ?: return
        val itemView = findBottomNavigationItemView(menuView, R.id.menu_discovery) ?: return
        if (mergedDiscoveryLongClickView === itemView) return
        mergedDiscoveryLongClickView?.setOnLongClickListener(null)
        itemView.setOnLongClickListener {
            if (AppConfig.mergeDiscoveryRss && AppConfig.showDiscovery && AppConfig.showRSS) {
                toggleMergedDiscoveryNavTarget()
                true
            } else {
                false
            }
        }
        mergedDiscoveryLongClickView = itemView
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
                    AppUpdate.preferredUpdate.check(lifecycleScope)
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
                withContext(IO) { AppCloudStorage.lastBackup().getOrNull() } ?: return@launch
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
        aiFloatingBall?.removeCallbacks(aiFloatingBallAttachRunnable)
        clearSideNavigationBackground()
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        Backup.autoBack(this)
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
        observeEvent<Boolean>(EventBus.NAVIGATION_BAR_CHANGED) {
            if (it == AppConfig.isNightTheme) {
                refreshBottomNavigationConfig()
            }
        }
        observeEvent<Boolean>(EventBus.TOP_BAR_CHANGED) {
            if (it == AppConfig.isNightTheme) {
                refreshMainTopBars(binding.root)
                binding.root.post {
                    refreshMainTopBars(binding.root)
                    scheduleLiquidGlassSetup(delayMillis = 96L)
                }
            }
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    applyBottomNavigationIcons()
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                updateAiFloatingBall()
                if (it) {
                    pagePosition = resolveHomePagePosition().coerceIn(0, bottomMenuCount - 1)
                    viewPagerMain.setCurrentItem(pagePosition, false)
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
        val showReadRecord = AppConfig.showReadRecord
        val mergedDiscovery = AppConfig.mergeDiscoveryRss && showDiscovery && showRss
        binding.bottomNavigationView.menu.let { menu ->
            menu.findItem(R.id.menu_discovery).isVisible = showDiscovery || (mergedDiscovery && showRss)
            menu.findItem(R.id.menu_rss)?.isVisible = showRss && !mergedDiscovery
            menu.findItem(R.id.menu_read_record)?.isVisible = showReadRecord
            if (mergedDiscovery) {
                if (resolveDiscoveryNavTarget() == idRss) {
                    menu.findItem(R.id.menu_discovery).setIcon(R.drawable.ic_bottom_rss_feed)
                    menu.findItem(R.id.menu_discovery).setTitle(R.string.rss)
                } else {
                    menu.findItem(R.id.menu_discovery).setIcon(R.drawable.ic_bottom_explore)
                    menu.findItem(R.id.menu_discovery).setTitle(R.string.discovery)
                }
            } else {
                menu.findItem(R.id.menu_discovery).setIcon(R.drawable.ic_bottom_explore)
                menu.findItem(R.id.menu_discovery).setTitle(R.string.discovery)
            }
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
        if (showReadRecord) {
            index++
            realPositions[index] = idReadRecord
        }
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        pagePosition = pagePosition.coerceIn(0, bottomMenuCount - 1)
        adapter.notifyDataSetChanged()
        applyBottomNavigationIcons()
        applyMergedDiscoveryIcon()
        binding.bottomNavigationView.post {
            bindMergedDiscoveryLongClick()
            updateSideNavigationItems()
            updateBottomNavigationIndicator(animate = false)
        }
    }

    private fun applyMergedDiscoveryIcon() {
        binding.run {
        val showDiscovery = AppConfig.showDiscovery
        val showRss = AppConfig.showRSS && bottomNavigationView.menu.findItem(R.id.menu_rss) != null
        if (!(AppConfig.mergeDiscoveryRss && showDiscovery && showRss)) return@run
        val key = if (resolveDiscoveryNavTarget() == idRss) "rss" else "discovery"
        NavigationBarIconConfig.currentMenuDrawable(this@MainActivity, key)?.let { icon ->
            bottomNavigationView.menu.findItem(R.id.menu_discovery)?.icon = icon
        }
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
            "explore" -> if (AppConfig.showDiscovery || AppConfig.mergeDiscoveryRss) visiblePositions.indexOf(idExplore).takeIf { it >= 0 }
                ?: visiblePositions.indexOf(resolveDiscoveryNavTarget()) else 0
            "rss" -> visiblePositions.indexOf(idRss).takeIf { it >= 0 }
                ?: visiblePositions.indexOf(resolveDiscoveryNavTarget())
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
            updateSideNavigationItems()
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
