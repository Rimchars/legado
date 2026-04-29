package io.legado.app.ui.main.ai

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import android.widget.PopupWindow
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiChatBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.utils.dpToPx
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.legado.app.utils.ColorUtils

class AiChatActivity : BaseActivity<ActivityAiChatBinding>(
    fullScreen = false,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAiChatBinding::inflate)

    private val viewModel by viewModels<AiChatViewModel>()
    private val adapter by lazy { AiChatAdapter(this) }
    private val historyTimeFormat by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
    private val composerBaseBottomMargin by lazy { 0 }
    private var modelActionText: TextView? = null
    private var modernMenuPopup: PopupWindow? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        observeMessages()
        updateHeader()
        updateComposerState()
    }

    override fun onResume() {
        super.onResume()
        applyPageChrome()
        updateHeader()
        updateComposerState()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_chat, menu)
        menu.findItem(R.id.menu_ai_model)?.actionView?.let { actionView ->
            modelActionText = actionView.findViewById(R.id.tv_ai_model_action)
            actionView.setOnClickListener {
                showModelSelectorDialog()
            }
        }
        applyPageChrome()
        updateHeader()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_ai_model -> {
                showModelSelectorDialog()
                return true
            }

            R.id.menu_ai_more -> {
                modernMenuPopup = ModernActionPopup.show(
                    binding.titleBar,
                    listOf(
                        ModernActionPopup.Action(getString(R.string.ai_new_chat)) {
                            startNewChatFromMenu()
                        },
                        ModernActionPopup.Action(getString(R.string.ai_chat_history)) {
                            openHistoryFromMenu()
                        },
                        ModernActionPopup.Action(getString(R.string.ai_setting)) {
                            openAiSettings()
                        }
                    ),
                    modernMenuPopup
                )
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun openHistoryFromMenu() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        showHistoryDialog()
    }

    private fun startNewChatFromMenu() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        viewModel.startNewSession()
        updateHeader()
    }

    private fun openAiSettings() {
        android.content.Intent(this, ConfigActivity::class.java).apply {
            putExtra("configTag", ConfigTag.AI_CONFIG)
        }.also(::startActivity)
    }

    private fun initView() {
        applyPageChrome()
        binding.rvAiMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvAiMessages.adapter = adapter
        binding.rvAiMessages.setEdgeEffectColor(primaryColor)
        binding.btnScrollPrev.setOnClickListener { scrollToPreviousAssistantStart() }
        binding.btnScrollNext.setOnClickListener { scrollToNextMessageBottom() }
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            val imeInset = windowInsets.imeHeight
            val bottomInset = if (imeInset > 0) imeInset else windowInsets.navigationBarHeight
            binding.composerContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = composerBaseBottomMargin + bottomInset
            }
            binding.composerContainer.post {
                updateMessagesBottomPadding()
            }
            windowInsets
        }
        binding.composerContainer.doOnLayout {
            updateMessagesBottomPadding()
        }
        binding.root.post {
            binding.root.requestApplyInsets()
        }
        binding.etAiInput.doAfterTextChanged {
            updateComposerState()
            binding.composerContainer.post {
                updateMessagesBottomPadding()
            }
        }
        binding.etAiInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.root.requestApplyInsets()
            }
        }
        binding.btnAiSend.setOnClickListener {
            if (viewModel.isRequesting) {
                cancelCurrentRequest()
            } else {
                dispatchSend()
            }
        }
        binding.etAiInput.setOnEditorActionListener { _, actionId, event ->
            val isSendAction = actionId == EditorInfo.IME_ACTION_SEND
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isSendAction || isEnterKey) {
                dispatchSend()
                true
            } else {
                false
            }
        }
        tintSendButton()
        binding.etAiInput.setTextColor(primaryTextColor)
        binding.etAiInput.setHintTextColor(secondaryTextColor)
    }

    private fun updateMessagesBottomPadding() {
        val composerBottom = (binding.composerContainer.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.bottomMargin ?: 0
        val extraBottom = binding.composerContainer.height + composerBottom + 18.dpToPx()
        binding.rvAiMessages.setPadding(
            binding.rvAiMessages.paddingLeft,
            binding.rvAiMessages.paddingTop,
            binding.rvAiMessages.paddingRight,
            extraBottom
        )
    }

    private fun observeMessages() {
        viewModel.messagesLiveData.observe(this) { messages ->
            adapter.submitList(messages)
            val hasMessages = messages.isNotEmpty()
            binding.rvAiMessages.isVisible = hasMessages
            binding.emptyContainer.isVisible = !hasMessages
            if (hasMessages) {
                binding.rvAiMessages.post {
                    binding.rvAiMessages.scrollToPosition(messages.lastIndex)
                }
            }
        }
        viewModel.requestingLiveData.observe(this) { requesting ->
            hideThinkingPanel()
            updateComposerState()
        }
    }

    private fun updateHeader() {
        val model = AppConfig.aiCurrentModelConfig
        binding.titleBar.subtitle = null
        modelActionText?.text = model?.modelId ?: getString(R.string.ai_current_model_summary_empty)
        modelActionText?.alpha = if (model == null) 0.72f else 1f
    }

    private fun dispatchSend() {
        val content = binding.etAiInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty() || viewModel.isRequesting) {
            return
        }
        val provider = AppConfig.aiCurrentProvider
        if (provider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
            toastOnUi(R.string.ai_missing_config)
            return
        }
        binding.etAiInput.text?.clear()
        viewModel.startRequest(
            userContent = content,
            thinkingText = getString(R.string.ai_chat_thinking),
            cancelledText = getString(R.string.ai_chat_cancelled),
            failureMessage = { getString(R.string.ai_request_failed, it) }
        )
    }

    private fun cancelCurrentRequest() {
        viewModel.stopRequest(getString(R.string.ai_chat_cancelled))
    }

    private fun showHistoryDialog() {
        val sessions = viewModel.historySessions()
        if (sessions.isEmpty()) {
            toastOnUi(R.string.ai_history_empty)
            return
        }
        val items = mutableListOf(getString(R.string.ai_history_clear_all))
        items += sessions.map { session ->
            "${session.title}\n${historyTimeFormat.format(Date(session.updatedAt))}"
        }
        selector(
            getString(R.string.ai_chat_history),
            items
        ) { _, _, index ->
            if (index == 0) {
                confirmClearAllHistory()
            } else {
                showHistorySessionActions(sessions[index - 1])
            }
        }
    }

    private fun showHistorySessionActions(session: AiChatSession) {
        selector(
            session.title,
            listOf(
                getString(R.string.ai_history_open),
                getString(R.string.ai_history_delete)
            )
        ) { _, _, index ->
            when (index) {
                0 -> {
                    viewModel.loadSession(session.id)
                    updateHeader()
                }

                1 -> confirmDeleteHistorySession(session)
            }
        }
    }

    private fun confirmDeleteHistorySession(session: AiChatSession) {
        alert(
            title = getString(R.string.ai_history_delete),
            message = getString(R.string.ai_history_delete_confirm, session.title)
        ) {
            okButton {
                viewModel.deleteSession(session.id)
                updateHeader()
            }
            cancelButton()
        }
    }

    private fun confirmClearAllHistory() {
        alert(
            title = getString(R.string.ai_history_clear_all),
            message = getString(R.string.ai_history_clear_all_confirm)
        ) {
            okButton {
                viewModel.clearAllSessions()
                updateHeader()
            }
            cancelButton()
        }
    }

    private fun showModelSelectorDialog() {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        selector(
            getString(R.string.ai_current_model),
            models.map { model ->
                providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} · $it" }
                    ?: model.modelId
            }
        ) { _, _, index ->
            AppConfig.aiCurrentModelId = models[index].id
            updateHeader()
        }
    }

    private fun showErrorDialog(error: AiChatException) {
        alert(
            title = getString(R.string.ai_request_error_title),
            message = error.message
        ) {
            okButton()
            neutralButton(getString(R.string.ai_view_log)) {
                showDialogFragment(
                    TextDialog(
                        getString(R.string.ai_request_error_log),
                        error.debugLog
                    )
                )
            }
        }
    }

    private fun updateComposerState() {
        val hasInput = binding.etAiInput.text?.isNotBlank() == true
        binding.etAiInput.isEnabled = true
        binding.btnAiSend.isEnabled = viewModel.isRequesting || hasInput
        binding.btnAiSend.alpha = if (binding.btnAiSend.isEnabled) 1f else 0.48f
        binding.btnAiSend.contentDescription = getString(
            if (viewModel.isRequesting) R.string.ai_chat_stop else R.string.ai_chat_send
        )
        binding.btnAiSend.setImageResource(
            if (viewModel.isRequesting) R.drawable.ic_stop_black_24dp else R.drawable.ic_arrow_right
        )
    }

    private fun hideThinkingPanel() {
        binding.thinkingPanel.isVisible = false
        binding.thinkingScroll.isVisible = false
    }

    private fun scrollToPreviousAssistantStart() {
        val items = viewModel.messagesLiveData.value.orEmpty()
        val layoutManager = binding.rvAiMessages.layoutManager as? LinearLayoutManager ?: return
        val anchor = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val target = (anchor - 1 downTo 0).firstOrNull {
            items.getOrNull(it)?.role == AiChatMessage.Role.ASSISTANT
        } ?: return
        layoutManager.scrollToPositionWithOffset(target, 0)
    }

    private fun scrollToNextMessageBottom() {
        val items = viewModel.messagesLiveData.value.orEmpty()
        val layoutManager = binding.rvAiMessages.layoutManager as? LinearLayoutManager ?: return
        val anchor = layoutManager.findLastVisibleItemPosition().coerceAtLeast(0)
        val target = (anchor + 1 until items.size).firstOrNull() ?: return
        binding.rvAiMessages.scrollToPosition(target)
        binding.rvAiMessages.post {
            val holder = binding.rvAiMessages.findViewHolderForAdapterPosition(target)
            val bottom = holder?.itemView?.bottom ?: return@post
            val delta = bottom - binding.rvAiMessages.height
            if (delta > 0) {
                binding.rvAiMessages.scrollBy(0, delta)
            }
        }
    }

    private fun tintSendButton() {
        val typedArray = theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent)
        )
        typedArray.use {
            val color = it.getColor(0, accentColor)
            binding.btnAiSend.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private fun applyPageChrome() {
        val baseColor = backgroundColor
        val baseIsLight = ColorUtils.isColorLight(baseColor)
        val surfaceColor = if (baseIsLight) {
            ColorUtils.blendColors(
                baseColor,
                ContextCompat.getColor(this, R.color.background_card),
                0.72f
            )
        } else {
            ColorUtils.blendColors(
                baseColor,
                ContextCompat.getColor(this, R.color.white),
                0.12f
            )
        }
        val strokeColor = ColorUtils.adjustAlpha(
            if (baseIsLight) primaryTextColor else ContextCompat.getColor(this, R.color.white),
            if (baseIsLight) 0.08f else 0.16f
        )
        binding.emptyContainer.background = createSurfaceDrawable(surfaceColor, strokeColor, 20f)
        binding.composerContainer.background = createSurfaceDrawable(
            ColorUtils.adjustAlpha(
                ColorUtils.blendColors(baseColor, accentColor, if (baseIsLight) 0.08f else 0.18f),
                if (baseIsLight) 0.96f else 0.88f
            ),
            ColorUtils.adjustAlpha(accentColor, if (baseIsLight) 0.18f else 0.24f),
            28f
        )
        binding.composerContainer.elevation = 8f.dpToPx()
        binding.titleBar.setTextColor(primaryTextColor)
        binding.titleBar.setSubTitleTextColor(secondaryTextColor)
        binding.titleBar.setColorFilter(primaryTextColor)
        modelActionText?.setTextColor(primaryTextColor)
        binding.tvAiEmpty.setTextColor(secondaryTextColor)
        binding.ivAiEmptyIcon.setColorFilter(secondaryTextColor)
    }

    private fun createSurfaceDrawable(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dpToPx()
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }
}
