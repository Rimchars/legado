package io.legado.app.ui.config

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityCoverCollectionManageBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.ItemCoverCollectionBinding
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CoverCollectionManageActivity : BaseActivity<ActivityCoverCollectionManageBinding>() {

    override val binding by viewBinding(ActivityCoverCollectionManageBinding::inflate)

    private var adapter: Adapter? = null
    private var isNight = false
    private val importZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importZip(uri) }
    }
    private val exportZip = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        loadCollections()
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this@CoverCollectionManageActivity, R.color.background_menu),
            UiCorner.panelRadius(this@CoverCollectionManageActivity)
        )
        listOf(btnDay, btnNight).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@CoverCollectionManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@CoverCollectionManageActivity)
            )
        }
        adapter = Adapter()
        recyclerView.layoutManager = LinearLayoutManager(this@CoverCollectionManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnDay.setOnClickListener {
            if (isNight) {
                isNight = false
                updateTabs()
                loadCollections()
            }
        }
        btnNight.setOnClickListener {
            if (!isNight) {
                isNight = true
                updateTabs()
                loadCollections()
            }
        }
        btnAdd.setOnClickListener { showAddActions() }
        root.applyUiBodyTypefaceDeep(this@CoverCollectionManageActivity.uiTypeface())
        updateTabs()
    }

    override fun onResume() {
        super.onResume()
        loadCollections()
    }

    private fun updateTabs() = binding.run {
        btnDay.isSelected = !isNight
        btnNight.isSelected = isNight
        btnDay.setTextColor(if (!isNight) accentColor else primaryTextColor)
        btnNight.setTextColor(if (isNight) accentColor else primaryTextColor)
    }

    private fun loadCollections() {
        lifecycleScope.launch {
            val items = CoverCollectionManager.loadEntries(isNight)
            adapter?.setItems(items)
        }
    }

    private fun showAddActions() {
        selector(
            items = arrayListOf(
                getString(R.string.cover_collection_add),
                getString(R.string.cover_collection_import_zip)
            )
        ) { _, index ->
            when (index) {
                0 -> showCreateDialog()
                1 -> importZip.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun showCreateDialog() {
        alert(R.string.cover_collection_name) {
            val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.cover_collection_name)
            }
            customView { dialogBinding.root }
            okButton {
                val name = dialogBinding.editView.text?.toString().orEmpty()
                lifecycleScope.launch {
                    kotlin.runCatching {
                        CoverCollectionManager.create(name, isNight)
                    }.onFailure {
                        toastOnUi(it.localizedMessage)
                    }
                    loadCollections()
                }
            }
            cancelButton()
        }
    }

    private fun showRenameDialog(entry: CoverCollectionManager.Entry) {
        if (entry.source == CoverCollectionManager.Source.REMOTE) return
        alert(R.string.cover_collection_name) {
            val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.cover_collection_name)
                editView.setText(entry.collection.name)
            }
            customView { dialogBinding.root }
            okButton {
                val name = dialogBinding.editView.text?.toString().orEmpty()
                lifecycleScope.launch {
                    kotlin.runCatching { CoverCollectionManager.rename(entry.collection, name) }
                        .onFailure { toastOnUi(it.localizedMessage) }
                    loadCollections()
                }
            }
            cancelButton()
        }
    }

    private fun importZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = withContext(Dispatchers.IO) {
                    val dir = externalFiles.getFile("coverCollectionImports").apply { mkdirs() }
                    val target = File(dir, "cover_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target
                }
                CoverCollectionManager.importZip(this@CoverCollectionManageActivity, file, isNight)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            loadCollections()
        }
    }

    private fun openDetail(entry: CoverCollectionManager.Entry) {
        if (entry.source == CoverCollectionManager.Source.REMOTE) {
            runAction { CoverCollectionManager.download(entry) }
            return
        }
        val item = entry.collection
        startActivity<CoverCollectionDetailActivity> {
            putExtra("isNight", item.isNight)
            putExtra("id", item.id)
        }
    }

    private fun exportCollection(entry: CoverCollectionManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { CoverCollectionManager.exportZip(entry) }
                .onSuccess { zipFile ->
                    exportZip.launch {
                        mode = HandleFileContract.EXPORT
                        showUploadUrl = false
                        fileData = HandleFileContract.FileData(zipFile.name, zipFile, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { block() }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadCollections()
        }
    }

    private fun showActions(entry: CoverCollectionManager.Entry) {
        val actions = buildList {
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.RENAME)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.EXPORT)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.UPLOAD)
            if (entry.source != CoverCollectionManager.Source.LOCAL) add(CoverAction.DOWNLOAD)
            if (entry.source != CoverCollectionManager.Source.REMOTE) add(CoverAction.DELETE_LOCAL)
            if (entry.source != CoverCollectionManager.Source.LOCAL) add(CoverAction.DELETE_REMOTE)
        }
        selector(entry.collection.name, actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                CoverAction.RENAME -> showRenameDialog(entry)
                CoverAction.EXPORT -> exportCollection(entry)
                CoverAction.UPLOAD -> runAction { CoverCollectionManager.upload(entry) }
                CoverAction.DOWNLOAD -> runAction { CoverCollectionManager.download(entry) }
                CoverAction.DELETE_LOCAL -> runAction { CoverCollectionManager.deleteLocal(entry) }
                CoverAction.DELETE_REMOTE -> runAction { CoverCollectionManager.deleteRemote(entry) }
            }
        }
    }

    private inner class Adapter :
        RecyclerAdapter<CoverCollectionManager.Entry, ItemCoverCollectionBinding>(this@CoverCollectionManageActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemCoverCollectionBinding {
            return ItemCoverCollectionBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
                btnMore.background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    ContextCompat.getColor(root.context, R.color.background_menu),
                    UiCorner.actionRadius(root.context)
                )
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemCoverCollectionBinding,
            item: CoverCollectionManager.Entry,
            payloads: MutableList<Any>
        ) = binding.run {
            val collection = item.collection
            tvName.text = collection.name
            val source = getString(when (item.source) {
                CoverCollectionManager.Source.LOCAL -> R.string.theme_source_local
                CoverCollectionManager.Source.REMOTE -> R.string.theme_source_remote
                CoverCollectionManager.Source.BOTH -> R.string.theme_source_both
            })
            tvInfo.text = "${getString(R.string.cover_collection_images_count, collection.images.size)} · $source"
            tvName.applyUiSectionTitleStyle(context)
            tvInfo.applyUiLabelStyle(context)
            tvInfo.setTextColor(secondaryTextColor)
            Glide.with(ivPreview).load(collection.images.firstOrNull()).centerCrop().into(ivPreview)
            btnMore.setOnClickListener { showActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemCoverCollectionBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { openDetail(it) }
            }
        }
    }

    private enum class CoverAction(val titleRes: Int) {
        RENAME(R.string.edit),
        EXPORT(R.string.theme_export_zip),
        UPLOAD(R.string.theme_upload_remote),
        DOWNLOAD(R.string.theme_download_local),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote)
    }
}