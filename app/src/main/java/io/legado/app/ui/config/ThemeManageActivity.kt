package io.legado.app.ui.config

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.DialogImageBlurringBinding
import io.legado.app.databinding.DialogThemePackageEditBinding
import io.legado.app.databinding.ItemThemePackageOptionBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThemeManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var isNightTheme = false
    private var editDialogBinding: DialogThemePackageEditBinding? = null
    private var editingEntry: ThemePackageManager.Entry? = null
    private var pendingBlur = 0
    private var pendingMainBackgroundPath: String? = null
    private var pendingBookInfoBackgroundPath: String? = null
    private var pendingUiCornerScale = 1f
    private var pendingUiLayoutAlpha = 100
    private var pendingFontScale = 0
    private var pendingUiCornerSearchFollow = false
    private var pendingUiCornerReplyFollow = false
    private var loadVersion = 0
    private val pendingRemoteSyncTasks = linkedMapOf<String, RemoteSyncTask>()
    @Volatile
    private var syncingRemoteTasks = false
    private var appliedDayThemeOverride: String? = null
    private var appliedNightThemeOverride: String? = null
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val targetPath = copySelectedImage(uri, if (it.requestCode == requestMainBackground) "main" else "book_info")
            if (it.requestCode == requestMainBackground) {
                pendingMainBackgroundPath = targetPath
                editDialogBinding?.let { binding -> updateImageRow(binding.rowMainBackground, true) }
            } else {
                pendingBookInfoBackgroundPath = targetPath
                editDialogBinding?.let { binding -> updateImageRow(binding.rowBookInfoBackground, false) }
            }
        }
    }
    private val importThemePackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            importThemeZip(uri)
        }
    }
    private val exportThemePackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let {
            toastOnUi(getString(R.string.theme_zip_exported))
        }
    }
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        restorePendingRemoteSyncTasks()
        initView()
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.ensureLocalAppliedTheme(this@ThemeManageActivity, false)
                ThemePackageManager.ensureLocalAppliedTheme(this@ThemeManageActivity, true)
            }
            loadThemes()
        }
        flushPendingRemoteSyncTasks()
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@ThemeManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@ThemeManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@ThemeManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@ThemeManageActivity)
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(this@ThemeManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnDay.setOnClickListener {
            if (isNightTheme) {
                isNightTheme = false
                updateTabs()
                loadThemes()
            }
        }
        btnNight.setOnClickListener {
            if (!isNightTheme) {
                isNightTheme = true
                updateTabs()
                loadThemes()
            }
        }
        btnAdd.setOnClickListener {
            showAddDialog()
        }
        updateTabs()
    }

    private fun updateTabs() = binding.run {
        btnDay.isSelected = !isNightTheme
        btnNight.isSelected = isNightTheme
        btnDay.setTextColor(if (!isNightTheme) accentColor else primaryTextColor)
        btnNight.setTextColor(if (isNightTheme) accentColor else primaryTextColor)
    }

    private fun loadThemes() {
        val version = ++loadVersion
        val useCloud = AppConfig.syncThemePackages
        binding.tvSummary.text = appendPendingRemoteSummary(getString(R.string.theme_package_summary_default))
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.loadLocalOnly(isNightTheme)
            }.onSuccess {
                if (version != loadVersion) return@launch
                adapter.items = it
                binding.tvSummary.text = appendPendingRemoteSummary(
                    if (it.isEmpty()) {
                        getString(
                            R.string.theme_package_empty,
                            getString(if (isNightTheme) R.string.theme_night_short else R.string.theme_day_short)
                        )
                    } else {
                        getString(R.string.theme_package_summary_default)
                    }
                )
                if (useCloud) {
                    loadThemesRemote(version)
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                if (version != loadVersion) return@launch
                binding.tvSummary.text = if (useCloud) {
                    getString(R.string.theme_package_cloud_load_failed, it.localizedMessage)
                } else {
                    getString(R.string.theme_package_load_failed, it.localizedMessage)
                }
            }
        }
    }

    private fun loadThemesRemote(version: Int) {
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.load(isNightTheme)
            }.onSuccess {
                if (version != loadVersion) return@onSuccess
                adapter.items = it
                binding.tvSummary.text = appendPendingRemoteSummary(
                    if (it.isEmpty()) {
                        getString(
                            R.string.theme_package_empty,
                            getString(if (isNightTheme) R.string.theme_night_short else R.string.theme_day_short)
                        )
                    } else {
                        getString(R.string.theme_package_summary_default)
                    }
                )
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                if (version != loadVersion) return@onFailure
                binding.tvSummary.text = appendPendingRemoteSummary(
                    getString(R.string.theme_package_cloud_load_failed, it.localizedMessage)
                )
            }
        }
    }

    private fun showAddDialog() {
        selector(
            getString(R.string.theme_add),
            listOf(getString(R.string.theme_manual_config), getString(R.string.theme_import_zip))
        ) { _, index ->
            when (index) {
                0 -> showManualAddDialog()
                1 -> importThemePackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun showManualAddDialog() {
        alert(getString(R.string.theme_manual_add)) {
            val dialogBinding = createEditBinding(currentConfig(), null)
            editDialogBinding = dialogBinding
            editingEntry = null
            customView { dialogBinding.root }
            okButton { saveTheme(dialogBinding) }
            onDismiss {
                editDialogBinding = null
                editingEntry = null
            }
            cancelButton()
        }
    }

    private fun showEditDialog(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                if (entry.source == ThemePackageManager.Source.REMOTE) {
                    ThemePackageManager.download(entry)
                } else {
                    entry
                }
            }.onSuccess { localEntry ->
                alert(getString(R.string.theme_edit)) {
                    val dialogBinding = createEditBinding(ThemePackageManager.getConfig(localEntry), localEntry)
                    editDialogBinding = dialogBinding
                    editingEntry = localEntry
                    customView { dialogBinding.root }
                    okButton {
                        saveTheme(dialogBinding)
                        editDialogBinding = null
                        editingEntry = null
                    }
                    onDismiss {
                        editDialogBinding = null
                        editingEntry = null
                    }
                    cancelButton()
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_package_read_failed, it.localizedMessage))
            }
        }
    }

    private fun createEditBinding(
        current: ThemeConfig.Config,
        entry: ThemePackageManager.Entry?
    ): DialogThemePackageEditBinding {
        pendingMainBackgroundPath = current.backgroundImgPath
        pendingBookInfoBackgroundPath = current.bookInfoBackgroundImgPath
        pendingBlur = current.backgroundImgBlur
        pendingUiCornerScale = current.uiCornerScale ?: AppConfig.uiCornerScale
        pendingUiLayoutAlpha = current.uiLayoutAlpha ?: AppConfig.uiLayoutAlpha
        pendingFontScale = current.fontScale ?: getPrefInt(PreferKey.fontScale, 0)
        pendingUiCornerSearchFollow = current.uiCornerSearchFollow ?: AppConfig.uiCornerSearchFollow
        pendingUiCornerReplyFollow = current.uiCornerReplyFollow ?: AppConfig.uiCornerReplyFollow
        return DialogThemePackageEditBinding.inflate(layoutInflater).apply {
            etName.setText(current.themeName)
            setupColorRow(rowPrimary, R.string.theme_color_primary, current.primaryColor, colorPrimary)
            setupColorRow(rowAccent, R.string.theme_color_accent, current.accentColor, colorAccent)
            setupColorRow(rowBackground, R.string.theme_color_background, current.backgroundColor, colorBackground)
            setupColorRow(rowBottomBackground, R.string.theme_color_bottom_background, current.bottomBackground, colorBottomBackground)
            setupColorRow(rowPrimaryText, R.string.theme_color_primary_text, current.primaryTextColor ?: "#${primaryTextColor.hexString}", colorPrimaryText)
            setupColorRow(rowSecondaryText, R.string.theme_color_secondary_text, current.secondaryTextColor ?: "#${secondaryTextColor.hexString}", colorSecondaryText)
            setupImageRow(rowMainBackground, R.string.theme_image_main_background, true)
            setupImageRow(rowBookInfoBackground, R.string.theme_image_book_info_background, false)
            setupInterfaceRows(this)
            etName.isEnabled = entry?.source != ThemePackageManager.Source.REMOTE
        }
    }

    private fun setupInterfaceRows(binding: DialogThemePackageEditBinding) = binding.run {
        setupCornerScaleRow(rowCornerScale)
        setupLayoutAlphaRow(rowLayoutAlpha)
        setupFontScaleRow(rowFontScale)
        setupSwitchRow(rowSearchFollow, R.string.ui_corner_search_follow) {
            pendingUiCornerSearchFollow = !pendingUiCornerSearchFollow
            updateSwitchRow(rowSearchFollow, pendingUiCornerSearchFollow)
        }
        setupSwitchRow(rowReplyFollow, R.string.ui_corner_reply_follow) {
            pendingUiCornerReplyFollow = !pendingUiCornerReplyFollow
            updateSwitchRow(rowReplyFollow, pendingUiCornerReplyFollow)
        }
        updateSwitchRow(rowSearchFollow, pendingUiCornerSearchFollow)
        updateSwitchRow(rowReplyFollow, pendingUiCornerReplyFollow)
    }

    private fun setupCornerScaleRow(row: ItemThemePackageOptionBinding) {
        row.tvTitle.text = getString(R.string.ui_corner_scale)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = pendingUiCornerScale.toScaleText()
        row.root.setOnClickListener {
            NumberPickerDialog(this, isDecimalMode = true)
                .setTitle(getString(R.string.ui_corner_scale))
                .setMaxValue(30)
                .setMinValue(0)
                .setValue((pendingUiCornerScale * 10).toInt())
                .setCustomButton(R.string.btn_default_s) {
                    pendingUiCornerScale = 1f
                    row.tvValue.text = pendingUiCornerScale.toScaleText()
                }
                .show {
                    pendingUiCornerScale = (it / 10f).coerceIn(0f, 3f)
                    row.tvValue.text = pendingUiCornerScale.toScaleText()
                }
        }
    }

    private fun setupLayoutAlphaRow(row: ItemThemePackageOptionBinding) {
        row.tvTitle.text = getString(R.string.ui_layout_alpha)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingUiLayoutAlpha)
        row.root.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.ui_layout_alpha))
                .setMaxValue(100)
                .setMinValue(0)
                .setValue(pendingUiLayoutAlpha)
                .setCustomButton(R.string.btn_default_s) {
                    pendingUiLayoutAlpha = 100
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingUiLayoutAlpha)
                }
                .show {
                    pendingUiLayoutAlpha = it.coerceIn(0, 100)
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingUiLayoutAlpha)
                }
        }
    }

    private fun setupFontScaleRow(row: ItemThemePackageOptionBinding) {
        row.tvTitle.text = getString(R.string.font_scale)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = if (pendingFontScale == 0) {
            getString(R.string.btn_default_s)
        } else {
            "%.1f".format(Locale.US, pendingFontScale / 10f)
        }
        row.root.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.font_scale))
                .setMaxValue(16)
                .setMinValue(8)
                .setValue(if (pendingFontScale == 0) 10 else pendingFontScale)
                .setCustomButton(R.string.btn_default_s) {
                    pendingFontScale = 0
                    setupFontScaleRow(row)
                }
                .show {
                    pendingFontScale = it.coerceIn(8, 16)
                    setupFontScaleRow(row)
                }
        }
    }

    private fun setupSwitchRow(row: ItemThemePackageOptionBinding, titleRes: Int, onClick: () -> Unit) {
        row.tvTitle.text = getString(titleRes)
        row.viewSwatch.visibility = View.INVISIBLE
        row.root.setOnClickListener { onClick() }
    }

    private fun updateSwitchRow(row: ItemThemePackageOptionBinding, checked: Boolean) {
        row.tvValue.text = getString(if (checked) R.string.enable else R.string.disable)
    }

    private fun setupColorRow(
        row: ItemThemePackageOptionBinding,
        titleRes: Int,
        colorText: String,
        target: Int
    ) {
        row.tvTitle.text = getString(titleRes)
        row.viewSwatch.visibility = View.VISIBLE
        row.tvValue.text = normalizeColor(colorText).uppercase(Locale.ROOT)
        updateSwatch(row, normalizeColor(colorText).toColorInt())
        row.root.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(normalizeColor(row.tvValue.text?.toString()).toColorInt())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(target)
                .show(this)
        }
    }

    private fun updateSwatch(row: ItemThemePackageOptionBinding, color: Int) {
        row.viewSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.scaledDp(11f)
            setColor(color)
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.adjustAlpha(primaryTextColor, 0.16f))
        }
    }

    private fun setupImageRow(row: ItemThemePackageOptionBinding, titleRes: Int, isMain: Boolean) {
        row.tvTitle.text = getString(titleRes)
        row.viewSwatch.visibility = View.INVISIBLE
        updateImageRow(row, isMain)
        row.root.setOnClickListener {
            showImageActions(isMain)
        }
    }

    private fun updateImageRow(row: ItemThemePackageOptionBinding, isMain: Boolean) {
        val path = if (isMain) pendingMainBackgroundPath else pendingBookInfoBackgroundPath
        row.tvValue.text = when {
            path.isNullOrBlank() && isMain -> getString(R.string.theme_image_value_unselected_blur, pendingBlur)
            path.isNullOrBlank() -> getString(R.string.theme_image_value_unselected)
            isMain -> getString(R.string.theme_image_value_file_blur, File(path).name, pendingBlur)
            else -> File(path).name
        }
    }

    private fun showImageActions(isMain: Boolean) {
        val hasImage = if (isMain) !pendingMainBackgroundPath.isNullOrBlank() else !pendingBookInfoBackgroundPath.isNullOrBlank()
        val actions = buildList {
            if (isMain) add(ThemeImageAction.BLUR)
            add(ThemeImageAction.SELECT)
            add(ThemeImageAction.ONLINE)
            if (hasImage) add(ThemeImageAction.DELETE)
        }
        selector(
            getString(if (isMain) R.string.theme_image_main_background else R.string.theme_image_book_info_background),
            actions.map { getString(it.titleRes) }
        ) { _, index ->
            when (actions[index]) {
                ThemeImageAction.BLUR -> showBlurDialog()
                ThemeImageAction.SELECT -> selectImage.launch {
                    requestCode = if (isMain) requestMainBackground else requestBookInfoBackground
                    mode = HandleFileContract.IMAGE
                }
                ThemeImageAction.ONLINE -> showOnlineImageDialog(isMain)
                ThemeImageAction.DELETE -> {
                    if (isMain) {
                        pendingMainBackgroundPath = null
                        editDialogBinding?.let { updateImageRow(it.rowMainBackground, true) }
                    } else {
                        pendingBookInfoBackgroundPath = null
                        editDialogBinding?.let { updateImageRow(it.rowBookInfoBackground, false) }
                    }
                }
            }
        }
    }

    private fun showOnlineImageDialog(isMain: Boolean) {
        alert(R.string.theme_image_online) {
            val alertBinding = io.legado.app.databinding.DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "https://..."
                editView.setText(
                    if (isMain) {
                        pendingMainBackgroundPath?.takeIf { it.startsWith("http", ignoreCase = true) }
                    } else {
                        pendingBookInfoBackgroundPath?.takeIf { it.startsWith("http", ignoreCase = true) }
                    }.orEmpty()
                )
            }
            customView { alertBinding.root }
            okButton {
                val url = alertBinding.editView.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true)
                ) {
                    toastOnUi(R.string.theme_image_online_invalid)
                    return@okButton
                }
                if (isMain) {
                    pendingMainBackgroundPath = url
                    editDialogBinding?.let { updateImageRow(it.rowMainBackground, true) }
                } else {
                    pendingBookInfoBackgroundPath = url
                    editDialogBinding?.let { updateImageRow(it.rowBookInfoBackground, false) }
                }
            }
            cancelButton()
        }
    }

    private fun showBlurDialog() {
        alert(R.string.theme_image_blur) {
            val blurBinding = DialogImageBlurringBinding.inflate(layoutInflater).apply {
                seekBar.progress = pendingBlur
                textViewValue.text = pendingBlur.toString()
                seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        textViewValue.text = progress.toString()
                    }
                })
            }
            customView { blurBinding.root }
            okButton {
                pendingBlur = blurBinding.seekBar.progress.coerceIn(0, 25)
                editDialogBinding?.let { updateImageRow(it.rowMainBackground, true) }
            }
            cancelButton()
        }
    }

    private fun saveTheme(dialogBinding: DialogThemePackageEditBinding) {
        val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
            .ifBlank { getString(if (isNightTheme) R.string.theme_night else R.string.theme_day) }
        val config = kotlin.runCatching {
            ThemeConfig.Config(
                themeName = name,
                isNightTheme = isNightTheme,
                primaryColor = normalizeColor(dialogBinding.rowPrimary.tvValue.text?.toString()),
                accentColor = normalizeColor(dialogBinding.rowAccent.tvValue.text?.toString()),
                backgroundColor = normalizeColor(dialogBinding.rowBackground.tvValue.text?.toString()),
                bottomBackground = normalizeColor(dialogBinding.rowBottomBackground.tvValue.text?.toString()),
                transparentNavBar = true,
                backgroundImgPath = pendingMainBackgroundPath,
                backgroundImgBlur = pendingBlur,
                bookInfoBackgroundImgPath = pendingBookInfoBackgroundPath,
                primaryTextColor = normalizeOptionalColor(dialogBinding.rowPrimaryText.tvValue.text?.toString()),
                secondaryTextColor = normalizeOptionalColor(dialogBinding.rowSecondaryText.tvValue.text?.toString()),
                uiCornerScale = pendingUiCornerScale,
                uiLayoutAlpha = pendingUiLayoutAlpha,
                uiCornerSearchFollow = pendingUiCornerSearchFollow,
                uiCornerReplyFollow = pendingUiCornerReplyFollow,
                fontScale = pendingFontScale
            )
        }.onFailure {
            toastOnUi("颜色格式不正确")
        }.getOrNull() ?: return
        addTheme(config)
    }

    private fun addTheme(config: ThemeConfig.Config) {
        val oldEntry = editingEntry
        lifecycleScope.launch {
            kotlin.runCatching {
                val wasApplied = oldEntry?.let { isApplied(it) } == true
                val exists = ThemePackageManager.localThemeExists(
                    config.isNightTheme,
                    config.themeName,
                    oldEntry?.dirName
                )
                if (exists) {
                    throw IllegalArgumentException(getString(R.string.theme_name_exists))
                }
                val entry = ThemePackageManager.addFromConfig(config)
                if (oldEntry != null && oldEntry.dirName != entry.dirName) {
                    if (oldEntry.source != ThemePackageManager.Source.REMOTE) {
                        ThemePackageManager.deleteLocal(oldEntry)
                    }
                    if (AppConfig.syncThemePackages && oldEntry.source != ThemePackageManager.Source.LOCAL) {
                        enqueueRemoteDelete(oldEntry)
                    }
                }
                if (wasApplied) {
                    ThemePackageManager.apply(this@ThemeManageActivity, entry, switchNightMode = false)
                }
                entry
            }.onSuccess {
                toastOnUi(getString(R.string.theme_saved_local))
                loadThemes()
                enqueueUploadIfNeeded(it)
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_save_failed, it.localizedMessage))
            }
        }
    }

    private fun enqueueUploadIfNeeded(entry: ThemePackageManager.Entry) {
        if (!AppConfig.syncThemePackages) return
        enqueueRemoteSync(
            RemoteSyncTask(
                key = "upload:${entry.packageInfo.isNightTheme}:${entry.dirName}",
                type = RemoteSyncTask.Type.UPLOAD,
                isNightTheme = entry.packageInfo.isNightTheme,
                dirName = entry.dirName
            )
        )
    }

    private fun currentConfig(): ThemeConfig.Config {
        val name = getString(if (isNightTheme) R.string.theme_night else R.string.theme_day)
        val primary = getPrefInt(
            if (isNightTheme) PreferKey.cNPrimary else PreferKey.cPrimary,
            getCompatColor(if (isNightTheme) R.color.md_blue_grey_600 else R.color.md_brown_500)
        )
        val accent = getPrefInt(
            if (isNightTheme) PreferKey.cNAccent else PreferKey.cAccent,
            getCompatColor(if (isNightTheme) R.color.md_deep_orange_800 else R.color.md_red_600)
        )
        val background = getPrefInt(
            if (isNightTheme) PreferKey.cNBackground else PreferKey.cBackground,
            getCompatColor(if (isNightTheme) R.color.md_grey_900 else R.color.md_grey_100)
        )
        val bottom = getPrefInt(
            if (isNightTheme) PreferKey.cNBBackground else PreferKey.cBBackground,
            getCompatColor(if (isNightTheme) R.color.md_grey_850 else R.color.md_grey_200)
        )
        return ThemeConfig.Config(
            themeName = name,
            isNightTheme = isNightTheme,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bottom.hexString}",
            transparentNavBar = true,
            backgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage),
            backgroundImgBlur = getPrefInt(if (isNightTheme) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring, 0),
            bookInfoBackgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            primaryTextColor = "#${ThemeStore.textColorPrimary(this).hexString}",
            secondaryTextColor = "#${ThemeStore.textColorSecondary(this).hexString}",
            uiCornerScale = AppConfig.uiCornerScale,
            uiLayoutAlpha = AppConfig.uiLayoutAlpha,
            uiCornerSearchFollow = AppConfig.uiCornerSearchFollow,
            uiCornerReplyFollow = AppConfig.uiCornerReplyFollow,
            fontScale = getPrefInt(PreferKey.fontScale, 0)
        )
    }

    private fun Float.toScaleText(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    private fun normalizeColor(value: String?): String {
        val color = value?.trim().orEmpty().let {
            if (it.startsWith("#")) it else "#$it"
        }
        color.toColorInt()
        return color
    }

    private fun normalizeOptionalColor(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return normalizeColor(text)
    }

    private fun copySelectedImage(uri: Uri, prefix: String): String? {
        return kotlin.runCatching {
            val dir = externalFiles.getFile("themePackageTemp").apply { mkdirs() }
            val suffix = contentResolver.getType(uri)?.substringAfterLast("/")?.let { ".$it" } ?: ".jpg"
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}$suffix")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: return null
            file.absolutePath
        }.onFailure {
            if (it.isJobCancellation()) return@onFailure
            toastOnUi(it.localizedMessage)
        }.getOrNull()
    }

    private fun showActions(entry: ThemePackageManager.Entry) {
        val actions = buildList {
            add(ThemeAction.APPLY)
            add(ThemeAction.EDIT)
            if (entry.source != ThemePackageManager.Source.REMOTE) add(ThemeAction.EXPORT)
            if (entry.source != ThemePackageManager.Source.LOCAL) add(ThemeAction.DOWNLOAD)
            if (entry.source != ThemePackageManager.Source.REMOTE) add(ThemeAction.UPLOAD)
            if (!isApplied(entry)) {
                if (entry.source != ThemePackageManager.Source.REMOTE) add(ThemeAction.DELETE_LOCAL)
                if (entry.source != ThemePackageManager.Source.LOCAL) add(ThemeAction.DELETE_REMOTE)
                if (entry.source == ThemePackageManager.Source.BOTH) add(ThemeAction.DELETE_BOTH)
            }
        }
        selector(entry.packageInfo.name, actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                ThemeAction.APPLY -> applyTheme(entry)
                ThemeAction.EDIT -> showEditDialog(entry)
                ThemeAction.EXPORT -> exportThemeZip(entry)
                ThemeAction.DOWNLOAD -> runAction(getString(R.string.theme_downloaded)) { ThemePackageManager.download(entry) }
                ThemeAction.UPLOAD -> {
                    enqueueUploadIfNeeded(entry)
                    toastOnUi(getString(R.string.theme_sync_queued))
                }
                ThemeAction.DELETE_LOCAL -> confirmDeleteTheme(entry, getString(R.string.theme_delete_local_confirm)) {
                    ThemePackageManager.deleteLocal(entry)
                }
                ThemeAction.DELETE_REMOTE -> confirmDeleteTheme(entry, getString(R.string.theme_delete_remote_confirm)) {
                    enqueueRemoteDelete(entry)
                }
                ThemeAction.DELETE_BOTH -> confirmDeleteTheme(entry, getString(R.string.theme_delete_both_confirm)) {
                    ThemePackageManager.deleteLocal(entry)
                    enqueueRemoteDelete(entry)
                }
            }
        }
    }

    private fun exportThemeZip(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.exportZip(entry)
            }.onSuccess { zipFile ->
                exportThemePackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(
                        zipFile.name,
                        zipFile,
                        "application/zip"
                    )
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_export_failed, it.localizedMessage))
            }
        }
    }

    private fun importThemeZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val dir = externalFiles.getFile("themePackageImports").apply { mkdirs() }
                val file = File(dir, "import_${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                ThemePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(getString(R.string.theme_imported))
                loadThemes()
                enqueueUploadIfNeeded(it)
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_import_failed, it.localizedMessage))
            }
        }
    }

    private fun applyTheme(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val localEntry = if (entry.source == ThemePackageManager.Source.REMOTE) {
                    ThemePackageManager.download(entry)
                } else {
                    entry
                }
                ThemePackageManager.apply(this@ThemeManageActivity, localEntry, switchNightMode = false)
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_apply_failed, it.localizedMessage))
            }.onSuccess {
                if (entry.packageInfo.isNightTheme) {
                    appliedNightThemeOverride = entry.packageInfo.name
                } else {
                    appliedDayThemeOverride = entry.packageInfo.name
                }
                toastOnUi(getString(R.string.theme_applied))
                adapter.notifyDataSetChanged()
                loadThemes()
            }
        }
    }

    private fun isApplied(entry: ThemePackageManager.Entry): Boolean {
        val overrideName = if (entry.packageInfo.isNightTheme) {
            appliedNightThemeOverride
        } else {
            appliedDayThemeOverride
        }
        if (overrideName != null) {
            return overrideName == entry.packageInfo.name
        }
        val key = if (entry.packageInfo.isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        return getPrefString(key) == entry.packageInfo.name
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching {
                block()
            }.onSuccess {
                toastOnUi(successMessage)
                loadThemes()
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun enqueueRemoteDelete(entry: ThemePackageManager.Entry) {
        if (!AppConfig.syncThemePackages) return
        enqueueRemoteSync(
            RemoteSyncTask(
                key = "delete:${entry.packageInfo.isNightTheme}:${entry.dirName}",
                type = RemoteSyncTask.Type.DELETE,
                isNightTheme = entry.packageInfo.isNightTheme,
                dirName = entry.dirName
            )
        )
    }

    private fun enqueueRemoteSync(task: RemoteSyncTask) {
        synchronized(pendingRemoteSyncTasks) {
            pendingRemoteSyncTasks[task.key] = task
            savePendingRemoteSyncTasksLocked()
        }
        flushPendingRemoteSyncTasks()
    }

    private fun restorePendingRemoteSyncTasks() {
        val tasks = getPrefString(PreferKey.themePackageSyncTasks).orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { GSON.fromJsonArray<RemoteSyncTask>(it).getOrNull() }
            .orEmpty()
        if (tasks.isEmpty()) return
        synchronized(pendingRemoteSyncTasks) {
            pendingRemoteSyncTasks.clear()
            tasks.forEach { task ->
                pendingRemoteSyncTasks[task.key] = task.copy(lastError = "")
            }
        }
    }

    private fun savePendingRemoteSyncTasksLocked() {
        val tasks = pendingRemoteSyncTasks.values.toList()
        if (tasks.isEmpty()) {
            removePref(PreferKey.themePackageSyncTasks)
        } else {
            putPrefString(PreferKey.themePackageSyncTasks, GSON.toJson(tasks))
        }
    }

    private fun flushPendingRemoteSyncTasks() {
        val hasPending = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.isNotEmpty() }
        if (syncingRemoteTasks || !hasPending || !AppConfig.syncThemePackages) return
        syncingRemoteTasks = true
        themeRemoteSyncScope.launch {
            val failed = linkedMapOf<String, RemoteSyncTask>()
            val tasks = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.values.toList() }
            tasks.forEach { task ->
                kotlin.runCatching {
                    task.execute()
                }.onSuccess {
                    synchronized(pendingRemoteSyncTasks) {
                        if (pendingRemoteSyncTasks[task.key] == task) {
                            pendingRemoteSyncTasks.remove(task.key)
                            savePendingRemoteSyncTasksLocked()
                        }
                    }
                }.onFailure {
                    if (!it.isJobCancellation()) {
                        failed[task.key] = task
                    }
                }
            }
            syncingRemoteTasks = false
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (failed.isEmpty()) {
                    toastOnUi(getString(R.string.theme_sync_done))
                    loadThemes()
                } else {
                    binding.tvSummary.text = appendPendingRemoteSummary(getString(R.string.theme_sync_failed_retry))
                    toastOnUi(getString(R.string.theme_sync_failed, failed.values.first().lastError))
                }
            }
            val pendingKeys = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.keys.toSet() }
            if (pendingKeys.any { it !in failed.keys }) {
                flushPendingRemoteSyncTasks()
            }
        }
    }

    private fun appendPendingRemoteSummary(base: String): String {
        val pendingCount = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.size }
        return if (pendingCount > 0) {
            "$base\n${getString(R.string.theme_sync_pending, pendingCount)}"
        } else {
            base
        }
    }

    private fun confirmDelete(message: String, block: suspend () -> Unit) {
        alert(getString(R.string.delete), message) {
            yesButton {
                runAction(getString(R.string.delete_success), block)
            }
            noButton()
        }
    }

    private fun confirmDeleteTheme(
        entry: ThemePackageManager.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        if (isApplied(entry)) {
            toastOnUi(getString(R.string.theme_delete_applied_forbidden))
            return
        }
        confirmDelete(message, block)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        var items: List<ThemePackageManager.Entry> = emptyList()
            set(value) {
                val oldItems = field
                field = value
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = oldItems.size
                    override fun getNewListSize(): Int = value.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val old = oldItems[oldItemPosition]
                        val new = value[newItemPosition]
                        return old.packageInfo.isNightTheme == new.packageInfo.isNightTheme &&
                                old.dirName == new.dirName
                    }

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val old = oldItems[oldItemPosition]
                        val new = value[newItemPosition]
                        return old.packageInfo == new.packageInfo &&
                                old.source == new.source &&
                                old.remoteUpdatedAt == new.remoteUpdatedAt &&
                                isApplied(old) == isApplied(new)
                    }
                }).dispatchUpdatesTo(this)
            }

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long {
            val item = items[position]
            return "${item.packageInfo.isNightTheme}:${item.dirName}".hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemThemePackageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(entry: ThemePackageManager.Entry) = itemBinding.run {
                val pkg = entry.packageInfo
                root.background = UiCorner.rounded(
                    ContextCompat.getColor(this@ThemeManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@ThemeManageActivity)
                )
                cardPreview.radius = UiCorner.panelRadius(this@ThemeManageActivity)
                tvName.text = pkg.name
                tvSource.visibility = View.GONE
                tvInfo.text = buildString {
                    if (isApplied(entry)) {
                        append(getString(R.string.theme_current_applied))
                        append(" · ")
                    }
                    append(getString(if (pkg.isNightTheme) R.string.theme_night_short else R.string.theme_day_short))
                    append(" · ")
                    val time = maxOf(pkg.updatedAt, entry.remoteUpdatedAt)
                    append(if (time > 0) dateFormat.format(Date(time)) else getString(R.string.theme_time_unknown))
                }
                tvName.setTextColor(primaryTextColor)
                tvInfo.setTextColor(secondaryTextColor)
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        ContextCompat.getColor(this@ThemeManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@ThemeManageActivity)
                    )
                }
                btnApply.setTextColor(accentColor)
                btnApply.text = getString(if (isApplied(entry)) R.string.theme_applied_state else R.string.theme_apply)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                bindPreview(entry)
                btnApply.setOnClickListener { applyTheme(entry) }
                btnEdit.setOnClickListener { showEditDialog(entry) }
                btnMore.setOnClickListener { showActions(entry) }
                root.setOnClickListener { showActions(entry) }
            }

            private fun ItemThemePackageBinding.bindPreview(entry: ThemePackageManager.Entry) {
                val config = kotlin.runCatching { ThemePackageManager.getConfig(entry) }
                    .getOrElse { entry.packageInfo.config }
                val fallbackColor = config?.backgroundColor.toPreviewColor(entry.packageInfo.isNightTheme)
                Glide.with(ivPreview.context).clear(ivPreview)
                cardPreview.setCardBackgroundColor(fallbackColor)
                ivPreview.setBackgroundColor(fallbackColor)
                ivPreview.setImageDrawable(null)
                val backgroundPath = config?.backgroundImgPath?.takeIf { it.isNotBlank() }
                if (backgroundPath.isNullOrBlank()) {
                    return
                }
                ImageLoader.load(ivPreview.context, backgroundPath)
                    .centerCrop()
                    .error(ColorDrawable(fallbackColor))
                    .into(ivPreview)
            }

            private fun String?.toPreviewColor(isNightTheme: Boolean): Int {
                return kotlin.runCatching {
                    val color = this?.trim().orEmpty()
                    val normalized = if (color.startsWith("#")) color else "#$color"
                    normalized.toColorInt()
                }.getOrElse {
                    if (isNightTheme) Color.BLACK else Color.WHITE
                }
            }
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val binding = editDialogBinding ?: return
        val hex = "#${color.hexString}".uppercase(Locale.ROOT)
        val row = when (dialogId) {
            colorPrimary -> binding.rowPrimary
            colorAccent -> binding.rowAccent
            colorBackground -> binding.rowBackground
            colorBottomBackground -> binding.rowBottomBackground
            colorPrimaryText -> binding.rowPrimaryText
            colorSecondaryText -> binding.rowSecondaryText
            else -> null
        } ?: return
        row.tvValue.text = hex
        updateSwatch(row, color)
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun Throwable.isJobCancellation(): Boolean {
        return this is CancellationException || cause?.isJobCancellation() == true
    }

    companion object {
        private val themeRemoteSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val requestMainBackground = 301
        private const val requestBookInfoBackground = 302
        private const val colorPrimary = 401
        private const val colorAccent = 402
        private const val colorBackground = 403
        private const val colorBottomBackground = 404
        private const val colorPrimaryText = 405
        private const val colorSecondaryText = 406
    }

    private enum class ThemeAction(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.theme_export_zip),
        DOWNLOAD(R.string.theme_download_local),
        UPLOAD(R.string.theme_upload_remote),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private enum class ThemeImageAction(val titleRes: Int) {
        BLUR(R.string.theme_image_blur),
        SELECT(R.string.theme_image_select),
        ONLINE(R.string.theme_image_online),
        DELETE(R.string.theme_image_delete)
    }

    private data class RemoteSyncTask(
        val key: String,
        val type: Type,
        val isNightTheme: Boolean,
        val dirName: String,
        var lastError: String = ""
    ) {
        suspend fun execute() {
            val entry = ThemePackageManager.Entry(
                packageInfo = ThemePackageManager.Package(
                    name = dirName,
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = 0L,
                    config = null
                ),
                source = ThemePackageManager.Source.LOCAL,
                localDir = ThemePackageManager.localDir(isNightTheme, dirName)
            )
            runCatching {
                when (type) {
                    Type.UPLOAD -> ThemePackageManager.upload(entry)
                    Type.DELETE -> ThemePackageManager.deleteRemote(entry)
                }
            }.onFailure {
                lastError = it.localizedMessage ?: it.toString()
                throw it
            }.getOrThrow()
        }

        enum class Type {
            UPLOAD,
            DELETE
        }
    }
}
