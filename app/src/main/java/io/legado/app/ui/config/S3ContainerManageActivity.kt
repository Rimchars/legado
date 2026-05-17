package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityS3ContainerManageBinding
import io.legado.app.databinding.DialogS3ContainerEditBinding
import io.legado.app.databinding.ItemS3ContainerBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.S3CloudStorageBackend
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class S3ContainerManageActivity : BaseActivity<ActivityS3ContainerManageBinding>() {

    override val binding by viewBinding(ActivityS3ContainerManageBinding::inflate)

    private val adapter by lazy { Adapter() }
    private val waitDialog by lazy { WaitDialog(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this, R.color.background_card),
            ContextCompat.getColor(this, R.color.background_menu),
            UiCorner.actionRadius(this)
        )
        binding.btnAdd.setOnClickListener { showEditDialog(null) }
        binding.tvSummary.applyUiLabelStyle(this)
        binding.tvSummary.setTextColor(secondaryTextColor)
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        waitDialog.dismiss()
    }

    private fun reload() {
        adapter.setItems(AppCloudStorage.listContainers())
    }

    private fun showEditDialog(item: S3Container? = null) {
        val dialogBinding = DialogS3ContainerEditBinding.inflate(LayoutInflater.from(this))
        dialogBinding.bind(item)
        dialogBinding.tvAdvancedToggle.setOnClickListener {
            val show = !dialogBinding.layoutAdvanced.isVisible
            dialogBinding.layoutAdvanced.isVisible = show
            dialogBinding.tvAdvancedToggle.setText(
                if (show) R.string.s3_container_advanced_hide else R.string.s3_container_advanced_show
            )
        }
        if (item != null && item.hasAdvancedConfig()) {
            dialogBinding.layoutAdvanced.isVisible = true
            dialogBinding.tvAdvancedToggle.setText(R.string.s3_container_advanced_hide)
        }
        alert(if (item == null) R.string.s3_container_add else R.string.s3_container_edit) {
            customView { dialogBinding.root }
            okButton {
                saveDialogItem(item, dialogBinding)?.let { saved ->
                    if (item == null) {
                        refreshCapacity(saved, showWait = false)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun DialogS3ContainerEditBinding.bind(item: S3Container?) {
        editName.setText(item?.name.orEmpty())
        editEndpoint.setText(item?.endpoint.orEmpty())
        editBucket.setText(item?.bucket.orEmpty())
        editPrefix.setText(item?.prefix ?: "legado")
        editRegion.setText(item?.region ?: "auto")
        editAccessKey.setText(item?.accessKey.orEmpty())
        editSecretKey.setText(item?.secretKey.orEmpty())
        editSessionToken.setText(item?.sessionToken.orEmpty())
        editCapacity.setText(capacityMbToGbText(item?.capacityMb ?: DEFAULT_CAPACITY_MB))
        cbPathStyle.isChecked = item?.pathStyle ?: true
        cbEnabled.isChecked = item?.enabled ?: true
    }

    private fun saveDialogItem(oldItem: S3Container?, binding: DialogS3ContainerEditBinding): S3Container? {
        val parsed = S3Config.parseAddress(
            binding.editEndpoint.text?.toString().orEmpty(),
            binding.editBucket.text?.toString().orEmpty(),
            binding.editRegion.text?.toString().orEmpty(),
            binding.cbPathStyle.isChecked
        )
        val capacityMb = gbTextToCapacityMb(binding.editCapacity.text?.toString().orEmpty())
        val usedBytes = oldItem?.usedBytes?.coerceAtLeast(0L) ?: 0L
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return null
        }
        if (binding.editAccessKey.text?.toString().orEmpty().isBlank()
            || binding.editSecretKey.text?.toString().orEmpty().isBlank()
        ) {
            toastOnUi(R.string.s3_container_key_required)
            return null
        }
        val newItem = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = binding.editName.text?.toString()?.trim().orEmpty().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = binding.editPrefix.text?.toString()?.trim().orEmpty().ifBlank { "legado" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = binding.editAccessKey.text?.toString()?.trim().orEmpty(),
            secretKey = binding.editSecretKey.text?.toString()?.trim().orEmpty(),
            sessionToken = binding.editSessionToken.text?.toString()?.trim().orEmpty().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = if (capacityMb > 0) usedBytes.coerceAtMost(mbToBytes(capacityMb)) else usedBytes,
            lastRefreshTime = oldItem?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = binding.cbEnabled.isChecked
        )
        AppCloudStorage.addContainer(newItem)
        if (AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT) == null) {
            AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, newItem.id)
        }
        reload()
        return newItem
    }

    private fun showActions(item: S3Container) {
        val actions = listOf(
            Action.EDIT,
            Action.TEST,
            Action.REFRESH,
            Action.SET_DEFAULT,
            if (item.enabled) Action.DISABLE else Action.ENABLE,
            Action.DELETE
        )
        selector(AppCloudStorage.containerDisplayLabel(item), actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                Action.EDIT -> showEditDialog(item)
                Action.TEST -> testConnection(item)
                Action.REFRESH -> refreshCapacity(item)
                Action.SET_DEFAULT -> {
                    if (!item.enabled) {
                        toastOnUi(R.string.s3_container_disabled)
                        return@selector
                    }
                    AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, item.id)
                    toastOnUi(R.string.s3_container_set_default_success)
                    reload()
                }
                Action.ENABLE -> updateItem(item.copy(enabled = true, isFull = false))
                Action.DISABLE -> updateItem(item.copy(enabled = false))
                Action.DELETE -> confirmDelete(item)
            }
        }
    }

    private fun updateItem(item: S3Container) {
        AppCloudStorage.updateContainer(item)
        reload()
    }

    private fun confirmDelete(item: S3Container) {
        alert(R.string.s3_container_delete) {
            setMessage(getString(R.string.s3_container_delete_confirm, AppCloudStorage.containerDisplayLabel(item)))
            okButton {
                AppCloudStorage.deleteContainer(item.id)
                reload()
            }
            cancelButton()
        }
    }

    private fun testConnection(item: S3Container) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    S3CloudStorageBackend(S3ContainerScope.DEFAULT, item.id).check()
                }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: S3Container, showWait: Boolean = true) {
        if (showWait) {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppCloudStorage.refreshUsage(item.id) }
            }.onSuccess {
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            if (showWait) waitDialog.dismiss()
        }
    }

    private inner class Adapter :
        RecyclerAdapter<S3Container, ItemS3ContainerBinding>(this@S3ContainerManageActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemS3ContainerBinding {
            return ItemS3ContainerBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
                btnMore.background = UiCorner.actionSelector(
                    ContextCompat.getColor(root.context, R.color.background_card),
                    ContextCompat.getColor(root.context, R.color.background_menu),
                    UiCorner.actionRadius(root.context)
                )
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemS3ContainerBinding,
            item: S3Container,
            payloads: MutableList<Any>
        ) = binding.run {
            val isDefault = AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT)?.id == item.id
            tvName.text = if (isDefault) {
                getString(R.string.s3_container_default_name, AppCloudStorage.containerDisplayLabel(item))
            } else {
                AppCloudStorage.containerDisplayLabel(item)
            }
            tvPath.text = "${item.bucket}/${item.prefix.trim('/')}"
            val capacityMb = item.capacityMb.coerceAtLeast(0)
            val usedBytes = item.usedBytes.coerceAtLeast(0)
            tvCapacity.text = if (capacityMb > 0) {
                val capacityBytes = mbToBytes(capacityMb)
                getString(
                    R.string.s3_container_capacity_line,
                    formatBytes(capacityBytes),
                    formatBytes(usedBytes),
                    formatBytes((capacityBytes - usedBytes).coerceAtLeast(0)),
                    if (item.isFull) getString(R.string.yes) else getString(R.string.no)
                )
            } else {
                getString(R.string.s3_container_capacity_unlimited_line, formatBytes(usedBytes))
            }
            tvState.text = getString(
                R.string.s3_container_state_line,
                if (item.enabled) getString(R.string.s3_container_enabled) else getString(R.string.s3_container_disabled)
            )
            tvName.applyUiSectionTitleStyle(this@S3ContainerManageActivity)
            tvPath.applyUiLabelStyle(this@S3ContainerManageActivity)
            tvCapacity.applyUiLabelStyle(this@S3ContainerManageActivity)
            tvState.applyUiLabelStyle(this@S3ContainerManageActivity)
            listOf(tvPath, tvCapacity, tvState).forEach { it.setTextColor(secondaryTextColor) }
            btnMore.setOnClickListener { showActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemS3ContainerBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { showEditDialog(it) }
            }
        }
    }

    private enum class Action(val titleRes: Int) {
        EDIT(R.string.s3_container_edit),
        TEST(R.string.s3_container_test),
        REFRESH(R.string.s3_container_refresh_capacity),
        SET_DEFAULT(R.string.s3_container_set_default),
        ENABLE(R.string.s3_container_enable),
        DISABLE(R.string.s3_container_disable),
        DELETE(R.string.s3_container_delete)
    }

    private companion object {
        const val DEFAULT_CAPACITY_MB = 5L * 1024L
        fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L
        fun gbTextToCapacityMb(value: String): Long {
            val gb = value.trim().toDoubleOrNull() ?: return 0L
            if (gb <= 0.0) return 0L
            return max(ceil(gb * 1024.0).toLong(), 1L)
        }

        fun capacityMbToGbText(value: Long): String {
            if (value <= 0L) return ""
            val gb = value / 1024.0
            return if (value % 1024L == 0L) {
                (value / 1024L).toString()
            } else {
                String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
            }
        }

        fun formatBytes(value: Long): String {
            val bytes = value.coerceAtLeast(0L).toDouble()
            val gb = bytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) {
                "${formatDecimal(gb)} GB"
            } else {
                val mb = bytes / 1024.0 / 1024.0
                "${max(mb.toLong(), 0L)} MB"
            }
        }

        fun formatDecimal(value: Double): String {
            return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

        fun S3Container.hasAdvancedConfig(): Boolean {
            return prefix != "legado"
                || region !in setOf("auto", "us-east-1")
                || pathStyle != true
                || !sessionToken.isNullOrBlank()
        }
    }
}
