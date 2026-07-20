package io.legado.app.ui.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ImageProvider
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.ui.widget.compose.ComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.ComposeSingleChoiceDialog
import io.legado.app.ui.widget.compose.ComposeTextInputDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale

class BubbleManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val entriesState = mutableStateOf<List<BubblePackageManager.Entry>>(emptyList())
    private val summaryState = mutableStateOf("")
    private val activeDirNameState = mutableStateOf(BubblePackageManager.activeDirName())
    private val forceSoftwareBubbleState = mutableStateOf(AppConfig.forceSoftwareParagraphBubble)
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private var editingConfig: BubblePackageManager.Config? = null
    private var editingEntry: BubblePackageManager.Entry? = null
    private var svgCursorPosition: Int = 0
    private var loadVersion = 0
    private var loadJob: Job? = null
    private val previewCache = BubblePreviewCache()
    private val handledWebDavTasks = mutableSetOf<String>()
    private val importFromNet by lazy { "网络导入" }
    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFromNet") {
                importNetZipAlert()
            } else {
                importZip(uri)
            }
        }
    }
    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val value = uri.toString()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                showDialogFragment(
                    ComposeConfirmDialog.create(
                        title = "上传成功",
                        message = value,
                        positiveText = getString(R.string.copy_text),
                        negativeText = getString(R.string.cancel),
                        onPositive = {
                            sendToClip(value)
                            toastOnUi(R.string.copy_complete)
                        }
                    )
                )
            } else {
                toastOnUi(R.string.export_success)
            }
        }
    }
    private val svgEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("text")?.let { text ->
                    editingConfig = editingConfig?.copy(svgTemplate = text)
                    svgCursorPosition =
                        result.data?.getIntExtra("cursorPosition", text.length) ?: text.length
                    showBubbleEditDialog(editingEntry)
                }
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        updateContainerMenu()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        binding.tabBar.visibility = android.view.View.GONE
        binding.tvSummary.visibility = android.view.View.GONE
        binding.btnAdd.visibility = android.view.View.GONE
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                BubbleManageScreen(
                    entries = entriesState.value,
                    summary = summaryState.value,
                    activeDirName = activeDirNameState.value,
                    forceSoftwareBubble = forceSoftwareBubbleState.value,
                    previewBitmapProvider = previewCache::load,
                    onForceSoftwareBubbleChange = ::setForceSoftwareBubble,
                    onApply = ::applyEntry,
                    onEdit = { entry -> showEditDialog(entry) },
                    onMoreActions = ::bubbleActions,
                    onAddClick = ::showAddActions
                )
            }
        }
        container.addView(cv, index)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        containerMenuItem = menu.add(0, MENU_CONTAINER, 0, R.string.s3_bucket).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_HELP, 1, R.string.help).apply {
            setIcon(R.drawable.ic_help)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CONTAINER -> {
                showContainerSelector()
                true
            }
            MENU_HELP -> {
                showBubbleHelp()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            val item = containerMenuItem ?: return
            item.isVisible = false
            return
        }
        cloudContainerId =
            AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id ?: containers.firstOrNull()?.id
        val item = containerMenuItem ?: return
        item.isVisible = true
        item.title = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
    }

    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId
                ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            val selectedIndex = containers.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            showDialogFragment(
                ComposeSingleChoiceDialog.create(
                    title = getString(R.string.s3_bucket),
                    labels = containers.map(AppCloudStorage::containerDisplayLabel),
                    selectedIndex = selectedIndex,
                    positiveText = getString(R.string.ok),
                    negativeText = getString(R.string.cancel),
                    onPositive = { index ->
                        val container = containers[index]
                        if (container.id != selected) {
                            AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                            cloudContainerId = container.id
                            updateContainerMenu()
                            loadPackages()
                        }
                    }
                )
            )
        }
    }

    private fun loadPackages() {
        val version = ++loadVersion
        val containerId = cloudContainerId
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val snapshot = BubblePackageManager.loadEntrySnapshot(containerId)
                if (version != loadVersion || isFinishing || isDestroyed) return@launch
                entriesState.value = snapshot.mergedEntries
                activeDirNameState.value = BubblePackageManager.activeDirName()
                summaryState.value = getString(R.string.bubble_manage_summary)

                val refreshed = BubblePackageManager.refreshEntries(
                    snapshot,
                    containerId,
                    CLOUD_SCOPE
                )
                if (version != loadVersion || isFinishing || isDestroyed) return@launch
                entriesState.value = refreshed
                activeDirNameState.value = BubblePackageManager.activeDirName()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (version == loadVersion && !isFinishing && !isDestroyed) {
                    summaryState.value = error.localizedMessage ?: ""
                }
            }
        }
    }

    private fun showAddActions() {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.add),
                labels = listOf("手动创建", "导入 zip"),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    when (index) {
                        0 -> showEditDialog(null)
                        1 -> importPackage.launch {
                            mode = HandleFileContract.FILE
                            allowExtensions = arrayOf("zip")
                            otherActions = arrayListOf(SelectItem(importFromNet, -1))
                        }
                    }
                }
            )
        )
    }

    private fun bubbleActions(entry: BubblePackageManager.Entry): List<AppManagementMenuAction> {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.source != BubblePackageManager.Source.BUILTIN) {
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.EDIT)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.EXPORT)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.SHARE_ZIP_FILE)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.SHARE_DIRECT_LINK)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.UPLOAD)
                if (entry.source != BubblePackageManager.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.DELETE_LOCAL)
                if (entry.source != BubblePackageManager.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == BubblePackageManager.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = action.title,
                danger = action == Action.DELETE_LOCAL ||
                    action == Action.DELETE_REMOTE ||
                    action == Action.DELETE_BOTH
            ) {
                when (action) {
                    Action.APPLY -> applyEntry(entry)
                    Action.EDIT -> showEditDialog(entry)
                    Action.EXPORT -> exportZip(entry)
                    Action.SHARE_ZIP_FILE -> shareZipFile(entry)
                    Action.SHARE_DIRECT_LINK -> shareZipDirectLink(entry)
                    Action.UPLOAD -> enqueueUpload(entry)
                    Action.DOWNLOAD -> runAction(refreshReading = false) {
                        BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    Action.DELETE_LOCAL -> confirmDelete {
                        BubblePackageManager.deleteLocal(entry)
                    }
                    Action.DELETE_REMOTE -> confirmDelete {
                        BubblePackageManager.deleteRemote(
                            entry, cloudContainerId, CLOUD_SCOPE
                        )
                    }
                    Action.DELETE_BOTH -> confirmDelete {
                        BubblePackageManager.deleteLocal(entry)
                        BubblePackageManager.deleteRemote(
                            entry, cloudContainerId, CLOUD_SCOPE
                        )
                    }
                }
            }
        }
    }

    // region Edit dialog

    private fun showEditDialog(entry: BubblePackageManager.Entry?) {
        if (entry?.source == BubblePackageManager.Source.BUILTIN ||
            entry?.source == BubblePackageManager.Source.REMOTE
        ) return
        editingEntry = entry
        editingConfig = (entry?.config ?: BubblePackageManager.builtinConfig().copy(
            name = "自定义段评气泡",
            dirName = "",
            updatedAt = System.currentTimeMillis()
        )).copy(dirName = entry?.dirName.orEmpty())
        showBubbleEditDialog(entry)
    }

    private fun showBubbleEditDialog(entry: BubblePackageManager.Entry?) {
        val config = editingConfig ?: return
        val isAdd = entry == null
        showDialogFragment(
            BubbleEditDialog.create(
                config = config,
                isAdd = isAdd,
                onSaved = { name, updatedConfig ->
                    val resourceConfig = editingConfig ?: config
                    val next = updatedConfig.copy(
                        name = name.trim().ifBlank { updatedConfig.name },
                        dirName = entry?.dirName.orEmpty(),
                        formatVersion = resourceConfig.formatVersion,
                        resources = resourceConfig.resources
                    )
                    editingConfig = next
                    runAction(
                        refreshReading = entry?.dirName == BubblePackageManager.activeDirName()
                    ) {
                        BubblePackageManager.addOrUpdate(next, entry)
                    }
                },
                onNameChanged = { name ->
                    editingConfig = editingConfig?.copy(name = name)
                },
                onOpenSizeScalePicker = {
                    showSizeScalePicker()
                },
                onOpenSvgEditor = {
                    openSvgEditor()
                },
                onPickColor = { dialogId, currentColor ->
                    ColorPickerDialog.newBuilder()
                        .setColor(currentColor)
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(dialogId)
                        .show(this@BubbleManageActivity)
                }
            )
        )
    }

    private fun showSizeScalePicker() {
        val config = editingConfig ?: return
        showDialogFragment(
            ComposeNumberPickerDialog.create(
                title = "大小倍率",
                value = (config.sizeScale.coerceIn(
                    BubblePackageManager.MIN_SIZE_SCALE,
                    BubblePackageManager.MAX_SIZE_SCALE
                ) * 10).toInt(),
                minValue = (BubblePackageManager.MIN_SIZE_SCALE * 10).toInt(),
                maxValue = (BubblePackageManager.MAX_SIZE_SCALE * 10).toInt(),
                isDecimalMode = true,
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                onPositive = { value ->
                    editingConfig = config.copy(
                        sizeScale = (value / 10f).coerceIn(
                            BubblePackageManager.MIN_SIZE_SCALE,
                            BubblePackageManager.MAX_SIZE_SCALE
                        )
                    )
                    showBubbleEditDialog(editingEntry)
                }
            )
        )
    }

    private fun openSvgEditor() {
        val svg = editingConfig?.svgTemplate.orEmpty()
        svgEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", svg)
            putExtra("title", "SVG 模板")
            putExtra("cursorPosition", svgCursorPosition.coerceIn(0, svg.length))
        })
    }

    // endregion

    private fun enqueueUpload(entry: BubblePackageManager.Entry) {
        val queued = WebDavTaskManager.enqueueUpload(
            key = "bubble_upload:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.BUBBLE_PACKAGE_UPLOAD,
            runningMessage = "正在上传气泡"
        ) {
            BubblePackageManager.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
        toastOnUi(
            if (queued) R.string.cache_manage_upload_queued
            else R.string.cache_manage_webdav_task_duplicate
        )
    }

    private fun observeWebDavTasks() {
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                states.values
                    .filter { it.type == WebDavTaskType.BUBBLE_PACKAGE_UPLOAD }
                    .filter { it.status == WebDavTaskStatus.COMPLETED }
                    .forEach { state ->
                        if (handledWebDavTasks.add("${state.key}:${state.status}")) {
                            loadPackages()
                        }
                    }
            }
        }
    }

    private fun exportZip(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { BubblePackageManager.exportZip(entry) }
                .onSuccess { zip ->
                    exportPackage.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun shareZipFile(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { BubblePackageManager.exportZip(entry) }
                .onSuccess { zip -> share(zip, "application/zip") }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun shareZipDirectLink(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val zip = BubblePackageManager.exportZip(entry)
                    DirectLinkUpload.upLoad(zip.name, zip, "application/zip")
                }
            }
                .onSuccess { url -> share(url, entry.config.name) }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun importNetZipAlert() {
        showDialogFragment(
            ComposeTextInputDialog.create(
                title = "网络导入",
                hint = "https://...",
                initialValue = "",
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                onPositive = { url ->
                    val trimmed = url.trim()
                    if (trimmed.isNotEmpty()) importNetZip(trimmed)
                }
            )
        )
    }

    private fun importNetZip(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = withContext(Dispatchers.IO) {
                    externalFiles.getFile(
                        "bubbleImports",
                        "import_${System.currentTimeMillis()}.zip"
                    ).also { target ->
                        target.parentFile?.mkdirs()
                        okHttpClient.newCallResponseBody {
                            url(url)
                        }.use { body ->
                            FileOutputStream(target).use { output ->
                                body.byteStream().copyTo(output)
                            }
                        }
                    }
                }
                BubblePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile(
                    "bubbleImports",
                    "import_${System.currentTimeMillis()}.zip"
                )
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                BubblePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun confirmDelete(block: suspend () -> Unit) {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.delete),
                message = getString(R.string.sure_del),
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = { runAction(block = block) }
            )
        )
    }

    private fun runAction(
        refreshReading: Boolean = true,
        refreshPackages: Boolean = true,
        block: suspend () -> Unit
    ) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    toastOnUi(R.string.success)
                    activeDirNameState.value = BubblePackageManager.activeDirName()
                    if (refreshReading) notifyBubbleChanged()
                }
                .onFailure { toastOnUi(it.localizedMessage) }
            if (refreshPackages) loadPackages()
        }
    }

    private fun notifyBubbleChanged() {
        ImageProvider.clear()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    private fun setForceSoftwareBubble(enabled: Boolean) {
        forceSoftwareBubbleState.value = enabled
        AppConfig.forceSoftwareParagraphBubble = enabled
        notifyBubbleChanged()
    }

    private fun applyEntry(entry: BubblePackageManager.Entry) {
        runAction(refreshPackages = entry.source == BubblePackageManager.Source.REMOTE) {
            val localEntry = if (entry.source == BubblePackageManager.Source.REMOTE) {
                BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
            } else {
                entry
            }
            BubblePackageManager.apply(localEntry)
        }
    }

    // region ColorPickerDialogListener

    override fun onColorSelected(dialogId: Int, color: Int) {
        val config = editingConfig ?: return
        val hex = String.format(Locale.ROOT, "#%06X", color and 0x00FFFFFF)
        editingConfig = when (dialogId) {
            BubbleEditDialog.COLOR_DAY_NORMAL -> config.copy(dayNormalColor = hex)
            BubbleEditDialog.COLOR_DAY_EMPHASIS -> config.copy(dayEmphasisColor = hex)
            BubbleEditDialog.COLOR_NIGHT_NORMAL -> config.copy(nightNormalColor = hex)
            BubbleEditDialog.COLOR_NIGHT_EMPHASIS -> config.copy(nightEmphasisColor = hex)
            else -> config
        }
        // Re-show the edit dialog with updated color
        showBubbleEditDialog(editingEntry)
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    // endregion

    private fun showBubbleHelp() {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.help),
                message = """
                    段评气泡用于把规则里的 dp 图片转成原生 SVG 气泡。

                    接入格式：
                    <img src="dp:12,{&quot;pclick&quot;:&quot;...&quot;,&quot;status&quot;:&quot;normal&quot;}">

                    dp: 后面的数字会替换 SVG 模板里的 ${"$"}{num}。
                    status 可选：normal 使用常规色，emphasis 使用强调色；不写 status 时默认 normal。
                    SVG 模板支持 ${"$"}{color} 和 ${"$"}{num} 两个占位。
                    内置气泡只读；需要自定义时请通过添加或导入创建新气泡。
                """.trimIndent(),
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                showNegative = false,
                positiveRequiresCallback = false,
                onPositive = {}
            )
        )
    }

    private enum class Action(val title: String) {
        APPLY("应用"),
        EDIT("编辑"),
        EXPORT("导出 zip"),
        SHARE_ZIP_FILE("文件分享"),
        SHARE_DIRECT_LINK("直链分享"),
        UPLOAD("上传"),
        DOWNLOAD("下载"),
        DELETE_LOCAL("删除本地"),
        DELETE_REMOTE("删除云端"),
        DELETE_BOTH("同时删除")
    }

    private companion object {
        private const val CLOUD_SCOPE = "bubble"
        private const val MENU_CONTAINER = 0x6801
        private const val MENU_HELP = 0x6802
    }
}
