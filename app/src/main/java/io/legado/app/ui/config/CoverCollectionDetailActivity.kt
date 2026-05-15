package io.legado.app.ui.config

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityCoverCollectionDetailBinding
import io.legado.app.databinding.ItemCoverCollectionImageBinding
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class CoverCollectionDetailActivity : BaseActivity<ActivityCoverCollectionDetailBinding>() {

    override val binding by viewBinding(ActivityCoverCollectionDetailBinding::inflate)

    private var adapter: Adapter? = null
    private var isNight = false
    private var collectionId: String? = null
    private var collection: CoverCollectionManager.Collection? = null
    private val importImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val current = collection ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                collection = CoverCollectionManager.addImages(this@CoverCollectionDetailActivity, current, uris)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            bindCollection()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        isNight = intent.getBooleanExtra("isNight", false)
        collectionId = intent.getStringExtra("id")
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        adapter = Adapter()
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter
        loadCollection()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(R.string.cover_collection_import_images).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        importImages.launch("image/*")
        return true
    }

    private fun loadCollection() {
        lifecycleScope.launch {
            collection = CoverCollectionManager.get(isNight, collectionId)
            bindCollection()
        }
    }

    private fun bindCollection() {
        val current = collection ?: return
        title = current.name
        binding.titleBar.title = current.name
        adapter?.setItems(current.images)
    }

    private inner class Adapter :
        RecyclerAdapter<String, ItemCoverCollectionImageBinding>(this@CoverCollectionDetailActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemCoverCollectionImageBinding {
            return ItemCoverCollectionImageBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemCoverCollectionImageBinding,
            item: String,
            payloads: MutableList<Any>
        ) {
            Glide.with(binding.ivImage).load(item).centerCrop().into(binding.ivImage)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemCoverCollectionImageBinding) {
        }
    }
}
