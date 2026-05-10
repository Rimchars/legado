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
            val items = CoverCollectionManager.load(isNight)
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

    private fun openDetail(item: CoverCollectionManager.Collection) {
        startActivity<CoverCollectionDetailActivity> {
            putExtra("isNight", item.isNight)
            putExtra("id", item.id)
        }
    }

    private inner class Adapter :
        RecyclerAdapter<CoverCollectionManager.Collection, ItemCoverCollectionBinding>(this@CoverCollectionManageActivity) {

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
            item: CoverCollectionManager.Collection,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.name
            tvInfo.text = getString(R.string.cover_collection_images_count, item.images.size)
            tvName.applyUiSectionTitleStyle(context)
            tvInfo.applyUiLabelStyle(context)
            tvInfo.setTextColor(secondaryTextColor)
            Glide.with(ivPreview).load(item.images.firstOrNull()).centerCrop().into(ivPreview)
            btnMore.setOnClickListener {
                selector(arrayListOf(getString(R.string.delete))) { _, index ->
                    if (index == 0) {
                        lifecycleScope.launch {
                            CoverCollectionManager.delete(item)
                            loadCollections()
                        }
                    }
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemCoverCollectionBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { openDetail(it) }
            }
        }
    }
}
