package io.legado.app.ui.config

import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.ImageAssetDelegate
import com.airbnb.lottie.LottieImageAsset
import com.airbnb.lottie.RenderMode
import io.legado.app.R
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.AdvancedTitleFontAssetDelegate
import io.legado.app.help.config.PackageResourcePolicy
import io.legado.app.help.config.PackageSvgResourceResolver
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.ui.book.read.page.LottieDecodeSize
import io.legado.app.ui.book.read.page.LottieImageMemoryPolicy
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.decodeBase64DataUrlBytes
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val advancedTitleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
internal fun AdvancedTitleManageScreen(
    entries: List<AdvancedTitlePackageManager.Entry>,
    activeId: String,
    loading: Boolean,
    previewProvider: suspend (AdvancedTitlePackageManager.Entry) -> String?,
    onApply: (AdvancedTitlePackageManager.Entry) -> Unit,
    onEdit: (AdvancedTitlePackageManager.Entry) -> Unit,
    onMoreActions: (AdvancedTitlePackageManager.Entry) -> List<AppManagementMenuAction>,
    onImport: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(
            fontFamily = palette.settings.bodyFontFamily
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.settings.page)
        ) {
            Text(
                text = if (loading) {
                    LocalContext.current.getString(R.string.loading)
                } else {
                    LocalContext.current.getString(R.string.advanced_title_manage_summary)
                },
                color = palette.settings.secondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
            ) {
                items(entries, key = { it.id }) { entry ->
                    AdvancedTitleItem(
                        entry = entry,
                        active = entry.id == activeId,
                        palette = palette,
                        previewProvider = previewProvider,
                        onApply = { onApply(entry) },
                        onEdit = { onEdit(entry) },
                        moreActions = onMoreActions(entry)
                    )
                }
            }

            LegadoMiuixActionButton(
                text = LocalContext.current.getString(R.string.import_str),
                palette = palette.miuix,
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                minHeight = 48.dp,
                primary = true
            )
            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun AdvancedTitleItem(
    entry: AdvancedTitlePackageManager.Entry,
    active: Boolean,
    palette: AppManagementPalette,
    previewProvider: suspend (AdvancedTitlePackageManager.Entry) -> String?,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    val editable = AdvancedTitlePackageManager.isEditable(entry)
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AdvancedTitlePreview(entry, previewProvider, palette)
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = entry.name,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildEntryInfo(entry, active),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdvancedTitleActionButton(
                        text = LocalContext.current.getString(
                            if (active) R.string.advanced_title_applied else R.string.advanced_title_apply
                        ),
                        palette = palette.miuix,
                        accent = true,
                        enabled = !active,
                        onClick = onApply
                    )
                    if (!entry.isBuiltin) {
                        AdvancedTitleActionButton(
                            text = LocalContext.current.getString(
                                if (editable) R.string.edit else R.string.read_only
                            ),
                            palette = palette.miuix,
                            accent = false,
                            enabled = editable,
                            onClick = onEdit
                        )
                    }
                    AppManagementMoreActionButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        contentDescription = LocalContext.current.getString(R.string.more)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedTitlePreview(
    entry: AdvancedTitlePackageManager.Entry,
    previewProvider: suspend (AdvancedTitlePackageManager.Entry) -> String?,
    palette: AppManagementPalette
) {
    val json by produceState<String?>(null, entry.id, entry.updatedAt) {
        value = previewProvider(entry)
    }
    val resourceContext = AdvancedTitlePackageManager.resourceContext(entry)
    val packageResolver = resourceContext?.let {
        PackageSvgResourceResolver(
            root = it.root,
            resources = it.resources,
            targetWidth = PREVIEW_ASSET_EDGE,
            targetHeight = PREVIEW_ASSET_EDGE
        )
    }
    Surface(
        modifier = Modifier.size(width = 112.dp, height = 72.dp),
        shape = RoundedCornerShape(10.dp),
        color = palette.miuix.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        val value = json
        if (value == null) {
            Box(modifier = Modifier.fillMaxSize())
        } else {
            AndroidView(
                factory = { context ->
                    LottieAnimationView(context).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        setCacheComposition(false)
                        repeatCount = 0
                        renderMode = RenderMode.SOFTWARE
                    }
                },
                update = { view ->
                    view.setImageAssetDelegate(
                        ImageAssetDelegate { asset -> resolvePreviewImage(asset, packageResolver) }
                    )
                    view.setFontAssetDelegate(
                        AdvancedTitleFontAssetDelegate(
                            packagedTypeface = { family, style, name ->
                                val reference = sequenceOf(family, name).firstOrNull {
                                    it.startsWith(PackageResourcePolicy.ALIAS_PREFIX, ignoreCase = true)
                                }
                                reference?.let {
                                    packageResolver?.resolveFont(
                                        it,
                                        if (style.contains("bold", true)) 700 else 400,
                                        style
                                    )
                                }
                            }
                        )
                    )
                    val key = LottieImageMemoryPolicy.sourceSha256(value) + ":" +
                        resourceContext?.cacheKey.orEmpty()
                    if (view.tag != key) {
                        view.cancelAnimation()
                        view.clearAnimation()
                        runCatching {
                            view.setAnimationFromJson(value, null)
                            view.progress = 0.5f
                            view.pauseAnimation()
                            view.tag = key
                        }
                    }
                },
                onRelease = { view ->
                    view.cancelAnimation()
                    view.clearAnimation()
                    view.tag = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun resolvePreviewImage(
    asset: LottieImageAsset,
    packageResolver: PackageSvgResourceResolver?
): Bitmap? {
    val candidates = buildList {
        asset.fileName?.let(::add)
        if (!asset.dirName.isNullOrBlank() && !asset.fileName.isNullOrBlank()) {
            add(asset.dirName + asset.fileName)
        }
    }
    candidates.firstOrNull { it.startsWith("data:image", ignoreCase = true) }?.let { source ->
        val bytes = source.decodeBase64DataUrlBytes() ?: return null
        if (source.contains("image/svg+xml", ignoreCase = true)) {
            return ByteArrayInputStream(bytes).use {
                SvgUtils.createBitmap(it, PREVIEW_ASSET_EDGE, PREVIEW_ASSET_EDGE)
            }
        }
        return decodePreviewBitmap(bytes)
    }
    candidates.forEach { candidate ->
        packageResolver?.resolveImage(candidate)?.let { return it }
    }
    return null
}

private fun decodePreviewBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val target = LottieImageMemoryPolicy.fitSourceInto(
        bounds.outWidth,
        bounds.outHeight,
        LottieDecodeSize(PREVIEW_ASSET_EDGE, PREVIEW_ASSET_EDGE)
    ) ?: return null
    val sample = LottieImageMemoryPolicy.sampleSize(bounds.outWidth, bounds.outHeight, target)
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    if (decoded.width == target.width && decoded.height == target.height) return decoded
    return Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
        if (it !== decoded) decoded.recycle()
    }
}

private const val PREVIEW_ASSET_EDGE = 256

@Composable
private fun AdvancedTitleActionButton(
    text: String,
    palette: LegadoMiuixPalette,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val actionRadius = palette.actionRadius ?: LocalContext.current.composeActionRadius()
    val minHeight = (38f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Surface(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(actionRadius),
        color = if (accent) palette.accent.copy(alpha = if (enabled) 0.14f else 0.08f)
        else palette.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = minHeight)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = if (enabled) palette.accent else palette.secondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun buildEntryInfo(
    entry: AdvancedTitlePackageManager.Entry,
    active: Boolean
): String = buildString {
    if (active) {
        append(LocalContext.current.getString(R.string.advanced_title_current))
        append(" · ")
    }
    append(
        LocalContext.current.getString(
            if (entry.isBuiltin) R.string.advanced_title_source_builtin
            else R.string.advanced_title_source_local
        )
    )
    if (!entry.isBuiltin && !AdvancedTitlePackageManager.isEditable(entry)) {
        append(" · ")
        append(LocalContext.current.getString(R.string.read_only))
    }
    if (entry.updatedAt > 0L) {
        append(" · ")
        append(advancedTitleDateFormat.format(Date(entry.updatedAt)))
    }
}
