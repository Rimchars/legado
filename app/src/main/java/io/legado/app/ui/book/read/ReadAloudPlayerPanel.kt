package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.releaseComposeImage
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.ai.AiReadAloudBgmService
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.ai.AiReadAloudRolePreviewSegment
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager
import io.legado.app.help.readaloud.ReadAloudSpeechPlanItem
import io.legado.app.help.readaloud.ReadAloudSpeechPlanner
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composeActionShape
import io.legado.app.lib.theme.composePanelShape
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReaderSheetStyle
import io.legado.app.ui.book.read.config.SpeechVoiceRoutePickerDialog
import io.legado.app.ui.book.read.config.routeMatchesGroup
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.ReadAloudTextCleaner
import io.legado.app.ui.book.read.page.entities.TextParagraph
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.ui.book.read.page.entities.indexForChapterPosition
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ReadAloudPlayerPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun finish()
        fun openBookCharacters()
        fun onReadAloudPlayerVisibilityChanged(visible: Boolean)
    }

    enum class DisplayMode {
        Immersive,
        Scene,
        Text
    }

    enum class PanelPhase {
        Hidden,
        Collapsed,
        Opening,
        Expanded
    }

    data class ParagraphUi(
        val index: Int,
        val text: String,
        val current: Boolean,
        val key: String = index.toString(),
        val sequence: Int = index
    )

    data class TextCueUi(
        val index: Int,
        val text: String,
        val current: Boolean,
        val key: String,
        val sequence: Int,
        val chapterPosition: Int
    )

    data class SceneSegmentUi(
        val index: Int,
        val key: String,
        val text: String,
        val roleType: String,
        val characterId: Long,
        val characterName: String,
        val avatar: String,
        val emotionName: String,
        val leftSide: Boolean,
        val narrator: Boolean,
        val current: Boolean,
        val chapterPosition: Int
    )

    data class FocusTextUi(
        val key: String = "",
        val sequence: Int = 0,
        val text: String = ""
    )

    data class ChapterPreviewUi(
        val index: Int,
        val title: String,
        val indexText: String,
        val current: Boolean,
        val volume: Boolean,
        val key: String
    )

    data class TtsEngineUi(
        val title: String,
        val subtitle: String,
        val value: String,
        val selected: Boolean,
        val key: String,
        val group: SpeechVoiceEngineGroup
    )

    data class CharacterPreviewUi(
        val id: Long,
        val name: String,
        val role: String,
        val summary: String,
        val key: String
    )

    data class AudioInfoUi(
        val enabled: Boolean = false,
        val cacheKey: String = "",
        val status: String = "",
        val bgmCount: Int = 0,
        val soundEffectCount: Int = 0,
        val bgmNames: List<String> = emptyList(),
        val soundEffectNames: List<String> = emptyList(),
        val elapsedMillis: Long = 0L,
        val requestCount: Int = 0,
        val inputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        val outputTokens: Int = 0,
        val totalTokens: Int = 0,
        val error: String = "",
        val fingerprint: String = ""
    )

    data class PlayerUiState(
        val bookName: String = "",
        val author: String = "",
        val coverUrl: String? = null,
        val sourceOrigin: String? = null,
        val coverForcePath: Boolean = false,
        val coverAllowNameOverlay: Boolean? = null,
        val chapterTitle: String = "",
        val chapterIndexText: String = "",
        val playing: Boolean = false,
        val playbackPhase: String = ReadAloudPlaybackState.PHASE_STOPPED,
        val playbackBusy: Boolean = false,
        val serviceRunning: Boolean = false,
        val timerMinute: Int = 0,
        val progress: Float = 0f,
        val progressText: String = "0%",
        val paragraphText: String = "",
        val paragraphIndex: Int = 0,
        val paragraphCount: Int = 0,
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        val chapterPreview: List<ChapterPreviewUi> = emptyList(),
        val ttsEngines: List<TtsEngineUi> = emptyList(),
        val speechRoute: SpeechRoute = SpeechRoute(),
        val characterPreview: List<CharacterPreviewUi> = emptyList(),
        val audioInfo: AudioInfoUi = AudioInfoUi(),
        val nearbyParagraphs: List<ParagraphUi> = emptyList(),
        val textCues: List<TextCueUi> = emptyList(),
        val sceneSegments: List<SceneSegmentUi> = emptyList(),
        val currentCueIndex: Int = 0,
        val chapterKey: String = "",
        val paragraphKey: String = "",
        val paragraphSequence: Int = 0,
        val focusText: FocusTextUi = FocusTextUi(),
        val speechRate: Int = AppConfig.ttsSpeechRate.coerceIn(0, 45),
        val followSystemSpeechRate: Boolean = AppConfig.ttsFlowSys,
        val mode: DisplayMode = DisplayMode.Immersive,
        val foregroundActive: Boolean = true,
        val expanded: Boolean = false,
        val opening: Boolean = false,
        val panelPhase: PanelPhase = PanelPhase.Hidden,
        val readMenuVisible: Boolean = false,
        val readMenuAvoidBounds: RectF? = null,
        val openToken: Int = 0,
        val roleStatusText: String = "",
        val roleStatusRunning: Boolean = false,
        val roleStatusError: Boolean = false,
        val roleStatusVisible: Boolean = false,
        val roleDetailVisible: Boolean = false,
        val roleBlockingCurrentContent: Boolean = false,
        val roleBlockingText: String = "",
        val roleState: AiReadAloudRoleState? = null,
        val multiRoleEnabled: Boolean = AppConfig.aiReadAloudRoleEnabled,
        val speakerLoudnessEnabled: Boolean = AppConfig.readAloudSpeakerLoudnessEnabled,
        val speakerLoudnessGainPercent: Int = 100,
        val speakerLoudnessLearned: Boolean = false,
        val speakerLoudnessLearnedCount: Int = 0
    )

    private val composeView = ComposeView(context)
    private var callBack: CallBack? = null
    private var dismissedForCurrentRun = false
    private var foregroundActive = true
    private var lastChapterStart = 0
    private var roleStatusText = ""
    private var roleStatusRunning = false
    private var roleStatusError = false
    private var roleStatusUntil = 0L
    private var roleState: AiReadAloudRoleState? = null
    private var roleDetailCollapsed = false
    private var roleDetailClosed = false
    private var roleDetailKey = ""
    private var playbackPhase = ReadAloudPlaybackState.PHASE_STOPPED
    private var playbackMessage = ""
    private var playbackActualPlaying: Boolean? = null
    private var playbackBuffering = false
    private var playbackCueIndex = -1
    private var playbackChapterIndex = -1
    private var playbackChapterUrl = ""
    private var playbackCueCount = 0
    private var playbackCueChapterPosition = -1
    private var playbackCueKey = ""
    private var playbackCueText = ""
    private var playbackPlanKey = ""
    private var expanded = false
    private var opening = false
    private var panelPhase = PanelPhase.Hidden
    private var readMenuVisible = false
    private var readMenuAvoidBounds: RectF? = null
    private var openToken = 0
    private val capsuleBounds = RectF()
    private var capsulePosition by mutableStateOf(CapsulePositionState())
    private var switchingTtsEngine = false
    private var pendingTtsEngineSwitch: PendingTtsEngineSwitch? = null
    private var chapterModelCache: PlayerChapterModel? = null
    private var ttsEngineOptionsCacheKey = ""
    private var ttsEngineOptionsCache: List<TtsEngineUi> = emptyList()

    private var uiState by mutableStateOf(PlayerUiState())

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.setContent {
            ReadAloudPlayerContent(
                state = uiState,
                onClose = ::closeByUser,
                onExpand = { openFromBottom(force = true) },
                onStop = ::stopReadAloud,
                onPlayPause = { callBack?.onClickReadAloud() },
                onModeChange = ::setMode,
                onOpenChapterList = {
                    callBack?.openChapterList()
                },
                onPreviousChapter = { moveChapterFromPanel(-1) },
                onNextChapter = { moveChapterFromPanel(1) },
                onChapterSelect = ::selectChapterFromPanel,
                onOpenSettings = ::openReadAloudSetting,
                onTimerChange = ::setTimer,
                onEngineSelect = ::selectTtsEngine,
                onEngineSheetOpen = ::refreshTtsEngineOptions,
                onProgressSeek = ::seekToParagraphProgress,
                onCueSelect = ::seekToChapterPosition,
                capsulePosition = capsulePosition,
                onCapsulePositionChange = ::updateCapsulePosition,
                onCapsuleBounds = ::updateCapsuleBounds,
                onOpenCharacters = { callBack?.openBookCharacters() },
                onHideRoleDetail = ::hideRoleDetail,
                onOpenRoleDetail = ::openRoleDetail,
                onDismissRoleStatus = ::dismissRoleStatus,
                onRetryRoleAssignment = ::retryRoleAssignment,
                onRoleSegmentAssign = ::updateRoleSegmentAssignment
            )
        }
    }

    fun attach(lifecycleOwner: LifecycleOwner, callBack: CallBack) {
        this.callBack = callBack
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun open(force: Boolean = true) {
        openFromBottom(force)
    }

    fun openFromBottom(force: Boolean = true) {
        if (force) {
            dismissedForCurrentRun = false
        }
        showPanel(expand = true, animateFromBottom = true)
    }

    fun onAloudState(status: Int, autoExpand: Boolean = true) {
        when (status) {
            io.legado.app.constant.Status.PLAY -> {
                switchingTtsEngine = false
                if (playbackPhase in setOf(
                        ReadAloudPlaybackState.PHASE_PAUSED,
                        ReadAloudPlaybackState.PHASE_STOPPED,
                        ReadAloudPlaybackState.PHASE_ERROR
                    )
                ) {
                    playbackPhase = ReadAloudPlaybackState.PHASE_PREPARING
                    playbackMessage = ""
                    playbackActualPlaying = null
                    playbackBuffering = true
                }
                syncPlaybackUiState()
                if (autoExpand && !dismissedForCurrentRun && (visibility != VISIBLE || !expanded)) {
                    showPanel(animateFromBottom = true)
                }
            }

            io.legado.app.constant.Status.PAUSE -> {
                switchingTtsEngine = false
                playbackPhase = ReadAloudPlaybackState.PHASE_PAUSED
                playbackMessage = ""
                playbackActualPlaying = false
                playbackBuffering = false
                syncPlaybackUiState()
            }
            io.legado.app.constant.Status.STOP -> {
                val keepVisibleForPendingStart = visibility == VISIBLE &&
                        playbackPhase in setOf(
                            ReadAloudPlaybackState.PHASE_PREPARING,
                            ReadAloudPlaybackState.PHASE_BUFFERING
                        )
                if (switchingTtsEngine) {
                    val pendingSwitch = pendingTtsEngineSwitch
                    if (pendingSwitch?.wasPlaying == true) {
                        pendingTtsEngineSwitch = null
                        ReadAloud.play(
                            context = context,
                            play = true,
                            pageIndex = pendingSwitch.pageIndex,
                            startPos = pendingSwitch.startPos
                        )
                    } else {
                        switchingTtsEngine = false
                        pendingTtsEngineSwitch = null
                    }
                    refresh()
                    return
                }
                playbackPhase = ReadAloudPlaybackState.PHASE_STOPPED
                playbackMessage = ""
                playbackActualPlaying = false
                playbackBuffering = false
                playbackCueIndex = -1
                playbackChapterIndex = -1
                dismissedForCurrentRun = false
                if (keepVisibleForPendingStart) {
                    syncPlaybackUiState()
                    refresh()
                } else {
                    hidePanel()
                }
            }
        }
    }

    fun onTtsProgress(chapterStart: Int) {
        val normalizedStart = chapterStart.coerceAtLeast(0)
        val playbackCueEnd = playbackCueChapterPosition + playbackCueText.length.coerceAtLeast(1)
        if (lastChapterStart == normalizedStart ||
            (playbackCueIndex >= 0 && playbackCueChapterPosition >= 0 &&
                    normalizedStart in playbackCueChapterPosition..playbackCueEnd)
        ) {
            lastChapterStart = normalizedStart
            return
        }
        lastChapterStart = normalizedStart
        refresh()
    }

    fun onChapterContentChanged() {
        chapterModelCache = null
        playbackCueIndex = -1
        playbackCueCount = 0
        playbackCueChapterPosition = -1
        playbackCueKey = ""
        playbackCueText = ""
        playbackPlanKey = ""
        if (visibility != VISIBLE && !BaseReadAloudService.isRun) return
        lastChapterStart = ReadBook.durChapterPos.coerceAtLeast(0)
        refresh()
    }

    fun onPlaybackState(state: ReadAloudPlaybackState) {
        val currentBookUrl = ReadBook.book?.bookUrl.orEmpty()
        if (state.bookUrl.isNotBlank() && currentBookUrl.isNotBlank() && state.bookUrl != currentBookUrl) return
        val currentChapterIndex = ReadBook.curTextChapter?.chapter?.index ?: ReadBook.durChapterIndex
        val currentChapterUrl = ReadBook.curTextChapter?.chapter?.url.orEmpty()
        if (state.chapterIndex >= 0 && state.chapterIndex != currentChapterIndex) return
        if (state.chapterUrl.isNotBlank() && currentChapterUrl.isNotBlank() && state.chapterUrl != currentChapterUrl) return
        val cueChanged = ReadAloudPanelLayout.playbackTargetChanged(
            currentCueIndex = playbackCueIndex,
            currentChapterIndex = playbackChapterIndex,
            currentPlanKey = playbackPlanKey,
            nextCueIndex = state.cueIndex,
            nextChapterIndex = state.chapterIndex,
            nextPlanKey = state.planKey
        )
        playbackPhase = state.phase
        playbackMessage = state.message
        playbackActualPlaying = state.playing
        playbackBuffering = state.busy
        playbackCueIndex = state.cueIndex
        playbackChapterIndex = state.chapterIndex
        playbackChapterUrl = state.chapterUrl
        playbackCueCount = state.cueCount
        playbackCueChapterPosition = state.cueChapterPosition
        playbackCueKey = state.cueKey
        playbackCueText = state.cueText
        playbackPlanKey = state.planKey
        if (cueChanged) {
            refresh()
        } else {
            syncPlaybackUiState()
        }
    }

    fun onAiRoleState(state: AiReadAloudRoleState) {
        val currentBookUrl = ReadBook.book?.bookUrl ?: return
        val currentChapterIndex = ReadBook.curTextChapter?.chapter?.index ?: return
        if (state.bookUrl != currentBookUrl) return
        if (state.stage != AiReadAloudRoleState.STAGE_CURRENT ||
            state.chapterIndex != currentChapterIndex
        ) {
            return
        }
        val nextKey = "${state.bookUrl}:${state.chapterIndex}:${state.stage}"
        if (nextKey != roleDetailKey) {
            roleDetailKey = nextKey
            roleDetailCollapsed = true
            roleDetailClosed = false
        }
        if (roleState != state) {
            chapterModelCache = null
        }
        roleState = state
        roleStatusText = buildRoleStatusText(state)
        roleStatusRunning = state.running
        roleStatusError = state.status == AiReadAloudRoleState.STATUS_FAILED
        roleStatusUntil = Long.MAX_VALUE
        refresh()
    }

    private fun hideRoleDetail() {
        roleDetailCollapsed = true
        refresh()
    }

    private fun openRoleDetail() {
        roleDetailCollapsed = false
        roleDetailClosed = false
        refresh()
    }

    private fun dismissRoleStatus() {
        if (roleStatusRunning) return
        roleStatusText = ""
        roleStatusError = false
        roleStatusUntil = 0L
        roleState = null
        roleDetailCollapsed = false
        roleDetailClosed = true
        refresh()
    }

    private fun retryRoleAssignment() {
        val book = ReadBook.book ?: return
        val bookUrl = BookCharacterIdentityMigrator.migrate(book).ifBlank { book.characterBookKey() }
        val chapter = ReadBook.curTextChapter ?: return
        AiReadAloudRoleService.clearChapterCache(bookUrl, chapter.chapter.index)
        chapterModelCache = null
        roleDetailCollapsed = true
        roleDetailClosed = false
        roleStatusText = "当前章节重新分配角色中"
        roleStatusRunning = true
        roleStatusError = false
        roleStatusUntil = Long.MAX_VALUE
        val pageIndex = ReadBook.durPageIndex
        val startPos = (ReadBook.durChapterPos - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
        ReadAloud.play(context, play = true, pageIndex = pageIndex, startPos = startPos)
        refresh()
    }

    private fun updateRoleSegmentAssignment(
        segment: AiReadAloudRolePreviewSegment,
        roleType: String,
        characterId: Long,
        characterName: String
    ) {
        Coroutine.async {
            val book = ReadBook.book
                ?: return@async AiReadAloudRoleService.ManualSegmentAssignmentResult(false, "书籍不存在")
            val chapter = ReadBook.curTextChapter
                ?: return@async AiReadAloudRoleService.ManualSegmentAssignmentResult(false, "章节不存在")
            val baseCues = chapter.buildReadAloudCues(context.getPrefBoolean(PreferKey.readAloudByPage))
            AiReadAloudRoleService.updateCachedSegmentAssignment(
                book = book,
                textChapter = chapter,
                paragraphs = baseCues.map { it.text },
                assignment = AiReadAloudRoleService.ManualSegmentAssignment(
                    paragraphIndex = segment.paragraphIndex,
                    start = segment.start,
                    end = segment.end,
                    roleType = roleType,
                    characterId = characterId,
                    characterName = characterName,
                    emotionName = segment.emotionName,
                    emotionTag = segment.emotionTag
                )
            )
        }.onSuccess { result ->
            roleStatusText = result.message
            roleStatusRunning = false
            roleStatusError = !result.success
            roleStatusUntil = System.currentTimeMillis() + 4_000L
            roleDetailCollapsed = false
            roleDetailClosed = false
            if (result.success) {
                roleState = roleState?.copy(
                    status = AiReadAloudRoleState.STATUS_SUCCESS,
                    message = result.message,
                    segmentCount = result.previewSegments.size,
                    previewSource = AiReadAloudRoleState.SOURCE_RESOLVED,
                    previewSegments = result.previewSegments,
                    error = ""
                )
                chapterModelCache = null
                ReadAloudConfigChangeNotifier.notifySpeech()
            }
            refresh()
        }.onError {
            roleStatusText = it.localizedMessage ?: it.javaClass.simpleName
            roleStatusRunning = false
            roleStatusError = true
            roleStatusUntil = System.currentTimeMillis() + 4_000L
            roleDetailCollapsed = false
            roleDetailClosed = false
            refresh()
        }
    }

    private fun selectChapterFromPanel(index: Int) {
        val chapterCount = ReadBook.chapterSize
        if (chapterCount <= 0) return
        val targetIndex = index.coerceIn(0, chapterCount - 1)
        val shouldContinueReadAloud = BaseReadAloudService.isRun
        val shouldPlay = !BaseReadAloudService.pause
        prepareChapterNavigation(targetIndex)
        if (shouldContinueReadAloud) {
            ReadAloud.selectChapter(context, targetIndex, continuePlayback = shouldPlay)
            return
        }
        ReadBook.openChapter(targetIndex, upContent = true) {
            onChapterContentChanged()
        }
    }

    private fun moveChapterFromPanel(delta: Int) {
        val chapterCount = ReadBook.chapterSize
        if (chapterCount <= 0) return
        val targetIndex = (ReadBook.durChapterIndex + delta).coerceIn(0, chapterCount - 1)
        if (targetIndex == ReadBook.durChapterIndex) return
        prepareChapterNavigation(targetIndex)
        if (BaseReadAloudService.isRun) {
            val continuePlayback = BaseReadAloudService.isPlay()
            if (delta < 0) {
                ReadAloud.prevChapter(context, continuePlayback)
            } else {
                ReadAloud.nextChapter(context, continuePlayback)
            }
            return
        }
        val moved = if (delta < 0) {
            ReadBook.moveToPrevChapter(
                upContent = true,
                toLast = false,
                fromReadAloud = false
            )
        } else {
            ReadBook.moveToNextChapter(true, fromReadAloud = false)
        }
        if (!moved) {
            refresh()
            return
        }
        post { onChapterContentChanged() }
    }

    private fun prepareChapterNavigation(chapterIndex: Int) {
        chapterModelCache = null
        roleState = null
        roleStatusText = ""
        roleStatusRunning = false
        roleStatusError = false
        roleStatusUntil = 0L
        roleDetailCollapsed = false
        roleDetailClosed = false
        playbackCueIndex = -1
        playbackChapterIndex = chapterIndex
        playbackChapterUrl = ""
        playbackCueCount = 0
        playbackCueChapterPosition = -1
        playbackCueKey = ""
        playbackCueText = ""
        playbackPlanKey = ""
        lastChapterStart = 0
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        val chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterIndex + 1)
        val title = runCatching {
            ReadBook.book?.let { appDb.bookChapterDao.getChapter(it.bookUrl, chapterIndex)?.title }
        }.getOrNull().orEmpty().ifBlank { "第 ${chapterIndex + 1} 章" }
        uiState = uiState.copy(
            chapterTitle = title,
            chapterIndexText = "${chapterIndex + 1}/$chapterCount",
            chapterIndex = chapterIndex,
            chapterCount = chapterCount,
            chapterPreview = buildChapterPreview(bookUrl, chapterIndex, chapterCount),
            progress = 0f,
            progressText = "0/0",
            paragraphText = "章节加载中",
            paragraphIndex = 0,
            paragraphCount = 0,
            nearbyParagraphs = emptyList(),
            textCues = emptyList(),
            sceneSegments = emptyList(),
            currentCueIndex = 0,
            chapterKey = "$bookUrl:$chapterIndex",
            paragraphKey = "$bookUrl:$chapterIndex:loading",
            paragraphSequence = chapterIndex * 100_000,
            focusText = FocusTextUi(
                key = "$bookUrl:$chapterIndex:loading",
                sequence = chapterIndex * 100_000,
                text = "章节加载中"
            ),
            roleBlockingCurrentContent = false,
            roleBlockingText = ""
        )
    }

    private fun buildRoleStatusText(state: AiReadAloudRoleState): String {
        val prefix = if (state.stage == AiReadAloudRoleState.STAGE_NEXT) "下一章节" else "当前章节"
        val detail = when {
            state.error.isNotBlank() && state.status == AiReadAloudRoleState.STATUS_FAILED -> state.error
            state.createdCharacterCount > 0 -> "新增 ${state.createdCharacterCount} 个角色"
            state.segmentCount > 0 -> "${state.segmentCount} 个片段"
            else -> ""
        }
        val message = state.message.ifBlank {
            when (state.status) {
                AiReadAloudRoleState.STATUS_RUNNING -> "${prefix}分配角色中"
                AiReadAloudRoleState.STATUS_SUCCESS -> "${prefix}角色分配完成"
                AiReadAloudRoleState.STATUS_FALLBACK -> "已使用默认分角色"
                AiReadAloudRoleState.STATUS_FAILED -> "${prefix}角色分配失败"
                else -> "${prefix}角色已分配"
            }
        }
        return listOf(message, detail).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun onTimerChanged(minute: Int) {
        uiState = uiState.copy(timerMinute = minute)
    }

    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        syncPlaybackUiState()
    }

    private fun syncPlaybackUiState() {
        val servicePlaying = playbackActualPlaying ?: BaseReadAloudService.isPlay()
        uiState = uiState.copy(
            playing = servicePlaying,
            playbackPhase = playbackPhase,
            playbackBusy = playbackBuffering ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_PREPARING ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_BUFFERING,
            serviceRunning = BaseReadAloudService.isRun,
            foregroundActive = foregroundActive && visibility == VISIBLE
        )
    }

    fun setReadMenuVisible(visible: Boolean) {
        readMenuVisible = visible
        if (!visible) {
            readMenuAvoidBounds = null
        }
        uiState = uiState.copy(
            readMenuVisible = visible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF)
        )
    }

    fun setReadMenuAvoidBounds(bounds: RectF?) {
        readMenuVisible = bounds != null
        readMenuAvoidBounds = bounds?.let(::RectF)
        uiState = uiState.copy(
            readMenuVisible = readMenuVisible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF)
        )
    }

    fun isExpanded(): Boolean = visibility == VISIBLE && expanded

    fun isFullPanelActive(): Boolean {
        return visibility == VISIBLE && (panelPhase == PanelPhase.Opening || panelPhase == PanelPhase.Expanded)
    }

    fun close() {
        closeByUser()
    }

    fun refresh() {
        uiState = buildState(uiState.mode)
    }

    fun onReadAloudConfigChanged(scope: String?) {
        when (scope) {
            EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE,
            EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH -> {
                invalidateTtsEngineOptions()
                chapterModelCache = null
            }
            EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO -> {
                chapterModelCache = null
            }
            else -> {
                invalidateTtsEngineOptions()
                chapterModelCache = null
            }
        }
        refresh()
    }

    private fun showPanel(expand: Boolean = true, animateFromBottom: Boolean = true) {
        post {
            val wasVisible = visibility == VISIBLE
            val wasExpanded = expanded
            if (expand && animateFromBottom && !AppConfig.isEInkMode) {
                expanded = false
                opening = true
                panelPhase = PanelPhase.Opening
                openToken++
                uiState = uiState.copy(
                    foregroundActive = foregroundActive,
                    expanded = false,
                    opening = true,
                    panelPhase = PanelPhase.Opening,
                    readMenuVisible = readMenuVisible,
                    readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                    openToken = openToken
                )
                visibility = VISIBLE
                bringToFront()
                ViewCompat.requestApplyInsets(this)
                ViewCompat.requestApplyInsets(composeView)
                postOnAnimation {
                    expanded = true
                    opening = false
                    panelPhase = PanelPhase.Expanded
                    uiState = buildState(uiState.mode).copy(
                        foregroundActive = foregroundActive,
                        expanded = true,
                        opening = false,
                        panelPhase = PanelPhase.Expanded,
                        readMenuVisible = readMenuVisible,
                        readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                        openToken = openToken
                    )
                    if (!wasVisible || !wasExpanded) {
                        callBack?.onReadAloudPlayerVisibilityChanged(true)
                    }
                }
                return@post
            }
            expanded = expand
            opening = false
            panelPhase = if (expand) PanelPhase.Expanded else PanelPhase.Collapsed
            if (expand) {
                openToken++
            }
            visibility = VISIBLE
            bringToFront()
            ViewCompat.requestApplyInsets(this)
            ViewCompat.requestApplyInsets(composeView)
            uiState = buildState(uiState.mode).copy(
                foregroundActive = foregroundActive,
                expanded = expanded,
                opening = opening,
                panelPhase = panelPhase,
                readMenuVisible = readMenuVisible,
                readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
                openToken = openToken
            )
            if (!wasVisible || (!wasExpanded && expanded)) {
                callBack?.onReadAloudPlayerVisibilityChanged(true)
            }
        }
    }

    private fun hidePanel() {
        val wasVisible = visibility == VISIBLE
        expanded = false
        opening = false
        panelPhase = PanelPhase.Hidden
        visibility = GONE
        capsuleBounds.setEmpty()
        uiState = buildState(uiState.mode).copy(
            foregroundActive = false,
            expanded = false,
            opening = false,
            panelPhase = PanelPhase.Hidden
        )
        if (wasVisible) {
            callBack?.onReadAloudPlayerVisibilityChanged(false)
        }
    }

    private fun closeByUser() {
        if (BaseReadAloudService.isRun) {
            dismissedForCurrentRun = true
            hidePanel()
        } else {
            hidePanel()
        }
    }


    private fun stopReadAloud() {
        ReadAloud.stop(context)
        dismissedForCurrentRun = false
        hidePanel()
    }

    private fun updateCapsuleBounds(bounds: RectF) {
        capsuleBounds.set(bounds)
    }

    private fun updateCapsulePosition(x: Float, y: Float) {
        val current = capsulePosition
        if (current.x != x || current.y != y) {
            capsulePosition = CapsulePositionState(x, y)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility == VISIBLE && !expanded && !uiState.roleStatusVisible && !uiState.roleDetailVisible) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                !capsuleBounds.contains(ev.x, ev.y)
            ) {
                return false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openReadAloudSetting() {
        (context as? AppCompatActivity)?.showDialogFragment(ReadAloudConfigDialog())
    }

    private fun setTimer(minute: Int) {
        AppConfig.ttsTimer = minute
        ReadAloud.setTimer(context, minute)
        uiState = uiState.copy(timerMinute = minute)
    }

    private fun selectTtsEngine(value: String) {
        val route = SpeechRoute.fromTtsEngineValue(value)
        val wasRunning = BaseReadAloudService.isRun
        val wasPlaying = BaseReadAloudService.isPlay()
        val pageIndex = ReadBook.durPageIndex
        val startPos = ReadBook.curTextChapter
            ?.getReadLength(pageIndex)
            ?.let { (ReadBook.durChapterPos - it).coerceAtLeast(0) }
            ?: 0
        ReadBook.book?.setTtsEngine(null)
        AppConfig.ttsEngine = value
        invalidateTtsEngineOptions()
        if (wasRunning) {
            switchingTtsEngine = true
            pendingTtsEngineSwitch = PendingTtsEngineSwitch(
                wasPlaying = wasPlaying,
                pageIndex = pageIndex,
                startPos = startPos
            )
        } else {
            switchingTtsEngine = false
            pendingTtsEngineSwitch = null
        }
        ReadAloud.upReadAloudClass()
        route.engineValue.toLongOrNull()
            ?.let { appDb.httpTTSDao.get(it) }
            ?.takeIf { !it.loginUrl.isNullOrBlank() && it.getLoginInfo().isNullOrBlank() }
            ?.let { httpTts ->
                context.startActivity<SourceLoginActivity> {
                    putExtra("type", "httpTts")
                    putExtra("key", httpTts.id.toString())
                }
            }
        uiState = buildState(uiState.mode)
    }


    private fun setMode(mode: DisplayMode) {
        val targetMode = if (!AppConfig.aiReadAloudRoleEnabled && mode == DisplayMode.Scene) {
            DisplayMode.Immersive
        } else {
            mode
        }
        if (uiState.mode != targetMode) {
            uiState = uiState.copy(mode = targetMode)
        }
    }

    private fun seekToParagraphProgress(progress: Float) {
        val cues = buildChapterModel()?.cues.orEmpty()
        if (cues.isEmpty()) return
        val targetIndex = if (cues.size == 1) {
            0
        } else {
            (progress.coerceIn(0f, 1f) * (cues.size - 1)).roundToInt()
        }.coerceIn(0, cues.lastIndex)
        seekToChapterPosition(cues[targetIndex].chapterPosition)
    }

    private fun seekToChapterPosition(chapterPosition: Int) {
        val chapter = ReadBook.curTextChapter ?: return
        val targetPos = chapterPosition.coerceAtLeast(0)
        val pageIndex = chapter.getPageIndexByCharIndex(targetPos)
        if (pageIndex < 0) return
        val startPos = (targetPos - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
        val model = buildChapterModel()
        val targetCueIndex = model?.indexForChapterPosition(targetPos) ?: -1
        ReadBook.durChapterPos = targetPos
        if (BaseReadAloudService.isRun && targetCueIndex >= 0) {
            ReadAloud.moveToCue(
                context = context,
                cueIndex = targetCueIndex,
                chapterPosition = targetPos,
                play = true
            )
        } else {
            ReadAloud.play(
                context = context,
                play = true,
                pageIndex = pageIndex,
                startPos = startPos
            )
        }
        lastChapterStart = targetPos
        uiState = buildState(uiState.mode)
    }

    private fun buildChapterModel(): PlayerChapterModel? {
        val book = ReadBook.book ?: return null
        val chapter = ReadBook.curTextChapter ?: return null
        val chapterSequence = chapter.chapter.index
        val readAloudByPage = context.getPrefBoolean(PreferKey.readAloudByPage)
        val paragraphs = chapter.getParagraphs(false)
        val baseCues = chapter.buildReadAloudCues(readAloudByPage)
        val cueTexts = baseCues.map { cue -> cue.text }
        val multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled
        val exactRoleCacheKey = if (multiRoleEnabled) {
            AiReadAloudRoleService.cacheKeyFor(book, chapter, cueTexts)
        } else {
            null
        }
        val exactRoleCache = exactRoleCacheKey?.let { appDb.aiReadAloudRoleCacheDao.get(it) }
        val roleCache = if (multiRoleEnabled) {
            AiReadAloudRoleService.cacheForPlayback(book, chapter, cueTexts) ?: exactRoleCache
        } else {
            null
        }
        val roleCacheKey = roleCache?.cacheKey ?: exactRoleCacheKey
        val roleCacheReady = roleCache?.status == AiReadAloudRoleCache.STATUS_SUCCESS &&
                roleCache.segmentsJson.isNotBlank()
        val roleCacheRunning = multiRoleEnabled &&
                !roleCacheReady &&
                exactRoleCache?.status == AiReadAloudRoleCache.STATUS_RUNNING &&
                AiReadAloudRoleService.isRunningCacheActive(exactRoleCacheKey, exactRoleCache.updatedAt)
        val audioInfo = buildAudioInfo(book.bookUrl, chapterSequence)
        val coverDisplay = CoverDisplayResolver.resolve(book)
        val coverUrl = coverDisplay.path
        val cueFingerprint = buildString {
            append(baseCues.size)
            append(':')
            append(baseCues.firstOrNull()?.key.orEmpty())
            append(':')
            append(baseCues.lastOrNull()?.key.orEmpty())
            append(':')
            append(baseCues.sumOf { it.text.length })
        }
        val modelKey = listOf(
            book.bookUrl,
            book.name,
            book.author,
            book.origin,
            coverDisplay.loadKey,
            chapterSequence.toString(),
            chapter.chapter.url.orEmpty(),
            chapter.chapter.title,
            chapter.chaptersSize.toString(),
            ReadBook.chapterSize.toString(),
            readAloudByPage.toString(),
            multiRoleEnabled.toString(),
            cueFingerprint,
            roleCacheKey.orEmpty(),
            roleCache?.status.orEmpty(),
            roleCache?.updatedAt?.toString().orEmpty(),
            roleCache?.characterHash.orEmpty(),
            roleCache?.voiceHash.orEmpty(),
            roleCache?.segmentsJson?.length?.toString().orEmpty(),
            roleCache?.segmentsJson?.hashCode()?.toString().orEmpty(),
            audioInfo.fingerprint
        ).joinToString("|")
        chapterModelCache?.takeIf { it.key == modelKey }?.let { return it }
        val chapterKey = "${book.bookUrl}:$chapterSequence"
        val characterBookKey = book.characterBookKey()
        val speechPlan = ReadAloudSpeechPlanner.build(
            bookUrl = characterBookKey,
            chapter = chapter,
            baseCues = baseCues,
            multiRoleEnabled = multiRoleEnabled,
            roleCacheKey = roleCacheKey
        )
        val planKey = ReadAloudSpeechPlanner.planKey(
            bookUrl = characterBookKey,
            chapter = chapter,
            cues = speechPlan.cues,
            roleCacheKey = roleCacheKey
        )
        val totalLength = chapter.lastPage
            ?.let { it.chapterPosition + it.charSize }
            ?.coerceAtLeast(1)
            ?: 1
        return PlayerChapterModel(
            key = modelKey,
            bookName = book.name.ifBlank { context.getString(R.string.book_name) },
            author = book.author,
            coverUrl = coverUrl,
            sourceOrigin = coverDisplay.sourceOrigin,
            coverForcePath = coverDisplay.forcePath,
            coverAllowNameOverlay = coverDisplay.allowNameOverlay,
            chapterTitle = chapter.chapter.title.ifBlank { "当前章节" },
            chapterIndexText = "${chapterSequence + 1}/${chapter.chaptersSize.coerceAtLeast(chapterSequence + 1)}",
            chapterSequence = chapterSequence,
            chapterKey = chapterKey,
            planKey = planKey,
            chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            totalLength = totalLength,
            paragraphs = paragraphs,
            baseCues = baseCues,
            cues = speechPlan.cues,
            cuePositions = speechPlan.cues.map { it.chapterPosition }.toIntArray(),
            speechItems = speechPlan.items,
            textCues = speechPlan.cues.toTextCueUi(chapterKey, chapterSequence),
            sceneSegments = if (multiRoleEnabled) speechPlan.items.toSceneSegmentUi(chapterKey) else emptyList(),
            roleCacheKey = roleCacheKey,
            roleCacheReady = roleCacheReady,
            roleCacheRunning = roleCacheRunning,
            chapterPreview = buildChapterPreview(book.bookUrl, chapterSequence, ReadBook.chapterSize),
            characterPreview = buildCharacterPreview(
                BookCharacterIdentityMigrator.migrate(book).ifBlank { book.characterBookKey() }
            ),
            audioInfo = audioInfo
        ).also {
            chapterModelCache = it
        }
    }

    private fun buildState(mode: DisplayMode): PlayerUiState {
        val multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled
        val effectiveMode = if (!multiRoleEnabled && mode == DisplayMode.Scene) {
            DisplayMode.Immersive
        } else {
            mode
        }
        val model = buildChapterModel()
        val paragraphs = model?.paragraphs.orEmpty()
        val cues = model?.cues.orEmpty()
        val chapterSequence = model?.chapterSequence ?: ReadBook.durChapterIndex
        val chapterKey = model?.chapterKey ?: "${ReadBook.book?.bookUrl.orEmpty()}:$chapterSequence"
        val totalLength = model?.totalLength ?: 1
        val playbackPlanCurrent = playbackPlanKey.isBlank() ||
                model?.planKey.isNullOrBlank() ||
                model?.planKey == playbackPlanKey
        val playbackCurrent = playbackCueIndex >= 0 &&
                playbackChapterIndex == chapterSequence &&
                playbackPlanCurrent &&
                playbackPhase != ReadAloudPlaybackState.PHASE_STOPPED &&
                playbackPhase != ReadAloudPlaybackState.PHASE_ERROR
        val playbackStart = playbackCueChapterPosition
            .takeIf { playbackCurrent && it >= 0 }
        val chapterStart = when {
            playbackStart != null -> playbackStart
            lastChapterStart > 0 -> lastChapterStart
            else -> ReadBook.durChapterPos
        }.coerceIn(0, totalLength)
        val paragraphIndex = paragraphs.indexForChapterPosition(chapterStart)
        val modelCueIndex = model?.indexForChapterPosition(chapterStart)
            ?: cues.indexForChapterPosition(chapterStart)
        val cueIndex = if (playbackCurrent) playbackCueIndex else modelCueIndex
        val cue = cues.getOrNull(cueIndex)
        val paragraph = paragraphs.getOrNull(paragraphIndex)
        val paragraphText = playbackCueText
            .takeIf { playbackCurrent && it.isNotBlank() }
            ?: cue?.text
            ?: paragraph?.text?.cleanReadAloudText().orEmpty()
        val paragraphCount = if (playbackCurrent && playbackCueCount > 0) playbackCueCount else cues.size
        val displayCueIndex = cueIndex.coerceIn(0, (paragraphCount - 1).coerceAtLeast(0))
        val paragraphProgress = when {
            paragraphCount <= 1 || cueIndex < 0 -> 0f
            else -> (displayCueIndex.toFloat() / (paragraphCount - 1).toFloat()).coerceIn(0f, 1f)
        }
        val paragraphProgressText = if (paragraphCount > 0 && cueIndex >= 0) {
            "${displayCueIndex + 1}/$paragraphCount"
        } else {
            "0/0"
        }
        val paragraphSequence = chapterSequence * 100_000 + displayCueIndex.coerceAtLeast(0)
        val paragraphKey = playbackCueKey
            .takeIf { playbackCurrent && it.isNotBlank() }
            ?: "$chapterKey:${cue?.chapterPosition ?: paragraph?.chapterPosition ?: cueIndex}"
        val sentenceSourceText = playbackCueText
            .takeIf { playbackCurrent && it.isNotBlank() }
            ?: cue?.text
            ?: paragraph?.text
        val sentenceStart = playbackStart
            ?: cue?.chapterPosition
            ?: paragraph?.chapterPosition
            ?: 0
        val sentence = sentenceSourceText
            ?.focusSentenceAt(chapterStart - sentenceStart)
            ?: (0 to paragraphText)
        val focusText = FocusTextUi(
            key = "$paragraphKey:${sentence.first}:${sentence.second.hashCode()}",
            sequence = paragraphSequence * 1_000 + sentence.first.coerceAtLeast(0),
            text = sentence.second.ifBlank { paragraphText.ifBlank { "暂无当前段落" } }
        )
        val nearby = emptyList<ParagraphUi>()
        val textCues = model?.textCues.orEmpty()
        val sceneSegments = if (multiRoleEnabled) model?.sceneSegments.orEmpty() else emptyList()
        if (multiRoleEnabled &&
            model?.roleCacheReady == true &&
            roleStatusRunning &&
            (roleState == null ||
                    roleState?.bookUrl == ReadBook.book?.bookUrl &&
                    roleState?.chapterIndex == chapterSequence &&
                    roleState?.stage == AiReadAloudRoleState.STAGE_CURRENT)
        ) {
            roleStatusText = ""
            roleStatusRunning = false
            roleStatusError = false
            roleStatusUntil = 0L
            if (roleState?.running == true) {
                roleState = null
            }
        }
        val currentRoleState = roleState?.takeIf {
            multiRoleEnabled &&
                    it.stage == AiReadAloudRoleState.STAGE_CURRENT &&
                    it.bookUrl == ReadBook.book?.bookUrl &&
                    it.chapterIndex == chapterSequence
        }
        val currentRoleRunning = currentRoleState?.running == true || model?.roleCacheRunning == true
        val roleBlockingCurrentContent = multiRoleEnabled &&
                model?.roleCacheKey != null &&
                currentRoleRunning &&
                !model.roleCacheReady
        val speechRoute = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
        val currentSpeechItem = model?.speechItems?.getOrNull(displayCueIndex)
        val loudnessInfo = ReadAloudSpeakerLoudnessManager.infoFor(
            currentSpeechItem,
            currentSpeechItem?.route ?: speechRoute
        )
        val timerMinute = BaseReadAloudService.timeMinute
        val servicePlaying = playbackActualPlaying ?: BaseReadAloudService.isPlay()
        val roleEventVisible = multiRoleEnabled &&
                roleStatusText.isNotBlank() &&
                !roleDetailClosed &&
                (roleStatusRunning || roleStatusUntil > System.currentTimeMillis())
        return PlayerUiState(
            bookName = model?.bookName.orEmpty(),
            author = model?.author.orEmpty(),
            coverUrl = model?.coverUrl,
            sourceOrigin = model?.sourceOrigin,
            coverForcePath = model?.coverForcePath ?: false,
            coverAllowNameOverlay = model?.coverAllowNameOverlay,
            chapterTitle = model?.chapterTitle.orEmpty(),
            chapterIndexText = model?.chapterIndexText.orEmpty(),
            playing = servicePlaying,
            playbackPhase = playbackPhase,
            playbackBusy = playbackBuffering ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_PREPARING ||
                    playbackPhase == ReadAloudPlaybackState.PHASE_BUFFERING,
            serviceRunning = BaseReadAloudService.isRun,
            timerMinute = timerMinute,
            progress = paragraphProgress,
            progressText = paragraphProgressText,
            paragraphText = paragraphText,
            paragraphIndex = if (cueIndex >= 0) cueIndex + 1 else 0,
            paragraphCount = paragraphCount,
            chapterIndex = chapterSequence.coerceAtLeast(0),
            chapterCount = model?.chapterCount ?: ReadBook.chapterSize.coerceAtLeast(chapterSequence + 1),
            chapterPreview = model?.chapterPreview.orEmpty(),
            ttsEngines = buildTtsEngineOptions(),
            speechRoute = speechRoute,
            characterPreview = model?.characterPreview.orEmpty(),
            audioInfo = model?.audioInfo ?: AudioInfoUi(enabled = AppConfig.aiReadAloudBgmEnabled),
            nearbyParagraphs = nearby,
            textCues = textCues,
            sceneSegments = sceneSegments,
            currentCueIndex = displayCueIndex.coerceAtLeast(0),
            chapterKey = chapterKey,
            paragraphKey = paragraphKey,
            paragraphSequence = paragraphSequence,
            focusText = focusText,
            speechRate = AppConfig.ttsSpeechRate.coerceIn(0, 45),
            followSystemSpeechRate = AppConfig.ttsFlowSys,
            mode = effectiveMode,
            foregroundActive = foregroundActive && visibility == VISIBLE,
            expanded = expanded,
            opening = opening,
            panelPhase = panelPhase,
            readMenuVisible = readMenuVisible,
            readMenuAvoidBounds = readMenuAvoidBounds?.let(::RectF),
            openToken = openToken,
            roleStatusText = roleStatusText,
            roleStatusRunning = roleStatusRunning,
            roleStatusError = roleStatusError,
            roleStatusVisible = roleEventVisible && roleDetailCollapsed,
            roleDetailVisible = roleEventVisible && !roleDetailCollapsed,
            roleBlockingCurrentContent = roleBlockingCurrentContent,
            roleBlockingText = currentRoleState?.message
                ?.ifBlank { roleStatusText }
                ?.ifBlank { "当前章节角色分配中" }
                ?: "当前章节角色分配中",
            roleState = roleState.takeIf { multiRoleEnabled },
            multiRoleEnabled = multiRoleEnabled,
            speakerLoudnessEnabled = AppConfig.readAloudSpeakerLoudnessEnabled,
            speakerLoudnessGainPercent = (loudnessInfo.gain * 100f).roundToInt(),
            speakerLoudnessLearned = loudnessInfo.learned,
            speakerLoudnessLearnedCount = ReadAloudSpeakerLoudnessManager.learnedSpeakerCount()
        )
    }

    private fun PlayerChapterModel.indexForChapterPosition(chapterPosition: Int): Int {
        if (cuePositions.isEmpty()) return -1
        var low = 0
        var high = cuePositions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = cuePositions[mid]
            when {
                value < chapterPosition -> low = mid + 1
                value > chapterPosition -> high = mid - 1
                else -> return mid
            }
        }
        return (low - 1).coerceIn(0, cuePositions.lastIndex)
    }

    private fun List<TextParagraph>.indexForChapterPosition(chapterPosition: Int): Int {
        if (isEmpty()) return -1
        var low = 0
        var high = lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val paragraph = this[mid]
            when {
                chapterPosition < paragraph.chapterPosition -> high = mid - 1
                chapterPosition > paragraph.chapterIndices.last -> low = mid + 1
                else -> return mid
            }
        }
        return (low - 1).coerceIn(0, lastIndex)
    }

    private fun List<TextParagraph>.nearbyParagraphs(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 4).coerceAtLeast(0)
        val end = (currentIndex + 4).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            val paragraph = this[index]
            ParagraphUi(
                index = index + 1,
                text = paragraph.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${paragraph.chapterPosition}:${paragraph.realNum}",
                sequence = chapterSequence * 100_000 + index
            )
        }
    }

    private fun List<ReadAloudCue>.nearbyCueParagraphs(
        currentIndex: Int,
        chapterKey: String,
        chapterSequence: Int
    ): List<ParagraphUi> {
        if (isEmpty() || currentIndex !in indices) return emptyList()
        val start = (currentIndex - 4).coerceAtLeast(0)
        val end = (currentIndex + 4).coerceAtMost(lastIndex)
        return (start..end).map { index ->
            val cue = this[index]
            ParagraphUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = index == currentIndex,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterSequence * 100_000 + index
            )
        }
    }

    private fun List<ReadAloudCue>.toTextCueUi(
        chapterKey: String,
        chapterSequence: Int
    ): List<TextCueUi> {
        return mapIndexed { index, cue ->
            TextCueUi(
                index = index + 1,
                text = cue.text.cleanReadAloudText(),
                current = false,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterSequence * 100_000 + index,
                chapterPosition = cue.chapterPosition
            )
        }
    }

    private fun buildChapterPreview(
        bookUrl: String?,
        currentIndex: Int,
        chapterCount: Int
    ): List<ChapterPreviewUi> {
        if (bookUrl.isNullOrBlank() || chapterCount <= 0) return emptyList()
        val start = (currentIndex - 5).coerceAtLeast(0)
        val end = (currentIndex + 6).coerceAtMost(chapterCount - 1)
        return runCatching {
            appDb.bookChapterDao.getChapterList(bookUrl, start, end).map { chapter ->
                ChapterPreviewUi(
                    index = chapter.index,
                    title = chapter.title.ifBlank { "未命名章节" },
                    indexText = if (chapter.isVolume) {
                        "卷"
                    } else {
                        "${chapter.index + 1}/$chapterCount"
                    },
                    current = chapter.index == currentIndex,
                    volume = chapter.isVolume,
                    key = "$bookUrl:${chapter.index}:${chapter.title}"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildTtsEngineOptions(): List<TtsEngineUi> {
        val httpTtsList = runCatching { appDb.httpTTSDao.all }.getOrDefault(emptyList())
        val cacheKey = buildTtsEngineOptionsCacheKey(httpTtsList)
        if (ttsEngineOptionsCacheKey == cacheKey && ttsEngineOptionsCache.isNotEmpty()) {
            return ttsEngineOptionsCache
        }
        val currentRoute = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
        return SpeechVoiceCatalogRepository
            .allGroups(context, httpTtsList)
            .map { group ->
            TtsEngineUi(
                    title = group.title,
                    subtitle = group.subtitle,
                    value = group.engineValue,
                    selected = routeMatchesGroup(currentRoute, group),
                    key = group.key,
                    group = group
            )
        }.also {
            ttsEngineOptionsCacheKey = cacheKey
            ttsEngineOptionsCache = it
        }
    }

    private fun invalidateTtsEngineOptions() {
        ttsEngineOptionsCacheKey = ""
        ttsEngineOptionsCache = emptyList()
    }

    private fun buildTtsEngineOptionsCacheKey(httpTtsList: List<HttpTTS>): String {
        return buildString {
            append(ReadAloud.ttsEngine.orEmpty())
            httpTtsList.forEach { httpTts ->
                append('|')
                append(httpTts.id)
                append(':')
                append(httpTts.lastUpdateTime)
                append(':')
                append(httpTts.name.hashCode())
                append(':')
                append(httpTts.url.hashCode())
                append(':')
                append(MD5Utils.md5Encode(httpTts.speakersJson))
                append(':')
                append(MD5Utils.md5Encode(httpTts.emotionsJson))
            }
            append("|speakerGroups=")
            append(runCatching {
                val groups = appDb.readAloudSpeakerGroupDao.groups()
                    .joinToString(";") { "${it.id}:${it.name}:${it.enabled}:${it.updatedAt}" }
                val items = appDb.readAloudSpeakerGroupDao.items()
                    .joinToString(";") {
                        "${it.groupId}:${it.engineType}:${it.engineValue}:${it.speakerName}:${it.toneID}:${it.updatedAt}"
                    }
                MD5Utils.md5Encode("$groups\n$items")
            }.getOrDefault(""))
        }
    }

    private fun refreshTtsEngineOptions() {
        invalidateTtsEngineOptions()
        uiState = buildState(uiState.mode)
    }

    private fun buildCharacterPreview(bookUrl: String?): List<CharacterPreviewUi> {
        if (bookUrl.isNullOrBlank()) return emptyList()
        return runCatching {
            appDb.bookCharacterDao.characters(bookUrl).take(8).map { character ->
                CharacterPreviewUi(
                    id = character.id,
                    name = character.displayName(),
                    role = character.roleLabel(),
                    summary = character.previewSummary(),
                    key = "character:${character.id}"
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildAudioInfo(bookUrl: String?, chapterIndex: Int): AudioInfoUi {
        if (bookUrl.isNullOrBlank() || chapterIndex < 0) {
            return AudioInfoUi(enabled = AppConfig.aiReadAloudBgmEnabled)
        }
        val cached = runCatching {
            AiReadAloudBgmService.cachedAudioInfoForChapter(bookUrl, chapterIndex)
        }.getOrNull()
        val usage = runCatching {
            appDb.aiReadAloudUsageRecordDao
                .list(AiReadAloudUsageRecord.TYPE_AUDIO, bookUrl, limit = 80)
                .firstOrNull { it.chapterIndex == chapterIndex }
        }.getOrNull()
        val fingerprint = buildString {
            append(AppConfig.aiReadAloudBgmEnabled)
            append('|')
            append(cached?.cacheKey.orEmpty())
            append('|')
            append(cached?.status.orEmpty())
            append('|')
            append(cached?.assignmentCount ?: 0)
            append('|')
            append(cached?.soundEffectCount ?: 0)
            append('|')
            append(cached?.updatedAt ?: 0L)
            append('|')
            append(usage?.id ?: 0L)
            append('|')
            append(usage?.totalTokens ?: 0)
        }
        return AudioInfoUi(
            enabled = AppConfig.aiReadAloudBgmEnabled,
            cacheKey = cached?.cacheKey.orEmpty(),
            status = cached?.status.orEmpty(),
            bgmCount = cached?.assignmentCount ?: 0,
            soundEffectCount = cached?.soundEffectCount ?: 0,
            bgmNames = cached?.bgmTrackNames.orEmpty(),
            soundEffectNames = cached?.soundEffectTrackNames.orEmpty(),
            elapsedMillis = usage?.elapsedMillis ?: 0L,
            requestCount = usage?.requestCount ?: 0,
            inputTokens = usage?.inputTokens ?: 0,
            cachedInputTokens = usage?.cachedInputTokens ?: 0,
            outputTokens = usage?.outputTokens ?: 0,
            totalTokens = usage?.totalTokens ?: 0,
            error = usage?.error.orEmpty(),
            fingerprint = fingerprint
        )
    }

    private fun List<ReadAloudSpeechPlanItem>.toSceneSegmentUi(
        chapterKey: String
    ): List<SceneSegmentUi> {
        return map { item ->
            SceneSegmentUi(
                index = item.index,
                key = "$chapterKey:scene:${item.index}:${item.sourceCueIndex}:${item.sourceStart}:${item.sourceEnd}",
                text = item.cue.text.cleanReadAloudText(),
                roleType = item.roleType,
                characterId = item.characterId,
                characterName = item.characterName,
                avatar = item.avatar,
                emotionName = item.emotionName,
                leftSide = item.leftSide,
                narrator = item.narrator,
                current = false,
                chapterPosition = item.cue.chapterPosition
            )
        }
    }


    private fun BookCharacter.previewSummary(): String {
        return listOf(identity, skills, attributes, biography)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(80)
            .ifBlank { "暂无角色摘要" }
    }

    private fun String.focusSentenceAt(offset: Int): Pair<Int, String> {
        if (isBlank()) return 0 to ""
        val cursor = offset.coerceIn(0, lastIndex.coerceAtLeast(0))
        fun isBoundary(char: Char): Boolean {
            return char == '。' || char == '！' || char == '？' ||
                    char == '!' || char == '?' || char == ';' || char == '；' ||
                    char == '\n'
        }
        fun isClosingQuote(char: Char): Boolean {
            return char == '”' || char == '’' || char == '」' || char == '』' ||
                    char == ')' || char == '）' || char == ']' || char == '】'
        }
        var start = cursor
        while (start > 0 && !isBoundary(this[start - 1])) {
            start--
        }
        while (start < length && this[start].isWhitespace()) {
            start++
        }
        var end = cursor
        while (end < length && !isBoundary(this[end])) {
            end++
        }
        if (end < length) {
            end++
        }
        while (end < length && isClosingQuote(this[end])) {
            end++
        }
        val safeStart = start.coerceIn(0, end.coerceAtLeast(0))
        return safeStart to substring(safeStart, end.coerceIn(safeStart, length)).cleanReadAloudText()
    }

    private fun String.cleanReadAloudText(): String {
        return ReadAloudTextCleaner.cleanInlineText(this)
            .ifBlank { "暂无当前段落" }
    }
}

@Composable
private fun ReadAloudPlayerContent(
    state: ReadAloudPlayerPanel.PlayerUiState,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    onPlayPause: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onTimerChange: (Int) -> Unit,
    onEngineSelect: (String) -> Unit,
    onEngineSheetOpen: () -> Unit,
    onProgressSeek: (Float) -> Unit,
    onCueSelect: (Int) -> Unit,
    capsulePosition: CapsulePositionState,
    onCapsulePositionChange: (Float, Float) -> Unit,
    onCapsuleBounds: (RectF) -> Unit,
    onOpenCharacters: () -> Unit,
    onHideRoleDetail: () -> Unit,
    onOpenRoleDetail: () -> Unit,
    onDismissRoleStatus: () -> Unit,
    onRetryRoleAssignment: () -> Unit,
    onRoleSegmentAssign: (AiReadAloudRolePreviewSegment, String, Long, String) -> Unit
) {
    val palette = ReaderSheetStyle.resolve(LocalContext.current)
    val colors = rememberPlayerColors(palette)
    val density = LocalDensity.current
    var activeSheet by remember { mutableStateOf(PlayerSheet.None) }
    var sheetVisible by remember { mutableStateOf(false) }
    val animateTextChanges = !AppConfig.isEInkMode && state.foregroundActive
    val animatePanelChanges = !AppConfig.isEInkMode && state.foregroundActive
    var sceneFullscreen by remember { mutableStateOf(false) }
    var sceneFullscreenTopBarHeight by remember { mutableStateOf(0.dp) }
    var editingRoleSegment by remember { mutableStateOf<AiReadAloudRolePreviewSegment?>(null) }
    val sceneFullscreenActive = sceneFullscreen &&
            state.mode == ReadAloudPlayerPanel.DisplayMode.Scene &&
            state.panelPhase == ReadAloudPlayerPanel.PanelPhase.Expanded &&
            !state.roleBlockingCurrentContent
    val sceneFullscreenTopInset = if (sceneFullscreenActive) {
        (sceneFullscreenTopBarHeight + 8.dp).coerceAtLeast(58.dp)
    } else {
        0.dp
    }
    LaunchedEffect(state.mode, state.panelPhase, state.roleBlockingCurrentContent) {
        if (state.mode != ReadAloudPlayerPanel.DisplayMode.Scene ||
            state.panelPhase != ReadAloudPlayerPanel.PanelPhase.Expanded ||
            state.roleBlockingCurrentContent
        ) {
            sceneFullscreen = false
        }
    }
    LaunchedEffect(sceneFullscreenActive) {
        if (sceneFullscreenActive) {
            sheetVisible = false
        }
    }
    PlayerBackHandler(enabled = sceneFullscreenActive) {
        sceneFullscreen = false
    }
    val sheetEnter = if (animatePanelChanges) {
        fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(240, easing = FastOutSlowInEasing)) { height -> height / 6 } +
                expandVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                )
    } else {
        fadeIn(tween(1)) + expandVertically(tween(1), expandFrom = Alignment.Top)
    }
    val sheetExit = if (animatePanelChanges) {
        shrinkVertically(
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        ) +
                slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { height -> height / 8 } +
                fadeOut(tween(170, easing = FastOutSlowInEasing))
    } else {
        shrinkVertically(tween(1), shrinkTowards = Alignment.Top) + fadeOut(tween(1))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.panelPhase == ReadAloudPlayerPanel.PanelPhase.Expanded,
            enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(260, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it } +
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CoverAtmosphereBackdrop(
                    state = state,
                    colors = colors,
                    animateFluid = !sceneFullscreenActive
                )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val short = maxHeight < 660.dp
            val veryShort = maxHeight < 560.dp
            val sidePadding = when {
                landscape -> 34.dp
                maxWidth < 360.dp -> 18.dp
                else -> 24.dp
            }
            val topPadding = if (veryShort) 10.dp else 18.dp
            val bottomPadding = if (veryShort) 16.dp else 26.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = sidePadding, end = sidePadding, top = topPadding, bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = !sceneFullscreenActive,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(180, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                            fadeOut(tween(140, easing = FastOutSlowInEasing))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MinimalHeader(
                            mode = state.mode,
                            multiRoleEnabled = state.multiRoleEnabled,
                            colors = colors,
                            onClose = onClose,
                            onOpenSettings = onOpenSettings,
                            onModeChange = {
                                sheetVisible = false
                                if (it == state.mode && it == ReadAloudPlayerPanel.DisplayMode.Scene) {
                                    sceneFullscreen = true
                                } else {
                                    sceneFullscreen = false
                                    onModeChange(it)
                                }
                            }
                        )
                        RoleAssignmentStatus(state = state, colors = colors, onOpen = onOpenRoleDetail)
                        Spacer(modifier = Modifier.height(if (veryShort) 4.dp else 10.dp))
                    }
                }
                if (landscape) {
                    LandscapePlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
                        sceneFullscreen = sceneFullscreenActive,
                        sceneFullscreenTopInset = sceneFullscreenTopInset,
                        animateTextChanges = animateTextChanges,
                        onCueSelect = onCueSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    PortraitPlayerBody(
                        state = state,
                        colors = colors,
                        short = short,
                        veryShort = veryShort,
                        sceneFullscreen = sceneFullscreenActive,
                        sceneFullscreenTopInset = sceneFullscreenTopInset,
                        animateTextChanges = animateTextChanges,
                        onCueSelect = onCueSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
                AnimatedVisibility(
                    visible = !sceneFullscreenActive,
                    enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(180, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                            fadeOut(tween(140, easing = FastOutSlowInEasing))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 14.dp))
                        MinimalProgress(state, colors, onProgressSeek)
                        Spacer(modifier = Modifier.height(if (veryShort) 8.dp else 12.dp))
                        PlayerControlDock(
                            state = state,
                            colors = colors,
                            activeSheet = if (sheetVisible) activeSheet else PlayerSheet.None,
                            onSheetChange = { sheet ->
                                if (sheetVisible && activeSheet == sheet) {
                                    sheetVisible = false
                                } else {
                                    if (sheet == PlayerSheet.Engine) {
                                        onEngineSheetOpen()
                                    }
                                    activeSheet = sheet
                                    sheetVisible = true
                                }
                            },
                            onPlayPause = onPlayPause,
                            onPreviousChapter = onPreviousChapter,
                            onNextChapter = onNextChapter,
                            onOpenChapterList = onOpenChapterList
                        )
                    }
                }
            }
            if (sheetVisible && activeSheet != PlayerSheet.None) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeSheet) {
                            detectTapGestures {
                                sheetVisible = false
                            }
                        }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(
                        start = sidePadding,
                        end = sidePadding,
                        bottom = bottomPadding + 138.dp
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = sheetVisible && activeSheet != PlayerSheet.None,
                    enter = sheetEnter,
                    exit = sheetExit
                ) {
                    PlayerSheetPanel(
                        sheet = activeSheet,
                        state = state,
                        colors = colors,
                        onOpenChapterList = onOpenChapterList,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onChapterSelect = onChapterSelect,
                        onTimerChange = onTimerChange,
                        onEngineSelect = onEngineSelect,
                        onOpenCharacters = onOpenCharacters
                    )
                }
            }
            AnimatedVisibility(
                visible = sceneFullscreenActive,
                enter = fadeIn(tween(160, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(130, easing = FastOutSlowInEasing))
            ) {
                SceneFullscreenTopBar(
                    title = state.chapterTitle,
                    colors = colors,
                    onExit = { sceneFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = sidePadding, end = sidePadding, top = topPadding)
                        .onGloballyPositioned { coordinates ->
                            sceneFullscreenTopBarHeight = with(density) {
                                coordinates.size.height.toDp()
                            }
                        }
                )
            }
        }
    }
        }
        RoleAssignmentProgressDialog(
            state = state,
            colors = colors,
            onHide = onHideRoleDetail,
            onDismiss = onDismissRoleStatus,
            onRetry = onRetryRoleAssignment,
            onEditSegment = { editingRoleSegment = it }
        )
        editingRoleSegment?.let { segment ->
            RoleSegmentEditDialog(
                state = state,
                segment = segment,
                colors = colors,
                onDismiss = { editingRoleSegment = null },
                onSave = { roleType, characterId, characterName ->
                    editingRoleSegment = null
                    onRoleSegmentAssign(segment, roleType, characterId, characterName)
                }
            )
        }
    }
}

