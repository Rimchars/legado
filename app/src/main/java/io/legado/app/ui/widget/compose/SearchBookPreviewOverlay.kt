package io.legado.app.ui.widget.compose

import android.content.Context
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.BookIntroUtils
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SearchBookPreviewState(
    val book: SearchBook,
    val originBounds: Rect? = null
)

private val SearchBookPreviewEmphasizedEasing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)
private val SearchBookPreviewStandardEasing = CubicBezierEasing(0.20f, 0.00f, 0.20f, 1.00f)

@Composable
fun SearchBookPreviewOverlay(
    state: SearchBookPreviewState?,
    renderConfig: BookshelfListRenderConfig,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    onDismissed: () -> Unit,
    onOpen: (SearchBook) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state == null) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val key = state.book.previewKey()
    val progress = remember(key) { Animatable(0f) }
    var closing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 280, easing = SearchBookPreviewEmphasizedEasing)
        )
    }
    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 190, easing = SearchBookPreviewStandardEasing)
            )
            onDismissed()
        }
    }
    SearchBookPreviewBackHandler(enabled = true, onBack = ::dismiss)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val rootWidthPx = with(density) { maxWidth.toPx() }
        val rootHeightPx = with(density) { maxHeight.toPx() }
        val minMarginPx = with(density) { 18.dp.toPx() }
        val targetWidthPx = minOf(rootWidthPx - minMarginPx * 2f, with(density) { 430.dp.toPx() })
            .coerceAtLeast(with(density) { 280.dp.toPx() })
        val targetHeightPx = minOf(rootHeightPx - with(density) { 92.dp.toPx() }, with(density) { 350.dp.toPx() })
            .coerceAtLeast(with(density) { 304.dp.toPx() })
        val targetLeft = (rootWidthPx - targetWidthPx) / 2f
        val targetTop = (rootHeightPx - targetHeightPx) / 2f
        val fallbackOrigin = Rect(
            left = rootWidthPx / 2f - with(density) { 44.dp.toPx() },
            top = rootHeightPx / 2f - with(density) { 60.dp.toPx() },
            right = rootWidthPx / 2f + with(density) { 44.dp.toPx() },
            bottom = rootHeightPx / 2f + with(density) { 60.dp.toPx() }
        )
        val origin = state.originBounds
            ?.takeIf { it.width > 8f && it.height > 8f }
            ?: fallbackOrigin
        val p = progress.value.coerceIn(0f, 1f)
        val left = lerpFloat(origin.left, targetLeft, p)
        val top = lerpFloat(origin.top, targetTop, p)
        val width = lerpFloat(origin.width, targetWidthPx, p)
        val height = lerpFloat(origin.height, targetHeightPx, p)
        val radius = lerpFloat(7f, with(density) { renderConfig.palette.panelRadius.toPx() }, p)
        val contentAlpha = ((p - 0.16f) / 0.84f).coerceIn(0f, 1f)
        // Match AppDialogFrame surface: bottomBackground / custom card + dialogAlpha (not list row glass alpha).
        val previewPanelColor = rememberDialogLikePanelColor()

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f * p))
                    .clickable { dismiss() }
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .width(with(density) { width.toDp() })
                    .height(with(density) { height.toDp() })
                    .clip(RoundedCornerShape(with(density) { radius.toDp() }))
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .appSettingPanelBackground(
                            normalColor = previewPanelColor,
                            panelImage = renderConfig.panelImage,
                            borderColor = renderConfig.palette.borderColor,
                            radiusPx = radius
                        )
                )
                SearchBookPreviewContent(
                    book = state.book,
                    renderConfig = renderConfig,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    detailsAlpha = contentAlpha,
                    onClose = { dismiss() },
                    onOpen = {
                        if (!closing) {
                            closing = true
                            onOpen(state.book)
                        }
                    }
                )
                if (closing) {
                    BookCoverImage(
                        book = state.book,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = (1f - contentAlpha).coerceIn(0f, 1f) },
                        style = CoverImageView.CoverStyle.PREVIEW,
                        fragment = fragment,
                        lifecycle = lifecycle,
                        preferThumb = true,
                        fillBounds = true
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBookPreviewContent(
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    fragment: Fragment?,
    lifecycle: Lifecycle?,
    detailsAlpha: Float,
    onClose: () -> Unit,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .graphicsLayer { alpha = detailsAlpha }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(98.dp)
                    .aspectRatio(0.75f)
            ) {
                BookCoverImage(
                    book = book,
                    modifier = Modifier.fillMaxSize(),
                    style = CoverImageView.CoverStyle.PREVIEW,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    preferThumb = true,
                    fillBounds = true
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = book.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 21.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = palette.titleFontFamily,
                    color = palette.primaryText
                )
                listOf(book.author, book.originName, book.latestChapterTitle.orEmpty())
                    .filter { it.isNotBlank() }
                    .take(3)
                    .forEach { meta ->
                        Text(
                            text = meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            fontFamily = palette.bodyFontFamily,
                            color = palette.secondaryText
                        )
                    }
            }
        }
        Spacer(modifier = Modifier.height(13.dp))
        val intro = remember(book.intro) {
            BookIntroUtils.listIntro(book.intro)
                ?: context.getString(R.string.intro_show_null)
        }
        Text(
            text = intro,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 84.dp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontFamily = palette.bodyFontFamily,
            softWrap = true,
            color = palette.primaryText.copy(alpha = 0.86f)
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SearchBookPreviewAction(
                    text = context.getString(R.string.close),
                    renderConfig = renderConfig,
                    emphatic = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClose
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SearchBookPreviewAction(
                    text = context.getString(R.string.read_record_open_book_info),
                    renderConfig = renderConfig,
                    emphatic = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpen
                )
            }
        }
    }
}

@Composable
private fun SearchBookPreviewAction(
    text: String,
    renderConfig: BookshelfListRenderConfig,
    emphatic: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(palette.actionRadius))
            .background(
                if (emphatic) palette.accent.copy(alpha = 0.17f)
                else palette.secondaryText.copy(alpha = 0.10f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.bodyFontFamily,
            color = if (emphatic) palette.accent else palette.primaryText
        )
    }
}

@Composable
private fun SearchBookPreviewBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    val activity = LocalContext.current as? AppCompatActivity ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        val callback = object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose {
            callback.remove()
        }
    }
}

private fun SearchBook.previewKey(): String {
    return "$origin|$bookUrl|${coverUrl.orEmpty()}|$name|$author"
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

@Composable
private fun rememberDialogLikePanelColor(): Int {
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val dialogAlpha = AppConfig.dialogAlpha
    val eInk = AppConfig.isEInkMode
    val surfaceBase = if (themeUiPalette.hasCustomCardColor) {
        themeUiPalette.cardColor
    } else {
        context.bottomBackground
    }
    return remember(surfaceBase, dialogAlpha, eInk, themeUiPalette.signature) {
        val alpha = if (eInk) {
            1f
        } else {
            dialogAlpha.coerceIn(0, 100) / 100f
        }
        ColorUtils.withAlpha(surfaceBase, alpha)
    }
}

