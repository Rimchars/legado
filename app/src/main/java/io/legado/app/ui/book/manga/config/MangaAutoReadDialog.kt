package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.config.AutoReadAction
import io.legado.app.ui.book.read.config.AutoReadModeButton
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

/**
 * 漫画自动翻页控制条(同小说AutoReadDialog:模式单选+速度滑块+底部快捷按钮)
 */
class MangaAutoReadDialog : ComposeDialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val callBack: CallBack? get() = activity as? CallBack

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
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
        val timedMode = (activity as? ReadMangaActivity)?.isAutoScrollEnabled() == true
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MangaAutoReadContent(
                    initialTimedMode = timedMode,
                    onShowMenu = {
                        callBack?.showMenuBar()
                        dismissAllowingStateLoss()
                    },
                    onOpenChapterList = { callBack?.openChapterList() },
                    onStopAutoPage = {
                        callBack?.autoPageStop()
                        post { dismissAllowingStateLoss() }
                    },
                    onOpenSetting = {
                        (activity as? ReadMangaActivity)?.showMangaConfigMenu()
                    },
                    onModeChange = { mode ->
                        (activity as? ReadMangaActivity)?.applyMangaAutoMode(mode)
                    },
                    onSpeedCommitted = { speed ->
                        (activity as? ReadMangaActivity)?.applyMangaAutoSpeed(speed)
                    }
                )
            }
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}

@Composable
private fun MangaAutoReadContent(
    initialTimedMode: Boolean,
    onShowMenu: () -> Unit,
    onOpenChapterList: () -> Unit,
    onStopAutoPage: () -> Unit,
    onOpenSetting: () -> Unit,
    onModeChange: (String) -> Unit,
    onSpeedCommitted: (Int) -> Unit
) {
    val dialogStyle = rememberAppDialogStyle()
    val sliderPalette = LegadoMiuixPalette(
        accent = dialogStyle.accent,
        surface = dialogStyle.surface,
        surfaceVariant = dialogStyle.fieldSurface,
        primaryText = dialogStyle.primaryText,
        secondaryText = dialogStyle.secondaryText,
        danger = dialogStyle.danger
    )
    val surface = dialogStyle.surface
    val panel = dialogStyle.fieldSurface
    val textColor = dialogStyle.primaryText
    val secondaryTextColor = dialogStyle.secondaryText
    var mode by remember { mutableIntStateOf(if (initialTimedMode) 1 else 0) }
    var speed by remember {
        mutableIntStateOf(AppConfig.mangaAutoPageSpeed.coerceIn(1, 120))
    }
    val speedTitle = if (mode == 1) {
        stringResource(R.string.auto_page_interval)
    } else {
        stringResource(R.string.auto_page_speed)
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = dialogStyle.bodyFontFamily)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(
                topStart = dialogStyle.panelRadius,
                topEnd = dialogStyle.panelRadius
            ),
            color = surface,
            contentColor = textColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LegadoMiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = panel,
                    contentColor = textColor,
                    cornerRadius = dialogStyle.panelRadius,
                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AutoReadModeButton(
                            text = stringResource(R.string.auto_read_mode_scroll),
                            selected = mode != 1,
                            palette = sliderPalette,
                            actionRadius = dialogStyle.actionRadius,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                mode = 0
                                onModeChange("scroll")
                            }
                        )
                        AutoReadModeButton(
                            text = stringResource(R.string.auto_read_mode_timed),
                            selected = mode == 1,
                            palette = sliderPalette,
                            actionRadius = dialogStyle.actionRadius,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                mode = 1
                                onModeChange("page")
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = speedTitle,
                            color = textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${speed}s",
                            color = secondaryTextColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    AppThemedStepperSlider(
                        value = speed,
                        range = 1..120,
                        onValueChange = { value ->
                            speed = value.coerceIn(1, 120)
                        },
                        palette = sliderPalette,
                        step = 1,
                        onValueChangeFinished = {
                            val nextSpeed = speed.coerceIn(1, 120)
                            if (AppConfig.mangaAutoPageSpeed != nextSpeed) {
                                AppConfig.mangaAutoPageSpeed = nextSpeed
                                onSpeedCommitted(nextSpeed)
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AutoReadAction(
                        iconRes = R.drawable.ic_toc,
                        text = stringResource(R.string.chapter_list),
                        textColor = textColor,
                        panelColor = panel,
                        actionRadius = dialogStyle.actionRadius,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenChapterList
                    )
                    AutoReadAction(
                        iconRes = R.drawable.ic_menu,
                        text = stringResource(R.string.main_menu),
                        textColor = textColor,
                        panelColor = panel,
                        actionRadius = dialogStyle.actionRadius,
                        modifier = Modifier.weight(1f),
                        onClick = onShowMenu
                    )
                    AutoReadAction(
                        iconRes = R.drawable.ic_auto_page_stop,
                        text = stringResource(R.string.stop),
                        textColor = textColor,
                        panelColor = panel,
                        actionRadius = dialogStyle.actionRadius,
                        modifier = Modifier.weight(1f),
                        onClick = onStopAutoPage
                    )
                    AutoReadAction(
                        iconRes = R.drawable.ic_settings,
                        text = stringResource(R.string.setting),
                        textColor = textColor,
                        panelColor = panel,
                        actionRadius = dialogStyle.actionRadius,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSetting
                    )
                }
            }
        }
    }
}
