package io.legado.app.ui.main.readrecord

import android.app.DatePickerDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordDaySummaryBinding
import io.legado.app.databinding.ItemReadRecordRecentBookBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.about.ReadHeatmapCell
import io.legado.app.ui.about.ReadRecordComponentConfigDialog
import io.legado.app.ui.about.ReadRecordComponentType
import io.legado.app.ui.about.ReadRecordComponents
import io.legado.app.ui.about.ReadRecordCoverAdapter
import io.legado.app.ui.about.ReadRecordGoalConfig
import io.legado.app.ui.about.ReadRecordRankDialog
import io.legado.app.ui.about.ReadRecordRankItem
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.about.ReadRecentVisualItem
import io.legado.app.ui.about.loadReadRecordCover
import io.legado.app.ui.about.openReadRecordBook
import io.legado.app.ui.about.showReadRecordGoalDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.registerForActivityResult
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class ReadRecordFragment() : BaseFragment(R.layout.activity_read_record), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(ActivityReadRecordBinding::bind)
    private val headlineFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_date_pattern), Locale.getDefault())
    }
    private val fullDayFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_date_pattern), Locale.getDefault())
    }
    private val monthFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_month_pattern), Locale.getDefault())
    }
    private val lastOpenFormatter by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    private var currentHeatmapCells: List<ReadHeatmapCell> = emptyList()
    private var selectedDate: LocalDate = LocalDate.now()
    private var loadJob: Job? = null
    private var loadedDate: LocalDate? = null
    private var lastLoadTime = 0L
    private var componentItems = ReadRecordComponents.load()
    private var currentRankItems: List<ReadRecordRankItem> = emptyList()
    private var currentGoalConfig: ReadRecordGoalConfig = ReadRecordWidgetStore.loadGoalConfig()
    private var currentTodayTime: Long = 0L
    private var currentTotalTime: Long = 0L
    private var currentReadBookCount: Int = 0
    private var pendingAvatarUpdate: ((String) -> Unit)? = null
    private val selectGoalAvatar = registerForActivityResult(HandleFileContract()) {
        it.uri?.toString()?.let { uri ->
            pendingAvatarUpdate?.invoke(uri)
        }
        pendingAvatarUpdate = null
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.visibility = View.GONE
        binding.scrollView.applyMainBottomBarPadding(withInitialPadding = true)
        binding.llRecordHeader.applyStatusBarPadding(withInitialPadding = true)
        binding.tvRecordDate.setOnClickListener {
            showDatePicker()
        }
        binding.ivComponentMenu.setOnClickListener {
            showComponentConfigDialog()
        }
        binding.ivRankMore.setOnClickListener {
            ReadRecordRankDialog.show(requireContext(), currentRankItems, ::formatDuring)
        }
        binding.ivGoalEdit.setOnClickListener {
            requireContext().showReadRecordGoalDialog(
                initial = currentGoalConfig,
                onPickAvatarRequest = { update ->
                    pendingAvatarUpdate = update
                    selectGoalAvatar.launch {
                        mode = HandleFileContract.IMAGE
                        title = getString(R.string.read_record_goal_avatar)
                    }
                }
            ) { config ->
                currentGoalConfig = config
                ReadRecordWidgetStore.saveGoalConfig(config)
                renderGoalCard(currentTodayTime, currentTotalTime, currentReadBookCount)
            }
        }
        binding.rvRecentCovers.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        applyComponentLayout()
        preloadData()
    }

    override fun onResume() {
        super.onResume()
        loadData(force = loadedDate == null || loadedDate != selectedDate || isDataStale())
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.book_read_record, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
                supportToolbar?.menu?.findItem(R.id.menu_enable_record)?.isChecked =
                    AppConfig.enableReadRecord
            }

            R.id.menu_clear_record -> {
                alert(R.string.delete, R.string.sure_del) {
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.readRecordDao.clear()
                                appDb.readRecordDailyDao.clear()
                                appDb.readRecentBookDao.clear()
                                ReadRecordWidgetStore.clearRecentSnapshots()
                            }
                            loadData(force = true)
                        }
                    }
                    noButton()
                }
            }
        }
    }

    private fun preloadData() {
        loadData(force = true)
    }

    private fun isDataStale(): Boolean {
        return System.currentTimeMillis() - lastLoadTime > DATA_STALE_MS
    }

    private fun loadData(force: Boolean = false) {
        if (!force && loadedDate == selectedDate) return
        if (loadJob?.isActive == true) {
            if (!force) return
            loadJob?.cancel()
        }
        supportToolbar?.menu?.findItem(R.id.menu_enable_record)?.isChecked =
            AppConfig.enableReadRecord
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val loadDate = selectedDate
            val dashboard = withContext(IO) {
                buildDashboard(loadDate)
            }
            if (loadDate != selectedDate) return@launch
            loadedDate = loadDate
            lastLoadTime = System.currentTimeMillis()
            renderDashboard(dashboard)
        }
    }

    private fun buildDashboard(today: LocalDate): ReadRecordDashboard {
        val month = YearMonth.from(today)
        val readRecordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
        val totalTime = appDb.readRecordDao.allTime
        val dailyStats = appDb.readRecordDailyDao.allDesc.mapNotNull { record ->
            runCatching {
                DailyReadSummary(
                    date = LocalDate.parse(record.date),
                    readTime = record.readTime
                )
            }.getOrNull()
        }.sortedByDescending { it.date }
        val dailyMap = dailyStats.associate { it.date to it.readTime }
        val recentBooks = appDb.readRecentBookDao.recentBooks(6)
            .map { book ->
                RecentReadBook(
                    book = book,
                    totalReadTime = readRecordMap[book.name]?.readTime ?: 0L
                )
            }
        val heatmapStart = today.minusDays(111)
        val heatmapCells = (0L..111L).map { offset ->
            val date = heatmapStart.plusDays(offset)
            ReadHeatmapCell(date, dailyMap[date] ?: 0L)
        }
        return ReadRecordDashboard(
            today = today,
            todayTime = dailyMap[today] ?: 0L,
            monthTime = dailyStats.filter { YearMonth.from(it.date) == month }.sumOf { it.readTime },
            totalTime = totalTime,
            activeDays = dailyStats.count { it.readTime > 0L },
            heatmapCells = heatmapCells,
            recentBooks = recentBooks,
            dailyTimeline = dailyStats.take(14),
            hasDailyStats = dailyStats.isNotEmpty(),
            recentCoverItems = ReadRecordWidgetStore.loadRecentVisualItems(5),
            rankItems = ReadRecordWidgetStore.buildRankItems(),
            goalConfig = ReadRecordWidgetStore.loadGoalConfig(),
            readBookCount = appDb.readRecordDao.allShow.size
        )
    }

    private fun renderDashboard(dashboard: ReadRecordDashboard) {
        currentHeatmapCells = dashboard.heatmapCells
        binding.tvRecordDate.text = dashboard.today.format(headlineFormatter)
        binding.tvRecordDateHint.text = getString(
            if (dashboard.hasDailyStats) {
                R.string.read_record_stats_ready
            } else {
                R.string.read_record_stats_waiting
            }
        )
        binding.tvTodayValue.text = if (dashboard.hasDailyStats) {
            formatDuring(dashboard.todayTime)
        } else {
            getString(R.string.read_record_placeholder)
        }
        binding.tvTodayLabel.text = if (dashboard.today == LocalDate.now()) {
            getString(R.string.read_record_today_label)
        } else {
            getString(R.string.read_record_selected_day_label)
        }
        binding.tvMonthValue.text = if (dashboard.hasDailyStats) {
            formatDuring(dashboard.monthTime)
        } else {
            getString(R.string.read_record_placeholder)
        }
        binding.tvTotalValue.text = formatDuring(dashboard.totalTime)
        binding.tvActiveDaysValue.text =
            getString(R.string.read_record_active_days_value, dashboard.activeDays)

        val startDate = dashboard.heatmapCells.firstOrNull()?.date ?: dashboard.today
        val centerDate = dashboard.heatmapCells.getOrNull(dashboard.heatmapCells.size / 2)?.date
            ?: dashboard.today
        val endDate = dashboard.heatmapCells.lastOrNull()?.date ?: dashboard.today
        binding.tvHeatmapMonthStart.text = startDate.format(monthFormatter)
        binding.tvHeatmapMonthCenter.text = centerDate.format(monthFormatter)
        binding.tvHeatmapMonthEnd.text = endDate.format(monthFormatter)
        binding.tvHeatmapEmpty.isVisible = !dashboard.hasDailyStats
        currentTodayTime = dashboard.todayTime
        currentTotalTime = dashboard.totalTime
        currentReadBookCount = dashboard.readBookCount

        renderRecentBooks(dashboard.recentBooks)
        renderDailyTimeline(dashboard.dailyTimeline, dashboard.hasDailyStats)
        renderRecentCovers(dashboard.recentCoverItems)
        renderReadRank(dashboard.rankItems.take(5), dashboard.rankItems)
        currentGoalConfig = dashboard.goalConfig
        renderGoalCard(dashboard.todayTime, dashboard.totalTime, dashboard.readBookCount)
        applyPageChrome()
    }

    private fun showComponentConfigDialog() {
        ReadRecordComponentConfigDialog.show(requireContext(), componentItems) { items ->
            componentItems = items.toMutableList()
            ReadRecordComponents.save(componentItems)
            applyComponentLayout()
            applyPageChrome()
        }
    }

    private fun applyComponentLayout() {
        if (componentItems.none { it.enabled }) {
            componentItems.firstOrNull()?.enabled = true
            ReadRecordComponents.save(componentItems)
        }
        val parent = binding.llReadRecordComponents
        val componentViews = linkedMapOf(
            ReadRecordComponentType.OVERVIEW to binding.panelOverview,
            ReadRecordComponentType.HEATMAP to binding.panelHeatmap,
            ReadRecordComponentType.RECENT_BOOKS to binding.panelRecentBooks,
            ReadRecordComponentType.DAILY_RECORDS to binding.panelDailyRecords,
            ReadRecordComponentType.RECENT_COVERS to binding.panelRecentCovers,
            ReadRecordComponentType.READ_RANK to binding.panelReadRank,
            ReadRecordComponentType.GOAL_CARD to binding.panelGoalCard
        )
        componentViews.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.isVisible = false
        }
        parent.removeAllViews()
        componentItems.filter { it.enabled }.forEachIndexed { index, item ->
            componentViews[item.type]?.let { view ->
                val lp = (view.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?: ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                lp.topMargin = if (index == 0) 0 else 16.dpToPx()
                view.layoutParams = lp
                view.isVisible = true
                parent.addView(view)
            }
        }
    }

    private fun showDatePicker() {
        val date = selectedDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                loadData(force = true)
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }

    private fun renderRecentBooks(items: List<RecentReadBook>) {
        binding.llRecentBooks.removeAllViews()
        binding.tvRecentBooksEmpty.isVisible = items.isEmpty()
        if (items.isEmpty()) return

        items.forEachIndexed { index, item ->
            val itemBinding =
                ItemReadRecordRecentBookBinding.inflate(layoutInflater, binding.llRecentBooks, false)
            itemBinding.vAccent.background = createFillDrawable(accentColor, 3f)
            itemBinding.tvBookName.text = item.book.name
            itemBinding.tvBookMeta.text = buildRecentBookMeta(item.book)
            itemBinding.tvBookTime.text = formatDuring(item.totalReadTime)
            itemBinding.root.setOnClickListener {
                startActivityForBook(item.book)
            }
            itemBinding.root.setOnLongClickListener {
                alert(getString(R.string.delete), item.book.name) {
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.readRecentBookDao.delete(item.book.bookUrl)
                                ReadRecordWidgetStore.removeRecentSnapshot(item.book.bookUrl)
                            }
                            loadData(force = true)
                        }
                    }
                    noButton()
                }
                true
            }
            binding.llRecentBooks.addView(itemBinding.root)
            if (index < items.lastIndex) {
                binding.llRecentBooks.addView(createDivider())
            }
        }
    }

    private fun renderDailyTimeline(items: List<DailyReadSummary>, hasDailyStats: Boolean) {
        binding.llDailyRecords.removeAllViews()
        binding.tvDailyRecordsEmpty.isVisible = !hasDailyStats || items.isEmpty()
        if (!hasDailyStats || items.isEmpty()) return

        items.forEachIndexed { index, item ->
            val itemBinding =
                ItemReadRecordDaySummaryBinding.inflate(layoutInflater, binding.llDailyRecords, false)
            itemBinding.tvDayTitle.text = item.date.format(fullDayFormatter)
            itemBinding.tvDaySubtitle.text = buildDaySubtitle(item.date)
            itemBinding.tvDayTime.text = formatDuring(item.readTime)
            itemBinding.root.setOnLongClickListener {
                alert(getString(R.string.delete), item.date.format(fullDayFormatter)) {
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.readRecordDailyDao.delete(item.date.toString())
                            }
                            loadData(force = true)
                        }
                    }
                    noButton()
                }
                true
            }
            binding.llDailyRecords.addView(itemBinding.root)
            if (index < items.lastIndex) {
                binding.llDailyRecords.addView(createDivider())
            }
        }
    }

    private fun renderRecentCovers(items: List<ReadRecentVisualItem>) {
        binding.tvRecentCoversEmpty.isVisible = items.isEmpty()
        binding.rvRecentCovers.isVisible = items.isNotEmpty()
        binding.rvRecentCovers.adapter = ReadRecordCoverAdapter(requireContext(), items) {
            requireContext().openReadRecordBook(it.book, it.snapshot.name)
        }
    }

    private fun renderReadRank(items: List<ReadRecordRankItem>, allItems: List<ReadRecordRankItem>) {
        currentRankItems = allItems
        binding.llReadRank.removeAllViews()
        binding.tvReadRankEmpty.isVisible = items.isEmpty()
        binding.ivRankMore.isVisible = allItems.isNotEmpty()
        if (items.isEmpty()) return
        items.forEachIndexed { index, item ->
            val rowBinding =
                io.legado.app.databinding.ItemReadRecordRankBinding.inflate(layoutInflater, binding.llReadRank, false)
            rowBinding.ivCover.loadReadRecordCover(item.book?.getDisplayCover() ?: item.snapshot?.displayCover())
            rowBinding.tvName.text = item.book?.name ?: item.snapshot?.name ?: item.displayName
            val author = item.book?.author ?: item.snapshot?.author ?: item.displayAuthor
            rowBinding.tvMeta.text = if (author.isBlank()) {
                getString(R.string.read_record_rank_number, index + 1)
            } else {
                "${index + 1}. $author"
            }
            rowBinding.tvTime.text = formatDuring(item.readTime)
            rowBinding.root.alpha = if (item.book == null) 0.72f else 1f
            rowBinding.root.setOnClickListener {
                requireContext().openReadRecordBook(item.book, item.displayName)
            }
            binding.llReadRank.addView(rowBinding.root)
            if (index < items.lastIndex) {
                binding.llReadRank.addView(createDivider())
            }
        }
    }

    private fun renderGoalCard(todayTime: Long, totalTime: Long, readBookCount: Int) {
        val todayText = formatDuring(todayTime)
        val totalText = formatDuring(totalTime)
        binding.ivGoalAvatar.loadReadRecordCover(currentGoalConfig.avatar)
        binding.tvGoalUserName.text = currentGoalConfig.userName.orEmpty()
        binding.tvGoalUserName.isVisible = !currentGoalConfig.userName.isNullOrBlank()
        binding.tvGoalToday.text = getString(R.string.read_record_goal_today, todayText)
        binding.tvGoalTotal.text = getString(R.string.read_record_goal_total, totalText)
        binding.tvGoalBooks.text = getString(R.string.read_record_goal_books, readBookCount)
        val goalMs = currentGoalConfig.dailyGoalMinutes * 60L * 1000L
        val percent = if (goalMs <= 0L) 0 else ((todayTime * 100) / goalMs).toInt().coerceIn(0, 100)
        binding.tvGoalProgress.text = getString(
            R.string.read_record_goal_target_progress,
            todayText,
            formatDuring(goalMs)
        )
        binding.progressGoal.progress = percent
    }

    private fun buildRecentBookMeta(book: Book): String {
        val parts = mutableListOf<String>()
        book.durChapterTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += getString(R.string.read_record_current_chapter, it)
        }
        parts += getString(
            R.string.read_record_last_open,
            lastOpenFormatter.format(Date(book.durChapterTime))
        )
        return parts.joinToString(" · ")
    }

    private fun buildDaySubtitle(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> getString(R.string.read_record_today_word)
            today.minusDays(1) -> getString(R.string.read_record_yesterday_word)
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
    }

    private fun applyPageChrome() {
        val panelSurfaceColor = ContextCompat.getColor(requireContext(), R.color.background_card)
        val cardSurfaceColor = ContextCompat.getColor(requireContext(), R.color.background_card)
        val strokeColor = ColorUtils.adjustAlpha(
            primaryTextColor,
            0.08f
        )
        val accentSurfaceColor = ColorUtils.blendColors(
            panelSurfaceColor,
            accentColor,
            0.16f
        )

        binding.panelOverview.background = null
        listOf(
            binding.panelHeatmap,
            binding.panelRecentBooks,
            binding.panelDailyRecords
        ).forEach { panel ->
            panel.background = createSurfaceDrawable(panelSurfaceColor, strokeColor, 14f)
        }
        listOf(
            binding.cardToday,
            binding.cardMonth,
            binding.cardActiveDays
        ).forEach { card ->
            card.background = createSurfaceDrawable(cardSurfaceColor, strokeColor, 12f)
        }
        binding.cardTotal.background =
            createSurfaceDrawable(accentSurfaceColor, ColorUtils.adjustAlpha(accentColor, 0.2f), 12f)

        val accentTextColor = if (ColorUtils.isColorLight(accentSurfaceColor)) {
            primaryTextColor
        } else {
            ContextCompat.getColor(requireContext(), R.color.white)
        }
        binding.tvTotalValue.setTextColor(accentTextColor)
        binding.tvTotalLabel.setTextColor(ColorUtils.adjustAlpha(accentTextColor, 0.72f))
        binding.tvRecordDate.setTextColor(primaryTextColor)
        binding.tvRecordDateHint.setTextColor(secondaryTextColor)
        binding.tvHeatmapSubtitle.setTextColor(secondaryTextColor)
        binding.tvHeatmapEmpty.setTextColor(secondaryTextColor)
        binding.tvRecentBooksEmpty.setTextColor(secondaryTextColor)
        binding.tvDailyRecordsEmpty.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthStart.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthCenter.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthEnd.setTextColor(secondaryTextColor)
        binding.panelRecentCovers.background =
            createSurfaceDrawable(panelSurfaceColor, strokeColor, 14f)
        binding.panelReadRank.background =
            createSurfaceDrawable(panelSurfaceColor, strokeColor, 14f)
        binding.panelGoalCard.background =
            createSurfaceDrawable(panelSurfaceColor, strokeColor, 14f)
        binding.ivComponentMenu.background = null
        binding.ivComponentMenu.setColorFilter(primaryTextColor)
        binding.ivRankMore.background = null
        binding.ivGoalEdit.background = null
        binding.ivRankMore.setColorFilter(secondaryTextColor)
        binding.ivGoalEdit.setColorFilter(secondaryTextColor)
        binding.heatmapView.submit(currentHeatmapCells, accentColor, panelSurfaceColor)
    }

    private fun createDivider(): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply {
                marginStart = 15.dpToPx()
            }
            setBackgroundColor(ColorUtils.adjustAlpha(primaryTextColor, 0.08f))
        }
    }

    private fun createSurfaceDrawable(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.scaledDp(radiusDp)
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    private fun createFillDrawable(fillColor: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.scaledDp(radiusDp)
            setColor(fillColor)
        }
    }

    private fun formatDuring(mss: Long): String {
        val days = mss / (1000 * 60 * 60 * 24)
        val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
        val seconds = mss % (1000 * 60) / 1000
        val d = if (days > 0) "${days}天" else ""
        val h = if (hours > 0) "${hours}小时" else ""
        val m = if (minutes > 0) "${minutes}分钟" else ""
        val s = if (seconds > 0 && days == 0L && hours == 0L) "${seconds}秒" else ""
        val time = "$d$h$m$s"
        return if (time.isBlank()) "0秒" else time
    }

}

private const val DATA_STALE_MS = 60_000L

private data class ReadRecordDashboard(
    val today: LocalDate,
    val todayTime: Long,
    val monthTime: Long,
    val totalTime: Long,
    val activeDays: Int,
    val heatmapCells: List<ReadHeatmapCell>,
    val recentBooks: List<RecentReadBook>,
    val dailyTimeline: List<DailyReadSummary>,
    val hasDailyStats: Boolean,
    val recentCoverItems: List<ReadRecentVisualItem>,
    val rankItems: List<ReadRecordRankItem>,
    val goalConfig: ReadRecordGoalConfig,
    val readBookCount: Int
)

private data class RecentReadBook(
    val book: Book,
    val totalReadTime: Long
)

private data class DailyReadSummary(
    val date: LocalDate,
    val readTime: Long
)