private enum class PlayerSheet {
    None,
    Chapter,
    Timer,
    Engine,
    Characters
}

private data class LyricsTarget(
    val key: String,
    val sequence: Int,
    val paragraphs: List<ReadAloudPlayerPanel.ParagraphUi>
)

internal data class PlayerColors(
    val night: Boolean,
    val background: Color,
    val panel: Color,
    val panelStrong: Color,
    val panelBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val subtleText: Color,
    val accent: Color,
    val accentText: Color,
    val fluidA: Color,
    val fluidB: Color,
    val fluidC: Color
)

internal data class CapsulePositionState(
    val x: Float? = null,
    val y: Float? = null
)

internal const val READ_ALOUD_CAPSULE_WIDTH_DP = 146
internal const val READ_ALOUD_CAPSULE_HEIGHT_DP = 54

private data class PendingTtsEngineSwitch(
    val wasPlaying: Boolean,
    val pageIndex: Int,
    val startPos: Int
)

private data class PlayerChapterModel(
    val key: String,
    val bookName: String,
    val author: String,
    val coverUrl: String?,
    val sourceOrigin: String?,
    val coverForcePath: Boolean,
    val coverAllowNameOverlay: Boolean,
    val chapterTitle: String,
    val chapterIndexText: String,
    val chapterSequence: Int,
    val chapterKey: String,
    val planKey: String,
    val chapterCount: Int,
    val totalLength: Int,
    val paragraphs: List<TextParagraph>,
    val baseCues: List<ReadAloudCue>,
    val cues: List<ReadAloudCue>,
    val cuePositions: IntArray,
    val speechItems: List<ReadAloudSpeechPlanItem>,
    val textCues: List<ReadAloudPlayerPanel.TextCueUi>,
    val sceneSegments: List<ReadAloudPlayerPanel.SceneSegmentUi>,
    val roleCacheKey: String?,
    val roleCacheReady: Boolean,
    val roleCacheRunning: Boolean,
    val chapterPreview: List<ReadAloudPlayerPanel.ChapterPreviewUi>,
    val characterPreview: List<ReadAloudPlayerPanel.CharacterPreviewUi>,
    val audioInfo: ReadAloudPlayerPanel.AudioInfoUi
)

