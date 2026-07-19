package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.book.read.config.AdvancedTitleConfigDialog
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

class AdvancedTitleManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    AdvancedTitleConfigDialog.Host {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val entriesState = mutableStateOf<List<AdvancedTitlePackageManager.Entry>>(emptyList())
    private val activeIdState = mutableStateOf(AdvancedTitlePackageManager.activeId())
    private val loadingState = mutableStateOf(false)
    private var loadJob: Job? = null
    private var loadVersion: Int = 0
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
        binding.titleBar.title = getString(R.string.advanced_title_manage)
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
                    AdvancedTitleManageScreen(
                        entries = entriesState.value,
                        activeId = activeIdState.value,
                        loading = loadingState.value,
                        previewProvider = { entry ->
                            withContext(Dispatchers.IO) {
                                if (!entry.isBuiltin && !AdvancedTitlePackageManager.isEditable(entry)) {
                                    null
                                } else {
                                    runCatching { AdvancedTitlePackageManager.readTemplate(entry) }.getOrNull()
                                }
                            }
                        },
                        onApply = ::applyEntry,
                        onEdit = ::editEntry,
                        onMoreActions = ::entryActions,
                        onImport = ::showImportPicker
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
                val entries = AdvancedTitlePackageManager.loadEntries()
                if (version == loadVersion) {
                    entriesState.value = entries
                    activeIdState.value = AdvancedTitlePackageManager.activeId()
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
        entry: AdvancedTitlePackageManager.Entry
    ): List<AppManagementMenuAction> = buildList {
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

    private fun showImportPicker() {
        importJson.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.advanced_title_import_title)
            allowExtensions = arrayOf("json")
            otherActions = arrayListOf(SelectItem(importFromNet, -1))
        }
    }

    private fun importUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    uri.readBytes(this@AdvancedTitleManageActivity, AdvancedTitlePackageManager.MAX_JSON_BYTES)
                }
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
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
                initialValue = "",
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
                        require(declared <= AdvancedTitlePackageManager.MAX_JSON_BYTES || declared < 0L) {
                            getString(R.string.advanced_title_too_large)
                        }
                        body.byteStream().readBytesLimited(AdvancedTitlePackageManager.MAX_JSON_BYTES)
                    }
                }
                val name = Uri.parse(url).lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.advanced_title_unnamed)
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.addOrUpdate(name, bytes.toString(Charsets.UTF_8))
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure {
                toastOnUi(getString(R.string.advanced_title_import_net_failed, it.localizedMessage.orEmpty()))
            }
        }
    }

    private fun editEntry(entry: AdvancedTitlePackageManager.Entry) {
        if (!AdvancedTitlePackageManager.isEditable(entry)) {
            toastOnUi(R.string.large_config_read_only)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.readTemplate(entry) }
            }.onSuccess { json ->
                if (supportFragmentManager.isStateSaved ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                    supportFragmentManager.findFragmentByTag("advancedTitleEdit") != null
                ) return@onSuccess
                AdvancedTitleConfigDialog.edit(
                    entryId = entry.id,
                    name = entry.name,
                    json = json,
                    splitRule = entry.config.splitRuleOrNull()
                        ?: AdvancedTitleConfig.globalRule,
                    heightFactor = entry.config.normalizedHeightFactorOrNull()
                        ?: AdvancedTitleConfig.heightFactor
                ).show(supportFragmentManager, "advancedTitleEdit")
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    override fun onAdvancedTitleSaved(
        entryId: String,
        name: String,
        json: String,
        splitRule: AdvancedTitleConfig.SplitRule,
        heightFactor: Int
    ) {
        val entry = entriesState.value.firstOrNull { it.id == entryId }
        if (entry == null || entry.isBuiltin) {
            toastOnUi(R.string.error)
            loadEntries()
            return
        }
        saveEditedEntry(entry, name, json, splitRule, heightFactor)
    }

    private fun saveEditedEntry(
        old: AdvancedTitlePackageManager.Entry,
        name: String,
        json: String,
        splitRule: AdvancedTitleConfig.SplitRule,
        heightFactor: Int
    ) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AdvancedTitlePackageManager.validateEditableJson(json)
                    val updated = AdvancedTitlePackageManager.addOrUpdate(
                        name = name,
                        json = json,
                        oldEntry = old,
                        splitRule = splitRule,
                        heightFactor = heightFactor
                    )
                    val active = AdvancedTitlePackageManager.activeId() == updated.id
                    if (active) AdvancedTitlePackageManager.apply(updated)
                    updated to active
                }
            }.onSuccess { (_, active) ->
                if (active) notifyReader()
                toastOnUi(R.string.success)
                loadEntries()
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun exportEntry(entry: AdvancedTitlePackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.readTemplate(entry) }
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

    private fun confirmDelete(entry: AdvancedTitlePackageManager.Entry) {
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
                            withContext(Dispatchers.IO) { AdvancedTitlePackageManager.delete(entry) }
                        }.onSuccess {
                            activeIdState.value = AdvancedTitlePackageManager.activeId()
                            notifyReader()
                            loadEntries()
                        }.onFailure { toastOnUi(it.localizedMessage) }
                    }
                }
            )
        )
    }

    private fun applyEntry(entry: AdvancedTitlePackageManager.Entry) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AdvancedTitlePackageManager.apply(entry) }
            }.onSuccess {
                activeIdState.value = entry.id
                ReadBookConfig.titleMode = AdvancedTitleConfig.TITLE_MODE_ADVANCED
                notifyReader()
                toastOnUi(R.string.success)
            }.onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun notifyReader() {
        LottieImageBitmapCache.clear()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
    }
}
