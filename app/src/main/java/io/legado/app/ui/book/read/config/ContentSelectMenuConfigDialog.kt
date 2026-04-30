package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.postEvent

class ContentSelectMenuConfigDialog : BaseDialogFragment(R.layout.dialog_wait) {

    private data class MenuAction(val id: String, val titleRes: Int)

    companion object {
        private const val DEFAULT_ACTIONS = "replace,copy,bookmark,aloud,dict,ask_ai,generate_image"
        private val actions = listOf(
            MenuAction("replace", R.string.replace),
            MenuAction("copy", android.R.string.copy),
            MenuAction("bookmark", R.string.bookmark),
            MenuAction("aloud", R.string.read_aloud),
            MenuAction("dict", R.string.dict),
            MenuAction("ask_ai", R.string.ask_ai),
            MenuAction("generate_image", R.string.generate_image),
        )

        private val defaultOpenPairs = listOf(
            "" to R.string.default_none,
            "dict" to R.string.default_dict,
            "ask_ai" to R.string.default_ask_ai,
            "generate_image" to R.string.default_generate_image
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val saved = ctx.getPrefStringSet(PreferKey.contentSelectActions, null)
        val selected = (saved ?: DEFAULT_ACTIONS.split(",").toMutableSet()).toMutableSet()
        val labels = actions.map { getString(it.titleRes) }.toTypedArray()
        val checked = actions.map { selected.contains(it.id) }.toBooleanArray()

        val defaultOpen = ctx.getPrefString(PreferKey.contentSelectDefaultOpen, "").orEmpty()
        val defaultItems = defaultOpenPairs.map { getString(it.second) }.toTypedArray()
        var defaultIndex = defaultOpenPairs.indexOfFirst { it.first == defaultOpen }.coerceAtLeast(0)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 0)
        }

        val actionsDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.content_select_actions)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val id = actions[which].id
                if (isChecked) selected.add(id) else selected.remove(id)
            }
            .create()

        val defaultDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.content_select_default_open)
            .setSingleChoiceItems(defaultItems, defaultIndex) { _, which ->
                defaultIndex = which
            }
            .create()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.content_select_menu_config)
            .setView(container)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                if (selected.isEmpty()) {
                    selected += "copy"
                }
                ctx.putPrefStringSet(PreferKey.contentSelectActions, selected)
                val chosenDefault = defaultOpenPairs[defaultIndex].first
                ctx.putPrefString(PreferKey.contentSelectDefaultOpen, chosenDefault)
                // 避免默认打开项不在动作集合中
                if (chosenDefault.isNotEmpty() && !selected.contains(chosenDefault)) {
                    selected.add(chosenDefault)
                    ctx.putPrefStringSet(PreferKey.contentSelectActions, selected)
                }
                postEvent("contentSelectMenuConfigChanged", true)
            }
            .setNeutralButton(R.string.content_select_actions) { _, _ ->
                actionsDialog.show()
            }
            .setNegativeButton(R.string.content_select_default_open) { _, _ ->
                defaultDialog.show()
            }
            .create()

        dialog.window?.setGravity(Gravity.CENTER)
        return dialog
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit
}
