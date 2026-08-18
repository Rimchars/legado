package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogSliderGrid
import io.legado.app.ui.widget.compose.AppDialogSliderItem
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.delay

class MangaColorFilterDialog : ComposeDialogFragment() {

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private val mConfig =
        GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
            ?: MangaColorFilterConfig()
    private var latestConfig = mConfig.copy()
    private val callback get() = activity as? Callback

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    MangaColorFilterContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun MangaColorFilterContent(style: AppDialogStyle) {
        var red by rememberSaveable { mutableIntStateOf(mConfig.r) }
        var green by rememberSaveable { mutableIntStateOf(mConfig.g) }
        var blue by rememberSaveable { mutableIntStateOf(mConfig.b) }
        var alpha by rememberSaveable { mutableIntStateOf(mConfig.a) }
        var hasUserChanges by rememberSaveable { mutableStateOf(false) }

        fun currentConfig(): MangaColorFilterConfig {
            return MangaColorFilterConfig(
                r = red,
                g = green,
                b = blue,
                a = alpha,
                l = mConfig.l
            )
        }

        fun markChanged() {
            latestConfig = currentConfig()
            hasUserChanges = true
        }

        SideEffect {
            latestConfig = currentConfig()
        }

        LaunchedEffect(red, green, blue, alpha, hasUserChanges) {
            if (!hasUserChanges) {
                return@LaunchedEffect
            }
            delay(180)
            callback?.updateColorFilter(latestConfig)
        }

        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.manga_color_filter),
                    color = style.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AppDialogSliderGrid(
                    items = listOf(
                        AppDialogSliderItem(
                            title = "R",
                            value = red,
                            range = 0..255,
                            onValueChange = { value ->
                                red = value
                                markChanged()
                            }
                        ),
                        AppDialogSliderItem(
                            title = "G",
                            value = green,
                            range = 0..255,
                            onValueChange = { value ->
                                green = value
                                markChanged()
                            }
                        ),
                        AppDialogSliderItem(
                            title = "B",
                            value = blue,
                            range = 0..255,
                            onValueChange = { value ->
                                blue = value
                                markChanged()
                            }
                        ),
                        AppDialogSliderItem(
                            title = "A",
                            value = alpha,
                            range = 0..255,
                            onValueChange = { value ->
                                alpha = value
                                markChanged()
                            }
                        )
                    )
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaColorFilter = latestConfig.toJson()
    }

    interface Callback {
        fun updateColorFilter(config: MangaColorFilterConfig)
    }

}
