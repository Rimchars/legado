package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
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

class NavigationBarManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var isNightMode = false
    private var editingEntry: NavigationBarIconConfig.Entry? = null
    private var editingDialog: LinearLayout? = null
    private var pendingConfig: NavigationBarIconConfig.Config? = null
    private var pendingIconRequest: IconRequest? = null
    private var pendingSidebarBackgroundEntry: NavigationBarIconConfig.Entry? = null
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val selectIcon = registerForActivityResult(HandleFileContract()) { result ->
        val request = pendingIconRequest?.takeIf { it.code == result.requestCode } ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.saveIconToPackage(
                        this@NavigationBarManageActivity,
                        uri,
                        request.entry,
                        request.item.key,
                        request.selected,
                        resources.getDimensionPixelSize(R.dimen.main_bottom_nav_icon_size)
                    )
                }
            }.onSuccess {
                editingEntry = it
                pendingConfig = it.config.copy(icons = it.config.icons.toMutableMap())
                notifyAppliedIfNeeded(it)
                refreshEditDialog()
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importPackage(uri) }
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let {
            toastOnUi(R.string.export_success)
        }
    }

    private val selectSidebarBackground = registerForActivityResult(HandleFileContract()) { result ->
        val entry = pendingSidebarBackgroundEntry ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        val metrics = resources.displayMetrics
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = requestSidebarBackground,
            aspectWidth = minOf(metrics.widthPixels, metrics.heightPixels),
            aspectHeight = maxOf(metrics.widthPixels, metrics.heightPixels),
            dirName = "navigationBarSidebarBackground",
            prefix = "sidebar_bg",
            targetWidth = 1440
        )
        pendingSidebarBackgroundEntry = entry
        cropSidebarBackground.launch(request.params)
    }

    private val cropSidebarBackground = registerForActivityResult(ImageCropContract()) { result ->
        val entry = pendingSidebarBackgroundEntry ?: return@registerForActivityResult
        pendingSidebarBackgroundEntry = null
        if (result == null) {
            return@registerForActivityResult
        }
        if (!File(result).exists()) {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.saveSidebarBackgroundToPackage(
                        this@NavigationBarManageActivity,
                        result,
                        entry
                    )
                }
            }.onSuccess {
                editingEntry = it
                pendingConfig = it.config.copy(icons = it.config.icons.toMutableMap())
                notifyAppliedIfNeeded(it)
                refreshEditDialog()
                loadPackages()
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.navigation_icon_decode_failed))
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.navigation_bar_manage)
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
            ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@NavigationBarManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                android.graphics.Color.TRANSPARENT,
                ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@NavigationBarManageActivity)
            )
        }
        btnAdd.text = getString(R.string.theme_add)
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_card),
            ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@NavigationBarManageActivity)
        )
        btnAdd.setOnClickListener {
            showAddDialog()
        }
        tvSummary.text = getString(R.string.navigation_bar_package_summary)
        recyclerView.layoutManager = LinearLayoutManager(this@NavigationBarManageActivity)
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
                    NavigationBarIconConfig.loadEntries(isNightMode, includeRemote = AppConfig.syncThemePackages)
                }
            }.onSuccess {
                adapter.submit(it, NavigationBarIconConfig.activeDirName(isNightMode))
                binding.tvSummary.text = if (it.size <= 1) {
                    getString(R.string.navigation_bar_package_empty)
                } else {
                    getString(R.string.navigation_bar_package_summary)
                }
            }.onFailure {
                binding.tvSummary.text = it.localizedMessage
            }
        }
    }

    private fun showEditDialog(entry: NavigationBarIconConfig.Entry?) {
        val base = entry ?: NavigationBarIconConfig.Entry(
            NavigationBarIconConfig.Config(
                name = nextPackageName(),
                isNightMode = isNightMode,
                layoutMode = AppConfig.bottomBarLayoutMode,
                sidebarGravity = AppConfig.bottomBarSidebarGravity,
                effectMode = AppConfig.bottomBarEffectMode,
                opacity = if (AppConfig.bottomBarEffectMode == "frosted") AppConfig.frostedGlassLevel else AppConfig.liquidGlassLevel
            ),
            NavigationBarIconConfig.Source.LOCAL,
            ""
        )
        if (base.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        editingEntry = if (base.localDir == null && entry != null) {
            toastOnUi(R.string.navigation_bar_download_first)
            return
        } else {
            base
        }
        pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
        val root = buildEditView()
        editingDialog = root
        alert(R.string.navigation_bar_edit) {
            customView { root }
            okButton {
                saveEditingPackage()
            }
            cancelButton()
        }
    }

    private fun buildEditView(): LinearLayout {
        val config = pendingConfig!!
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 2, 4)
            val name = EditText(this@NavigationBarManageActivity).apply {
                tag = "name"
                setText(config.name)
                hint = getString(R.string.navigation_bar_name)
                setSingleLine(true)
                textSize = 15f
                setPadding(14.dp, 0, 14.dp, 0)
                background = UiCorner.opaqueRounded(
                    ContextCompat.getColor(context, R.color.background_card),
                    UiCorner.actionRadius(context)
                )
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp)
            }
            addView(name)
            addView(optionRow(getString(R.string.bottom_bar_layout_mode), layoutModeLabel(config.layoutMode)) {
                selector(
                    getString(R.string.bottom_bar_layout_mode),
                    listOf(
                        getString(R.string.bottom_bar_layout_floating),
                        getString(R.string.bottom_bar_layout_sidebar)
                    )
                ) { _, index ->
                    config.layoutMode = if (index == 1) "sidebar" else "floating"
                    refreshEditDialog()
                }
            })
            addView(optionRow(
                getString(R.string.merge_discovery_rss),
                getString(if (AppConfig.mergeDiscoveryRss) R.string.enabled else R.string.disabled)
            ) {
                putPrefBoolean(PreferKey.mergeDiscoveryRss, !AppConfig.mergeDiscoveryRss)
                postEvent(EventBus.NOTIFY_MAIN, false)
                refreshEditDialog()
            })
            if (config.layoutMode != "sidebar") {
                addView(optionRow(getString(R.string.bottom_bar_effect_mode), effectModeLabel(config.effectMode)) {
                    selector(
                        getString(R.string.bottom_bar_effect_mode),
                        listOf(
                            getString(R.string.bottom_bar_effect_solid),
                            getString(R.string.bottom_bar_effect_glass),
                            getString(R.string.bottom_bar_effect_frosted)
                        )
                    ) { _, index ->
                        config.effectMode = when (index) {
                            0 -> "solid"
                            2 -> "frosted"
                            else -> "glass"
                        }
                        refreshEditDialog()
                    }
                })
                addView(optionRow(getString(R.string.bottom_bar_opacity), "${config.opacity}%") {
                    NumberPickerDialog(this@NavigationBarManageActivity)
                        .setTitle(getString(R.string.bottom_bar_opacity))
                        .setMinValue(0)
                        .setMaxValue(100)
                        .setValue(config.opacity)
                        .setCustomButton(R.string.btn_default_s) {
                            config.opacity = 76
                            refreshEditDialog()
                        }
                        .show {
                            config.opacity = it.coerceIn(0, 100)
                            refreshEditDialog()
                        }
                })
            } else {
                addView(optionRow(
                    getString(R.string.navigation_bar_sidebar_background),
                    if (config.sidebarBackgroundPath.isNullOrBlank()) {
                        getString(R.string.select_image)
                    } else {
                        getString(R.string.theme_image_selected)
                    }
                ) {
                    selector(
                        getString(R.string.navigation_bar_sidebar_background),
                        buildList {
                            add(getString(R.string.select_image))
                            if (!config.sidebarBackgroundPath.isNullOrBlank()) {
                                add(getString(R.string.delete))
                            }
                        }
                    ) { _, index ->
                        if (index == 0) {
                            pendingSidebarBackgroundEntry = entry
                            selectSidebarBackground.launch {
                                mode = HandleFileContract.IMAGE
                                title = getString(R.string.navigation_bar_sidebar_background)
                            }
                        } else {
                            editingEntry = NavigationBarIconConfig.clearSidebarBackground(entry)
                            pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                            notifyAppliedIfNeeded(editingEntry!!)
                            refreshEditDialog()
                            loadPackages()
                        }
                    }
                })
            }
            NavigationBarIconConfig.items.forEach { item ->
                addView(iconRow(item))
            }
        }
    }

    private fun refreshEditDialog() {
        val root = editingDialog ?: return
        root.removeAllViews()
        buildEditView().let { rebuilt ->
            while (rebuilt.childCount > 0) {
                root.addView(rebuilt.getChildAt(0).also { rebuilt.removeView(it) })
            }
        }
    }

    private fun optionRow(title: String, value: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 14.dp, 0)
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp).apply {
                topMargin = 8.dp
            }
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(primaryTextColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(secondaryTextColor)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun iconRow(item: NavigationBarIconConfig.NavItem): View {
        val entry = editingEntry ?: return View(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = UiCorner.opaqueRounded(
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            }
            addView(TextView(context).apply {
                setText(item.titleRes)
                textSize = 15f
                setTextColor(primaryTextColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(previewButton(entry, item, false))
            addView(previewButton(entry, item, true))
        }
    }

    private fun previewButton(entry: NavigationBarIconConfig.Entry, item: NavigationBarIconConfig.NavItem, selected: Boolean): ImageView {
        return ImageView(this).apply {
            contentDescription = getString(if (selected) R.string.navigation_icon_selected else R.string.navigation_icon_normal)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(NavigationBarIconConfig.previewDrawable(this@NavigationBarManageActivity, entry, item, selected))
            background = UiCorner.actionSelector(
                ContextCompat.getColor(context, R.color.background_menu),
                ContextCompat.getColor(context, R.color.background_card),
                UiCorner.actionRadius(context)
            )
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginStart = 8.dp }
            setOnClickListener {
                selector(
                    contentDescription,
                    listOf(getString(R.string.select_image), getString(R.string.delete))
                ) { _, index ->
                    if (index == 0) {
                        val code = NavigationBarIconConfig.items.indexOf(item) * 2 + if (selected) 1 else 0
                        pendingIconRequest = IconRequest(code, entry, item, selected)
                        selectIcon.launch {
                            mode = HandleFileContract.FILE
                            requestCode = code
                            title = getString(R.string.navigation_icon_select_file)
                            allowExtensions = arrayOf("ico", "svg", "png", "jpg", "jpeg")
                        }
                    } else {
                        editingEntry = NavigationBarIconConfig.clearIcon(entry, item.key, selected)
                        pendingConfig = editingEntry!!.config.copy(icons = editingEntry!!.config.icons.toMutableMap())
                        notifyAppliedIfNeeded(editingEntry!!)
                        refreshEditDialog()
                        loadPackages()
                    }
                }
            }
        }
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val name = editingDialog?.findViewWithTag<EditText>("name")?.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    NavigationBarIconConfig.addOrUpdate(config.copy(name = name), editingEntry)
                }
            }.onSuccess {
                notifyAppliedIfNeeded(it)
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun showActions(entry: NavigationBarIconConfig.Entry) {
        val actions = buildList {
            add(NavAction.APPLY)
            if (entry.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME) {
                add(NavAction.EDIT)
                add(NavAction.EXPORT)
                if (AppConfig.syncThemePackages) add(NavAction.UPLOAD)
                if (entry.source != NavigationBarIconConfig.Source.LOCAL) add(NavAction.DOWNLOAD)
                if (entry.source != NavigationBarIconConfig.Source.REMOTE) add(NavAction.DELETE_LOCAL)
                if (entry.source != NavigationBarIconConfig.Source.LOCAL) add(NavAction.DELETE_REMOTE)
                if (entry.source == NavigationBarIconConfig.Source.BOTH) add(NavAction.DELETE_BOTH)
            }
        }
        selector(entry.config.name, actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                NavAction.APPLY -> applyPackage(entry)
                NavAction.EDIT -> showEditDialog(entry)
                NavAction.EXPORT -> exportPackage(entry)
                NavAction.UPLOAD -> runAction { NavigationBarIconConfig.upload(entry) }
                NavAction.DOWNLOAD -> runAction { NavigationBarIconConfig.download(entry) }
                NavAction.DELETE_LOCAL -> confirmDelete(entry, getString(R.string.navigation_bar_delete_local_confirm)) {
                    NavigationBarIconConfig.deleteLocal(entry)
                    postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                }
                NavAction.DELETE_REMOTE -> confirmDelete(entry, getString(R.string.navigation_bar_delete_remote_confirm)) {
                    NavigationBarIconConfig.deleteRemote(entry)
                }
                NavAction.DELETE_BOTH -> confirmDelete(entry, getString(R.string.navigation_bar_delete_both_confirm)) {
                    NavigationBarIconConfig.delete(entry)
                    postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
                }
            }
        }
    }

    private fun confirmDelete(
        entry: NavigationBarIconConfig.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        alert(getString(R.string.delete), message) {
            yesButton {
                runAction(block)
            }
            noButton()
        }
    }

    private fun applyPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { if (entry.source == NavigationBarIconConfig.Source.REMOTE) NavigationBarIconConfig.download(entry) else entry }
            }.onSuccess {
                NavigationBarIconConfig.apply(it)
                postEvent(EventBus.NAVIGATION_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun exportPackage(entry: NavigationBarIconConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { NavigationBarIconConfig.exportZip(entry) }
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
                val file = externalFiles.getFile("navigationBarImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { NavigationBarIconConfig.importZip(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    toastOnUi(R.string.success)
                }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun notifyAppliedIfNeeded(entry: NavigationBarIconConfig.Entry) {
        if (entry.dirName == NavigationBarIconConfig.activeDirName(entry.config.isNightMode)) {
            NavigationBarIconConfig.apply(entry)
            postEvent(EventBus.NAVIGATION_BAR_CHANGED, entry.config.isNightMode)
        }
    }

    private fun effectModeLabel(value: String): String {
        return when (value) {
            "solid" -> getString(R.string.bottom_bar_effect_solid)
            "frosted" -> getString(R.string.bottom_bar_effect_frosted)
            else -> getString(R.string.bottom_bar_effect_glass)
        }
    }

    private fun layoutModeLabel(value: String): String {
        return when (value) {
            "sidebar" -> getString(R.string.bottom_bar_layout_sidebar)
            else -> getString(R.string.bottom_bar_layout_floating)
        }
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.navigation_bar_custom_name)
        val usedNames = adapter.items.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private data class IconRequest(
        val code: Int,
        val entry: NavigationBarIconConfig.Entry,
        val item: NavigationBarIconConfig.NavItem,
        val selected: Boolean
    )

    private companion object {
        const val requestSidebarBackground = 7001
    }

    private enum class NavAction(val titleRes: Int) {
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

        var items: List<NavigationBarIconConfig.Entry> = emptyList()
            private set
        private var activeDirName = NavigationBarIconConfig.DEFAULT_DIR_NAME

        fun submit(value: List<NavigationBarIconConfig.Entry>, activeDirName: String) {
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

            fun bind(entry: NavigationBarIconConfig.Entry) = itemBinding.run {
                root.background = UiCorner.opaqueRounded(
                    ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@NavigationBarManageActivity)
                )
                tvName.text = entry.config.name
                tvInfo.text = buildString {
                    append(effectModeLabel(entry.config.effectMode))
                    append(" · ")
                    append(getString(R.string.bottom_bar_opacity))
                    append(" ")
                    append(entry.config.opacity)
                    append("%")
                    if (entry.config.updatedAt > 0) {
                        append(" · ")
                        append(dateFormat.format(Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))))
                    }
                }
                tvSource.text = when {
                    entry.dirName == activeDirName -> getString(R.string.theme_source_using)
                    entry.source == NavigationBarIconConfig.Source.BUILTIN -> getString(R.string.theme_source_local)
                    entry.source == NavigationBarIconConfig.Source.REMOTE -> getString(R.string.theme_source_remote)
                    entry.source == NavigationBarIconConfig.Source.BOTH -> getString(R.string.theme_source_both)
                    else -> getString(R.string.theme_source_local)
                }
                tvName.setTextColor(primaryTextColor)
                tvInfo.setTextColor(secondaryTextColor)
                tvSource.setTextColor(accentColor)
                listOf(btnApply, btnEdit, btnMore, tvSource).forEach {
                    it.background = UiCorner.actionSelector(
                        ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_card),
                        ContextCompat.getColor(this@NavigationBarManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@NavigationBarManageActivity)
                    )
                }
                cardPreview.visibility = View.GONE
                btnApply.text = getString(if (entry.dirName == activeDirName) R.string.theme_applied_state else R.string.theme_apply)
                btnEdit.text = getString(R.string.edit)
                btnApply.setOnClickListener { applyPackage(entry) }
                btnEdit.setOnClickListener { showEditDialog(entry) }
                btnMore.setOnClickListener { showActions(entry) }
                root.setOnClickListener { showActions(entry) }
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
