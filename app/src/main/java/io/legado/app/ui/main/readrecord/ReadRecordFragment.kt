package io.legado.app.ui.main.readrecord

import android.app.DatePickerDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordDaySummaryBinding
import io.legado.app.databinding.ItemReadRecordRecentBookBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.about.ReadHeatmapCell
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
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
        DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault())
    }
    private val fullDayFormatter by lazy {
        DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault())
    }
    private val monthFormatter by lazy {
        DateTimeFormatter.ofPattern("M月", Locale.getDefault())
    }
    private val lastOpenFormatter by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    private var currentHeatmapCells: List<ReadHeatmapCell> = emptyList()
    private var selectedDate: LocalDate = LocalDate.now()
    private var loadJob: Job? = null
    private var loadedDate: LocalDate? = null
    private var lastLoadTime = 0L

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.visibility = View.GONE
        binding.scrollView.applyMainBottomBarPadding(withInitialPadding = true)
        binding.tvRecordDate.applyStatusBarPadding(withInitialPadding = true)
        binding.tvRecordDate.setOnClickListener {
            showDatePicker()
        }
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
                                appDb.bookDao.all.forEach { book ->
                                    book.durChapterTime = 0L
                                    book.durChapterTitle = null
                                    appDb.bookDao.update(book)
                                }
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
        val recentBooks = appDb.bookDao.all
            .filter { it.durChapterTime > 0L && it.name.isNotBlank() }
            .sortedByDescending { it.durChapterTime }
            .take(6)
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
            hasDailyStats = dailyStats.isNotEmpty()
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

        renderRecentBooks(dashboard.recentBooks)
        renderDailyTimeline(dashboard.dailyTimeline, dashboard.hasDailyStats)
        applyPageChrome()
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
            binding.llDailyRecords.addView(itemBinding.root)
            if (index < items.lastIndex) {
                binding.llDailyRecords.addView(createDivider())
            }
        }
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
        val baseColor = backgroundColor
        val baseIsLight = ColorUtils.isColorLight(baseColor)
        val panelSurfaceColor = if (baseIsLight) {
            ColorUtils.blendColors(
                baseColor,
                ContextCompat.getColor(requireContext(), R.color.background_card),
                0.82f
            )
        } else {
            ColorUtils.blendColors(
                baseColor,
                ContextCompat.getColor(requireContext(), R.color.white),
                0.1f
            )
        }
        val cardSurfaceColor = if (baseIsLight) {
            ColorUtils.blendColors(
                baseColor,
                ContextCompat.getColor(requireContext(), R.color.background_menu),
                0.92f
            )
        } else {
            ColorUtils.blendColors(
                baseColor,
                ContextCompat.getColor(requireContext(), R.color.white),
                0.06f
            )
        }
        val strokeColor = ColorUtils.adjustAlpha(
            if (baseIsLight) primaryTextColor else ContextCompat.getColor(requireContext(), R.color.white),
            if (baseIsLight) 0.08f else 0.14f
        )
        val accentSurfaceColor = ColorUtils.blendColors(
            panelSurfaceColor,
            accentColor,
            if (baseIsLight) 0.16f else 0.24f
        )

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
            cornerRadius = radiusDp.dpToPx()
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    private fun createFillDrawable(fillColor: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dpToPx()
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
    val hasDailyStats: Boolean
)

private data class RecentReadBook(
    val book: Book,
    val totalReadTime: Long
)

private data class DailyReadSummary(
    val date: LocalDate,
    val readTime: Long
)
