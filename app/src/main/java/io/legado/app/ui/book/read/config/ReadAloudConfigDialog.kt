package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudQuotePair
import io.legado.app.help.readaloud.role.ReadAloudRolePreprocessor
import io.legado.app.help.readaloud.role.ReadAloudSoundEffectPreprocessor
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

private const val KEY_AI_READ_ALOUD_BGM_MANAGE = "aiReadAloudBgmManage"
private const val KEY_AI_READ_ALOUD_USAGE_RECORDS = "aiReadAloudUsageRecords"
private const val KEY_AI_READ_ALOUD_MODEL_ROUTING = "aiReadAloudModelRouting"
private const val KEY_READ_ALOUD_SPEAKER_MANAGE = "readAloudSpeakerManage"
private const val KEY_READ_ALOUD_LOUDNESS_RESET = "readAloudSpeakerLoudnessReset"
private const val KEY_MEDIA_BUTTON_PER_NEXT = "mediaButtonPerNext"
private const val KEY_SYS_TTS_CONFIG = "sysTtsConfig"
private const val SPEAKER_MANAGE_SUMMARY =
    "管理多角色可用发言人；未绑定或失效时朗读会自动回退到默认配音，不在正文里反复提示"

enum class ReadAloudConfigGroup(
    val title: String,
    val preferenceKeys: Set<String>
) {
    Base(
        "\u57fa\u7840",
        setOf(
            PreferKey.ignoreAudioFocus,
            PreferKey.pauseReadAloudWhilePhoneCalls,
            PreferKey.readAloudWakeLock,
            PreferKey.showReadAloudFloatingBall,
            KEY_MEDIA_BUTTON_PER_NEXT
        )
    ),
    Reading(
        "\u6717\u8bfb",
        setOf(
            PreferKey.readAloudByPage,
            PreferKey.streamReadAloudAudio,
            PreferKey.ttsFollowSys,
            PreferKey.ttsSpeechRate
        )
    ),
    AiRole(
        "\u591a\u89d2\u8272",
        setOf(
            PreferKey.aiReadAloudRoleEnabled,
            KEY_AI_READ_ALOUD_MODEL_ROUTING,
            PreferKey.aiReadAloudRoleBackupModelId,
            PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
            PreferKey.aiReadAloudAudioModelId,
            PreferKey.aiReadAloudAudioBackupModelId,
            PreferKey.aiReadAloudAutoCreateCharacters,
            PreferKey.aiReadAloudAutoCreateCharacterPrompt,
            PreferKey.aiReadAloudAutoCreateAvatar,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
            PreferKey.aiReadAloudRoleMode,
            PreferKey.aiReadAloudRolePreprocess,
            PreferKey.aiReadAloudRoleThreadCount,
            PreferKey.aiReadAloudRoleContextParagraphs,
            PreferKey.aiReadAloudRoleMergeGapParagraphs,
            PreferKey.aiReadAloudRolePrompt,
            KEY_AI_READ_ALOUD_USAGE_RECORDS,
            PreferKey.aiReadAloudBgmEnabled,
            KEY_AI_READ_ALOUD_BGM_MANAGE,
            PreferKey.aiReadAloudBgmPrompt,
            PreferKey.aiReadAloudSoundEffectPrompt,
            PreferKey.aiReadAloudBgmVolume,
            PreferKey.aiReadAloudSfxVolume,
            PreferKey.readAloudSpeakerLoudnessEnabled,
            KEY_READ_ALOUD_LOUDNESS_RESET
        )
    ),
    Engine(
        "\u5f15\u64ce",
        setOf(
            PreferKey.ttsEngine,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
            KEY_SYS_TTS_CONFIG
        )
    );

    companion object {
        private val hiddenPreferenceKeys = setOf(
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.readAloudTargetVoiceVolume,
            PreferKey.readAloudMaxSpeakerGain,
            PreferKey.readAloudNarratorBaseGain
        )
        val allPreferenceKeys: Set<String> =
            values().flatMap { it.preferenceKeys }.toSet() + hiddenPreferenceKeys
    }
}

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"
    private val groupTabs = arrayListOf<Pair<ReadAloudConfigGroup, TextView>>()
    private var selectedGroup = ReadAloudConfigGroup.Base

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            val maxHeight = minOf(
                (resources.displayMetrics.heightPixels * 0.72f).toInt(),
                620.dpToPx()
            ).coerceAtLeast(360.dpToPx())
            setLayout(0.9f, maxHeight)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        groupTabs.clear()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = requireContext().dialogSurfaceBackground
            clipToOutline = true
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 8.dpToPx())
        }
        val tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            setBackgroundResource(R.drawable.bg_bookshelf_tag_track)
        }
        ReadAloudConfigGroup.values().forEach { group ->
            val tab = TextView(requireContext()).apply {
                text = group.title
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = requireContext().uiTypeface()
                isSingleLine = true
                background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    requireContext().themeCardColorOrDefault(),
                    UiCorner.actionRadius(requireContext())
                )
                setOnClickListener { selectGroup(group) }
            }
            groupTabs.add(group to tab)
            tabBar.addView(
                tab,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                42.dpToPx()
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        )
        root.addView(
            FrameLayout(requireContext()).apply { id = R.id.tag1 },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        container?.addView(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.tag1, preferenceFragment, readAloudPreferTag)
            .commit()
        selectGroup(selectedGroup)
    }

    private fun selectGroup(group: ReadAloudConfigGroup) {
        selectedGroup = group
        groupTabs.forEach { (tabGroup, tab) ->
            val selected = tabGroup == group
            tab.isSelected = selected
            tab.setTextColor(
                if (selected) requireContext().primaryColor
                else requireContext().primaryTextColor
            )
        }
        (childFragmentManager.findFragmentByTag(readAloudPreferTag) as? ReadAloudPreferenceFragment)
            ?.selectGroup(group)
    }

    class ReadAloudPreferenceFragment : ComposeSettingFragment(),
        SpeakEngineDialog.CallBack {

        private var selectedGroup = ReadAloudConfigGroup.Base

        override val titleRes: Int = R.string.aloud_config

        override val applyActivityTitle: Boolean = false

        override val autoOpenTargetItem: Boolean = false

        private val speakEngineSummary: String
            get() {
                val route = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
                val engineName = when (route.engineType) {
                    SpeechRoute.ENGINE_HTTP -> route.engineValue.toLongOrNull()
                        ?.let { appDb.httpTTSDao.getName(it) }
                        ?: "HTTP TTS"
                    SpeechRoute.ENGINE_SYSTEM -> route.speakerName.ifBlank { getString(R.string.system_tts) }
                    else -> getString(R.string.system_tts)
                }
                return buildList {
                    add(engineName)
                    route.speakerName
                        .takeIf { it.isNotBlank() && it != engineName }
                        ?.let(::add)
                    route.emotionName.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(" · ")
            }

        override fun buildPageSpec(): SettingPageSpec {
            return SettingPageSpec(
                titleRes = titleRes,
                sections = listOf(
                    SettingSectionSpec(
                        title = selectedGroup.title,
                        items = when (selectedGroup) {
                            ReadAloudConfigGroup.Base -> baseItems()
                            ReadAloudConfigGroup.Reading -> readingItems()
                            ReadAloudConfigGroup.AiRole -> aiRoleItems()
                            ReadAloudConfigGroup.Engine -> engineItems()
                        }
                    )
                )
            )
        }

        override fun onSettingPreferenceChanged(key: String) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ttsFollowSys,
                PreferKey.ttsSpeechRate -> {
                    applySpeechRate()
                }

                PreferKey.showReadAloudFloatingBall -> Unit

                PreferKey.aiReadAloudRoleEnabled,
                PreferKey.aiReadAloudRoleModelId,
                PreferKey.aiReadAloudRoleBackupModelId,
                PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
                PreferKey.aiReadAloudAudioModelId,
                PreferKey.aiReadAloudAudioBackupModelId,
                PreferKey.aiReadAloudAutoCreateCharacters,
                PreferKey.aiReadAloudAutoCreateCharacterPrompt,
                PreferKey.aiReadAloudAutoCreateAvatar,
                PreferKey.aiReadAloudRoleMode,
                PreferKey.aiReadAloudRolePreprocess,
                PreferKey.aiReadAloudRoleThreadCount,
                PreferKey.aiReadAloudRoleContextParagraphs,
                PreferKey.aiReadAloudRoleMergeGapParagraphs,
                PreferKey.aiReadAloudRolePrompt -> {
                    postReadAloudConfigChanged(
                        if (key == PreferKey.aiReadAloudRoleEnabled) {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                        } else {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH
                        }
                    )
                }
                PreferKey.aiReadAloudBgmEnabled,
                PreferKey.aiReadAloudBgmPrompt,
                PreferKey.aiReadAloudSoundEffectPrompt,
                PreferKey.aiReadAloudBgmVolume,
                PreferKey.aiReadAloudSfxVolume,
                PreferKey.readAloudSpeakerLoudnessEnabled,
                PreferKey.readAloudTargetVoiceVolume,
                PreferKey.readAloudMaxSpeakerGain,
                PreferKey.readAloudNarratorBaseGain -> {
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                }
            }
        }

        private fun postReadAloudConfigChanged(scope: String) {
            ReadAloudConfigChangeNotifier.notify(scope)
        }

        override fun upSpeakEngineSummary() {
            refreshSettings()
        }

        fun selectGroup(group: ReadAloudConfigGroup) {
            selectedGroup = group
            refreshSettings()
        }

        private fun applySpeechRate() {
            ReadAloud.upTtsSpeechRate(requireContext())
            if (!BaseReadAloudService.pause) {
                ReadAloud.pause(requireContext())
                ReadAloud.resume(requireContext())
            }
        }

        private fun updateAiRolePreferences() {
            refreshSettings()
        }

        private fun baseItems(): List<SettingItemSpec> {
            return listOf(
                switch(
                    key = PreferKey.ignoreAudioFocus,
                    title = getString(R.string.ignore_audio_focus_title),
                    summary = getString(R.string.ignore_audio_focus_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.pauseReadAloudWhilePhoneCalls,
                    title = getString(R.string.pause_read_aloud_while_phone_calls_title),
                    summary = getString(R.string.pause_read_aloud_while_phone_calls_summary),
                    defaultValue = false,
                    enabled = AppConfig.ignoreAudioFocus
                ),
                switch(
                    key = PreferKey.readAloudWakeLock,
                    title = getString(R.string.read_aloud_wake_lock),
                    summary = getString(R.string.read_aloud_wake_lock_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.showReadAloudFloatingBall,
                    title = getString(R.string.read_aloud_floating_ball),
                    summary = getString(R.string.read_aloud_floating_ball_summary),
                    checked = booleanSetting(PreferKey.showReadAloudFloatingBall, true),
                    onCheckedChange = ::setReadAloudFloatingBallEnabled
                ),
                switch(
                    key = KEY_MEDIA_BUTTON_PER_NEXT,
                    title = getString(R.string.pref_media_button_per_next),
                    summary = getString(R.string.pref_media_button_per_next_summary),
                    defaultValue = false
                )
            )
        }

        private fun setReadAloudFloatingBallEnabled(enabled: Boolean) {
            if (!enabled) {
                updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
                return
            }
            PermissionsCompat.Builder()
                .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                .rationale(R.string.read_aloud_floating_ball_permission_rationale)
                .onGranted {
                    updateBooleanSetting(PreferKey.showReadAloudFloatingBall, true)
                }
                .onDenied {
                    updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
                }
                .onError {
                    updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
                }
                .request()
        }

        private fun readingItems(): List<SettingItemSpec> {
            return listOf(
                switch(
                    key = PreferKey.readAloudByPage,
                    title = getString(R.string.read_aloud_by_page),
                    summary = getString(R.string.read_aloud_by_page_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.streamReadAloudAudio,
                    title = getString(R.string.stream_read_aloud_audio),
                    summary = getString(R.string.stream_read_aloud_audio_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.ttsFollowSys,
                    title = getString(R.string.flow_sys),
                    summary = "开启后使用系统语速，语速滑条仅展示当前保存值",
                    defaultValue = true
                ),
                slider(
                    key = PreferKey.ttsSpeechRate,
                    title = getString(R.string.read_aloud_speed),
                    summary = formatSpeechRate(AppConfig.ttsSpeechRate),
                    value = AppConfig.ttsSpeechRate.coerceIn(0, 45),
                    range = 0..45,
                    enabled = !AppConfig.ttsFlowSys
                )
            )
        }

        private fun aiRoleItems(): List<SettingItemSpec> {
            val enabled = AppConfig.aiReadAloudRoleEnabled
            val hasModel = AppConfig.aiReadAloudRoleModelConfig != null
            val fullMode = AppConfig.aiReadAloudRoleMode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL
            val audioEnabled = enabled && AppConfig.aiReadAloudBgmEnabled
            val bgmCount = appDb.readAloudBgmDao
                .enabledTracksByType(io.legado.app.data.entities.ReadAloudBgmTrack.TYPE_BGM)
                .size
            val sfxCount = appDb.readAloudBgmDao
                .enabledTracksByType(io.legado.app.data.entities.ReadAloudBgmTrack.TYPE_SFX)
                .size
            val loudnessCount = ReadAloudSpeakerLoudnessManager.learnedSpeakerCount()
            return listOf(
                switch(
                    key = PreferKey.aiReadAloudRoleEnabled,
                    title = "多角色",
                    summary = if (hasModel) "后台分析当前章节的旁白和角色片段，并缓存结果" else "请先选择多角色模型",
                    checked = enabled,
                    enabled = hasModel,
                    onCheckedChange = { AppConfig.aiReadAloudRoleEnabled = it }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_MODEL_ROUTING,
                    title = "AI 模型与超时",
                    summary = aiReadAloudModelRoutingSummary(),
                    enabled = hasModel,
                    onClick = ::showAiReadAloudModelRoutingDialog
                ),
                switch(
                    key = PreferKey.aiReadAloudAutoCreateCharacters,
                    title = "自动创建角色并分配发言人",
                    summary = if (AppConfig.aiReadAloudAutoCreateCharacters) {
                        "新角色会自动建卡，未配置发言人时会从可用目录稳定分配"
                    } else {
                        "只使用已有角色卡，不自动创建或分配发言人"
                    },
                    checked = AppConfig.aiReadAloudAutoCreateCharacters,
                    enabled = enabled,
                    onCheckedChange = { AppConfig.aiReadAloudAutoCreateCharacters = it }
                ),
                action(
                    key = PreferKey.aiReadAloudAutoCreateCharacterPrompt,
                    title = "自动建卡提示词",
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudAutoCreateCharacterPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultAutoCreateCharacterPrompt
                    ),
                    enabled = enabled && AppConfig.aiReadAloudAutoCreateCharacters,
                    onClick = ::showAutoCreateCharacterPromptDialog
                ),
                switch(
                    key = PreferKey.aiReadAloudAutoCreateAvatar,
                    title = "自动生成角色头像",
                    summary = when {
                        !AppConfig.aiReadAloudAutoCreateAvatar -> "关闭后不会为自动创建角色生成头像"
                        AppConfig.aiEnabledImageProviders.isEmpty() -> "没有可用生图提供商，自动跳过"
                        else -> "使用当前生图提供商后台生成头像"
                    },
                    checked = AppConfig.aiReadAloudAutoCreateAvatar,
                    enabled = enabled && AppConfig.aiReadAloudAutoCreateCharacters,
                    onCheckedChange = { AppConfig.aiReadAloudAutoCreateAvatar = it }
                ),
                action(
                    key = KEY_READ_ALOUD_SPEAKER_MANAGE,
                    title = "发言人管理",
                    summary = SPEAKER_MANAGE_SUMMARY,
                    onClick = { startActivity<SpeakerGroupManageActivity>() }
                ),
                choice(
                    key = PreferKey.aiReadAloudRoleMode,
                    title = "处理模式",
                    entriesRes = R.array.ai_read_aloud_role_mode_title,
                    valuesRes = R.array.ai_read_aloud_role_mode_value,
                    selectedValue = AppConfig.aiReadAloudRoleMode,
                    enabled = enabled
                ),
                action(
                    key = PreferKey.aiReadAloudRolePreprocess,
                    title = "预处理规则",
                    summary = "${if (AppConfig.aiReadAloudRoleUsingDefaultPreprocessRules) "内置默认 · " else ""}引号、冒号、跨段对话、心理活动等预切分规则",
                    enabled = enabled,
                    onClick = ::showMultiRolePreprocessRulesDialog
                ),
                action(
                    key = PreferKey.aiReadAloudRoleThreadCount,
                    title = "并发请求数",
                    summary = "${AppConfig.aiReadAloudRoleThreadCount} 个并发请求，越高越快但不省 Token",
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog("并发请求数", AppConfig.aiReadAloudRoleThreadCount, 1, 10) {
                        AppConfig.aiReadAloudRoleThreadCount = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRoleContextParagraphs,
                    title = "上下文段落数",
                    summary = "上下各 ${AppConfig.aiReadAloudRoleContextParagraphs} 段",
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog("上下文段落数", AppConfig.aiReadAloudRoleContextParagraphs, 0, 20) {
                        AppConfig.aiReadAloudRoleContextParagraphs = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRoleMergeGapParagraphs,
                    title = "相隔段落合并",
                    summary = "${AppConfig.aiReadAloudRoleMergeGapParagraphs} 段内合并为同一请求",
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog("相隔段落合并", AppConfig.aiReadAloudRoleMergeGapParagraphs, 0, 10) {
                        AppConfig.aiReadAloudRoleMergeGapParagraphs = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRolePrompt,
                    title = "预注入分角色提示词",
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudRolePrompt,
                        useDefault = AppConfig.aiReadAloudRoleUsingDefaultPrompt
                    ),
                    enabled = enabled,
                    onClick = ::showMultiRolePromptDialog
                ),
                action(
                    key = KEY_AI_READ_ALOUD_USAGE_RECORDS,
                    title = "消耗记录",
                    summary = appDb.aiReadAloudUsageRecordDao.list(limit = 1000).size
                        .let { if (it > 0) "$it 条记录，可筛选和批量删除" else "暂无消耗记录" },
                    onClick = {
                        startActivity(
                            android.content.Intent(requireContext(), AiReadAloudUsageRecordActivity::class.java)
                        )
                    }
                ),
                switch(
                    key = PreferKey.aiReadAloudBgmEnabled,
                    title = "智能配乐",
                    summary = if (AppConfig.aiReadAloudBgmEnabled) {
                        "AI 可按段落范围选择配乐，朗读时淡入淡出"
                    } else {
                        "关闭后不会请求或播放章节配乐"
                    },
                    checked = AppConfig.aiReadAloudBgmEnabled,
                    enabled = enabled,
                    onCheckedChange = { AppConfig.aiReadAloudBgmEnabled = it }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_BGM_MANAGE,
                    title = "智能音频设置",
                    summary = "$bgmCount 首配乐 · $sfxCount 个音效",
                    enabled = enabled,
                    onClick = {
                        startActivity(
                            android.content.Intent(requireContext(), ReadAloudBgmManageActivity::class.java)
                        )
                    }
                ),
                action(
                    key = PreferKey.aiReadAloudBgmPrompt,
                    title = "配乐提示词",
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudBgmPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultBgmPrompt
                    ),
                    enabled = audioEnabled,
                    onClick = ::showBgmPromptDialog
                ),
                action(
                    key = PreferKey.aiReadAloudSoundEffectPrompt,
                    title = "音效提示词",
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudSoundEffectPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultSoundEffectPrompt
                    ),
                    enabled = audioEnabled,
                    onClick = ::showSoundEffectPromptDialog
                ),
                slider(
                    key = PreferKey.aiReadAloudBgmVolume,
                    title = "配乐音量",
                    summary = "当前 ${AppConfig.aiReadAloudBgmVolume}%",
                    value = AppConfig.aiReadAloudBgmVolume,
                    range = 0..100,
                    enabled = audioEnabled
                ),
                slider(
                    key = PreferKey.aiReadAloudSfxVolume,
                    title = "音效音量",
                    summary = "当前 ${AppConfig.aiReadAloudSfxVolume}%",
                    value = AppConfig.aiReadAloudSfxVolume,
                    range = 0..100,
                    enabled = audioEnabled
                ),
                switch(
                    key = PreferKey.readAloudSpeakerLoudnessEnabled,
                    title = "发言人响度均衡",
                    summary = if (AppConfig.readAloudSpeakerLoudnessEnabled) {
                        "自动平衡旁白和角色响度"
                    } else {
                        "关闭后所有朗读音频按原始音量播放"
                    },
                    checked = AppConfig.readAloudSpeakerLoudnessEnabled,
                    onCheckedChange = { AppConfig.readAloudSpeakerLoudnessEnabled = it }
                ),
                action(
                    key = KEY_READ_ALOUD_LOUDNESS_RESET,
                    title = "重置响度学习",
                    summary = if (loudnessCount > 0) "已学习 $loudnessCount 个发言人" else "暂无学习数据",
                    enabled = loudnessCount > 0
                ) {
                    ReadAloudSpeakerLoudnessManager.reset()
                    updateAiRolePreferences()
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                    toastOnUi("已重置发言人响度学习数据")
                }
            )
        }

        private fun engineItems(): List<SettingItemSpec> {
            return listOf(
                action(
                    key = PreferKey.ttsEngine,
                    title = getString(R.string.speak_engine),
                    summary = speakEngineSummary,
                    onClick = { showDialogFragment(SpeakEngineDialog()) }
                ),
                action(
                    key = KEY_READ_ALOUD_SPEAKER_MANAGE,
                    title = "发言人管理",
                    summary = SPEAKER_MANAGE_SUMMARY,
                    onClick = { startActivity<SpeakerGroupManageActivity>() }
                ),
                action(
                    key = KEY_SYS_TTS_CONFIG,
                    title = getString(R.string.sys_tts_config),
                    summary = getString(R.string.sys_tts_config_summary),
                    onClick = { IntentHelp.openTTSSetting() }
                )
            )
        }

        private fun switch(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            defaultValue: Boolean,
            enabled: Boolean = true
        ): SettingSwitchSpec {
            return switch(
                key = key,
                title = title,
                summary = summary,
                checked = booleanSetting(key, defaultValue),
                enabled = enabled,
                onCheckedChange = { updateBooleanSetting(key, it) }
            )
        }

        private fun switch(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            checked: Boolean,
            enabled: Boolean = true,
            onCheckedChange: (Boolean) -> Unit
        ): SettingSwitchSpec {
            return SettingSwitchSpec(
                key = key,
                title = title,
                summary = summary,
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }

        private fun slider(
            key: String,
            title: CharSequence,
            summary: CharSequence,
            value: Int,
            range: IntRange,
            enabled: Boolean = true
        ): SettingSliderSpec {
            return SettingSliderSpec(
                key = key,
                title = title,
                summary = summary,
                value = value.coerceIn(range.first, range.last),
                valueRange = range,
                enabled = enabled,
                onValueChange = { updateIntSetting(key, it) }
            )
        }

        private fun action(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            visible: Boolean = true,
            enabled: Boolean = true,
            onClick: () -> Unit
        ): SettingActionSpec {
            return SettingActionSpec(
                key = key,
                title = title,
                summary = summary,
                visible = visible,
                enabled = enabled,
                onClick = onClick
            )
        }

        private fun choice(
            key: String,
            title: CharSequence,
            entriesRes: Int,
            valuesRes: Int,
            selectedValue: String,
            enabled: Boolean = true
        ): SettingChoiceSpec {
            val options = choiceOptions(entriesRes, valuesRes)
            return SettingChoiceSpec(
                key = key,
                title = title,
                summary = choiceLabel(options, selectedValue),
                options = options,
                selectedValue = selectedValue,
                enabled = enabled,
                onSelected = { updateStringSetting(key, it) }
            )
        }

        private fun choiceOptions(
            entriesRes: Int,
            valuesRes: Int
        ): List<SettingChoiceOption> {
            val entries = resources.getStringArray(entriesRes)
            val values = resources.getStringArray(valuesRes)
            return values.mapIndexed { index, value ->
                SettingChoiceOption(
                    value = value,
                    label = entries.getOrElse(index) { value }
                )
            }
        }

        private fun choiceLabel(
            options: List<SettingChoiceOption>,
            selectedValue: String
        ): String {
            return options.firstOrNull { it.value == selectedValue }
                ?.label
                ?.toString()
                ?: selectedValue
        }

        private fun firstLineSummary(
            value: String,
            useDefault: Boolean
        ): String {
            val prefix = if (useDefault) "内置默认 · " else ""
            return prefix + value.lineSequence()
                .firstOrNull()
                ?.take(40)
                ?.ifBlank { null }
                .orEmpty()
        }

        private fun showAiReadAloudModelRoutingDialog() {
            val options = listOf(
                "多角色主模型：${modelLabel(AppConfig.aiReadAloudRoleModelConfig)}",
                "多角色备用模型：${optionalModelLabel(AppConfig.aiReadAloudRoleBackupModelId, "不使用")}",
                "首响超时：${AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds} 秒",
                "智能音频模型：${optionalModelLabel(AppConfig.aiReadAloudAudioModelId, "跟随多角色")}",
                "智能音频备用：${optionalModelLabel(AppConfig.aiReadAloudAudioBackupModelId, "跟随多角色备用")}"
            )
            showComposeChoiceListDialog("AI 模型与超时", options) { index ->
                when (index) {
                    0 -> showAiModelPicker(
                        title = "多角色主模型",
                        currentModelId = AppConfig.aiReadAloudRoleModelId,
                        noneLabel = null
                    ) {
                        AppConfig.aiReadAloudRoleModelId = it
                        updateAiRolePreferences()
                    }
                    1 -> showAiModelPicker(
                        title = "多角色备用模型",
                        currentModelId = AppConfig.aiReadAloudRoleBackupModelId,
                        noneLabel = "不使用备用模型"
                    ) {
                        AppConfig.aiReadAloudRoleBackupModelId = it
                        updateAiRolePreferences()
                    }
                    2 -> showAiRoleNumberDialog(
                        title = "首响超时秒数",
                        value = AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds,
                        min = 5,
                        max = 90
                    ) {
                        AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds = it
                        updateAiRolePreferences()
                    }
                    3 -> showAiModelPicker(
                        title = "智能音频模型",
                        currentModelId = AppConfig.aiReadAloudAudioModelId,
                        noneLabel = "跟随多角色主模型"
                    ) {
                        AppConfig.aiReadAloudAudioModelId = it
                        updateAiRolePreferences()
                    }
                    4 -> showAiModelPicker(
                        title = "智能音频备用模型",
                        currentModelId = AppConfig.aiReadAloudAudioBackupModelId,
                        noneLabel = "跟随多角色备用模型"
                    ) {
                        AppConfig.aiReadAloudAudioBackupModelId = it
                        updateAiRolePreferences()
                    }
                }
            }
        }

        private fun showAiModelPicker(
            title: String,
            currentModelId: String?,
            noneLabel: String?,
            onSelected: (String?) -> Unit
        ) {
            val models = AppConfig.aiModelConfigList
            if (models.isEmpty()) {
                toastOnUi(R.string.ai_no_models)
                return
            }
            val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
            val offset = if (noneLabel == null) 0 else 1
            val labels = buildList {
                noneLabel?.let {
                    add(if (currentModelId.isNullOrBlank()) "$it ✓" else it)
                }
                models.forEach { model ->
                    val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                        ?.let { "${model.modelId} - $it" }
                        ?: model.modelId
                    add(if (model.id == currentModelId) "$label ✓" else label)
                }
            }
            val selectedIndex = if (currentModelId.isNullOrBlank() && noneLabel != null) {
                0
            } else {
                models.indexOfFirst { it.id == currentModelId }
                    .takeIf { it >= 0 }
                    ?.plus(offset)
                    ?: -1
            }
            showComposeChoiceListDialog(title, labels, selectedIndex = selectedIndex) { index ->
                if (index < offset) {
                    onSelected(null)
                } else {
                    onSelected(models[index - offset].id)
                }
                selectGroup(selectedGroup)
            }
        }

        private fun aiReadAloudModelRoutingSummary(): String {
            val role = modelLabel(AppConfig.aiReadAloudRoleModelConfig)
            val backup = optionalModelLabel(AppConfig.aiReadAloudRoleBackupModelId, "无备用")
            val audio = optionalModelLabel(AppConfig.aiReadAloudAudioModelId, "音频跟随")
            return "$role · 备用 $backup · 首响 ${AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds}s · $audio"
        }

        private fun optionalModelLabel(modelId: String?, fallback: String): String {
            if (modelId.isNullOrBlank()) return fallback
            return modelLabel(AppConfig.aiModelConfigList.firstOrNull { it.id == modelId })
        }


        private fun modelLabel(model: io.legado.app.ui.main.ai.AiModelConfig?): String {
            model ?: return "未配置"
            val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
                ?.name
                ?.takeIf { it.isNotBlank() }
            return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
        }

        private fun formatSpeechRate(value: Int): String {
            return ((value.coerceIn(0, 45) + 5) / 10f).toString()
        }

        private fun showAiRoleNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            val displayTitle = when (max) {
                10 -> "多角色线程数"
                40 -> "每批段落数"
                else -> title
            }
            showComposeNumberPickerDialog(
                title = displayTitle,
                value = value,
                minValue = min,
                maxValue = max,
                onValue = onValue
            )
        }

        private fun showLongTextInputDialog(
            title: String,
            hint: String,
            initialValue: String,
            maxLength: Int? = null,
            maxLengthMessage: String = "",
            minLines: Int,
            onValue: (String) -> Unit,
            neutralText: String? = null,
            onNeutral: (() -> Unit)? = null,
            validateContent: ((String) -> Boolean)? = null
        ) {
            showComposeTextInputDialog(
                title = title,
                hint = hint,
                initialValue = initialValue,
                neutralText = neutralText,
                minLines = minLines,
                maxLines = maxOf(minLines, 10),
                validateInput = { value ->
                    when {
                        maxLength != null && value.length > maxLength -> {
                            toastOnUi(maxLengthMessage)
                            false
                        }
                        validateContent?.invoke(value) == false -> false
                        else -> true
                    }
                },
                onPositive = onValue,
                onNeutral = onNeutral
            )
        }

        private fun showMultiRolePromptDialog() {
            showLongTextInputDialog(
                title = "预注入分角色提示词",
                hint = "内置默认会自动生效。这里可补充角色判断规则、旁白/台词标注偏好等。",
                initialValue = AppConfig.aiReadAloudRolePrompt,
                maxLength = 4000,
                maxLengthMessage = "提示词最多 4000 字",
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudRolePrompt = it
                    updateAiRolePreferences()
                },
                neutralText = "恢复默认",
                onNeutral = {
                    AppConfig.aiReadAloudRolePrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showAutoCreateCharacterPromptDialog() {
            showLongTextInputDialog(
                title = "自动建卡提示词",
                hint = "内置默认会自动生效。这里用于约束什么时候新增角色，以及如何判断性别、年龄、角色等级。",
                initialValue = AppConfig.aiReadAloudAutoCreateCharacterPrompt,
                maxLength = 4000,
                maxLengthMessage = "提示词最多 4000 字",
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudAutoCreateCharacterPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = "恢复默认",
                onNeutral = {
                    AppConfig.aiReadAloudAutoCreateCharacterPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showBgmPromptDialog() {
            showLongTextInputDialog(
                title = "配乐提示词",
                hint = "只填写配乐策略，不要写工具 JSON。留空会恢复内置默认。",
                initialValue = AppConfig.aiReadAloudBgmPrompt,
                maxLength = 4000,
                maxLengthMessage = "提示词最大 4000 字",
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudBgmPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = "恢复默认",
                onNeutral = {
                    AppConfig.aiReadAloudBgmPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showSoundEffectPromptDialog() {
            showLongTextInputDialog(
                title = "音效提示词",
                hint = "只填写音效选择策略。音效只能从候选事件中选择，留空会恢复内置默认。",
                initialValue = AppConfig.aiReadAloudSoundEffectPrompt,
                maxLength = 4000,
                maxLengthMessage = "提示词最大 4000 字",
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudSoundEffectPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = "恢复默认",
                onNeutral = {
                    AppConfig.aiReadAloudSoundEffectPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showMultiRolePreprocessRulesDialog() {
            val config = ReadAloudPreprocessRuleConfig.current()
            val items = listOf(
                "引号与句末规则 · ${config.quotePairs.size} 组引号",
                "心理活动提示词 · ${config.thoughtCuePatterns.size} 条",
                "判断阈值 · 台词 ${config.dialogueMinLength} / 强调 ${config.emphasisMaxLength}",
                "音效预筛触发词 · ${config.soundEffectCuePatterns.size} 个",
                "试运行预处理",
                "恢复默认"
            )
            showComposeChoiceListDialog("预处理规则", items) { index ->
                when (index) {
                    0 -> showPreprocessQuoteDialog(config)
                    1 -> showRuleListEditor(
                        title = "心理活动提示词",
                        values = config.thoughtCuePatterns,
                        hint = "每行一个提示词，例如：心道",
                        onSave = { savePreprocessConfig(config.copy(thoughtCuePatterns = it)) }
                    )
                    2 -> showPreprocessThresholdDialog(config)
                    3 -> showSoundEffectRuleDialog(config)
                    4 -> showPreprocessTrialDialog()
                    5 -> {
                        AppConfig.aiReadAloudRolePreprocessRules = ""
                        updateAiRolePreferences()
                        toastOnUi("已恢复内置预处理规则")
                    }
                }
            }
        }

        private fun showPreprocessQuoteDialog(config: ReadAloudPreprocessRuleConfig) {
            showLongTextInputDialog(
                title = "引号与句末规则",
                hint = "每行一组引号，格式：开引号 空格 闭引号，例如：\n“ ”\n「 」",
                initialValue = config.quotePairs.joinToString("\n") { "${it.open} ${it.close}" },
                minLines = 6,
                onValue = { value ->
                    savePreprocessConfig(config.copy(quotePairs = parseQuotePairs(value)))
                },
                neutralText = "句末符号",
                onNeutral = { showSentencePunctuationDialog(config) },
                validateContent = { value ->
                    if (parseQuotePairs(value).isEmpty()) {
                        toastOnUi("至少保留一组引号")
                        false
                    } else {
                        true
                    }
                }
            )
        }

        private fun showSentencePunctuationDialog(config: ReadAloudPreprocessRuleConfig) {
            showComposeTextInputDialog(
                title = "句末符号",
                hint = "直接填写句末符号，例如：。！？…!?",
                initialValue = config.sentencePunctuation,
                minLines = 1,
                maxLines = 1,
                validateInput = { value ->
                    if (value.trim().isBlank()) {
                        toastOnUi("句末符号不能为空")
                        false
                    } else {
                        true
                    }
                },
                onPositive = { value ->
                    savePreprocessConfig(config.copy(sentencePunctuation = value.trim()))
                }
            )
        }

        private fun showPreprocessThresholdDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                "台词最短长度 · ${config.dialogueMinLength}",
                "强调文本最大长度 · ${config.emphasisMaxLength}",
                "冒号前提示最大长度 · ${config.colonCueMaxLength}",
                "跨段引号合并 · ${if (config.mergeCrossParagraphQuote) "开启" else "关闭"}",
                "音效上下文字符数 · ${config.soundEffectContextChars}"
            )
            showComposeChoiceListDialog("判断阈值", items) { index ->
                when (index) {
                    0 -> showPreprocessNumberDialog("台词最短长度", config.dialogueMinLength, 1, 80) {
                        savePreprocessConfig(config.copy(dialogueMinLength = it))
                    }
                    1 -> showPreprocessNumberDialog("强调文本最大长度", config.emphasisMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(emphasisMaxLength = it))
                    }
                    2 -> showPreprocessNumberDialog("冒号前提示最大长度", config.colonCueMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(colonCueMaxLength = it))
                    }
                    3 -> savePreprocessConfig(config.copy(mergeCrossParagraphQuote = !config.mergeCrossParagraphQuote))
                    4 -> showPreprocessNumberDialog("音效上下文字符数", config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showSoundEffectRuleDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                "音效预筛触发词 · ${config.soundEffectCuePatterns.size} 条",
                "音效排除词 · ${config.soundEffectExcludePatterns.size} 条",
                "上下文字符数 · ${config.soundEffectContextChars}"
            )
            showComposeChoiceListDialog("音效预筛规则", items) { index ->
                when (index) {
                    0 -> showRuleListEditor(
                        title = "音效预筛触发词",
                        values = config.soundEffectCuePatterns,
                        hint = "每行一个触发词，只用于找可能需要音效的句子，例如：吱呀、敲门声、枪声",
                        onSave = { savePreprocessConfig(config.copy(soundEffectCuePatterns = it)) }
                    )
                    1 -> showRuleListEditor(
                        title = "音效排除词",
                        values = config.soundEffectExcludePatterns,
                        hint = "每行一个排除词，例如：心声、名声",
                        onSave = { savePreprocessConfig(config.copy(soundEffectExcludePatterns = it)) }
                    )
                    2 -> showPreprocessNumberDialog("音效上下文字符数", config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showRuleListEditor(
            title: String,
            values: List<String>,
            hint: String,
            onSave: (List<String>) -> Unit
        ) {
            showLongTextInputDialog(
                title = title,
                hint = hint,
                initialValue = values.joinToString("\n"),
                minLines = 8,
                onValue = { value -> onSave(parseRuleItems(value)) },
                validateContent = { value ->
                    if (parseRuleItems(value).isEmpty()) {
                        toastOnUi("至少保留一条规则")
                        false
                    } else {
                        true
                    }
                }
            )
        }

        private fun showPreprocessNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            showComposeNumberPickerDialog(
                title = title,
                value = value,
                minValue = min,
                maxValue = max,
                onValue = onValue
            )
        }

        private fun showPreprocessTrialDialog() {
            val sample = """
                江艳凑在杨间身边小心翼翼地问道：
                “那栋房子真的有鬼啊？”
                门轴忽然吱呀一声响了起来。
                他心道：“事情不太对。”
            """.trimIndent()
            showComposeTextInputDialog(
                title = "预处理试运行",
                hint = "粘贴几段正文，试运行当前预处理规则。",
                initialValue = sample,
                minLines = 10,
                maxLines = 14,
                validateInput = { value ->
                    if (value.isBlank()) {
                        toastOnUi("请先输入正文")
                        false
                    } else {
                        true
                    }
                },
                onPositive = { showPreprocessTrialResult(it) }
            )
        }

        private fun showPreprocessTrialResult(text: String) {
            val paragraphs = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val roleResult = ReadAloudRolePreprocessor.process(paragraphs)
            val sfxResult = ReadAloudSoundEffectPreprocessor.process(paragraphs)
            val message = buildString {
                appendLine("角色 unit：${roleResult.units.size}")
                roleResult.units.take(40).forEachIndexed { index, unit ->
                    append(index + 1)
                    append(". ")
                    append(unit.kind)
                    append(" · ")
                    append(if (unit.needsAi) "需要 AI" else unit.characterName.ifBlank { unit.roleType })
                    append(" · P")
                    append(unit.firstParagraphIndex + 1)
                    append(" · ")
                    appendLine(unit.text.replace('\n', ' ').take(80))
                }
                if (roleResult.units.size > 40) appendLine("……")
                appendLine()
                appendLine("音效候选：${sfxResult.candidates.size}")
                sfxResult.candidates.take(30).forEachIndexed { index, item ->
                    append(index + 1)
                    append(". ")
                    append(item.cue)
                    append(" · P")
                    append(item.paragraphIndex + 1)
                    append(" · ")
                    appendLine(item.context.take(80))
                }
            }.take(6000)
            showComposeConfirmDialog(
                title = "试运行结果",
                message = message,
                showNegative = false,
                messageInContent = true,
                onPositive = {}
            )
        }

        private fun savePreprocessConfig(config: ReadAloudPreprocessRuleConfig) {
            val json = config.toJsonString()
            if (json.length > 8000) {
                toastOnUi("预处理规则最多 8000 字")
                return
            }
            AppConfig.aiReadAloudRolePreprocessRules = json
            updateAiRolePreferences()
            toastOnUi("已保存预处理规则")
        }

        private fun parseQuotePairs(value: String): List<ReadAloudQuotePair> {
            return value.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    when {
                        parts.size >= 2 -> ReadAloudQuotePair(parts[0].take(1), parts[1].take(1))
                        line.length >= 2 -> ReadAloudQuotePair(line.take(1), line.takeLast(1))
                        else -> null
                    }
                }
                .distinct()
                .toList()
        }

        private fun parseRuleItems(value: String): List<String> {
            return value.lineSequence()
                .flatMap { it.split(',', '，', '、').asSequence() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }


    }
}
