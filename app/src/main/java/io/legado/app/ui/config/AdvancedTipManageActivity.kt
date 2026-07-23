package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import io.legado.app.help.CacheManager
import io.legado.app.ui.code.CodeEditActivity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AdvancedTipSlot
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.AdvancedTipPackageManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.read.page.LottieImageBitmapCache
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.ui.widget.compose.ComposeTextInputDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.readBytes
import io.legado.app.utils.readBytesLimited
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdvancedTipManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    companion object {
        const val EXTRA_SLOT = "slot"

        fun start(context: Context, slot: AdvancedTipSlot) {
            context.startActivity(
                Intent(context, AdvancedTipManageActivity::class.java)
                    .putExtra(EXTRA_SLOT, slot.name)
            )
        }
    }

    private val slot: AdvancedTipSlot by lazy {
        val raw = intent.getStringExtra(EXTRA_SLOT).orEmpty()
        runCatching { AdvancedTipSlot.valueOf(raw) }.getOrDefault(AdvancedTipSlot.HEADER)
    }
    private val manager: AdvancedTipPackageManager by lazy { AdvancedTipPackageManager.of(slot) }

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val entriesState = mutableStateOf<List<AdvancedTipPackageManager.Entry>>(emptyList())
    private val activeIdState = mutableStateOf(AdvancedTipPackageManager.BUILTIN_ID)
    private val loadingState = mutableStateOf(false)
    private var loadJob: Job? = null
    private var loadVersion: Int = 0

    private var editingEntryId: String? = null

    private val jsonEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            editingEntryId = null
            return@registerForActivityResult
        }
        val data = result.data
        val entryId = editingEntryId
        editingEntryId = null
        if (entryId == null || data == null) return@registerForActivityResult
        val cacheKey = data.getStringExtra("cacheKey")
        val text = if (cacheKey != null) {
            CacheManager.getFromMemory(cacheKey) as? String
        } else {
            data.getStringExtra("text")
        } ?: return@registerForActivityResult
        saveEditedJson(entryId, text)
    }
    private val importFromNet by lazy { getString(R.string.advanced_title_import_from_net) }

    private val importJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.path == "/$importFromNet") {
                showNetworkImportDialog()
            } else {
                importUri(uri)
            }
        }
    }

    private val exportJson = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val value = uri.toString()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                showDialogFragment(
                    ComposeConfirmDialog.create(
                        title = getString(R.string.advanced_title_exported),
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
                toastOnUi(R.string.advanced_title_exported)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(slot.manageTitleRes)
        activeIdState.value = manager.activeId()
        initComposeContent()
        loadEntries()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        container.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setContent {
                    AdvancedTipManageScreen(
                        entries = entriesState.value,
                        activeId = activeIdState.value,
                        loading = loadingState.value,
                        summaryRes = slot.manageSummaryRes,
                        previewProvider = { entry ->
                            withContext(Dispatchers.IO) {
                                runCatching { manager.readTemplate(entry) }.getOrNull()
                            }
                        },
                        onApply = ::applyEntry,
                        onMoreActions = ::entryActions,
                        onImport = ::showAddMenu
                    )
                }
            },
            index
        )
    }

    private fun loadEntries() {
        loadJob?.cancel()
        val version = ++loadVersion
        loadJob = lifecycleScope.launch {
            loadingState.value = true
            try {
                val entries = manager.loadEntries()
                if (version == loadVersion) {
                    entriesState.value = entries
                    activeIdState.value = manager.activeId()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (version == loadVersion) toastOnUi(error.localizedMessage)
            } finally {
                if (version == loadVersion) loadingState.value = false
            }
        }
    }

    private fun entryActions(
        entry: AdvancedTipPackageManager.Entry
    ): List<AppManagementMenuAction> = buildList {
        if (!entry.isBuiltin) {
            add(AppManagementMenuAction(getString(R.string.advanced_title_open_editor)) { editJsonEntry(entry) })
            add(AppManagementMenuAction(getString(R.string.advanced_title_name)) { renameEntry(entry) })
        }
        add(AppManagementMenuAction(getString(R.string.export_str)) { exportEntry(entry) })
        if (!entry.isBuiltin) {
            add(
                AppManagementMenuAction(
                    text = getString(R.string.delete),
                    danger = true
                ) { confirmDelete(entry) }
            )
        }
    }

    private fun showAddMenu() {
        alert(getString(R.string.advanced_title_add)) {
            items(
                listOf(
                    getString(R.string.advanced_title_add_from_builtin),
                    getString(R.string.advanced_title_import_from_file),
                    getString(R.string.advanced_title_import_from_net),
                )
            ) { _, _, index ->
                when (index) {
                    0 -> addFromBuiltin()
                    1 -> showImportPicker()
                    2 -> showNetworkImportDialog()
                }
            }
        }
    }

    private fun showImportPicker() {
        importJson.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.advanced_title_import_title)
            allowExtensions = arrayOf("json", "lottie")
            otherActions = arrayListOf(SelectItem(importFromNet, -1))
        }
    }

    private fun addFromBuiltin() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val builtin = manager.builtinEntry()
                    val json = manager.readTemplate(builtin)
                    manager.addOrUpdate(
                        name = getString(R.string.advanced_title_unnamed),
                        json = json
                    )
                }
            }.onSuccess {
                toastOnUi(R.string.advanced_title_added)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun importUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    uri.readBytes(this@AdvancedTipManageActivity, AdvancedTipPackageManager.MAX_JSON_BYTES)
                }
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    manager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun showNetworkImportDialog() {
        showDialogFragment(
            ComposeTextInputDialog.create(
                title = getString(R.string.advanced_title_input_url),
                hint = "https://...",
                initialValue = "https://skybook.qzz.io/file/json/19hMepHey95bDYaFXJfq89.json",
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                onPositive = { value ->
                    value.trim().takeIf { it.isNotEmpty() }?.let(::importNetwork)
                }
            )
        )
    }

    private fun importNetwork(url: String) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { body ->
                        val declared = body.contentLength()
                        require(declared <= AdvancedTipPackageManager.MAX_JSON_BYTES || declared < 0L) {
                            getString(R.string.advanced_title_too_large)
                        }
                        body.byteStream().readBytesLimited(AdvancedTipPackageManager.MAX_JSON_BYTES)
                    }
                }
                val name = Uri.parse(url).lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    val entry = manager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                    manager.apply(entry)
                    entry
                }
            }.onSuccess { entry ->
                activeIdState.value = entry.id
                when (slot) {
                    AdvancedTipSlot.HEADER -> ReadTipConfig.headerMode = ReadTipConfig.HEADER_MODE_ADVANCED
                    AdvancedTipSlot.FOOTER -> ReadTipConfig.footerMode = ReadTipConfig.FOOTER_MODE_ADVANCED
                }
                notifyReader()
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure {
                toastOnUi(getString(R.string.advanced_title_import_net_failed, it.localizedMessage.orEmpty()))
            }
        }
    }


    private fun renameEntry(entry: AdvancedTipPackageManager.Entry) {
        if (entry.isBuiltin) return
        showDialogFragment(
            ComposeTextInputDialog.create(
                title = getString(R.string.advanced_title_name),
                hint = getString(R.string.advanced_title_name),
                initialValue = entry.name,
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                onPositive = rename@{ text ->
                    val name = text.trim()
                    if (name.isEmpty()) {
                        toastOnUi(R.string.advanced_title_name_required)
                        return@rename
                    }
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val json = manager.readTemplate(entry)
                                val updated = manager.addOrUpdate(name, json, entry)
                                if (manager.activeId() == updated.id) manager.apply(updated)
                            }
                        }.onSuccess {
                            toastOnUi(R.string.success)
                            loadEntries()
                        }.onFailure { toastOnUi(it.localizedMessage) }
                    }
                }
            )
        )
    }

    private fun editJsonEntry(entry: AdvancedTipPackageManager.Entry) {
        if (entry.isBuiltin) {
            toastOnUi(R.string.read_only)
            return
        }
        if (!manager.isEditable(entry)) {
            toastOnUi(R.string.advanced_title_json_too_large_to_edit)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.readTemplate(entry) }
            }.onSuccess { json ->
                if (supportFragmentManager.isStateSaved ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) return@onSuccess
                val bytes = json.toByteArray(Charsets.UTF_8).size.toLong()
                if (bytes > AdvancedTipPackageManager.MAX_EDITABLE_JSON_BYTES) {
                    toastOnUi(R.string.advanced_title_json_too_large_to_edit)
                    return@onSuccess
                }
                editingEntryId = entry.id
                val key = "advanced_tip_edit_" + System.nanoTime()
                CacheManager.putMemory(key, json)
                jsonEditor.launch(
                    Intent(this@AdvancedTipManageActivity, CodeEditActivity::class.java).apply {
                        putExtra("cacheKey", key)
                        putExtra("writable", true)
                        putExtra("title", getString(R.string.advanced_title_json_label))
                        putExtra("cursorPosition", 0)
                    }
                )
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun saveEditedJson(entryId: String, json: String) {
        val entry = entriesState.value.firstOrNull { it.id == entryId }
        if (entry == null || entry.isBuiltin) {
            toastOnUi(R.string.error)
            loadEntries()
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    manager.validateEditableJson(json)
                    val updated = manager.addOrUpdate(
                        name = entry.name,
                        json = json,
                        oldEntry = entry
                    )
                    if (manager.activeId() == updated.id) manager.apply(updated)
                    updated
                }
            }.onSuccess {
                activeIdState.value = manager.activeId()
                notifyReader()
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun exportEntry(entry: AdvancedTipPackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.readTemplate(entry) }
            }.onSuccess { json ->
                val safeName = entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "advancedTitle" }
                exportJson.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "$safeName.json",
                        json.toByteArray(Charsets.UTF_8),
                        "application/json"
                    )
                }
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun confirmDelete(entry: AdvancedTipPackageManager.Entry) {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.delete),
                message = getString(R.string.sure_del),
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { manager.delete(entry) }
                        }.onSuccess {
                            activeIdState.value = manager.activeId()
                            notifyReader()
                            loadEntries()
                        }.onFailure { toastOnUi(it.localizedMessage) }
                    }
                }
            )
        )
    }

    private fun applyEntry(entry: AdvancedTipPackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.apply(entry) }
            }.onSuccess {
                activeIdState.value = entry.id
                when (slot) {
                    AdvancedTipSlot.HEADER -> ReadTipConfig.headerMode = ReadTipConfig.HEADER_MODE_ADVANCED
                    AdvancedTipSlot.FOOTER -> ReadTipConfig.footerMode = ReadTipConfig.FOOTER_MODE_ADVANCED
                }
                notifyReader()
                toastOnUi(R.string.success)
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun notifyReader() {
        LottieImageBitmapCache.clear()
        postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6))
    }
}
