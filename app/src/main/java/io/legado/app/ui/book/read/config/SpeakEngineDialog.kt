package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteSanitizer
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.readaloud.speech.SpeechVoiceOption
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.showShibbolethDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

/**
 * TTS 引擎管理。
 */
class SpeakEngineDialog : BaseDialogFragment(0), SpeakEngineDialogActions {

    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var ttsEngine by mutableStateOf(ReadAloud.ttsEngine)
    private var httpTtsList by mutableStateOf<List<HttpTTS>>(emptyList())
    private var pickerGroupKey by mutableStateOf<String?>(null)

    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showDialogFragment(ImportHttpTtsDialog(uri.toString())) }
    }

    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeTextInputDialog(
                title = getString(R.string.export_success),
                hint = getString(R.string.path),
                initialValue = url,
                message = DirectLinkUpload.getSummary().takeIf { url.isAbsUrl() },
                readOnly = true,
                positiveText = getString(R.string.copy_text),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = { requireContext().sendToClip(url) },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.TTS_RULE) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SpeakEngineScreen(
                    ttsEngine = ttsEngine,
                    httpTtsList = httpTtsList,
                    pickerGroupKey = pickerGroupKey,
                    actions = this@SpeakEngineDialog
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll()
                .catch { AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it) }
                .flowOn(IO)
                .conflate()
                .collect {
                    httpTtsList = it
                }
        }
    }

    override fun openSpeakerPicker(group: SpeechVoiceEngineGroup) {
        pickerGroupKey = group.key
    }

    override fun closeSpeakerPicker() {
        pickerGroupKey = null
    }

    override fun selectRoute(route: SpeechRoute) {
        ttsEngine = route.toJson()
        pickerGroupKey = null
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = ttsEngine
        callBack?.upSpeakEngineSummary()
        notifyReadAloudEngineChanged()
        route.engineValue.toLongOrNull()
            ?.let { appDb.httpTTSDao.get(it) }
            ?.takeIf { !it.loginUrl.isNullOrBlank() && it.getLoginInfo().isNullOrBlank() }
            ?.let { loginKey ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "httpTts")
                    putExtra("key", loginKey.id.toString())
                }
            }
    }

    override fun addHttpTts() {
        showDialogFragment<HttpTtsEditDialog>()
    }

    override fun editHttpTts(id: Long) {
        showDialogFragment(HttpTtsEditDialog(id))
    }

    override fun deleteHttpTts(httpTTS: HttpTTS) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + httpTTS.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = {
                val appContext = requireContext().applicationContext
                lifecycleScope.launch(IO) {
                    appDb.httpTTSDao.delete(httpTTS)
                    val result = SpeechRouteSanitizer.cleanDeletedHttpTts(httpTTS)
                    if (result.changed) {
                        val message = buildList {
                            if (result.characterCount > 0) add("${result.characterCount} 个角色")
                            if (result.bookCount > 0) add("${result.bookCount} 本书")
                            if (result.speakerGroupItemCount > 0) add("${result.speakerGroupItemCount} 个发言人分组条目")
                            if (result.globalCleared) add("通用朗读引擎")
                        }.joinToString("、")
                        appContext.toastOnUi("已清理 $message 的失效朗读配置")
                    }
                    notifyReadAloudEngineChanged()
                }
            }
        )
    }

    private fun notifyReadAloudEngineChanged() {
        ReadAloud.refreshReadAloudClass()
        postEvent(
            EventBus.READ_ALOUD_CONFIG_CHANGED,
            Bundle().apply {
                putString(
                    EventBus.READ_ALOUD_CONFIG_SCOPE,
                    EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                )
            }
        )
    }

    override fun login(group: SpeechVoiceEngineGroup) {
        group.loginKey.takeIf { it.isNotBlank() }?.let { key ->
            startActivity<SourceLoginActivity> {
                putExtra("type", "httpTts")
                putExtra("key", key)
            }
        }
    }

    override fun importDefault() {
        viewModel.importDefault()
    }

    override fun importLocal() {
        importDocResult.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    override fun importOnline() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls = aCache.getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        alert(R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    override fun exportAll() {
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts.json",
                GSON.toJson(httpTtsList).toByteArray(),
                "application/json"
            )
        }
    }

    override fun exportSelected() {
        val id = SpeechRoute.fromTtsEngineValue(ttsEngine).engineValue.toLongOrNull()
        val tts = id?.let { appDb.httpTTSDao.get(it) }
        if (tts == null) {
            toastOnUi(R.string.is_system_tts_no_export)
            return
        }
        exportHttpTts(tts)
    }

    override fun exportHttpTts(httpTTS: HttpTTS) {
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts_${httpTTS.name}.json",
                GSON.toJson(httpTTS).toByteArray(),
                "application/json"
            )
        }
    }

    override fun clearCache() {
        execute {
            notifyReadAloudEngineChanged()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    override fun close() {
        dismissAllowingStateLoss()
    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }
}

private interface SpeakEngineDialogActions {
    fun openSpeakerPicker(group: SpeechVoiceEngineGroup)
    fun closeSpeakerPicker()
    fun selectRoute(route: SpeechRoute)
    fun addHttpTts()
    fun editHttpTts(id: Long)
    fun deleteHttpTts(httpTTS: HttpTTS)
    fun login(group: SpeechVoiceEngineGroup)
    fun importDefault()
    fun importLocal()
    fun importOnline()
    fun exportAll()
    fun exportSelected()
    fun exportHttpTts(httpTTS: HttpTTS)
    fun clearCache()
    fun close()
}

@Composable
private fun SpeakEngineScreen(
    ttsEngine: String?,
    httpTtsList: List<HttpTTS>,
    pickerGroupKey: String?,
    actions: SpeakEngineDialogActions
) {
    val context = LocalContext.current
    val colors = rememberSpeakEngineColors()
    val groups = rememberSpeechGroups(httpTtsList)
    val currentRoute = SpeechRoute.fromTtsEngineValue(ttsEngine)
    var importDialogVisible by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily(context.uiTypeface()))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.page,
            shape = RoundedCornerShape(context.composePanelRadius())
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "朗读引擎",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = actions::close) { Text("关闭", color = colors.subText) }
            }
            Text(
                text = speechRouteSummary(currentRoute, groups, defaultText = "系统默认"),
                color = colors.subText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            EngineTopActions(
                colors = colors,
                onAdd = actions::addHttpTts,
                onImport = { importDialogVisible = true },
                onExportAll = actions::exportAll,
                onClearCache = actions::clearCache,
                modifier = Modifier.padding(top = 12.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(groups, key = { it.key }) { group ->
                    val httpTts = httpTtsForGroup(group, httpTtsList)
                    EngineGroupRow(
                        group = group,
                        selected = routeMatchesGroup(currentRoute, group),
                        colors = colors,
                        onClick = { actions.openSpeakerPicker(group) },
                        onLogin = if (!group.loginUrl.isNullOrBlank()) {
                            { actions.login(group) }
                        } else {
                            null
                        },
                        onEdit = httpTts?.let { { actions.editHttpTts(it.id) } },
                        onExport = httpTts?.let { { actions.exportHttpTts(it) } },
                        onDelete = httpTts?.let { { actions.deleteHttpTts(it) } }
                    )
                }
            }
        }
        pickerGroupKey?.let { key ->
            SpeechVoiceRoutePickerDialog(
                title = "选择发言人",
                groups = groups,
                currentRoute = currentRoute,
                initialGroupKey = key,
                onDismiss = actions::closeSpeakerPicker,
                onRouteSelected = actions::selectRoute,
                onLogin = actions::login
            )
        }
        if (importDialogVisible) {
            ImportChoiceDialog(
                colors = colors,
                onDismiss = { importDialogVisible = false },
                onDefault = {
                    importDialogVisible = false
                    actions.importDefault()
                },
                onLocal = {
                    importDialogVisible = false
                    actions.importLocal()
                },
                onOnline = {
                    importDialogVisible = false
                    actions.importOnline()
                }
            )
            }
        }
    }
}