@Composable
internal fun rememberPlayerColors(palette: ReaderSheetStyle.Palette): PlayerColors {
    val accent = Color(palette.accentColor)
    val night = AppConfig.isNightTheme || !ColorUtils.isColorLight(palette.surface)
    val panel = if (night) {
        Color(ColorUtils.blendColors(palette.panel, android.graphics.Color.BLACK, 0.50f))
    } else {
        Color(ColorUtils.blendColors(palette.panel, android.graphics.Color.WHITE, 0.82f))
    }
    val panelStrong = if (night) {
        Color(ColorUtils.blendColors(palette.panelStrong, android.graphics.Color.BLACK, 0.62f))
    } else {
        Color(ColorUtils.blendColors(palette.panelStrong, android.graphics.Color.WHITE, 0.88f))
    }
    return PlayerColors(
        night = night,
        background = if (night) {
            Color(ColorUtils.blendColors(palette.surface, android.graphics.Color.BLACK, 0.68f))
        } else {
            Color(ColorUtils.blendColors(palette.surface, android.graphics.Color.WHITE, 0.72f))
        },
        panel = panel,
        panelStrong = panelStrong,
        panelBorder = Color.Transparent,
        primaryText = Color(palette.textColor).copy(alpha = 0.94f),
        secondaryText = Color(palette.secondaryTextColor).copy(alpha = 0.88f),
        subtleText = Color(palette.secondaryTextColor).copy(alpha = 0.66f),
        accent = accent,
        accentText = if (ColorUtils.isColorLight(palette.accentColor)) Color.Black else Color.White,
        fluidA = accent,
        fluidB = Color(ColorUtils.blendColors(palette.accentColor, 0xFF9E5A2A.toInt(), 0.38f)),
        fluidC = Color(ColorUtils.blendColors(palette.primaryColor, 0xFF24505A.toInt(), 0.42f))
    )
}

