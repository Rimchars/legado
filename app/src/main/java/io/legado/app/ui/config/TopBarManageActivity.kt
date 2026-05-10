package io.legado.app.ui.config

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TopBarManageActivity : BaseActivity<ActivityThemeManageBinding>(), ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var isNightMode = false
    private var editingEntry: TopBarConfig.Entry? = null
    private var editingDialog: LinearLayout? = null
    private var pendingConfig: TopBarConfig.Config? = null
    private var pendingColorTarget = 0
    private var pendingWallpaperCropRequest: ImageCropHelper.Request? = null
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importPackage)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    private val selectWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startWallpaperCrop)
    }

    private val cropWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result).exists()) {
            pendingConfig?.wallpaperPath = result
            refreshEditDialog()
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.top_bar_manage)
        initView()
        loadPackages()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@TopBarManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@TopBarManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@TopBarManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@TopBarManageActivity)
            )
        }
        btnAdd.text = getString(R.string.theme_add)
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@TopBarManageActivity, R.color.background_card),
            ContextCompat.getColor(this@TopBarManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@TopBarManageActivity)
        )
        btnAdd.setOnClickListener { showAddDialog() }
        tvSummary.text = getString(R.string.top_bar_manage_summary)
        recyclerView.layoutManager = LinearLayoutManager(this@TopBarManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnDay.setOnClickListener {
            if (isNightMode) {
                isNightMode = false
                updateTabs()
                loadPackages()
            }
        }
        btnNight.setOnClickListener {
            if (!isNightMode) {
                isNightMode = true
                updateTabs()
                loadPackages()
            }
        }
        updateTabs()
        titleBar.toolbar.menu.add(R.string.import_str).setOnMenuItemClickListener {
            importPackage.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("zip")
            }
            true
        }
    }

    private fun showAddDialog() {
        selector(
            getString(R.string.theme_add),
            listOf(getString(R.string.theme_manual_config), getString(R.string.theme_import_zip))
        ) { _, index ->
            when (index) {
                0 -> showEditDialog(null)
                1 -> importPackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun updateTabs() = binding.run {
        btnDay.isSelected = !isNightMode
        btnNight.isSelected = isNightMode
        btnDay.setTextColor(if (!isNightMode) accentColor else primaryTextColor)
        btnNight.setTextColor(if (isNightMode) accentColor else primaryTextColor)
    }

    private fun loadPackages() {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.loadEntries(this@TopBarManageActivity, isNightMode, AppConfig.syncThemePackages)
                }
            }.onSuccess {
                adapter.submit(it, TopBarConfig.activeDirName(isNightMode))
                binding.tvSummary.text = getString(R.string.top_bar_manage_summary)
            }.onFailure {
                binding.tvSummary.text = it.localizedMessage
            }
        }
    }

    private fun showEditDialog(entry: TopBarConfig.Entry?) {
        val base = entry ?: TopBarConfig.Entry(
            TopBarConfig.defaultConfig(this, isNightMode).copy(name = nextPackageName()),
            TopBarConfig.Source.LOCAL,
            ""
        )
        if (entry != null && base.dirName == TopBarConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        if (base.localDir == null && entry != null && base.source == TopBarConfig.Source.REMOTE) {
            toastOnUi(R.string.navigation_bar_download_first)
            return
        }
        editingEntry = base
        pendingConfig = base.config.copy()
        val root = buildEditView()
        editingDialog = root
        alert(R.string.top_bar_edit) {
            customView { root }
            okButton { saveEditingPackage() }
            cancelButton()
        }
    }

    private fun buildEditView(): LinearLayout {
        val config = pendingConfig!!
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 2, 4)
            applyUiBodyTypefaceDeep(this@TopBarManageActivity.uiTypeface())
            addView(PackageManageUi.nameInput(this@TopBarManageActivity, config.name, getString(R.string.top_bar_name)))
            addView(optionRow(getString(R.string.top_bar_style), styleLabel(config.style)) {
                selector(
                    getString(R.string.top_bar_style),
                    listOf(
                        getString(R.string.top_bar_style_default),
                        getString(R.string.top_bar_style_immersive)
                    )
                ) { _, index ->
                    config.style = when (index) {
                        1 -> TopBarConfig.STYLE_IMMERSIVE
                        else -> TopBarConfig.STYLE_DEFAULT
                    }
                    if (config.style == TopBarConfig.STYLE_IMMERSIVE) {
                        if (config.backgroundColor == null) {
                            config.backgroundColor = TopBarConfig.defaultBackgroundColor(config.isNightMode)
                        }
                        if (config.cornerScale == null) {
                            config.cornerScale = 1f
                        }
                        if (config.tagBarColor == null) {
                            config.tagBarColor = Color.WHITE
                        }
                        if (config.tagBarAlpha == 100) {
                            config.tagBarAlpha = 0
                        }
                    }
                    refreshEditDialog()
                }
            })
            if (config.style == TopBarConfig.STYLE_IMMERSIVE) {
                addView(optionRow(getString(R.string.top_bar_corner_scale), cornerScaleLabel(config.cornerScale)) {
                    showCornerScalePicker(config.cornerScale ?: 1f) {
                        config.cornerScale = it
                    }
                })
                val backgroundColor = config.backgroundColor ?: TopBarConfig.defaultBackgroundColor(config.isNightMode)
                addView(optionRow(getString(R.string.top_bar_background_color), colorLabel(backgroundColor), backgroundColor) {
                    showColorPicker(COLOR_BACKGROUND, backgroundColor)
                })
                addView(optionRow(getString(R.string.top_bar_wallpaper), wallpaperLabel(config.wallpaperPath)) {
                    showWallpaperSelector()
                })
                addView(optionRow(getString(R.string.top_bar_wallpaper_alpha), "${config.wallpaperAlpha}%") {
                    showAlphaPicker(getString(R.string.top_bar_wallpaper_alpha), config.wallpaperAlpha) {
                        config.wallpaperAlpha = it
                    }
                })
                val tagBarColor = config.tagBarColor ?: Color.WHITE
                addView(optionRow(getString(R.string.top_bar_tag_bar_color), colorLabel(tagBarColor), tagBarColor) {
                    showColorPicker(COLOR_TAG_BAR, tagBarColor)
                })
                addView(optionRow(getString(R.string.top_bar_tag_bar_alpha), "${config.tagBarAlpha}%") {
                    showAlphaPicker(getString(R.string.top_bar_tag_bar_alpha), config.tagBarAlpha) {
                        config.tagBarAlpha = it
                    }
                })
                val selectedColor = config.tagSelectedColor ?: defaultSelectedColor()
                addView(optionRow(getString(R.string.top_bar_tag_selected_color), colorLabel(selectedColor), selectedColor) {
                    showColorPicker(COLOR_TAG_SELECTED, selectedColor)
                })
                addView(optionRow(getString(R.string.top_bar_tag_selected_alpha), "${config.tagSelectedAlpha}%") {
                    showAlphaPicker(getString(R.string.top_bar_tag_selected_alpha), config.tagSelectedAlpha) {
                        config.tagSelectedAlpha = it
                    }
                })
            } else {
                val tagBarColor = config.tagBarColor ?: defaultTagBarColor()
                addView(optionRow(getString(R.string.top_bar_tag_bar_color), colorLabel(tagBarColor), tagBarColor) {
                    showColorPicker(COLOR_TAG_BAR, tagBarColor)
                })
                addView(optionRow(getString(R.string.top_bar_tag_bar_alpha), "${config.tagBarAlpha}%") {
                    showAlphaPicker(getString(R.string.top_bar_tag_bar_alpha), config.tagBarAlpha) {
                        config.tagBarAlpha = it
                    }
                })
                val selectedColor = config.tagSelectedColor ?: defaultSelectedColor()
                addView(optionRow(getString(R.string.top_bar_tag_selected_color), colorLabel(selectedColor), selectedColor) {
                    showColorPicker(COLOR_TAG_SELECTED, selectedColor)
                })
                addView(optionRow(getString(R.string.top_bar_tag_selected_alpha), "${config.tagSelectedAlpha}%") {
                    showAlphaPicker(getString(R.string.top_bar_tag_selected_alpha), config.tagSelectedAlpha) {
                        config.tagSelectedAlpha = it
                    }
                })
            }
        }
    }

    private fun showWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        selector(
            getString(R.string.top_bar_wallpaper),
            buildList {
                add(getString(R.string.theme_image_select))
                if (hasWallpaper) add(getString(R.string.theme_image_delete))
            }
        ) { _, index ->
            if (index == 0) {
                selectWallpaper.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.top_bar_wallpaper)
                }
            } else {
                pendingConfig?.wallpaperPath = null
                refreshEditDialog()
            }
        }
    }

    private fun startWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = REQUEST_WALLPAPER,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (220 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "topBarWallpapers",
            prefix = "top_bar",
            targetWidth = 1600
        )
        pendingWallpaperCropRequest = request
        cropWallpaper.launch(request.params)
    }

    private fun optionRow(title: String, value: String, onClick: () -> Unit): View {
        return PackageManageUi.optionRow(this, title, value, onClick)
    }

    private fun optionRow(title: String, value: String, colorPreview: Int, onClick: () -> Unit): View {
        return PackageManageUi.optionRow(this, title, value, colorPreview, onClick)
    }

    private fun showAlphaPicker(title: String, value: Int, apply: (Int) -> Unit) {
        NumberPickerDialog(this)
            .setTitle(title)
            .setMinValue(0)
            .setMaxValue(100)
            .setValue(value)
            .show {
                apply(it.coerceIn(0, 100))
                refreshEditDialog()
            }
    }

    private fun showCornerScalePicker(value: Float, apply: (Float) -> Unit) {
        NumberPickerDialog(this, isDecimalMode = true)
            .setTitle(getString(R.string.top_bar_corner_scale))
            .setMinValue(0)
            .setMaxValue(30)
            .setValue((value.coerceIn(0f, 3f) * 10).toInt())
            .show {
                apply((it / 10f).coerceIn(0f, 3f))
                refreshEditDialog()
            }
    }

    private fun showColorPicker(target: Int, color: Int) {
        pendingColorTarget = target
        ColorPickerDialog.newBuilder()
            .setDialogId(target)
            .setColor(color)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .show(this)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val config = pendingConfig ?: return
        when (dialogId) {
            COLOR_BACKGROUND -> config.backgroundColor = color
            COLOR_TAG_BAR -> config.tagBarColor = color
            COLOR_TAG_SELECTED -> config.tagSelectedColor = color
        }
        refreshEditDialog()
    }

    override fun onDialogDismissed(dialogId: Int) {
        pendingColorTarget = 0
    }

    private fun refreshEditDialog() {
        val root = editingDialog ?: return
        root.removeAllViews()
        val rebuilt = buildEditView()
        while (rebuilt.childCount > 0) {
            val child = rebuilt.getChildAt(0)
            rebuilt.removeView(child)
            root.addView(child)
        }
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val oldEntry = editingEntry
        val name = editingDialog?.findViewWithTag<EditText>("name")?.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.addOrUpdate(config.copy(name = name), oldEntry.takeIf { it?.dirName != TopBarConfig.DEFAULT_DIR_NAME })
                }
            }.onSuccess {
                if (oldEntry?.dirName == TopBarConfig.DEFAULT_DIR_NAME || it.dirName == TopBarConfig.activeDirName(it.config.isNightMode)) {
                    TopBarConfig.apply(it)
                    postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                }
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun showActions(entry: TopBarConfig.Entry) {
        val actions = buildList {
            add(Action.APPLY)
            add(Action.EDIT)
            if (entry.dirName != TopBarConfig.DEFAULT_DIR_NAME) {
                add(Action.EXPORT)
                if (AppConfig.syncThemePackages) add(Action.UPLOAD)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.DELETE_LOCAL)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == TopBarConfig.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        selector(entry.config.name, actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                Action.APPLY -> applyPackage(entry)
                Action.EDIT -> showEditDialog(entry)
                Action.EXPORT -> exportPackage(entry)
                Action.UPLOAD -> runAction { TopBarConfig.upload(entry) }
                Action.DOWNLOAD -> runAction { TopBarConfig.download(entry) }
                Action.DELETE_LOCAL -> confirmDelete(entry, getString(R.string.navigation_bar_delete_local_confirm)) {
                    TopBarConfig.deleteLocal(entry)
                    postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                }
                Action.DELETE_REMOTE -> confirmDelete(entry, getString(R.string.navigation_bar_delete_remote_confirm)) {
                    TopBarConfig.deleteRemote(entry)
                }
                Action.DELETE_BOTH -> confirmDelete(entry, getString(R.string.navigation_bar_delete_both_confirm)) {
                    TopBarConfig.delete(entry)
                    postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                }
            }
        }
    }

    private fun applyPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { if (entry.source == TopBarConfig.Source.REMOTE) TopBarConfig.download(entry) else entry }
            }.onSuccess {
                TopBarConfig.apply(it)
                postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun confirmDelete(entry: TopBarConfig.Entry, message: String, block: suspend () -> Unit) {
        alert(getString(R.string.delete), message) {
            yesButton { runAction(block) }
            noButton()
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { toastOnUi(R.string.success) }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun exportPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { TopBarConfig.exportZip(entry) }
            }.onSuccess { zip ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importPackage(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile("topBarImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { TopBarConfig.importZip(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun colorLabel(color: Int?): String {
        return color?.let { "#${Integer.toHexString(it).takeLast(6).uppercase(Locale.ROOT)}" }
            ?: getString(R.string.top_bar_follow_theme)
    }

    private fun cornerScaleLabel(value: Float?): String {
        return String.format(Locale.ROOT, "%.1f", (value ?: 1f).coerceIn(0f, 3f))
    }

    private fun wallpaperLabel(path: String?): String {
        return if (path.isNullOrBlank()) {
            getString(R.string.theme_image_value_unselected)
        } else {
            getString(R.string.theme_image_selected)
        }
    }

    private fun styleLabel(style: String): String {
        return getString(
            when (style) {
                TopBarConfig.STYLE_IMMERSIVE -> R.string.top_bar_style_immersive
                else -> R.string.top_bar_style_default
            }
        )
    }

    private fun defaultTagBarColor(): Int = ContextCompat.getColor(this, R.color.background_menu)

    private fun defaultSelectedColor(): Int = ContextCompat.getColor(this, R.color.background_card)

    private fun nextPackageName(): String {
        val base = getString(R.string.top_bar_custom_name)
        val usedNames = adapter.items.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private enum class Action(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.export),
        UPLOAD(R.string.navigation_bar_upload),
        DOWNLOAD(R.string.action_download),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        var items: List<TopBarConfig.Entry> = emptyList()
            private set
        private var activeDirName = TopBarConfig.DEFAULT_DIR_NAME

        fun submit(value: List<TopBarConfig.Entry>, activeDirName: String) {
            val old = items
            val oldActive = this.activeDirName
            items = value
            this.activeDirName = activeDirName
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = value.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return old[oldItemPosition].dirName == value[newItemPosition].dirName &&
                        old[oldItemPosition].config.isNightMode == value[newItemPosition].config.isNightMode
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = old[oldItemPosition]
                    val newItem = value[newItemPosition]
                    val oldApplied = oldItem.dirName == oldActive
                    val newApplied = newItem.dirName == activeDirName
                    return oldItem == newItem && oldApplied == newApplied
                }
            }).dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(entry: TopBarConfig.Entry) = itemBinding.run {
                root.background = UiCorner.panelRounded(
                    this@TopBarManageActivity,
                    ContextCompat.getColor(this@TopBarManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@TopBarManageActivity)
                )
                tvName.text = entry.config.name
                tvInfo.text = buildString {
                    append(styleLabel(entry.config.style))
                    append(" · ")
                    append(getString(R.string.top_bar_tag_bar_alpha))
                    append(" ")
                    append(entry.config.tagBarAlpha)
                    append("%")
                    if (entry.config.updatedAt > 0) {
                        append(" · ")
                        append(dateFormat.format(Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))))
                    }
                }
                tvSource.visibility = View.GONE
                tvName.setTextColor(primaryTextColor)
                tvInfo.setTextColor(secondaryTextColor)
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        android.graphics.Color.TRANSPARENT,
                        ContextCompat.getColor(this@TopBarManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@TopBarManageActivity)
                    )
                }
                cardPreview.visibility = View.VISIBLE
                cardPreview.radius = TopBarConfig.cornerRadius(this@TopBarManageActivity, entry.config)
                cardPreview.setCardBackgroundColor(TopBarConfig.resolveBackgroundColor(entry.config))
                ivPreview.alpha = (entry.config.wallpaperAlpha.coerceIn(0, 100) / 100f)
                val wallpaper = previewWallpaperFile(entry)
                if (wallpaper != null) {
                    ImageLoader.load(ivPreview.context, wallpaper.absolutePath)
                        .centerCrop()
                        .signature(ObjectKey("${wallpaper.absolutePath}:${wallpaper.lastModified()}"))
                        .into(ivPreview)
                } else {
                    Glide.with(ivPreview.context).clear(ivPreview)
                    ivPreview.setImageDrawable(null)
                    ivPreview.alpha = 1f
                }
                btnApply.text = getString(if (entry.dirName == activeDirName) R.string.theme_applied_state else R.string.theme_apply)
                btnEdit.text = getString(R.string.edit)
                btnEdit.visibility = View.VISIBLE
                btnApply.setTextColor(accentColor)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.typeface = this@TopBarManageActivity.uiTypeface()
                }
                btnApply.setOnClickListener { applyPackage(entry) }
                btnEdit.setOnClickListener { showEditDialog(entry) }
                btnMore.setOnClickListener { showActions(entry) }
                root.setOnClickListener { showActions(entry) }
            }
        }
    }

    private fun previewWallpaperFile(entry: TopBarConfig.Entry): File? {
        val path = entry.config.wallpaperPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        val resolved = if (file.isAbsolute) {
            file
        } else {
            File(entry.localDir ?: return null, path)
        }
        return resolved.takeIf { it.exists() && it.isFile }
    }

    private companion object {
        const val COLOR_TAG_BAR = 5101
        const val COLOR_TAG_SELECTED = 5102
        const val REQUEST_WALLPAPER = 5103
        const val COLOR_BACKGROUND = 5104
    }
}
