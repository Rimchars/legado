package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

class AutoReadDialog : ComposeDialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val callBack: CallBack? get() = activity as? CallBack
    private var registeredBottomDialog = false

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

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (registeredBottomDialog) {
            (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
            registeredBottomDialog = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val readActivity = activity as? ReadBookActivity
        val bottomDialog = readActivity?.bottomDialog ?: 0
        val alreadyRegistered = registeredBottomDialog
        val shouldDismissForExistingDialog = !alreadyRegistered && bottomDialog > 0
        if (!alreadyRegistered && readActivity != null) {
            readActivity.bottomDialog = bottomDialog + 1
            registeredBottomDialog = true
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            if (shouldDismissForExistingDialog) {
                post { dismissAllowingStateLoss() }
            }
            setContent {
                if (!shouldDismissForExistingDialog) {
                    AutoReadContent(
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
                            (activity as? BaseReadBookActivity)?.showPageAnimConfig {
                                (activity as? ReadBookActivity)?.upPageAnim()
                                ReadBook.loadContent(false)
                            }
                        },
                        onSpeedCommitted = ::upTtsSpeechRate
                    )
                }
            }
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}

@Composable
private fun AutoReadContent(
    onShowMenu: () -> Unit,
    onOpenChapterList: () -> Unit,
    onStopAutoPage: () -> Unit,
    onOpenSetting: () -> Unit,
    onSpeedCommitted: () -> Unit
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
    val accent = dialogStyle.accent
    var mode by remember { mutableIntStateOf(ReadBookConfig.autoReadMode) }
    var speed by remember {
        mutableIntStateOf(ReadBookConfig.autoReadSpeed.coerceIn(1, 120))
    }
    val speedTitle = if (mode == ReadBookConfig.AUTO_READ_MODE_TIMED) {
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
                            selected = mode != ReadBookConfig.AUTO_READ_MODE_TIMED,
                            palette = sliderPalette,
                            actionRadius = dialogStyle.actionRadius,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                mode = ReadBookConfig.AUTO_READ_MODE_SCROLL
                                ReadBookConfig.autoReadMode = mode
                            }
                        )
                        AutoReadModeButton(
                            text = stringResource(R.string.auto_read_mode_timed),
                            selected = mode == ReadBookConfig.AUTO_READ_MODE_TIMED,
                            palette = sliderPalette,
                            actionRadius = dialogStyle.actionRadius,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                mode = ReadBookConfig.AUTO_READ_MODE_TIMED
                                ReadBookConfig.autoReadMode = mode
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
                            if (ReadBookConfig.autoReadSpeed != nextSpeed) {
                                ReadBookConfig.autoReadSpeed = nextSpeed
                                onSpeedCommitted()
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

@Composable
internal fun AutoReadModeButton(
    text: String,
    selected: Boolean,
    palette: LegadoMiuixPalette,
    actionRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick),
        color = if (selected) palette.accent.copy(alpha = 0.18f) else palette.surfaceVariant,
        contentColor = if (selected) palette.accent else palette.primaryText,
        cornerRadius = actionRadius,
        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (selected) palette.accent else palette.primaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun AutoReadAction(
    iconRes: Int,
    text: String,
    textColor: Color,
    panelColor: Color,
    actionRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(actionRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = text,
            tint = textColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
