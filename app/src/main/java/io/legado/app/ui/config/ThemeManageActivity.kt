package io.legado.app.ui.config

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThemeManageActivity : BaseActivity<ActivityThemeManageBinding>(), ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var isNightTheme = false
    private var editDialogBinding: DialogThemePackageEditBinding? = null
    private var editingEntry: ThemePackageManager.Entry? = null
    private var pendingBlur = 0
    private var pendingMainBackgroundPath: String? = null
    private var pendingBookInfoBackgroundPath: String? = null
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
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        loadThemes()
    }

    private fun initView() = binding.run {
        recyclerView.layoutManager = LinearLayoutManager(this@ThemeManageActivity)
        recyclerView.adapter = adapter
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
        btnDay.setBackgroundResource(if (!isNightTheme) R.drawable.bg_bookshelf_tag_item else 0)
        btnNight.setBackgroundResource(if (isNightTheme) R.drawable.bg_bookshelf_tag_item else 0)
        btnDay.setTextColor(if (!isNightTheme) accentColor else primaryTextColor)
        btnNight.setTextColor(if (isNightTheme) accentColor else primaryTextColor)
    }

    private fun loadThemes() {
        binding.tvSummary.text = if (AppConfig.syncThemePackages) {
            "正在读取本地和云端主题..."
        } else {
            "正在读取本地主题..."
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.load(isNightTheme)
            }.onSuccess {
                adapter.items = it
                binding.tvSummary.text = if (it.isEmpty()) {
                    "暂无主题。添加会保存当前${if (isNightTheme) "夜间" else "日间"}主题。"
                } else {
                    "共 ${it.size} 个主题${if (AppConfig.syncThemePackages) "，已合并本地和云端状态" else "，当前仅显示本地"}。"
                }
            }.onFailure {
                binding.tvSummary.text = "读取失败：${it.localizedMessage}"
            }
        }
    }

    private fun showAddDialog() {
        alert("添加主题") {
            val dialogBinding = createEditBinding(currentConfig(), null)
            editDialogBinding = dialogBinding
            editingEntry = null
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
                alert("编辑主题") {
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
                toastOnUi("读取主题失败：${it.localizedMessage}")
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
        return DialogThemePackageEditBinding.inflate(layoutInflater).apply {
            etName.setText(current.themeName)
            setupColorRow(rowPrimary, "主色调", current.primaryColor, colorPrimary)
            setupColorRow(rowAccent, "强调色", current.accentColor, colorAccent)
            setupColorRow(rowBackground, "背景色", current.backgroundColor, colorBackground)
            setupColorRow(rowBottomBackground, "底栏色", current.bottomBackground, colorBottomBackground)
            setupColorRow(rowPrimaryText, "主文字色", current.primaryTextColor ?: "#${primaryTextColor.hexString}", colorPrimaryText)
            setupColorRow(rowSecondaryText, "副文字色", current.secondaryTextColor ?: "#${secondaryTextColor.hexString}", colorSecondaryText)
            setupImageRow(rowMainBackground, "主界面背景", true)
            setupImageRow(rowBookInfoBackground, "详情页背景", false)
            etName.isEnabled = entry?.source != ThemePackageManager.Source.REMOTE
        }
    }

    private fun setupColorRow(
        row: ItemThemePackageOptionBinding,
        title: String,
        colorText: String,
        target: Int
    ) {
        row.tvTitle.text = title
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
            cornerRadius = 11f * resources.displayMetrics.density
            setColor(color)
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.adjustAlpha(primaryTextColor, 0.16f))
        }
    }

    private fun setupImageRow(row: ItemThemePackageOptionBinding, title: String, isMain: Boolean) {
        row.tvTitle.text = title
        row.viewSwatch.visibility = View.INVISIBLE
        updateImageRow(row, isMain)
        row.root.setOnClickListener {
            showImageActions(isMain)
        }
    }

    private fun updateImageRow(row: ItemThemePackageOptionBinding, isMain: Boolean) {
        val path = if (isMain) pendingMainBackgroundPath else pendingBookInfoBackgroundPath
        row.tvValue.text = when {
            path.isNullOrBlank() && isMain -> "未选择 · 模糊 $pendingBlur"
            path.isNullOrBlank() -> "未选择"
            isMain -> "${File(path).name} · 模糊 $pendingBlur"
            else -> File(path).name
        }
    }

    private fun showImageActions(isMain: Boolean) {
        val hasImage = if (isMain) !pendingMainBackgroundPath.isNullOrBlank() else !pendingBookInfoBackgroundPath.isNullOrBlank()
        val actions = buildList {
            if (isMain) add("背景图片模糊")
            add("选择图片")
            if (hasImage) add("删除图片")
        }
        selector(if (isMain) "主界面背景" else "详情页背景", actions) { _, index ->
            when (actions[index]) {
                "背景图片模糊" -> showBlurDialog()
                "选择图片" -> selectImage.launch {
                    requestCode = if (isMain) requestMainBackground else requestBookInfoBackground
                    mode = HandleFileContract.IMAGE
                }
                "删除图片" -> {
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

    private fun showBlurDialog() {
        alert("背景图片模糊") {
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
            .ifBlank { if (isNightTheme) "夜间主题" else "日间主题" }
        val config = kotlin.runCatching {
            ThemeConfig.Config(
                themeName = name,
                isNightTheme = isNightTheme,
                primaryColor = normalizeColor(dialogBinding.rowPrimary.tvValue.text?.toString()),
                accentColor = normalizeColor(dialogBinding.rowAccent.tvValue.text?.toString()),
                backgroundColor = normalizeColor(dialogBinding.rowBackground.tvValue.text?.toString()),
                bottomBackground = normalizeColor(dialogBinding.rowBottomBackground.tvValue.text?.toString()),
                transparentNavBar = false,
                backgroundImgPath = pendingMainBackgroundPath,
                backgroundImgBlur = pendingBlur,
                bookInfoBackgroundImgPath = pendingBookInfoBackgroundPath,
                primaryTextColor = normalizeOptionalColor(dialogBinding.rowPrimaryText.tvValue.text?.toString()),
                secondaryTextColor = normalizeOptionalColor(dialogBinding.rowSecondaryText.tvValue.text?.toString())
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
                val entry = ThemePackageManager.addFromConfig(config)
                if (oldEntry != null && oldEntry.dirName != entry.dirName) {
                    if (oldEntry.source != ThemePackageManager.Source.REMOTE) {
                        ThemePackageManager.deleteLocal(oldEntry)
                    }
                    if (AppConfig.syncThemePackages && oldEntry.source != ThemePackageManager.Source.LOCAL) {
                        ThemePackageManager.deleteRemote(oldEntry)
                    }
                }
                if (wasApplied) {
                    ThemePackageManager.apply(this@ThemeManageActivity, entry)
                }
                entry
            }.onSuccess {
                toastOnUi("主题已保存到本地")
                loadThemes()
                uploadThemeInBackground(it)
            }.onFailure {
                toastOnUi("保存主题失败：${it.localizedMessage}")
            }
        }
    }

    private fun uploadThemeInBackground(entry: ThemePackageManager.Entry) {
        if (!AppConfig.syncThemePackages) return
        Coroutine.async {
            ThemePackageManager.upload(entry)
        }.onSuccess {
            toastOnUi("主题已同步到云端")
            loadThemes()
        }.onError {
            toastOnUi("云端主题同步失败：${it.localizedMessage}")
        }
    }

    private fun currentConfig(): ThemeConfig.Config {
        val name = if (isNightTheme) "夜间主题" else "日间主题"
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
            transparentNavBar = getPrefBoolean(if (isNightTheme) PreferKey.tNavBarN else PreferKey.tNavBar),
            backgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage),
            backgroundImgBlur = getPrefInt(if (isNightTheme) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring, 0),
            bookInfoBackgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            primaryTextColor = "#${ThemeStore.textColorPrimary(this).hexString}",
            secondaryTextColor = "#${ThemeStore.textColorSecondary(this).hexString}"
        )
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
            toastOnUi(it.localizedMessage)
        }.getOrNull()
    }

    private fun showActions(entry: ThemePackageManager.Entry) {
        val actions = buildList {
            add("应用")
            add("编辑")
            if (entry.source != ThemePackageManager.Source.LOCAL) add("下载到本地")
            if (entry.source != ThemePackageManager.Source.REMOTE) add("上传到云端")
            if (entry.source != ThemePackageManager.Source.REMOTE) add("删除本地")
            if (entry.source != ThemePackageManager.Source.LOCAL) add("删除云端")
            if (entry.source == ThemePackageManager.Source.BOTH) add("同时删除")
        }
        selector(entry.packageInfo.name, actions) { _, index ->
            when (actions[index]) {
                "应用" -> applyTheme(entry)
                "编辑" -> showEditDialog(entry)
                "下载到本地" -> runAction("下载完成") { ThemePackageManager.download(entry) }
                "上传到云端" -> runAction("上传完成") { ThemePackageManager.upload(entry) }
                "删除本地" -> confirmDelete("删除本地主题？") { ThemePackageManager.deleteLocal(entry) }
                "删除云端" -> confirmDelete("删除云端主题？") { ThemePackageManager.deleteRemote(entry) }
                "同时删除" -> confirmDelete("同时删除本地和云端主题？") {
                    ThemePackageManager.deleteLocal(entry)
                    ThemePackageManager.deleteRemote(entry)
                }
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
                ThemePackageManager.apply(this@ThemeManageActivity, localEntry)
            }.onFailure {
                toastOnUi("应用主题失败：${it.localizedMessage}")
            }.onSuccess {
                toastOnUi("主题已应用")
                loadThemes()
            }
        }
    }

    private fun isApplied(entry: ThemePackageManager.Entry): Boolean {
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
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun confirmDelete(message: String, block: suspend () -> Unit) {
        alert("删除", message) {
            yesButton {
                runAction("已删除", block)
            }
            noButton()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        var items: List<ThemePackageManager.Entry> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
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
                tvName.text = pkg.name
                tvSource.text = when (entry.source) {
                    ThemePackageManager.Source.LOCAL -> if (isApplied(entry)) "使用中" else "本地"
                    ThemePackageManager.Source.REMOTE -> if (isApplied(entry)) "使用中" else "云端"
                    ThemePackageManager.Source.BOTH -> if (isApplied(entry)) "使用中" else "本地+云端"
                }
                tvInfo.text = buildString {
                    if (isApplied(entry)) {
                        append("当前应用 · ")
                    }
                    append(if (pkg.isNightTheme) "夜间" else "日间")
                    append(" · ")
                    val time = maxOf(pkg.updatedAt, entry.remoteUpdatedAt)
                    append(if (time > 0) dateFormat.format(Date(time)) else "未记录时间")
                }
                tvName.setTextColor(primaryTextColor)
                tvInfo.setTextColor(secondaryTextColor)
                tvSource.setTextColor(accentColor)
                btnApply.setTextColor(accentColor)
                btnApply.text = if (isApplied(entry)) "已应用" else "应用"
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                btnApply.setOnClickListener { applyTheme(entry) }
                btnEdit.setOnClickListener { showEditDialog(entry) }
                btnMore.setOnClickListener { showActions(entry) }
                root.setOnClickListener { showActions(entry) }
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

    companion object {
        private const val requestMainBackground = 301
        private const val requestBookInfoBackground = 302
        private const val colorPrimary = 401
        private const val colorAccent = 402
        private const val colorBackground = 403
        private const val colorBottomBackground = 404
        private const val colorPrimaryText = 405
        private const val colorSecondaryText = 406
    }
}
