package io.legado.app.ui.config

import android.os.Bundle
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AppearanceKit
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.AppearanceKitType
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MENU_CREATE = 1
private const val MENU_IMPORT = 2
private const val MENU_EXPORT = 3
private const val MENU_SYNC_TASKS = 4

class AppearanceKitActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var kitsState by mutableStateOf<List<AppearanceKit>>(emptyList())
    private var kitPreviewsState by mutableStateOf<Map<String, KitPreviewData>>(emptyMap())
    private var currentKitIdState by mutableStateOf("")

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importAppearanceKit)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        if (it.uri != null) {
            toastOnUi(R.string.export_success)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.appearance_kit_manage)
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        installComposeContent()
        refreshKits()
    }

    override fun onResume() {
        super.onResume()
        refreshKits()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CREATE, 0, R.string.appearance_kit_create)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_IMPORT, 1, R.string.appearance_kit_import)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_EXPORT, 2, R.string.appearance_kit_export)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SYNC_TASKS, 3, R.string.package_sync_task_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CREATE -> { showCreateKitDialog(); true }
            MENU_IMPORT -> { selectImportPackage(); true }
            MENU_EXPORT -> { exportCurrentKit(); true }
            MENU_SYNC_TASKS -> { showSyncTasks(); true }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun installComposeContent() {
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    AppearanceKitScreen(
                        kits = kitsState,
                        previews = kitPreviewsState,
                        currentKitId = currentKitIdState,
                        onApply = ::applyKit,
                        onEdit = ::editKit,
                        onDelete = ::confirmDeleteKit
                    )
                }
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
    }

    private fun refreshKits() {
        lifecycleScope.launch {
            val kits = AppearanceKitManager.builtinKits() + AppearanceKitManager.importedThemeKits()
            kitsState = kits
            currentKitIdState = AppearanceKitManager.currentKitId()
            kitPreviewsState = withContext(Dispatchers.IO) {
                kits.associate { it.id to buildKitPreview(it) }
            }
        }
    }

    private suspend fun buildKitPreview(kit: AppearanceKit): KitPreviewData {
        val binding = kit.binding
        val themeRef = binding?.dayTheme ?: binding?.nightTheme
        val isNight = binding?.dayTheme == null && binding?.nightTheme != null
        val entry = themeRef?.let { ref ->
            ThemePackageManager.loadLocalOnly(isNight).firstOrNull {
                it.dirName == ref.dirName || it.packageInfo.name == ref.name
            }
        }
        val config = entry?.let {
            runCatching { ThemePackageManager.getConfig(it) }.getOrNull()
        }
        val darkFallback = kit.id == AppearanceKitManager.KIT_SIDEBAR || isNight
        val backgroundColor = config?.backgroundColor.toPreviewColor(darkFallback)
        val primaryColor = config?.primaryColor.toPreviewColor(darkFallback)
        val accentColor = config?.accentColor.toPreviewColor(darkFallback, defaultAccent = true)
        val bottomColor = config?.bottomBackground.toPreviewColor(darkFallback)
        val cardColor = config?.cardColor.toOptionalPreviewColor() ?: bottomColor
        val backgroundPath = config?.backgroundImgPath?.takeIf { it.isNotBlank() } ?: kit.previewPath
        val signature = backgroundPath
            ?.takeIf { !it.startsWith("http", ignoreCase = true) }
            ?.let { path ->
                val file = File(path)
                if (file.exists()) ObjectKey("${file.absolutePath}:${file.length()}:${file.lastModified()}") else null
            }
        return KitPreviewData(
            backgroundColor = backgroundColor,
            primaryColor = primaryColor,
            accentColor = accentColor,
            cardColor = cardColor,
            bottomColor = bottomColor,
            backgroundPath = backgroundPath,
            signature = signature
        )
    }

    private fun String?.toPreviewColor(isNight: Boolean, defaultAccent: Boolean = false): Int {
        return runCatching {
            val value = this?.trim().orEmpty()
            val normalized = if (value.startsWith("#")) value else "#$value"
            normalized.toColorInt()
        }.getOrElse {
            when {
                defaultAccent -> getColor(R.color.accent)
                isNight -> android.graphics.Color.rgb(26, 28, 32)
                else -> android.graphics.Color.rgb(246, 247, 249)
            }
        }
    }

    private fun String?.toOptionalPreviewColor(): Int? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val normalized = if (value.startsWith("#")) value else "#$value"
            normalized.toColorInt()
        }.getOrNull()
    }

    private fun applyKit(kit: AppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.apply(this@AppearanceKitActivity, kit)
            }.onSuccess {
                currentKitIdState = kit.id
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun confirmDeleteKit(kit: AppearanceKit) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.appearance_kit_delete_confirm, kit.name),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { deleteKit(kit) }
        )
    }

    private fun editKit(kit: AppearanceKit) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return
        startActivity<AppearanceKitEditActivity> {
            putExtra(AppearanceKitEditActivity.EXTRA_KIT_ID, kit.id)
        }
    }

    private fun deleteKit(kit: AppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.deleteImportedTheme(this@AppearanceKitActivity, kit)
            }.onSuccess { deleted ->
                toastOnUi(if (deleted) R.string.delete_success else R.string.error)
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showCreateKitDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.appearance_kit_create),
            hint = getString(R.string.appearance_kit_name),
            initialValue = getString(R.string.appearance_kit_manage),
            validateInput = { it.trim().isNotBlank() },
            onPositive = ::createKit
        )
    }

    private fun createKit(name: String) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.createFromCurrent(this@AppearanceKitActivity, name)
            }.onSuccess { kit ->
                refreshKits()
                startActivity<AppearanceKitEditActivity> {
                    putExtra(AppearanceKitEditActivity.EXTRA_KIT_ID, kit.id)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun selectImportPackage() {
        importPackage.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.appearance_kit_import)
            allowExtensions = arrayOf("red", "zip")
        }
    }

    private fun importAppearanceKit(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val file = externalFiles.getFile("appearanceKitImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                AppearanceKitManager.importPackage(file)
            }.onSuccess { result ->
                toastOnUi(getString(R.string.appearance_kit_imported, result.total))
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun exportCurrentKit() {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.exportCurrent(this@AppearanceKitActivity)
            }.onSuccess { file ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(
                        file.name.ifBlank { "appearance_kit.zip" },
                        file,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showSyncTasks() {
        showPackageSyncTaskDialog(
            setOf(
                WebDavTaskType.THEME_PACKAGE_UPLOAD,
                WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
                WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD
            )
        )
    }
}

@Composable
private fun AppearanceKitScreen(
    kits: List<AppearanceKit>,
    previews: Map<String, KitPreviewData>,
    currentKitId: String,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onDelete: (AppearanceKit) -> Unit
) {
    val palette = rememberAppManagementPalette()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("kits") {
            KitSection(
                title = stringResourceCompat(R.string.appearance_kit_manage),
                palette = palette,
                rows = kits,
                previews = previews,
                currentKitId = currentKitId,
                onApply = onApply,
                onEdit = onEdit,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun KitSection(
    title: String,
    palette: AppManagementPalette,
    rows: List<AppearanceKit>,
    previews: Map<String, KitPreviewData>,
    currentKitId: String,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onDelete: (AppearanceKit) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        AppSettingSectionTitle(title = title, palette = palette.settings)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height((((rows.size + 1) / 2) * 214).dp),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            items(rows, key = { it.id }) { kit ->
                AppearanceKitCard(
                    kit = kit,
                    preview = previews[kit.id] ?: KitPreviewData.default(),
                    active = kit.id == currentKitId,
                    palette = palette,
                    onClick = { if (kit.id != currentKitId) onApply(kit) },
                    onLongClick = if (kit.type == AppearanceKitType.IMPORTED_THEME) {
                        { onEdit(kit) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    trailing: String,
    secondaryTrailing: String = "",
    tertiaryTrailing: String = "",
    previewSeed: String? = null,
    palette: AppSettingPalette,
    isLast: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)? = null,
    onTertiaryClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = palette.rowPressed,
                dividerColor = palette.divider,
                showDivider = !isLast,
                radiusPx = palette.panelRadiusPx,
                isFirst = false,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        previewSeed?.let {
            KitPreview(seed = it, palette = palette)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                color = palette.secondaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
        if (secondaryTrailing.isNotBlank() && onSecondaryClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = secondaryTrailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSecondaryClick
                )
            )
        }
        if (tertiaryTrailing.isNotBlank() && onTertiaryClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = tertiaryTrailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTertiaryClick
                )
            )
        }
    }
}

@Composable
private fun KitPreview(seed: String, palette: AppSettingPalette) {
    val colors = remember(seed) { legacyPreviewColors(seed) }
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(colors))
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.66f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(palette.row).copy(alpha = 0.72f))
        )
    }
}

private fun legacyPreviewColors(seed: String): List<Color> {
    val hash = seed.hashCode()
    val first = Color(0xFF000000 or (hash and 0x00FFFFFF).toLong())
    val second = Color(0xFF000000 or ((hash * 31) and 0x00FFFFFF).toLong())
    return listOf(first, second)
}

private data class KitPreviewData(
    val backgroundColor: Int,
    val primaryColor: Int,
    val accentColor: Int,
    val cardColor: Int,
    val bottomColor: Int,
    val backgroundPath: String?,
    val signature: ObjectKey?
) {
    companion object {
        fun default(): KitPreviewData {
            return KitPreviewData(
                backgroundColor = android.graphics.Color.rgb(246, 247, 249),
                primaryColor = android.graphics.Color.rgb(42, 46, 52),
                accentColor = android.graphics.Color.rgb(80, 120, 220),
                cardColor = android.graphics.Color.WHITE,
                bottomColor = android.graphics.Color.rgb(236, 238, 242),
                backgroundPath = null,
                signature = null
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppearanceKitCard(
    kit: AppearanceKit,
    preview: KitPreviewData,
    active: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(206.dp)
            .clip(RoundedCornerShape(palette.miuix.panelRadius ?: 14.dp))
            .background(if (pressed) Color(palette.settings.rowPressed) else Color(palette.settings.row))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(9.dp)
    ) {
        AppearanceKitPreview(
            preview = preview,
            active = active,
            palette = palette,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = kit.name,
            color = palette.settings.primaryText,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = kit.summary,
            color = palette.settings.secondaryText,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(preview.primaryColor, preview.accentColor, preview.cardColor, preview.bottomColor).forEach {
                Box(
                    modifier = Modifier
                        .size(width = 18.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(it))
                )
            }
        }
    }
}

@Composable
private fun AppearanceKitPreview(
    preview: KitPreviewData,
    active: Boolean,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(palette.miuix.panelRadius ?: 12.dp))
            .background(Color(preview.backgroundColor))
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.background_image)
                }
            },
            update = { imageView ->
                imageView.setBackgroundColor(preview.backgroundColor)
                val path = preview.backgroundPath?.takeIf { it.isNotBlank() }
                val loadTag = "${path.orEmpty()}|${preview.backgroundColor}|${preview.signature}"
                if (imageView.tag != loadTag) {
                    imageView.tag = loadTag
                    imageView.releaseComposeImage()
                    if (path != null) {
                        val appContext = imageView.context.applicationContext ?: imageView.context
                        val request = ImageLoader.load(appContext, path)
                            .centerCrop()
                            .error(ColorDrawable(preview.backgroundColor))
                        preview.signature?.let { request.signature(it) }
                        request.into(imageView)
                    }
                }
            },
            onRelease = { it.releaseComposeImage() },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(9.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (active) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.settings.accent.copy(alpha = 0.92f),
                        contentColor = palette.settings.onAccent,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Text(
                            text = stringResource(R.string.theme_applied_state),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = palette.settings.onAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(preview.cardColor).copy(alpha = 0.86f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(15.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(preview.bottomColor).copy(alpha = 0.90f))
                )
            }
        }
    }
}