@Composable
private fun rememberSpeechGroups(httpTtsList: List<HttpTTS>): List<SpeechVoiceEngineGroup> {
    val context = LocalContext.current
    return SpeechVoiceCatalogRepository.allGroups(context, httpTtsList)
}


private fun httpTtsForGroup(group: SpeechVoiceEngineGroup, httpTtsList: List<HttpTTS>): HttpTTS? {
    val id = group.loginKey.toLongOrNull() ?: return null
    return httpTtsList.firstOrNull { it.id == id }
}

@Composable
private fun EngineTopActions(
    colors: SpeakEngineColors,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallEngineAction("新增", onAdd, colors)
        SmallEngineAction("导入", onImport, colors)
        SmallEngineAction("导出全部", onExportAll, colors)
        SmallEngineAction("清缓存", onClearCache, colors)
    }
}

@Composable
private fun ImportChoiceDialog(
    colors: SpeakEngineColors,
    onDismiss: () -> Unit,
    onDefault: () -> Unit,
    onLocal: () -> Unit,
    onOnline: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            color = colors.page,
            shape = RoundedCornerShape(LocalContext.current.composePanelRadius()),
            border = BorderStroke(1.dp, colors.stroke)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "导入朗读规则",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("关闭", color = colors.subText) }
                }
                ImportChoiceRow("默认规则", "导入内置 HTTP TTS 规则", colors, onDefault)
                ImportChoiceRow("本地导入", "从本机 txt/json 文件导入", colors, onLocal)
                ImportChoiceRow("在线导入", "通过 URL 导入朗读规则", colors, onOnline)
            }
        }
    }
}

