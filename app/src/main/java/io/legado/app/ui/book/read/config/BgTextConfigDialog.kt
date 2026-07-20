package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.releaseComposeImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.appcompat.widget.AppCompatImageView
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.longToast
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.readBytes
import io.legado.app.utils.readUri
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import splitties.init.appCtx

class BgTextConfigDialog : BaseDialogFragment(0) {

    companion object {
        const val TEXT_COLOR = 121
        const val BG_COLOR = 122
        const val TEXT_ACCENT_COLOR = 123
    }

    private val configFileName = "readConfig.zip"
    private val importFormNet = "网络导入"
    private var presetBgImages by mutableStateOf<List<String>>(emptyList())
    private var refreshTick by mutableIntStateOf(0)
    private var pendingSelfConfigEvents = 0

    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> setBgFromUri(uri) }
    }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> exportConfig(uri) }
    }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetConfigAlert()
            } else {
                importConfig(uri)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            if (pendingSelfConfigEvents > 0) {
                pendingSelfConfigEvents--
            } else {
                refreshTick++
            }
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    BgTextConfigContent(style = style)
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        presetBgImages = requireContext().assets.list("bg")?.toList().orEmpty()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    @Composable
    private fun BgTextConfigContent(style: AppDialogStyle) {
        refreshTick
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                HeaderSection(style)
                ToggleSection(style)
                if (ReadBook.book?.isImage != true) {
                    UnderlineSection(style)
                }
                ColorSection(style)
                ImportExportSection(style)
                SliderSection(style)
                BackgroundImageSection(style)
            }
        }
    }

    @Composable
    private fun HeaderSection(style: AppDialogStyle) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.style_name),
                    color = style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ReadBookConfig.durConfig.name.ifBlank { "文字" },
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = style.secondaryText,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { editStyleName() }
                        .padding(2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.restore),
                    color = style.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { restorePreset() }
                )
            }
        }
    }

    @Composable
    private fun ToggleSection(style: AppDialogStyle) {
        ReaderSectionCard(style = style, title = null, contentPadding = PaddingValues(6.dp)) {
            var darkStatusIcon by rememberSaveable(refreshTick) {
                mutableStateOf(ReadBookConfig.durConfig.curStatusIconDark())
            }
            var scrollFollowBg by rememberSaveable(refreshTick) {
                mutableStateOf(ReadBookConfig.durConfig.curReadScrollFollowBackground())
            }
            ReaderSwitchRow(
                title = stringResource(R.string.dark_status_icon),
                checked = darkStatusIcon,
                style = style
            ) {
                darkStatusIcon = it
                ReadBookConfig.durConfig.setCurStatusIconDark(it)
                (activity as? ReadBookActivity)?.upSystemUiVisibility()
            }
            ReaderSwitchRow(
                title = stringResource(R.string.read_scroll_follow_background),
                checked = scrollFollowBg,
                style = style,
                summary = stringResource(R.string.read_scroll_follow_background_summary)
            ) {
                scrollFollowBg = it
                ReadBookConfig.durConfig.setCurReadScrollFollowBackground(it)
                postReadConfigChanged(1, 5)
            }
        }
    }

    @Composable
    private fun UnderlineSection(style: AppDialogStyle) {
        ReaderSectionCard(
            style = style,
            title = stringResource(R.string.text_underline),
            contentPadding = PaddingValues(8.dp)
        ) {
            var underlineMode by rememberSaveable(refreshTick) {
                mutableIntStateOf(ReadBookConfig.underlineMode)
            }
            var strokeWidthStep by rememberSaveable(refreshTick) {
                mutableIntStateOf((ReadBookConfig.underlineStrokeWidth * 2f).roundToInt())
            }
            var dashLength by rememberSaveable(refreshTick) {
                mutableIntStateOf(ReadBookConfig.underlineDashLength.roundToInt())
            }
            ReaderSegmentedOptions(
                options = listOf(
                    ReaderOption("0", "关闭"),
                    ReaderOption("1", "实线"),
                    ReaderOption("2", "虚线")
                ),
                selectedValue = underlineMode.toString(),
                style = style,
                pillStyle = true
            ) { value ->
                val next = value.toIntOrNull() ?: return@ReaderSegmentedOptions
                if (next == underlineMode) return@ReaderSegmentedOptions
                underlineMode = next
                ReadBookConfig.underlineMode = next
                postReadConfigChanged(9, 11)
            }
            if (underlineMode != 0) {
                SliderRow(
                    title = stringResource(R.string.underline_stroke_width),
                    value = strokeWidthStep,
                    range = 1..8,
                    style = style,
                    valueText = "${strokeWidthStep / 2f} dp"
                ) {
                    strokeWidthStep = it
                    ReadBookConfig.underlineStrokeWidth = it / 2f
                    postReadConfigChanged(9, 11)
                }
            }
            if (underlineMode == 2) {
                SliderRow(
                    title = stringResource(R.string.underline_dash_length),
                    value = dashLength,
                    range = 2..30,
                    style = style,
                    valueText = "$dashLength dp"
                ) {
                    dashLength = it
                    ReadBookConfig.underlineDashLength = it.toFloat()
                    postReadConfigChanged(9, 11)
                }
            }
        }
    }

    @Composable
    private fun ColorSection(style: AppDialogStyle) {
        ReaderSectionCard(style = style, title = null, contentPadding = PaddingValues(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ColorAction(
                    text = stringResource(R.string.text_color),
                    color = Color(ReadBookConfig.durConfig.curTextColor()),
                    style = style,
                    modifier = Modifier.weight(1f)
                ) { showTextColorPicker() }
                ColorAction(
                    text = stringResource(R.string.bg_color),
                    color = currentBackgroundSwatch(),
                    style = style,
                    modifier = Modifier.weight(1f)
                ) { showBgColorPicker() }
                ColorAction(
                    text = stringResource(R.string.text_accent_color),
                    color = Color(ReadBookConfig.durConfig.curTextAccentColor()),
                    style = style,
                    modifier = Modifier.weight(1f)
                ) { showTextAccentColorPicker() }
            }
        }
    }

    @Composable
    private fun ColorAction(
        text: String,
        color: Color,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = modifier
                .heightIn(min = 42.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(color)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = text,
                    color = style.primaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun ImportExportSection(style: AppDialogStyle) {
        ReaderSectionCard(style = style, title = null, contentPadding = PaddingValues(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallActionButton(
                    text = stringResource(R.string.import_str),
                    iconRes = R.drawable.ic_import,
                    style = style,
                    modifier = Modifier.weight(1f),
                    onClick = ::launchImport
                )
                SmallActionButton(
                    text = stringResource(R.string.export_str),
                    iconRes = R.drawable.ic_export,
                    style = style,
                    modifier = Modifier.weight(1f),
                    onClick = ::launchExport
                )
                SmallActionButton(
                    text = stringResource(R.string.delete),
                    iconRes = R.drawable.ic_clear_all,
                    style = style,
                    modifier = Modifier.weight(1f),
                    danger = true,
                    onClick = ::deleteCurrentConfig
                )
            }
        }
    }

    @Composable
    private fun SmallActionButton(
        text: String,
        iconRes: Int,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        danger: Boolean = false,
        onClick: () -> Unit
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier.heightIn(min = 42.dp),
            shape = RoundedCornerShape(style.actionRadius),
            color = if (danger) style.danger.copy(alpha = 0.11f) else style.fieldSurface,
            contentColor = if (danger) style.danger else style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = text,
                    tint = if (danger) style.danger else style.primaryText,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    color = if (danger) style.danger else style.primaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun SliderSection(style: AppDialogStyle) {
        ReaderSectionCard(style = style, title = null, contentPadding = PaddingValues(8.dp)) {
            var bgAlpha by rememberSaveable(refreshTick) { mutableIntStateOf(ReadBookConfig.bgAlpha) }
            var textShadow by rememberSaveable(refreshTick) { mutableIntStateOf(ReadBookConfig.paperInkStrength) }
            SliderRow(
                title = stringResource(R.string.bg_alpha),
                value = bgAlpha,
                range = 0..100,
                style = style
            ) {
                bgAlpha = it
                ReadBookConfig.bgAlpha = it
                postReadConfigChanged(3)
            }
            SliderRow(
                title = stringResource(R.string.text_shadow),
                value = textShadow,
                range = 0..100,
                style = style,
                valueText = if (textShadow == 0) stringResource(R.string.jf_convert_o) else "$textShadow%"
            ) {
                textShadow = it
                ReadBookConfig.paperInkStrength = it
                postReadConfigChanged(2, 9, 6)
            }
        }
    }

    @Composable
    private fun SliderRow(
        title: String,
        value: Int,
        range: IntRange,
        style: AppDialogStyle,
        valueText: String = "$value%",
        onValueChange: (Int) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = valueText,
                    color = style.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AppThemedStepperSlider(
                value = value,
                range = range,
                onValueChange = onValueChange,
                palette = style.toMiuixPalette(),
                trackHeight = 32.dp,
                thumbSize = 24.dp,
                endpointWidth = 28.dp
            )
        }
    }

    @Composable
    private fun BackgroundImageSection(style: AppDialogStyle) {
        ReaderSectionCard(
            style = style,
            title = stringResource(R.string.bg_image),
            contentPadding = PaddingValues(8.dp)
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "select_image") {
                    SelectImageTile(style)
                }
                items(
                    items = presetBgImages,
                    key = { it }
                ) { imageName ->
                    PresetBackgroundTile(imageName = imageName, style = style)
                }
            }
        }
    }

    @Composable
    private fun SelectImageTile(style: AppDialogStyle) {
        Surface(
            modifier = Modifier
                .width(82.dp)
                .height(82.dp)
                .clickable {
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                },
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = stringResource(R.string.select_image),
                    tint = style.primaryText,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.select_image),
                    color = style.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun PresetBackgroundTile(imageName: String, style: AppDialogStyle) {
        Surface(
            modifier = Modifier
                .width(82.dp)
                .height(82.dp)
                .clickable {
                    ReadBookConfig.durConfig.setCurBg(1, imageName)
                    postReadConfigChanged(1)
                    refreshTick++
                },
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = style.actionRadius, topEnd = style.actionRadius))
                        .background(style.surface)
                ) {
                    PresetBackgroundPreview(
                        imageName = imageName,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = imageName.substringBeforeLast("."),
                    color = style.secondaryText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }

    @Composable
    private fun PresetBackgroundPreview(imageName: String, modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                AppCompatImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                ImageLoader.load(imageView.context, "file:///android_asset/bg/$imageName")
                    .centerCrop()
                    .into(imageView)
            },
            onRelease = { it.releaseComposeImage() }
        )
    }

    private fun editStyleName() {
        showComposeTextInputDialog(
            title = getString(R.string.style_name),
            hint = "name",
            initialValue = ReadBookConfig.durConfig.name,
            onPositive = {
                ReadBookConfig.durConfig.name = it
                refreshTick++
            }
        )
    }

    private fun restorePreset() {
        val defaultConfigs = DefaultData.readConfigs
        val layoutNames = defaultConfigs.map { it.name }
        showComposeChoiceListDialog("选择预设布局", layoutNames) { i ->
            if (i >= 0) {
                ReadBookConfig.durConfig = defaultConfigs[i].copy()
                refreshTick++
                postReadConfigChanged(1, 2, 5)
            }
        }
    }

    private fun showTextColorPicker() {
        ColorPickerDialog.newBuilder()
            .setColor(ReadBookConfig.durConfig.curTextColor())
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(TEXT_COLOR)
            .show(requireActivity())
    }

    private fun showTextAccentColorPicker() {
        ColorPickerDialog.newBuilder()
            .setColor(ReadBookConfig.durConfig.curTextAccentColor())
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(TEXT_ACCENT_COLOR)
            .show(requireActivity())
    }

    private fun showBgColorPicker() {
        val bgColor =
            if (ReadBookConfig.durConfig.curBgType() == 0) ReadBookConfig.durConfig.curBgStr().toColorInt()
            else "#015A86".toColorInt()
        ColorPickerDialog.newBuilder()
            .setColor(bgColor)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(BG_COLOR)
            .show(requireActivity())
    }

    private fun currentBackgroundSwatch(): Color {
        return if (ReadBookConfig.durConfig.curBgType() == 0) {
            runCatching { Color(ReadBookConfig.durConfig.curBgStr().toColorInt()) }
                .getOrDefault(Color(0xFF015A86))
        } else {
            Color(0xFF015A86)
        }
    }

    private fun postReadConfigChanged(vararg configKeys: Int) {
        pendingSelfConfigEvents++
        postEvent(EventBus.UP_CONFIG, arrayListOf(*configKeys.toTypedArray()))
    }

    private fun launchImport() {
        selectImportDoc.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("zip")
            otherActions = arrayListOf(SelectItem(importFormNet, -1))
        }
    }

    private fun launchExport() {
        selectExportDir.launch {
            title = getString(R.string.export_str)
        }
    }

    private fun deleteCurrentConfig() {
        if (ReadBookConfig.deleteDur()) {
            postReadConfigChanged(1, 2, 5)
            dismissAllowingStateLoss()
        } else {
            toastOnUi("数量已是最少, 不能删除.")
        }
    }

    private fun exportConfig(uri: Uri) {
        val exportFileName = if (ReadBookConfig.config.name.isBlank()) {
            configFileName
        } else {
            "${ReadBookConfig.config.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<File>()
            val configDir = requireContext().externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = ReadBookConfig.getExportConfig()
            val fontPath = ReadBookConfig.textFont
            if (fontPath.isNotEmpty()) {
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = fontDoc.name
                val fontInputStream = fontDoc.openInputStream().getOrNull()
                fontInputStream?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out ->
                        it.copyTo(out)
                    }
                    config.textFont = fontName
                    exportFiles.add(fontExportFile)
                }
            }
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = ReadBookConfig.durConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(requireContext().externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    File(configZipPath).inputStream().use {
                        it.copyTo(out)
                    }
                }
            }
        }.onSuccess {
            toastOnUi("导出成功, 文件名为 $exportFileName")
        }.onError {
            it.printOnDebug()
            AppLog.put("导出失败:${it.localizedMessage}", it)
            longToast("导出失败:${it.localizedMessage}")
        }
    }

    private fun copyBgImage(path: String, configDir: File): File? {
        val bgName = FileUtils.getName(path)
        val bgFile = File(path)
        if (bgFile.exists()) {
            val bgExportFile = File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
                return bgExportFile
            }
        }
        return null
    }

    private fun importNetConfigAlert() {
        showComposeTextInputDialog(
            title = "输入地址",
            onPositive = { url ->
                if (url.isNotBlank()) {
                    importNetConfig(url)
                }
            }
        )
    }

    private fun importNetConfig(url: String) {
        execute {
            okHttpClient.newCallResponseBody {
                url(url)
            }.bytes().let {
                importConfig(it)
            }
        }.onError {
            longToast(it.stackTraceStr)
        }
    }

    private fun importConfig(uri: Uri) {
        execute {
            importConfig(uri.readBytes(requireContext()))
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun importConfig(byteArray: ByteArray) {
        execute {
            ReadBookConfig.import(byteArray)
        }.onSuccess {
            ReadBookConfig.durConfig = it
            refreshTick++
            postReadConfigChanged(1, 2, 5)
            toastOnUi("导入成功")
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun setBgFromUri(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    ReadBookConfig.durConfig.setCurBg(2, fileName)
                    postReadConfigChanged(1)
                    refreshTick++
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookConfig.durConfig.setCurBg(2, fileName)
                postReadConfigChanged(1)
                refreshTick++
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