@Composable
private fun CoverAtmosphereBackdrop(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    animateFluid: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        CoverBackdropImage(
            state = state,
            alpha = 0.30f,
            modifier = Modifier.fillMaxSize()
        )
        if (animateFluid) {
            FluidBackdropLayer(state, colors)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (colors.night) {
                            listOf(
                                Color.Black.copy(alpha = 0.32f),
                                colors.background.copy(alpha = 0.54f),
                                Color.Black.copy(alpha = 0.66f)
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.40f),
                                colors.background.copy(alpha = 0.76f),
                                Color.White.copy(alpha = 0.82f)
                            )
                        }
                    )
                )
        )
    }
}

@Composable
private fun CoverBackdropImage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = {
            AppCompatImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
                this.alpha = alpha
            }
        },
        update = {
            it.alpha = alpha
            val loadKey = listOf(
                state.coverUrl.orEmpty(),
                state.sourceOrigin.orEmpty(),
                AppConfig.useDefaultCover.toString(),
                state.coverForcePath.toString()
            ).joinToString("|")
            if (it.tag != loadKey) {
                it.tag = loadKey
                BookCover.loadBlur(
                    context = it.context,
                    path = state.coverUrl,
                    loadOnlyWifi = false,
                    sourceOrigin = state.sourceOrigin,
                    forcePath = state.coverForcePath
                ).into(it)
            }
        },
        onRelease = { it.releaseComposeImage() }
    )
}

