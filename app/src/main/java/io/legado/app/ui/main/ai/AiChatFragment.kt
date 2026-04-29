package io.legado.app.ui.main.ai

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.res.use
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.databinding.FragmentAiChatBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiChatFragment() : BaseFragment(R.layout.fragment_ai_chat), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int?
        get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentAiChatBinding::bind)
    private val viewModel by viewModels<AiChatViewModel>()
    private val adapter by lazy { AiChatAdapter(requireContext()) }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        observeMessages()
        updateComposerState()
    }

    private fun initView() {
        binding.topRow.applyStatusBarPadding(withInitialPadding = true)
        binding.composerContainer.applyNavigationBarMargin(withInitialMargin = true)
        binding.rvAiMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAiMessages.adapter = adapter
        binding.rvAiMessages.setEdgeEffectColor(primaryColor)
        binding.btnAiSettings.setOnClickListener {
            startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.AI_CONFIG)
            }
        }
        binding.btnAiSend.setOnClickListener {
            if (viewModel.isRequesting) {
                cancelCurrentRequest()
            } else {
                dispatchSend()
            }
        }
        binding.etAiInput.doAfterTextChanged {
            updateComposerState()
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
        binding.composerContainer.background = GradientDrawable().apply {
            cornerRadius = 18.dpToPx().toFloat()
            setColor(
                ColorUtils.adjustAlpha(
                    ColorUtils.blendColors(primaryTextColor, Color.GRAY, 0.72f),
                    0.16f
                )
            )
            setStroke(1.dpToPx(), ColorUtils.adjustAlpha(primaryTextColor, 0.08f))
        }
        binding.composerContainer.elevation = 0f
        binding.etAiInput.setTextColor(primaryTextColor)
        binding.etAiInput.setHintTextColor(secondaryTextColor)
    }

    private fun observeMessages() {
        viewModel.messagesLiveData.observe(viewLifecycleOwner) { messages ->
            adapter.submitList(messages)
            val hasMessages = messages.isNotEmpty()
            binding.rvAiMessages.isVisible = hasMessages
            binding.tvAiEmpty.isVisible = !hasMessages
            if (hasMessages) {
                binding.rvAiMessages.post {
                    binding.rvAiMessages.scrollToPosition(messages.lastIndex)
                }
            }
        }
        viewModel.requestingLiveData.observe(viewLifecycleOwner) { requesting ->
            if (requesting) {
                showThinkingPanel()
            } else {
                hideThinkingPanel()
            }
            updateComposerState()
        }
        viewModel.thinkingLiveData.observe(viewLifecycleOwner) { thinking ->
            val detail = thinking.trim()
            binding.tvThinkingDetail.text = detail
            binding.thinkingScroll.isVisible = detail.isNotBlank() &&
                detail != getString(R.string.ai_chat_thinking)
            if (viewModel.isRequesting) {
                binding.thinkingPanel.isVisible = true
            }
        }
    }

    private fun dispatchSend() {
        val content = binding.etAiInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty() || viewModel.isRequesting) {
            return
        }
        if (AppConfig.aiCurrentProvider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
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

    private fun showThinkingPanel() {
        binding.tvThinkingTitle.text = getString(R.string.ai_chat_thinking)
        val detail = viewModel.thinkingLiveData.value.orEmpty().trim()
        binding.tvThinkingDetail.text = detail
        binding.thinkingScroll.isVisible = detail.isNotBlank() &&
            detail != getString(R.string.ai_chat_thinking)
        binding.thinkingPanel.isVisible = true
    }

    private fun hideThinkingPanel() {
        binding.thinkingPanel.isVisible = false
        binding.thinkingScroll.isVisible = false
    }

    private fun tintSendButton() {
        val typedArray = requireContext().theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent)
        )
        typedArray.use {
            val color = it.getColor(0, accentColor)
            binding.btnAiSend.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }
}