@Composable
private fun ImportChoiceRow(
    title: String,
    subtitle: String,
    colors: SpeakEngineColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun EngineGroupRow(
    group: SpeechVoiceEngineGroup,
    selected: Boolean,
    colors: SpeakEngineColors,
    onClick: () -> Unit,
    onLogin: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) colors.accent.copy(alpha = 0.15f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.title, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(group.subtitle, color = colors.subText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selected) {
                    Text("当前", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            val explicitCount = group.options.count { it.explicitSpeaker }
            if (explicitCount > 0 || group.emotions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (explicitCount > 0) {
                        InfoPill("${explicitCount}发言人", colors)
                    }
                    if (group.emotions.isNotEmpty()) {
                        InfoPill("${group.emotions.size}情绪", colors)
                    }
                }
            }
            if (onLogin != null || onEdit != null || onExport != null || onDelete != null) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    onLogin?.let { InlineEngineAction("登录", colors.accent, it) }
                    onEdit?.let { InlineEngineAction("编辑", colors.accent, it) }
                    onExport?.let { InlineEngineAction("导出", colors.accent, it) }
                    onDelete?.let { InlineEngineAction("删除", colors.danger, it) }
                }
            }
        }
    }
}

@Composable
private fun CompactEngineAction(
    text: String,
    colors: SpeakEngineColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(34.dp).clickable(onClick = onClick),
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EngineDetail(
    group: SpeechVoiceEngineGroup?,
    httpTts: HttpTTS?,
    colors: SpeakEngineColors,
    actions: SpeakEngineDialogActions
) {
    if (group == null) {
        Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Text("暂无朗读引擎", color = colors.subText, fontSize = 14.sp)
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(group.subtitle, color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (!group.loginUrl.isNullOrBlank() || httpTts != null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!group.loginUrl.isNullOrBlank()) {
                    DetailEngineAction("登录", colors.accent) { actions.login(group) }
                }
                httpTts?.let {
                    DetailEngineAction("编辑", colors.accent) { actions.editHttpTts(it.id) }
                    DetailEngineAction("删除", colors.danger) { actions.deleteHttpTts(it) }
                }
            }
        }
        Text(
            "发言人列表",
            color = colors.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 74.dp, max = 300.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(group.options, key = { it.key }) { option ->
                SpeakerOptionRow(option = option, colors = colors)
            }
        }
        if (group.emotions.isNotEmpty()) {
            Text(
                "情绪",
                color = colors.subText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp)
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.emotions.forEach { emotion ->
                    Surface(
                        color = colors.page,
                        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
                        border = BorderStroke(1.dp, colors.stroke)
                    ) {
                        Text(emotion.emotionName, color = colors.text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        Text(
            text = "选择此引擎后，普通朗读会使用该引擎；角色配音可在角色编辑页按发言人单独指定。",
            color = colors.subText,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

@Composable
private fun SpeakerOptionRow(option: SpeechVoiceOption, colors: SpeakEngineColors) {
    Surface(
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(option.speakerName, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    option.groupName.ifBlank { option.engineName },
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (option.toneID.isNotBlank()) {
                Text(
                    option.toneID,
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, colors: SpeakEngineColors) {
    Surface(
        color = colors.page,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Text(text, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun InlineEngineAction(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(32.dp)) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun DetailEngineAction(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(34.dp).clickable(onClick = onClick),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SmallEngineAction(text: String, onClick: () -> Unit, colors: SpeakEngineColors) {
    Surface(
        modifier = Modifier.height(34.dp).clickable(onClick = onClick),
        color = colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, colors.stroke)
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = colors.text, fontSize = 12.sp)
        }
    }
}

private data class SpeakEngineColors(
    val page: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val accent: Color,
    val danger: Color
)

@Composable
private fun rememberSpeakEngineColors(): SpeakEngineColors {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val page = Color(if (night) 0xff15171b.toInt() else 0xffffffff.toInt())
    val card = Color(if (night) 0xff20242a.toInt() else 0xfff6f7fa.toInt())
    val text = Color(context.primaryTextColor)
    val subText = Color(context.secondaryTextColor)
    val stroke = Color(if (night) 0x26ffffff else 0x18000000)
    return SpeakEngineColors(
        page = page,
        card = card,
        text = text,
        subText = subText,
        stroke = stroke,
        accent = Color(accent),
        danger = Color(ColorUtils.blendColors(0xffff4444.toInt(), accent, 0.08f))
    )
}