@Composable
internal fun ReadAloudCapsule(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    capsulePosition: CapsulePositionState,
    onPositionChange: (Float, Float) -> Unit,
    onBounds: (RectF) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val capsuleHeight = READ_ALOUD_CAPSULE_HEIGHT_DP.dp
        val capsuleWidth = READ_ALOUD_CAPSULE_WIDTH_DP.dp
        val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
        val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
        val sidePx = with(density) { 18.dp.toPx() }
        val bottomGapPx = with(density) { 28.dp.toPx() }
        val avoidGapPx = with(density) { 12.dp.toPx() }
        val safeInsets = WindowInsets.safeDrawing
        val safeLeft = safeInsets.getLeft(density, layoutDirection).toFloat()
        val safeRight = safeInsets.getRight(density, layoutDirection).toFloat()
        val safeTop = safeInsets.getTop(density).toFloat()
        val safeBottom = safeInsets.getBottom(density).toFloat()
        val minX = safeLeft + sidePx
        val maxX = (widthPx - safeRight - capsuleWidthPx - sidePx).coerceAtLeast(minX)
        val minY = safeTop + sidePx
        val maxY = (heightPx - safeBottom - capsuleHeightPx - bottomGapPx).coerceAtLeast(minY)
        var baseOffsetX by remember { mutableStateOf(capsulePosition.x ?: minX) }
        var baseOffsetY by remember { mutableStateOf(capsulePosition.y ?: maxY) }
        var dragging by remember { mutableStateOf(false) }
        val coverRotation = remember { Animatable(0f) }
        val rotating = state.playing && state.foregroundActive && !AppConfig.isEInkMode
        val clampedBaseX = baseOffsetX.coerceIn(minX, maxX)
        val clampedBaseY = baseOffsetY.coerceIn(minY, maxY)
        val menuAvoidBounds = state.readMenuAvoidBounds
        val displayOffsetY = if (dragging) {
            clampedBaseY
        } else {
            menuAvoidBounds?.let { bounds ->
                val capsuleLeft = clampedBaseX
                val capsuleRight = clampedBaseX + capsuleWidthPx
                val capsuleTop = clampedBaseY
                val capsuleBottom = clampedBaseY + capsuleHeightPx
                val horizontallyOverlapped = capsuleRight > bounds.left && capsuleLeft < bounds.right
                val verticallyOverlapped = capsuleBottom + avoidGapPx > bounds.top && capsuleTop < bounds.bottom
                if (horizontallyOverlapped && verticallyOverlapped) {
                    (bounds.top - capsuleHeightPx - avoidGapPx).coerceIn(minY, maxY)
                } else {
                    clampedBaseY
                }
            } ?: clampedBaseY
        }
        val animatedX by animateFloatAsState(
            targetValue = clampedBaseX,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleX"
        )
        val animatedY by animateFloatAsState(
            targetValue = displayOffsetY,
            animationSpec = tween(if (dragging) 1 else 260, easing = FastOutSlowInEasing),
            label = "readAloudCapsuleY"
        )
        val renderedX = if (dragging) clampedBaseX else animatedX
        val renderedY = if (dragging) displayOffsetY else animatedY
        val latestClampedBaseX by rememberUpdatedState(clampedBaseX)
        val latestDisplayOffsetY by rememberUpdatedState(displayOffsetY)
        LaunchedEffect(widthPx, heightPx, minX, maxX, minY, maxY) {
            baseOffsetX = if (baseOffsetX + capsuleWidthPx / 2f < widthPx / 2f) minX else maxX
            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
            onPositionChange(baseOffsetX, baseOffsetY)
        }
        LaunchedEffect(capsulePosition.x, capsulePosition.y) {
            if (!dragging) {
                capsulePosition.x?.let { baseOffsetX = it }
                capsulePosition.y?.let { baseOffsetY = it }
            }
        }
        LaunchedEffect(rotating) {
            if (rotating) {
                while (true) {
                    val start = coverRotation.value % 360f
                    coverRotation.snapTo(start)
                    coverRotation.animateTo(
                        targetValue = start + 360f,
                        animationSpec = tween(durationMillis = 16000, easing = LinearEasing)
                    )
                }
            }
        }
        ReadAloudCapsuleSurface(
            state = state,
            colors = colors,
            onPlayPause = onPlayPause,
            onExpand = onExpand,
            onClose = onClose,
            modifier = Modifier
                .offset { IntOffset(renderedX.roundToInt(), renderedY.roundToInt()) }
                .width(capsuleWidth)
                .height(capsuleHeight)
                .onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    onBounds(RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
                }
                .pointerInput(widthPx, heightPx, minX, maxX, minY, maxY) {
                    detectDragGestures(
                        onDragStart = {
                            baseOffsetX = latestClampedBaseX
                            baseOffsetY = latestDisplayOffsetY
                            dragging = true
                            onPositionChange(baseOffsetX, baseOffsetY)
                        },
                        onDragEnd = {
                            dragging = false
                            baseOffsetX = if (baseOffsetX + capsuleWidthPx / 2f < widthPx / 2f) {
                                minX
                            } else {
                                maxX
                            }
                            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
                            onPositionChange(baseOffsetX, baseOffsetY)
                        },
                        onDragCancel = {
                            dragging = false
                            baseOffsetX = baseOffsetX.coerceIn(minX, maxX)
                            baseOffsetY = baseOffsetY.coerceIn(minY, maxY)
                            onPositionChange(baseOffsetX, baseOffsetY)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        baseOffsetX = (baseOffsetX + dragAmount.x).coerceIn(minX, maxX)
                        baseOffsetY = (baseOffsetY + dragAmount.y).coerceIn(minY, maxY)
                    }
                },
            coverRotation = coverRotation.value
        )
    }
}

@Composable
internal fun ReadAloudCapsuleSurface(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onCoverLongPress: (() -> Unit)? = null,
    onClose: () -> Unit,
    coverOnEnd: Boolean = false,
    modifier: Modifier = Modifier,
    coverRotation: Float = 0f,
    horizontalPadding: Dp = 6.dp,
    shadowElevation: Dp = 12.dp
) {
    val coverButtonSize = 42.dp
    val playButtonSize = 38.dp
    val closeButtonSize = 34.dp
    val coverButton: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(coverButtonSize)
                .graphicsLayer { rotationZ = coverRotation % 360f }
                .clip(CircleShape)
                .background(colors.panel)
                .pointerInput(onExpand, onCoverLongPress) {
                    detectTapGestures(
                        onTap = { onExpand() },
                        onLongPress = { onCoverLongPress?.invoke() }
                    )
                }
        ) {
            BookCoverImage(
                path = state.coverUrl,
                name = state.bookName,
                author = state.author,
                sourceOrigin = state.sourceOrigin,
                modifier = Modifier.fillMaxSize(),
                style = CoverImageView.CoverStyle.FLAT,
                loadOnlyWifi = false,
                preferThumb = true,
                forcePath = state.coverForcePath,
                allowNameOverlay = state.coverAllowNameOverlay,
                fillBounds = true
            )
        }
    }
    val playButton: @Composable () -> Unit = {
        Surface(
            onClick = onPlayPause,
            modifier = Modifier.size(playButtonSize),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.92f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.playbackBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black.copy(alpha = 0.72f),
                        trackColor = Color.Black.copy(alpha = 0.12f)
                    )
                } else {
                    Icon(
                        painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.86f),
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
    val closeButton: @Composable () -> Unit = {
        Surface(
            onClick = onClose,
            modifier = Modifier.size(closeButtonSize),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_x),
                    contentDescription = null,
                    tint = colors.primaryText,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.panelStrong,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = shadowElevation
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (coverOnEnd) {
                closeButton()
                playButton()
                coverButton()
            } else {
                coverButton()
                playButton()
                closeButton()
            }
        }
    }
}

@Composable
private fun FluidBackdropLayer(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors
) {
    val visualActive = state.foregroundActive && state.playing
    val shift = if (visualActive) {
        val transition = rememberInfiniteTransition(label = "readAloudFluid")
        val value by transition.animateFloat(
            initialValue = -0.10f,
            targetValue = 0.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fluidShift"
        )
        value
    } else {
        0f
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    colors.fluidC.copy(alpha = 0.28f),
                    Color.Transparent,
                    colors.fluidB.copy(alpha = 0.22f)
                ),
                start = Offset(size.width * (0.05f + shift), 0f),
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidA.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width * (0.20f + shift), size.height * 0.06f),
                radius = size.maxDimension * 0.70f
            ),
            radius = size.maxDimension * 0.70f,
            center = Offset(size.width * (0.20f + shift), size.height * 0.06f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidB.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * (0.78f - shift), size.height * 0.50f),
                radius = size.maxDimension * 0.58f
            ),
            radius = size.maxDimension * 0.58f,
            center = Offset(size.width * (0.78f - shift), size.height * 0.50f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.fluidC.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * (0.34f + shift * 0.5f), size.height * 0.98f),
                radius = size.maxDimension * 0.62f
            ),
            radius = size.maxDimension * 0.62f,
            center = Offset(size.width * (0.34f + shift * 0.5f), size.height * 0.98f)
        )
    }
}

@Composable
private fun MinimalHeader(
    mode: ReadAloudPlayerPanel.DisplayMode,
    multiRoleEnabled: Boolean,
    colors: PlayerColors,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            HeaderIconButton(
                icon = R.drawable.ic_expand_more,
                contentDescription = "收起",
                colors = colors,
                onClick = onClose
            )
        }
        Box(contentAlignment = Alignment.Center) {
            ModeSwitch(mode, multiRoleEnabled, colors, onModeChange)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderIconButton(
                icon = R.drawable.ic_settings,
                contentDescription = "设置",
                colors = colors,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun SceneFullscreenTopBar(
    title: String,
    colors: PlayerColors,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIconButton(
            icon = R.drawable.ic_expand_more,
            contentDescription = "退出全屏",
            colors = colors,
            onClick = onExit
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.ifBlank { "当前章节" },
                color = colors.primaryText.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun PlayerBackHandler(
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

@Composable
private fun RoleAssignmentStatus(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpen: () -> Unit
) {
    AnimatedVisibility(
        visible = state.roleStatusVisible,
        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                expandVertically(tween(180, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) +
                fadeOut(tween(140, easing = FastOutSlowInEasing))
    ) {
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.roleStatusRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                    trackColor = Color.Transparent
                )
            }
            Text(
                text = state.roleStatusText,
                color = if (state.roleStatusError) Color(0xFFFF8A9A) else colors.primaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RoleAssignmentProgressDialog(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onEditSegment: (AiReadAloudRolePreviewSegment) -> Unit
) {
    val roleState = state.roleState ?: return
    PlayerBackHandler(enabled = state.roleDetailVisible, onBack = onHide)
    AnimatedVisibility(
        visible = state.roleDetailVisible,
        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.96f),
        exit = scaleOut(tween(150, easing = FastOutSlowInEasing), targetScale = 0.98f) +
                fadeOut(tween(140, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (state.roleStatusRunning) 0.18f else 0.10f))
                .systemBarsPadding()
                .padding(22.dp),
            contentAlignment = Alignment.Center
        ) {
            val shape = LocalContext.current.composePanelShape()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp),
                shape = shape,
                color = colors.panelStrong.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, colors.panelBorder),
                shadowElevation = 14.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                text = "多角色分配明细",
                                color = colors.primaryText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = roleState.chapterTitle.ifBlank { state.chapterTitle },
                                color = colors.subtleText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RoleDialogAction("重新分配", colors, onRetry)
                            RoleDialogAction("隐藏", colors, onHide)
                            if (!state.roleStatusRunning) RoleDialogAction("关闭", colors, onDismiss)
                        }
                    }
                    RoleAssignmentSummary(state, roleState, colors)
                    AudioAssignmentSummary(state.audioInfo, colors)
                    RolePreviewList(
                        state = state,
                        roleState = roleState,
                        colors = colors,
                        onEditSegment = onEditSegment,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleDialogAction(
    text: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = colors.panel.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Text(
            text = text,
            color = colors.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun AudioAssignmentSummary(
    audioInfo: ReadAloudPlayerPanel.AudioInfoUi,
    colors: PlayerColors
) {
    if (!audioInfo.enabled) return
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = "智能音频",
            color = colors.subtleText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoleSummaryChip(
                if (audioInfo.cacheKey.isBlank()) "未分析" else audioStatusLabel(audioInfo.status),
                colors
            )
            RoleSummaryChip("配乐 ${audioInfo.bgmCount}", colors)
            RoleSummaryChip("音效 ${audioInfo.soundEffectCount}", colors)
            if (audioInfo.elapsedMillis > 0L) {
                RoleSummaryChip("耗时 ${formatRoleElapsed(audioInfo.elapsedMillis)}", colors)
            }
            if (audioInfo.requestCount > 0) {
                RoleSummaryChip("${audioInfo.requestCount} 次请求", colors)
            }
            if (audioInfo.totalTokens > 0) {
                RoleSummaryChip("Token ${audioInfo.totalTokens}", colors)
            }
            if (audioInfo.inputTokens > 0) {
                val uncached = (audioInfo.inputTokens - audioInfo.cachedInputTokens).coerceAtLeast(0)
                RoleSummaryChip("输入 ${audioInfo.inputTokens}", colors)
                if (uncached > 0) RoleSummaryChip("未命中 $uncached", colors)
            }
            if (audioInfo.cachedInputTokens > 0) {
                RoleSummaryChip("缓存命中 ${audioInfo.cachedInputTokens}", colors)
            }
            if (audioInfo.outputTokens > 0) {
                RoleSummaryChip("输出 ${audioInfo.outputTokens}", colors)
            }
        }
        val names = buildList {
            if (audioInfo.bgmNames.isNotEmpty()) {
                add("配乐：" + audioInfo.bgmNames.joinToString("、"))
            }
            if (audioInfo.soundEffectNames.isNotEmpty()) {
                add("音效：" + audioInfo.soundEffectNames.joinToString("、"))
            }
        }.joinToString("  ")
        if (names.isNotBlank()) {
            Text(
                text = names,
                color = colors.secondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (audioInfo.error.isNotBlank()) {
            Text(
                text = audioInfo.error,
                color = Color(0xFFFF8A9A),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun audioStatusLabel(status: String): String {
    return when (status) {
        "running" -> "分析中"
        "success" -> "已完成"
        "failed" -> "失败"
        else -> "已缓存"
    }
}

@Composable
private fun RoleAssignmentSummary(
    playerState: ReadAloudPlayerPanel.PlayerUiState,
    roleState: AiReadAloudRoleState,
    colors: PlayerColors
) {
    val preview = roleState.previewSegments
    val unmatchedCount = preview.count {
        roleSegmentNeedsVoice(it) && !it.matchedCharacter
    }
    val unboundVoiceCount = preview.count {
        roleSegmentNeedsVoice(it) && it.matchedCharacter && roleSegmentVoiceLabel(it).isBlank()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RoleSummaryChip(roleStatusLabel(roleState), colors)
        RoleSummaryChip(roleSourceLabel(roleState.previewSource), colors)
        RoleSummaryChip("${preview.size} 片段", colors)
        if (roleState.elapsedMillis > 0L) {
            RoleSummaryChip("耗时 ${formatRoleElapsed(roleState.elapsedMillis)}", colors)
        }
        if (roleState.requestCount > 0) {
            RoleSummaryChip("${roleState.requestCount} 次请求", colors)
        }
        if (roleState.totalTokens > 0) {
            RoleSummaryChip("Token ${roleState.totalTokens}", colors)
        } else if (roleState.requestCount > 0 && !roleState.running) {
            RoleSummaryChip("Token 未返回", colors)
        }
        if (roleState.inputTokens > 0) {
            val uncached = (roleState.inputTokens - roleState.cachedInputTokens).coerceAtLeast(0)
            RoleSummaryChip("输入 ${roleState.inputTokens}", colors)
            if (uncached > 0) {
                RoleSummaryChip("未命中 $uncached", colors)
            }
        }
        if (roleState.outputTokens > 0) {
            RoleSummaryChip("输出 ${roleState.outputTokens}", colors)
        }
        if (roleState.cachedInputTokens > 0) {
            RoleSummaryChip("缓存命中 ${roleState.cachedInputTokens}", colors)
        }
        if (roleState.createdCharacterCount > 0) {
            RoleSummaryChip("新增 ${roleState.createdCharacterCount} 角色", colors)
        }
        if (playerState.speakerLoudnessEnabled) {
            RoleSummaryChip("响度 ${playerState.speakerLoudnessGainPercent}%", colors)
            if (playerState.speakerLoudnessLearnedCount > 0) {
                RoleSummaryChip("已学习 ${playerState.speakerLoudnessLearnedCount} 发言人", colors)
            } else if (!playerState.speakerLoudnessLearned) {
                RoleSummaryChip("响度学习中", colors)
            }
        }
        if (unmatchedCount > 0) {
            RoleSummaryChip("$unmatchedCount 未匹配", colors, danger = true)
        }
        if (unboundVoiceCount > 0) {
            RoleSummaryChip("$unboundVoiceCount 未绑定发言人", colors, danger = true)
        }
    }
    Text(
        text = playerState.roleStatusText,
        color = colors.secondaryText,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 10.dp)
    )
    if (roleState.error.isNotBlank()) {
        Text(
            text = roleState.error,
            color = Color(0xFFFF8A9A),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun formatRoleElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds < 60L) {
        "${seconds}s"
    } else {
        "${seconds / 60L}m${seconds % 60L}s"
    }
}

@Composable
private fun RoleSummaryChip(
    text: String,
    colors: PlayerColors,
    danger: Boolean = false
) {
    Text(
        text = text,
        color = if (danger) Color(0xFFFF8A9A) else colors.secondaryText,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 5.dp)
    )
}

@Composable
private fun RolePreviewList(
    state: ReadAloudPlayerPanel.PlayerUiState,
    roleState: AiReadAloudRoleState,
    colors: PlayerColors,
    onEditSegment: (AiReadAloudRolePreviewSegment) -> Unit,
    modifier: Modifier = Modifier
) {
    val preview = roleState.previewSegments
    if (preview.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
            color = colors.panel.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, colors.panelBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.roleStatusRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent
                    )
                }
                Text(
                    text = when {
                        state.roleStatusRunning -> "等待 AI 返回具体片段分配"
                        roleState.error.isNotBlank() -> "没有可展示的分配片段"
                        else -> "暂无分配明细"
                    },
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
        return
    }
    val groups = remember(preview) {
        preview.groupBy { it.paragraphIndex }
            .toSortedMap()
            .map { it.key to it.value.sortedWith(compareBy<AiReadAloudRolePreviewSegment> { segment -> segment.start }.thenBy { segment -> segment.end }) }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 430.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.first }) { (paragraphIndex, segments) ->
            RolePreviewParagraphGroup(
                paragraphIndex = paragraphIndex,
                segments = segments,
                current = paragraphIndex == state.currentCueIndex,
                colors = colors,
                onEditSegment = onEditSegment
            )
        }
    }
}

@Composable
private fun RolePreviewParagraphGroup(
    paragraphIndex: Int,
    segments: List<AiReadAloudRolePreviewSegment>,
    current: Boolean,
    colors: PlayerColors,
    onEditSegment: (AiReadAloudRolePreviewSegment) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = if (current) colors.accent.copy(alpha = 0.12f) else colors.panel.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "段落 ${paragraphIndex + 1}",
                    color = if (current) colors.accent else colors.subtleText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (current) {
                    Text("当前朗读", color = colors.accent, fontSize = 11.sp)
                }
            }
            segments.forEach { segment ->
                RolePreviewSegmentRow(segment, colors, onClick = { onEditSegment(segment) })
            }
        }
    }
}

@Composable
private fun RolePreviewSegmentRow(
    segment: AiReadAloudRolePreviewSegment,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    val danger = roleSegmentNeedsVoice(segment) && !segment.matchedCharacter
    val unboundVoice = roleSegmentNeedsVoice(segment) &&
            segment.matchedCharacter &&
            roleSegmentVoiceLabel(segment).isBlank()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = colors.panelStrong.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoleTypeChip(segment.roleType, colors, danger)
                Text(
                    text = roleSpeakerLine(segment),
                    color = when {
                        danger -> Color(0xFFFFB3BB)
                        unboundVoice -> Color(0xFFFFC46B)
                        else -> colors.secondaryText
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(segment.confidence * 100).roundToInt()}%",
                    color = colors.subtleText,
                    fontSize = 11.sp
                )
            }
            Text(
                text = segment.text.ifBlank { "空片段" },
                color = colors.primaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
private fun RoleSegmentEditDialog(
    state: ReadAloudPlayerPanel.PlayerUiState,
    segment: AiReadAloudRolePreviewSegment,
    colors: PlayerColors,
    onDismiss: () -> Unit,
    onSave: (String, Long, String) -> Unit
) {
    PlayerBackHandler(enabled = true, onBack = onDismiss)
    val characters by produceState<List<BookCharacter>>(initialValue = emptyList(), state.bookName, state.author) {
        value = withContext(Dispatchers.IO) {
            val bookKey = ReadBook.book?.characterBookKey().orEmpty()
            if (bookKey.isBlank()) emptyList() else appDb.bookCharacterDao.characters(bookKey)
        }
    }
    var roleType by remember(segment.key) {
        mutableStateOf(
            segment.roleType.takeIf { it in setOf("narrator", "character", "thought") }
                ?: "character"
        )
    }
    var query by remember(segment.key) { mutableStateOf("") }
    var selectedCharacterId by remember(segment.key) { mutableStateOf(segment.characterId) }
    var selectedCharacterName by remember(segment.key) { mutableStateOf(segment.characterName) }
    val filteredCharacters = remember(characters, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            characters
        } else {
            characters.filter { character ->
                character.displayName().contains(keyword, ignoreCase = true) ||
                        character.name.contains(keyword, ignoreCase = true) ||
                        character.identity.contains(keyword, ignoreCase = true)
            }
        }
    }
    val canSave = roleType == "narrator" || selectedCharacterId > 0L || selectedCharacterName.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .systemBarsPadding()
            .padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = LocalContext.current.composePanelShape(),
            color = colors.panelStrong.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, colors.panelBorder),
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "修改片段分配",
                            color = colors.primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "段落 ${segment.paragraphIndex + 1}",
                            color = colors.subtleText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    RoleDialogAction("取消", colors, onDismiss)
                }
                Text(
                    text = segment.text,
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("narrator", "character", "thought").forEach { type ->
                        RoleEditChoice(
                            text = roleTypeLabel(type),
                            selected = roleType == type,
                            colors = colors,
                            onClick = {
                                roleType = type
                                if (type == "narrator") {
                                    selectedCharacterId = 0L
                                    selectedCharacterName = ""
                                }
                            }
                        )
                    }
                }
                AnimatedVisibility(visible = roleType != "narrator") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text("搜索角色") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (filteredCharacters.isEmpty()) {
                                item {
                                    Text(
                                        text = "没有可选角色",
                                        color = colors.secondaryText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            } else {
                                items(filteredCharacters, key = { it.id }) { character ->
                                    RoleCharacterChoiceRow(
                                        character = character,
                                        selected = selectedCharacterId == character.id,
                                        colors = colors,
                                        onClick = {
                                            selectedCharacterId = character.id
                                            selectedCharacterName = character.name
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        onClick = {
                            if (canSave) {
                                onSave(roleType, selectedCharacterId, selectedCharacterName)
                            }
                        },
                        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
                        color = if (canSave) colors.accent.copy(alpha = 0.20f) else colors.panel.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, if (canSave) colors.accent.copy(alpha = 0.35f) else colors.panelBorder)
                    ) {
                        Text(
                            text = "保存",
                            color = if (canSave) colors.accent else colors.subtleText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleEditChoice(
    text: String,
    selected: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = if (selected) colors.accent.copy(alpha = 0.18f) else colors.panel.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.35f) else colors.panelBorder)
    ) {
        Text(
            text = text,
            color = if (selected) colors.accent else colors.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun RoleCharacterChoiceRow(
    character: BookCharacter,
    selected: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    val route = remember(character.speechRouteJson) { SpeechRoute.fromJson(character.speechRouteJson) }
    val voice = route.speakerName
        .ifBlank { route.toneID }
        .ifBlank { route.groupName }
        .ifBlank { if (route.isConfigured) "已绑定发言人" else "未绑定发言人" }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = if (selected) colors.accent.copy(alpha = 0.16f) else colors.panel.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.36f) else colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.displayName(),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = listOf(character.genderLabel(), character.identity, voice)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (selected) {
                Text("已选", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RoleTypeChip(
    roleType: String,
    colors: PlayerColors,
    danger: Boolean
) {
    val color = when {
        danger -> Color(0xFFFF8A9A)
        roleType == "narrator" -> colors.subtleText
        roleType == "thought" -> Color(0xFFBFA7FF)
        roleType == "character" -> colors.accent
        else -> colors.secondaryText
    }
    Surface(
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Color.Transparent)
    ) {
        Text(
            text = roleTypeLabel(roleType),
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun roleTypeLabel(roleType: String): String {
    return when (roleType) {
        "narrator" -> "旁白"
        "character" -> "角色"
        "thought" -> "心理"
        else -> "其他"
    }
}

private fun roleStatusLabel(state: AiReadAloudRoleState): String {
    return when (state.status) {
        AiReadAloudRoleState.STATUS_RUNNING -> "分配中"
        AiReadAloudRoleState.STATUS_SUCCESS -> "已完成"
        AiReadAloudRoleState.STATUS_FALLBACK -> "Fallback"
        AiReadAloudRoleState.STATUS_FAILED -> "失败"
        AiReadAloudRoleState.STATUS_SKIPPED -> "已缓存"
        else -> "待处理"
    }
}

private fun roleSourceLabel(source: String): String {
    return when (source) {
        AiReadAloudRoleState.SOURCE_AI -> "AI 原始结果"
        AiReadAloudRoleState.SOURCE_RULE -> "本地预处理"
        AiReadAloudRoleState.SOURCE_AI_CONFIRM -> "AI 确认"
        AiReadAloudRoleState.SOURCE_RESOLVED -> "已匹配角色"
        AiReadAloudRoleState.SOURCE_CACHE -> "缓存"
        AiReadAloudRoleState.SOURCE_FALLBACK -> "默认切分"
        else -> "等待结果"
    }
}

private fun roleSpeakerLine(segment: AiReadAloudRolePreviewSegment): String {
    val roleName = when {
        segment.roleType == "narrator" -> "旁白"
        segment.characterName.isBlank() -> "未识别角色"
        segment.matchedCharacter -> segment.characterName
        else -> "${segment.characterName}（未匹配）"
    }
    val voice = roleSegmentVoiceLabel(segment).takeIf { it.isNotBlank() }
        ?: if (roleSegmentNeedsVoice(segment) && segment.matchedCharacter) "未绑定发言人" else null
    val emotion = segment.emotionName.takeIf { it.isNotBlank() }
    return buildList {
        add(roleName)
        voice?.let(::add)
        emotion?.let(::add)
    }.joinToString(" · ")
}

private fun roleSegmentNeedsVoice(segment: AiReadAloudRolePreviewSegment): Boolean {
    return segment.roleType == "character" || segment.roleType == "thought"
}

private fun roleSegmentVoiceLabel(segment: AiReadAloudRolePreviewSegment): String {
    return segment.speakerName
        .ifBlank { segment.toneID }
        .ifBlank { segment.groupName }
        .ifBlank {
            when (segment.engineType) {
                SpeechRoute.ENGINE_SYSTEM -> "系统语音"
                SpeechRoute.ENGINE_HTTP -> "HTTP TTS"
                else -> ""
            }
        }
}

@Composable
private fun HeaderIconButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = colors.primaryText,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun ModeSwitch(
    mode: ReadAloudPlayerPanel.DisplayMode,
    multiRoleEnabled: Boolean,
    colors: PlayerColors,
    onModeChange: (ReadAloudPlayerPanel.DisplayMode) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    val modes = remember(multiRoleEnabled) {
        buildList {
            add(ReadAloudPlayerPanel.DisplayMode.Immersive to "沉浸")
            if (multiRoleEnabled) add(ReadAloudPlayerPanel.DisplayMode.Scene to "情景")
            add(ReadAloudPlayerPanel.DisplayMode.Text to "原文")
        }
    }
    Row(
        modifier = Modifier
            .width(if (multiRoleEnabled) 188.dp else 132.dp)
            .height(32.dp)
            .clip(actionShape)
            .background(Color.White.copy(alpha = 0.13f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        modes.forEach { (displayMode, text) ->
            ModeChip(
                text = text,
                selected = mode == displayMode,
                fullscreenHint = mode == ReadAloudPlayerPanel.DisplayMode.Scene &&
                        displayMode == ReadAloudPlayerPanel.DisplayMode.Scene,
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f)
            ) {
                onModeChange(displayMode)
            }
        }
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    fullscreenHint: Boolean,
    colors: PlayerColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = if (selected) colors.accentText else colors.secondaryText,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            if (fullscreenHint) {
                Text(
                    text = "全屏",
                    color = colors.accentText.copy(alpha = 0.78f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun PortraitPlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    veryShort: Boolean,
    sceneFullscreen: Boolean,
    sceneFullscreenTopInset: Dp,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.roleBlockingCurrentContent) {
        RoleAssigningBody(state = state, colors = colors, modifier = modifier)
        return
    }
    when (state.mode) {
        ReadAloudPlayerPanel.DisplayMode.Immersive -> ImmersivePlayerStage(
            state = state,
            colors = colors,
            short = short,
            veryShort = veryShort,
            animateTextChanges = animateTextChanges,
            modifier = modifier
        )

        ReadAloudPlayerPanel.DisplayMode.Scene -> ScenePlayerStage(
            state = state,
            colors = colors,
            compact = short,
            fullscreen = sceneFullscreen,
            fullscreenTopInset = sceneFullscreenTopInset,
            onCueSelect = onCueSelect,
            modifier = modifier
        )

        ReadAloudPlayerPanel.DisplayMode.Text -> LyricsPlayerStage(
            state = state,
            colors = colors,
            compact = short,
            maxParagraphs = if (veryShort) 5 else 7,
            currentMaxLines = if (veryShort) 4 else 6,
            animateTextChanges = animateTextChanges,
            onCueSelect = onCueSelect,
            modifier = modifier
        )
    }
}

@Composable
private fun LandscapePlayerBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    sceneFullscreen: Boolean,
    sceneFullscreenTopInset: Dp,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.roleBlockingCurrentContent) {
        RoleAssigningBody(state = state, colors = colors, modifier = modifier)
        return
    }
    val immersive = state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (immersive) {
            CoverArt(
                state = state,
                colors = colors,
                width = if (short) 136.dp else 176.dp
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            BookIdentity(
                state = state,
                colors = colors,
                centered = false,
                compact = short
            )
            Spacer(modifier = Modifier.height(if (short) 12.dp else 18.dp))
            if (state.mode == ReadAloudPlayerPanel.DisplayMode.Immersive) {
                FocusSentenceBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    animateTextChanges = animateTextChanges,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (state.mode == ReadAloudPlayerPanel.DisplayMode.Scene) {
                ScenePlayerStage(
                    state = state,
                    colors = colors,
                    compact = short,
                    fullscreen = sceneFullscreen,
                    fullscreenTopInset = sceneFullscreenTopInset,
                    onCueSelect = onCueSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )
            } else {
                LyricCueBody(
                    state = state,
                    colors = colors,
                    compact = short,
                    maxParagraphs = if (short) 5 else 7,
                    currentMaxLines = if (short) 4 else 6,
                    textAlign = TextAlign.Start,
                    animateTextChanges = animateTextChanges,
                    onCueSelect = onCueSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun RoleAssigningBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = colors.accent,
                trackColor = Color.Transparent
            )
            Text(
                text = state.roleBlockingText.ifBlank { "当前章节角色分配中" },
                color = colors.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "分配完成后再显示本章内容",
                color = colors.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ImmersivePlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    short: Boolean,
    veryShort: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CoverArt(
            state = state,
            colors = colors,
            width = when {
                veryShort -> 128.dp
                short -> 164.dp
                else -> 210.dp
            }
        )
        Spacer(modifier = Modifier.height(if (veryShort) 14.dp else 22.dp))
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = veryShort
        )
        Spacer(modifier = Modifier.height(if (veryShort) 12.dp else 22.dp))
        FocusSentenceBody(
            state = state,
            colors = colors,
            compact = short,
            animateTextChanges = animateTextChanges,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (veryShort) 84.dp else 118.dp)
        )
    }
}

@Composable
private fun LyricsPlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BookIdentity(
            state = state,
            colors = colors,
            centered = true,
            compact = compact
        )
        Spacer(modifier = Modifier.height(if (compact) 12.dp else 18.dp))
        LyricCueBody(
            state = state,
            colors = colors,
            compact = compact,
            maxParagraphs = maxParagraphs,
            currentMaxLines = currentMaxLines,
            animateTextChanges = animateTextChanges,
            onCueSelect = onCueSelect,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun ScenePlayerStage(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    fullscreen: Boolean = false,
    fullscreenTopInset: Dp = 0.dp,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = !fullscreen,
            enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                    expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = shrinkVertically(tween(150, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(120, easing = FastOutSlowInEasing))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BookIdentity(
                    state = state,
                    colors = colors,
                    centered = true,
                    compact = compact
                )
                Spacer(modifier = Modifier.height(if (compact) 10.dp else 16.dp))
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            val listState = rememberLazyListState()
            val density = LocalDensity.current
            val currentIndex = state.currentCueIndex.coerceIn(
                0,
                state.sceneSegments.lastIndex.coerceAtLeast(0)
            )
            val viewportTopInset = if (fullscreen) fullscreenTopInset.coerceAtLeast(58.dp) else 0.dp
            val visibleHeight = (maxHeight - viewportTopInset).coerceAtLeast(1.dp)
            val listTopPadding = 12.dp
            val listBottomPadding = if (fullscreen) 96.dp else 12.dp
            val currentScrollOffset = if (fullscreen) {
                with(density) {
                    -((visibleHeight * 0.5f) - if (compact) 30.dp else 36.dp)
                        .coerceAtLeast(0.dp)
                        .roundToPx()
                }
            } else {
                0
            }
            var programmaticScroll by remember(state.chapterKey) { mutableStateOf(false) }
            var lastSceneTarget by remember(state.chapterKey) { mutableStateOf<Int?>(null) }
            LaunchedEffect(state.chapterKey, fullscreen) {
                lastSceneTarget = null
            }
            LaunchedEffect(state.chapterKey, currentIndex, fullscreen) {
                val userScrolling = listState.isScrollInProgress && !programmaticScroll
                if (state.sceneSegments.isNotEmpty() && !userScrolling) {
                    val firstTarget = lastSceneTarget == null
                    val targetDistance = lastSceneTarget
                        ?.let { kotlin.math.abs(it - currentIndex) }
                        ?: 0
                    try {
                        programmaticScroll = true
                        if (firstTarget) {
                            listState.scrollToItem(currentIndex, scrollOffset = currentScrollOffset)
                        } else if (lastSceneTarget != currentIndex) {
                            if (AppConfig.isEInkMode || !state.foregroundActive || targetDistance > 8) {
                                listState.scrollToItem(currentIndex, scrollOffset = currentScrollOffset)
                            } else {
                                listState.animateScrollToItem(currentIndex, scrollOffset = currentScrollOffset)
                            }
                        }
                    } finally {
                        programmaticScroll = false
                    }
                    lastSceneTarget = currentIndex
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = viewportTopInset)
                    .clipToBounds(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = if (fullscreen) 780.dp else 760.dp),
                    contentPadding = PaddingValues(
                        top = listTopPadding,
                        bottom = listBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        when {
                            fullscreen -> 14.dp
                            compact -> 9.dp
                            else -> 12.dp
                        }
                    )
                ) {
                    items(state.sceneSegments, key = { it.key }) { segment ->
                        SceneSegmentRow(
                            segment = segment,
                            current = segment.index == currentIndex,
                            colors = colors,
                            compact = compact,
                            fullscreen = fullscreen,
                            onClick = {
                                onCueSelect(segment.chapterPosition)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneSegmentRow(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    fullscreen: Boolean,
    onClick: () -> Unit
) {
    if (segment.narrator) {
        val interactionSource = remember { MutableInteractionSource() }
        val maxWidth = if (fullscreen) 560.dp else 430.dp
        val textColor = when {
            current && fullscreen -> colors.primaryText.copy(alpha = 0.78f)
            current -> colors.secondaryText.copy(alpha = 0.86f)
            fullscreen -> colors.subtleText.copy(alpha = 0.74f)
            else -> colors.subtleText
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = segment.text,
                color = textColor,
                fontSize = when {
                    compact -> 12.sp
                    fullscreen -> 14.sp
                    else -> 13.sp
                },
                lineHeight = when {
                    compact -> 18.sp
                    fullscreen -> 23.sp
                    else -> 20.sp
                },
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                    .padding(
                        horizontal = if (fullscreen) 20.dp else 14.dp,
                        vertical = if (fullscreen) 9.dp else 6.dp
                    )
            )
        }
        return
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth(if (fullscreen) 0.88f else 0.86f)
                .align(if (segment.leftSide) Alignment.CenterStart else Alignment.CenterEnd),
            horizontalArrangement = if (segment.leftSide) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            if (segment.leftSide) {
                SceneAvatar(segment, colors, compact)
                Spacer(modifier = Modifier.width(6.dp))
                SceneBubbleArrow(leftSide = true, current = current, colors = colors)
                SceneBubble(
                    segment = segment,
                    current = current,
                    colors = colors,
                    compact = compact,
                    fullscreen = fullscreen,
                    onClick = onClick,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                SceneBubble(
                    segment = segment,
                    current = current,
                    colors = colors,
                    compact = compact,
                    fullscreen = fullscreen,
                    onClick = onClick,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SceneBubbleArrow(leftSide = false, current = current, colors = colors)
                Spacer(modifier = Modifier.width(6.dp))
                SceneAvatar(segment, colors, compact)
            }
        }
    }
}

@Composable
private fun SceneBubbleArrow(
    leftSide: Boolean,
    current: Boolean,
    colors: PlayerColors
) {
    val arrowColor = sceneBubbleFillColor(
        leftSide = leftSide,
        current = current,
        colors = colors
    )
    Canvas(
        modifier = Modifier
            .padding(top = 14.dp)
            .size(width = 8.dp, height = 14.dp)
    ) {
        val path = androidx.compose.ui.graphics.Path().apply {
            if (leftSide) {
                moveTo(size.width, 0f)
                lineTo(0f, size.height / 2f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
            }
            close()
        }
        drawPath(path, arrowColor)
    }
}

@Composable
private fun SceneBubble(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    fullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = sceneBubbleFillColor(segment.leftSide, current, colors)
    val textColor = if (segment.leftSide) colors.primaryText else colors.accentText
    val actionShape = LocalContext.current.composeActionShape()
    val maxWidth = when {
        fullscreen -> 540.dp
        compact -> 460.dp
        else -> 520.dp
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .shadow(
                elevation = if (current) 8.dp else 4.dp,
                shape = actionShape,
                clip = false
            )
    ) {
        Column(
            modifier = Modifier
                .clip(actionShape)
                .background(bubbleColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(
                    horizontal = if (fullscreen) 15.dp else 13.dp,
                    vertical = if (fullscreen) 11.dp else 10.dp
                ),
            horizontalAlignment = if (segment.leftSide) Alignment.Start else Alignment.End
        ) {
            val emotion = segment.emotionName.takeIf { it.isNotBlank() }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!segment.leftSide) {
                    emotion?.let {
                        Text(
                            text = it,
                            color = textColor.copy(alpha = 0.70f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                Text(
                    text = segment.characterName,
                    color = textColor.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (segment.leftSide) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.widthIn(max = if (compact) 230.dp else 280.dp)
                )
                if (segment.leftSide) {
                    emotion?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = it,
                            color = textColor.copy(alpha = 0.70f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                    }
                }
            }
            Text(
                text = segment.text,
                color = textColor,
                fontSize = when {
                    compact -> 14.sp
                    fullscreen -> 16.sp
                    else -> 15.sp
                },
                lineHeight = when {
                    compact -> 21.sp
                    fullscreen -> 24.sp
                    else -> 23.sp
                },
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

private fun sceneBubbleFillColor(
    leftSide: Boolean,
    current: Boolean,
    colors: PlayerColors
): Color {
    val base = if (leftSide) {
        colors.panelStrong
    } else {
        val target = if (colors.night) colors.panelStrong else colors.panel
        Color(ColorUtils.blendColors(colors.accent.toArgb(), target.toArgb(), 0.10f))
    }.copy(alpha = 1f)
    if (current) return base
    val mutedTarget = if (colors.night) colors.panel else Color.White
    return Color(ColorUtils.blendColors(base.toArgb(), mutedTarget.toArgb(), 0.18f))
}

@Composable
private fun SceneAvatar(
    segment: ReadAloudPlayerPanel.SceneSegmentUi,
    colors: PlayerColors,
    compact: Boolean
) {
    val context = LocalContext.current
    val size = if (compact) 34.dp else 38.dp
    val avatarPath = segment.avatar.ifBlank { null }
    val bitmap by produceState<Bitmap?>(initialValue = null, avatarPath, size) {
        value = null
        if (avatarPath.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val px = (size.value * context.resources.displayMetrics.density)
                    .roundToInt()
                    .coerceAtLeast(1)
                ImageLoader.loadBitmap(context.applicationContext, avatarPath)
                    .centerCrop()
                    .submit(px, px)
                    .get()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.panel),
        contentAlignment = Alignment.Center
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = segment.characterName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val initial = segment.characterName
                .trim()
                .firstOrNull()
                ?.toString()
                .orEmpty()
            if (initial.isNotBlank()) {
                Text(
                    text = initial,
                    color = colors.primaryText.copy(alpha = 0.82f),
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_bottom_person),
                    contentDescription = segment.characterName,
                    tint = colors.subtleText,
                    modifier = Modifier.size(if (compact) 19.dp else 21.dp)
                )
            }
        }
    }
}

@Composable
private fun CoverArt(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    width: Dp,
    modifier: Modifier = Modifier
) {
    BookCoverImage(
        path = state.coverUrl,
        name = state.bookName,
        author = state.author,
        sourceOrigin = state.sourceOrigin,
        modifier = modifier
            .width(width)
            .aspectRatio(0.75f),
        style = CoverImageView.CoverStyle.DETAIL,
        loadOnlyWifi = false,
        preferThumb = false,
        forcePath = state.coverForcePath,
        allowNameOverlay = state.coverAllowNameOverlay,
        fillBounds = true
    )
}

@Composable
private fun BookIdentity(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    centered: Boolean,
    compact: Boolean
) {
    Column(
        modifier = Modifier.widthIn(max = 560.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            text = state.bookName.ifBlank { "当前书籍" },
            color = colors.primaryText,
            fontSize = if (compact) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = state.chapterTitle.ifBlank { "当前章节" },
            color = colors.secondaryText,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun FocusSentenceBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    animateTextChanges: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val focus = state.focusText.takeIf { it.text.isNotBlank() }
        ?: ReadAloudPlayerPanel.FocusTextUi(
            key = state.paragraphKey,
            sequence = state.paragraphSequence,
            text = state.paragraphText.ifBlank { "暂无当前段落" }
        )
    AnimatedContent(
        targetState = focus,
        transitionSpec = {
            val direction = if (targetState.sequence >= initialState.sequence) 1 else -1
            if (animateTextChanges) {
                ((slideInVertically(tween(320)) { height -> height * direction / 3 } +
                        fadeIn(tween(220)) +
                        scaleIn(tween(320), initialScale = 0.98f)) togetherWith
                        (slideOutVertically(tween(240)) { height -> -height * direction / 4 } +
                                fadeOut(tween(160)) +
                                scaleOut(tween(240), targetScale = 1.02f)))
                    .using(SizeTransform(clip = false))
            } else {
                (fadeIn(tween(1)) togetherWith fadeOut(tween(1)))
                    .using(SizeTransform(clip = false))
            }
        },
        modifier = modifier,
        label = "readAloudFocusText"
    ) { target ->
        Text(
            text = target.text,
            color = colors.primaryText,
            fontSize = if (compact) 24.sp else 28.sp,
            lineHeight = if (compact) 33.sp else 38.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (compact) 3 else 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LyricCueBody(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    compact: Boolean,
    maxParagraphs: Int,
    currentMaxLines: Int,
    animateTextChanges: Boolean,
    onCueSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center
) {
    val cues = state.textCues.ifEmpty {
        listOf(
            ReadAloudPlayerPanel.TextCueUi(
                index = state.paragraphIndex.coerceAtLeast(1),
                text = state.paragraphText.ifBlank { "暂无当前段落" },
                current = true,
                key = state.paragraphKey.ifBlank { state.paragraphIndex.toString() },
                sequence = state.paragraphSequence,
                chapterPosition = ReadBook.durChapterPos
            )
        )
    }
    val listState = rememberLazyListState()
    var userTouching by remember(state.chapterKey) { mutableStateOf(false) }
    var programmaticScroll by remember(state.chapterKey) { mutableStateOf(false) }
    var lastCenteredTarget by remember(state.chapterKey) { mutableStateOf<Int?>(null) }
    val currentIndex = state.currentCueIndex.coerceIn(0, cues.lastIndex)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 720.dp)
    ) {
        val centerPadding = (maxHeight / 2)
            .coerceAtLeast(if (compact) 42.dp else 58.dp)

        suspend fun centerCue(index: Int, animated: Boolean) {
            if (cues.isEmpty()) return
            val targetIndex = index.coerceIn(0, cues.lastIndex)
            var targetItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }
            if (targetItem == null) {
                listState.scrollToItem(targetIndex, scrollOffset = 0)
                targetItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetIndex }
            }
            targetItem?.let { item ->
                val scrollDelta = ReadAloudPanelLayout.centeredScrollDelta(
                    viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                    viewportEndOffset = listState.layoutInfo.viewportEndOffset,
                    itemOffset = item.offset,
                    itemSize = item.size
                )
                if (kotlin.math.abs(scrollDelta) >= 1f) {
                    if (animated) {
                        listState.animateScrollBy(scrollDelta)
                    } else {
                        listState.scrollBy(scrollDelta)
                    }
                }
            }
        }

        LaunchedEffect(state.chapterKey) {
            userTouching = false
            lastCenteredTarget = null
        }
        LaunchedEffect(state.chapterKey, currentIndex) {
            val userScrolling = listState.isScrollInProgress && !programmaticScroll
            if (!userTouching && !userScrolling && cues.isNotEmpty()) {
                val firstTarget = lastCenteredTarget == null
                try {
                    programmaticScroll = true
                    centerCue(
                        index = currentIndex,
                        animated = !firstTarget && !AppConfig.isEInkMode && state.foregroundActive
                    )
                } finally {
                    programmaticScroll = false
                }
                lastCenteredTarget = currentIndex
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.chapterKey) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            userTouching = event.changes.any { it.pressed }
                        }
                    }
                },
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            horizontalAlignment = when (textAlign) {
                TextAlign.Start -> Alignment.Start
                TextAlign.End -> Alignment.End
                else -> Alignment.CenterHorizontally
            }
        ) {
            itemsIndexed(cues, key = { _, cue -> cue.key }) { _, cue ->
                LyricCueLine(
                    cue = cue,
                    current = cue.index - 1 == currentIndex,
                    colors = colors,
                    compact = compact,
                    currentMaxLines = currentMaxLines,
                    textAlign = textAlign,
                    animate = animateTextChanges,
                    onClick = {
                        userTouching = false
                        onCueSelect(cue.chapterPosition)
                    }
                )
            }
        }
    }
}

@Composable
private fun LyricCueLine(
    cue: ReadAloudPlayerPanel.TextCueUi,
    current: Boolean,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean,
    onClick: () -> Unit
) {
    val emphasis by animateFloatAsState(
        targetValue = if (current) 1f else 0f,
        animationSpec = tween(if (animate) 220 else 1),
        label = "readAloudCueEmphasis"
    )
    val fontSize = if (compact) 20.sp else 23.sp
    val lineHeight = if (compact) 28.sp else 32.sp
    Text(
        text = cue.text,
        color = colors.primaryText.copy(alpha = 0.36f + emphasis * 0.58f),
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (current) currentMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Composable
private fun LyricParagraphLine(
    paragraph: ReadAloudPlayerPanel.ParagraphUi,
    colors: PlayerColors,
    compact: Boolean,
    currentMaxLines: Int,
    textAlign: TextAlign,
    animate: Boolean
) {
    val emphasis by animateFloatAsState(
        targetValue = if (paragraph.current) 1f else 0f,
        animationSpec = tween(if (animate) 220 else 1),
        label = "readAloudLyricEmphasis"
    )
    val fontSize = when {
        compact -> 14f + emphasis * 6f
        else -> 15f + emphasis * 8f
    }
    val lineHeight = when {
        compact -> 21f + emphasis * 7f
        else -> 23f + emphasis * 9f
    }
    Text(
        text = paragraph.text,
        color = colors.primaryText.copy(alpha = 0.36f + emphasis * 0.58f),
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        fontWeight = if (paragraph.current) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = if (paragraph.current) currentMaxLines else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MinimalProgress(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onProgressSeek: (Float) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    var draggingProgress by remember(state.chapterKey) { mutableStateOf<Float?>(null) }
    fun snapProgress(value: Float): Float {
        val maxIndex = state.paragraphCount - 1
        if (maxIndex <= 0) return 0f
        return ((value.coerceIn(0f, 1f) * maxIndex).roundToInt() / maxIndex.toFloat())
            .coerceIn(0f, 1f)
    }
    val progress = draggingProgress ?: state.progress
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { draggingProgress = snapProgress(it) },
            onValueChangeFinished = {
                draggingProgress?.let(onProgressSeek)
                draggingProgress = null
            },
            enabled = state.paragraphCount > 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(actionShape),
            colors = SliderDefaults.colors(
                thumbColor = colors.primaryText,
                activeTrackColor = colors.primaryText,
                inactiveTrackColor = colors.panel,
                disabledThumbColor = colors.subtleText,
                disabledActiveTrackColor = colors.panel,
                disabledInactiveTrackColor = colors.panel
            )
        )
        if (false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.chapterIndexText.ifBlank { "章节" },
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = state.progressText,
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        }
    }
}

@Composable
private fun PlayerControlDock(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    activeSheet: PlayerSheet,
    onSheetChange: (PlayerSheet) -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapterList: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 300.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundTransportButton(
                icon = R.drawable.ic_skip_previous,
                contentDescription = "上一章",
                colors = colors,
                size = 50.dp,
                iconSize = 25.dp,
                onClick = onPreviousChapter
            )
            Surface(
                onClick = onPlayPause,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 16.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.playbackBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = Color.Black.copy(alpha = 0.72f),
                            trackColor = Color.Black.copy(alpha = 0.12f)
                        )
                    } else {
                        Icon(
                            painter = painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                            contentDescription = if (state.playing) context.getString(R.string.pause) else context.getString(R.string.audio_play),
                            tint = Color.Black.copy(alpha = 0.88f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            RoundTransportButton(
                icon = R.drawable.ic_skip_next,
                contentDescription = "下一章",
                colors = colors,
                size = 50.dp,
                iconSize = 25.dp,
                onClick = onNextChapter
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FeaturePill(
                icon = R.drawable.ic_toc,
                text = context.getString(R.string.chapter_list),
                selected = activeSheet == PlayerSheet.Chapter,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Chapter)
            }
            FeaturePill(
                icon = R.drawable.ic_time_add_24dp,
                text = if (state.timerMinute > 0) context.getString(R.string.timer_m, state.timerMinute)
                else context.getString(R.string.set_timer),
                selected = activeSheet == PlayerSheet.Timer,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Timer)
            }
            FeaturePill(
                icon = R.drawable.ic_settings,
                text = "\u5f15\u64ce",
                selected = activeSheet == PlayerSheet.Engine,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Engine)
            }
            FeaturePill(
                icon = R.drawable.ic_bottom_person_e,
                selectedIcon = R.drawable.ic_bottom_person_s,
                text = "\u89d2\u8272",
                selected = activeSheet == PlayerSheet.Characters,
                colors = colors,
                modifier = Modifier.weight(1f)
            ) {
                onSheetChange(PlayerSheet.Characters)
            }
        }
    }
}

@Composable
private fun RoundTransportButton(
    icon: Int,
    contentDescription: String,
    colors: PlayerColors,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = colors.primaryText,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun FeaturePill(
    icon: Int,
    selectedIcon: Int = icon,
    text: String,
    selected: Boolean,
    colors: PlayerColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = actionShape,
        color = if (selected) colors.accent else colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = if (selected) 8.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(if (selected) selectedIcon else icon),
                contentDescription = text,
                tint = if (selected) colors.accentText else colors.primaryText,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                color = if (selected) colors.accentText else colors.primaryText,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlayerSheetPanel(
    sheet: PlayerSheet,
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit,
    onTimerChange: (Int) -> Unit,
    onEngineSelect: (String) -> Unit,
    onOpenCharacters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val panelShape = LocalContext.current.composePanelShape()
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = panelShape,
        color = colors.panelStrong,
        border = BorderStroke(1.dp, colors.panelBorder),
        shadowElevation = 12.dp
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
        ) {
            AnimatedContent(
                targetState = sheet,
                transitionSpec = {
                    (fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 10 })
                        .togetherWith(
                            fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                                    slideOutVertically(tween(140, easing = FastOutSlowInEasing)) { -it / 12 }
                        )
                        .using(SizeTransform(clip = false))
                },
                label = "readAloudSheetContent"
            ) { targetSheet ->
                when (targetSheet) {
                    PlayerSheet.Chapter -> ChapterSheet(
                        state = state,
                        colors = colors,
                        onOpenChapterList = onOpenChapterList,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onChapterSelect = onChapterSelect
                    )
                    PlayerSheet.Timer -> TimerSheet(state, colors, onTimerChange)
                    PlayerSheet.Engine -> EngineSheet(state, colors, onEngineSelect)
                    PlayerSheet.Characters -> CharactersSheet(state, colors, onOpenCharacters)
                    PlayerSheet.None -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChapterSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenChapterList: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    val chapterCount = state.chapterCount.coerceAtLeast(1)
    val chapters = state.chapterPreview
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.chapter_list),
                    color = colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.chapterTitle.ifBlank { state.chapterIndexText },
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = "${state.chapterIndex.coerceIn(0, chapterCount - 1) + 1}/$chapterCount",
                color = colors.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 188.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = chapters,
                key = { it.key }
            ) { chapter ->
                ChapterPreviewRow(
                    chapter = chapter,
                    colors = colors,
                    shape = actionShape,
                    onClick = { onChapterSelect(chapter.index) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetActionButton(
                text = context.getString(R.string.previous_chapter),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onPreviousChapter
            )
            SheetActionButton(
                text = context.getString(R.string.chapter_list),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onOpenChapterList
            )
            SheetActionButton(
                text = context.getString(R.string.next_chapter),
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onNextChapter
            )
        }
    }
}

@Composable
private fun ChapterPreviewRow(
    chapter: ReadAloudPlayerPanel.ChapterPreviewUi,
    colors: PlayerColors,
    shape: Shape,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = !chapter.volume, onClick = onClick),
        shape = shape,
        color = when {
            chapter.current -> colors.accent
            chapter.volume -> colors.panel
            else -> colors.panel
        },
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chapter.indexText,
                color = if (chapter.current) colors.accentText else colors.subtleText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(56.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = chapter.title,
                color = if (chapter.current) colors.accentText else colors.primaryText,
                fontSize = if (chapter.volume) 12.sp else 13.sp,
                fontWeight = if (chapter.current || chapter.volume) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SheetActionButton(
    text: String,
    colors: PlayerColors,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = shape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = colors.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TimerSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onTimerChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    val times = listOf(0, 5, 10, 15, 30, 60, 90, 180)
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = context.getString(R.string.set_timer),
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        times.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { minute ->
                    val selected = state.timerMinute == minute || (state.timerMinute <= 0 && minute == 0)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { onTimerChange(minute) },
                        shape = actionShape,
                        color = if (selected) colors.accent else colors.panel
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = context.getString(R.string.timer_m, minute),
                                color = if (selected) colors.accentText else colors.primaryText,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onEngineSelect: (String) -> Unit
) {
    val actionShape = LocalContext.current.composeActionShape()
    var pickerGroupKey by remember(state.ttsEngines) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "\u6717\u8bfb\u5f15\u64ce",
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp, max = 202.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.ttsEngines, key = { it.key }) { engine ->
                EngineRow(
                    engine = engine,
                    colors = colors,
                    shape = actionShape,
                    onClick = { pickerGroupKey = engine.key }
                )
            }
        }
    }
    pickerGroupKey?.let { key ->
        SpeechVoiceRoutePickerDialog(
            title = "选择发言人",
            groups = state.ttsEngines.map { it.group },
            currentRoute = state.speechRoute,
            initialGroupKey = key,
            onDismiss = { pickerGroupKey = null },
            onRouteSelected = { route ->
                pickerGroupKey = null
                onEngineSelect(route.toJson())
            }
        )
    }
}

@Composable
private fun EngineRow(
    engine: ReadAloudPlayerPanel.TtsEngineUi,
    colors: PlayerColors,
    shape: Shape,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (engine.selected) colors.accent else colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.title,
                    color = if (engine.selected) colors.accentText else colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = if (engine.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = engine.subtitle,
                    color = if (engine.selected) colors.accentText.copy(alpha = 0.72f) else colors.subtleText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (engine.selected) {
                Text(
                    text = "\u5f53\u524d",
                    color = colors.accentText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CharactersSheet(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    onOpenCharacters: () -> Unit
) {
    val context = LocalContext.current
    val actionShape = context.composeActionShape()
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u89d2\u8272",
                color = colors.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.characterPreview.size}",
                color = colors.subtleText,
                fontSize = 12.sp
            )
        }
        if (state.characterPreview.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u6682\u65e0\u89d2\u8272",
                    color = colors.secondaryText,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 188.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.characterPreview, key = { it.key }) { character ->
                    CharacterPreviewRow(character, colors, actionShape)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            SheetActionButton(
                text = "\u5b8c\u6574\u89d2\u8272\u9875",
                colors = colors,
                shape = actionShape,
                modifier = Modifier.weight(1f),
                onClick = onOpenCharacters
            )
        }
    }
}

@Composable
private fun CharacterPreviewRow(
    character: ReadAloudPlayerPanel.CharacterPreviewUi,
    colors: PlayerColors,
    shape: Shape
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = shape,
        color = colors.panel,
        border = BorderStroke(1.dp, colors.panelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = character.role,
                color = colors.subtleText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(66.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    color = colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = character.summary,
                    color = colors.subtleText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
