package io.legado.app.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogWebdavAccountEditBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3CapacityFullException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toEditable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private companion object {
        const val KEY_WEB_DAV_ACCOUNT_MANAGE = "webDavAccountManage"
        const val KEY_S3_CONTAINER_MANAGE = "s3ContainerManage"
    }

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var restoreJob: Job? = null
    private var activeBackupPath: String? = null
    private var pendingS3FullBackupPath: String? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText(R.string.restore)
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        migrateCloudStoragePreferenceTypes()
        addPreferencesFromResource(R.xml.pref_config_backup)
        findPreference<EditTextPreference>(PreferKey.webDavDir)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDir?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDeviceName)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDeviceName?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        updateCloudStorageVisibility()
        upWebDavAccountSummary()
        findPreference<Preference>(PreferKey.syncThemePackages)?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !hasCloudStorageAccount()) {
                    toastOnUi(R.string.cloud_storage_config_required)
                    false
                } else {
                    true
                }
            }
        }
        listOf(
            PreferKey.cloudStorageType,
            PreferKey.webDavDir,
            PreferKey.webDavDeviceName,
            PreferKey.backupPath
        ).forEach { upPreferenceSummary(it, getPrefString(it)) }
        findPreference<io.legado.app.lib.prefs.Preference>("web_dav_restore")
            ?.onLongClick {
                restoreFromLocal()
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun migrateCloudStoragePreferenceTypes() {
        val preferences = preferenceManager.sharedPreferences ?: return
        val booleanDefaults = mapOf(
            PreferKey.s3PathStyle to true,
            PreferKey.autoSwitchS3Container to true,
            PreferKey.s3FullWebDavFallbackNeverRemind to false
        )
        booleanDefaults.forEach { (key, defaultValue) ->
            val raw = preferences.all[key]
            if (raw != null && raw !is Boolean) {
                val value = when (raw) {
                    is String -> raw.toBooleanStrictOrNull() ?: (raw == "1")
                    is Number -> raw.toInt() != 0
                    else -> defaultValue
                }
                preferences.edit().putBoolean(key, value).apply()
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.cloudStorageType,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                upWebDavAccountSummary()
                updateCloudStorageVisibility()
                viewModel.upCloudStorageConfig()
            }

            PreferKey.s3Containers,
            PreferKey.s3ContainerSelections,
            PreferKey.autoSwitchS3Container -> listView.post {
                updateCloudStorageVisibility()
                viewModel.upCloudStorageConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun hasCloudStorageAccount(): Boolean {
        return when (CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))) {
            CloudStorageType.WEBDAV -> hasWebDavAccount()
            CloudStorageType.S3 -> hasS3Account()
        }
    }

    private fun hasWebDavAccount(): Boolean {
        return !getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !getPrefString(PreferKey.webDavPassword).isNullOrBlank()
    }

    private fun hasS3Account(): Boolean {
        return AppCloudStorage.listContainers().any {
            it.enabled && it.endpoint.isNotBlank() && it.bucket.isNotBlank()
                    && it.accessKey.isNotBlank() && it.secretKey.isNotBlank()
        }
    }

    private fun updateCloudStorageVisibility() {
        val type = CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))
        val webDavVisible = type == CloudStorageType.WEBDAV
        val s3Visible = type == CloudStorageType.S3
        listOf(KEY_WEB_DAV_ACCOUNT_MANAGE, PreferKey.webDavDir, PreferKey.webDavDeviceName)
            .forEach { findPreference<Preference>(it)?.isVisible = webDavVisible }
        listOf(KEY_S3_CONTAINER_MANAGE, PreferKey.autoSwitchS3Container)
            .forEach { findPreference<Preference>(it)?.isVisible = s3Visible }
    }

    private fun upWebDavAccountSummary() {
        val preference = findPreference<Preference>(KEY_WEB_DAV_ACCOUNT_MANAGE) ?: return
        val url = getPrefString(PreferKey.webDavUrl).orEmpty()
        val account = getPrefString(PreferKey.webDavAccount).orEmpty()
        val password = getPrefString(PreferKey.webDavPassword).orEmpty()
        preference.summary = when {
            url.isBlank() && account.isBlank() && password.isBlank() ->
                getString(R.string.webdav_account_manage_summary)
            account.isBlank() ->
                url.ifBlank { getString(R.string.webdav_account_manage_summary) }
            url.isBlank() ->
                account
            else ->
                "$url / $account"
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_account_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.web_dav_pw_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            PreferKey.webDavDir -> preference.summary = when (value) {
                null -> "legado"
                else -> value
            }

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the new value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> selectBackupPath.launch()
            PreferKey.restoreIgnore -> backupIgnore()
            KEY_WEB_DAV_ACCOUNT_MANAGE -> showWebDavAccountDialog()
            KEY_S3_CONTAINER_MANAGE -> requireContext().startActivity<S3ContainerManageActivity>()
            "web_dav_backup" -> backup()
            "web_dav_restore" -> restore()
            "import_old" -> restoreOld.launch()
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    private fun showWebDavAccountDialog() {
        val dialogBinding = DialogWebdavAccountEditBinding.inflate(layoutInflater).apply {
            editUrl.setText(getPrefString(PreferKey.webDavUrl).orEmpty())
            editAccount.setText(getPrefString(PreferKey.webDavAccount).orEmpty())
            editPassword.setText(getPrefString(PreferKey.webDavPassword).orEmpty())
            editPassword.setSelection(editPassword.text.length)
        }
        alert(R.string.webdav_account_manage) {
            customView { dialogBinding.root }
            okButton {
                appCtx.defaultSharedPreferences.edit {
                    putString(PreferKey.webDavUrl, dialogBinding.editUrl.text?.toString()?.trim().orEmpty())
                    putString(PreferKey.webDavAccount, dialogBinding.editAccount.text?.toString()?.trim().orEmpty())
                    putString(PreferKey.webDavPassword, dialogBinding.editPassword.text?.toString().orEmpty())
                }
                upWebDavAccountSummary()
                updateCloudStorageVisibility()
                viewModel.upCloudStorageConfig()
            }
            cancelButton()
        }
    }


    fun backup(ignoreS3FullPrompt: Boolean = false) {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath, uploadCloud = true, checkS3FullPrompt = !ignoreS3FullPrompt)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath, checkS3FullPrompt = !ignoreS3FullPrompt)
            }
        }
    }

    private fun backup(
        backupPath: String,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        checkS3FullPrompt: Boolean = true
    ) {
        if (uploadCloud && checkS3FullPrompt && shouldShowS3FullWebDavFallback()) {
            showS3FullWebDavFallbackDialog(backupPath)
            return
        }
        waitDialog.setText(R.string.backup)
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                activeBackupPath = backupPath
                Backup.backupLocked(requireContext(), backupPath, uploadCloud, uploadWebDavFallback)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: S3CapacityFullException) {
                ensureActive()
                if (showS3FullFallbackAfterFailure(backupPath)) {
                    return@launch
                }
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                activeBackupPath = null
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun shouldShowS3FullWebDavFallback(): Boolean {
        if (CloudStorageType.from(getPrefString(PreferKey.cloudStorageType)) != CloudStorageType.S3) {
            return false
        }
        if (appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)) {
            return false
        }
        val items = AppCloudStorage.listContainers().filter { it.enabled }
        return items.isNotEmpty() && items.all { it.isFull }
    }

    private fun showS3FullFallbackAfterFailure(backupPath: String): Boolean {
        if (appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)) {
            return false
        }
        showS3FullWebDavFallbackDialog(backupPath)
        return true
    }

    private fun showS3FullWebDavFallbackDialog(backupPath: String) {
        pendingS3FullBackupPath = backupPath
        val hasWebDav = !getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !getPrefString(PreferKey.webDavPassword).isNullOrBlank()
        alert(R.string.s3_full_webdav_fallback_title) {
            setMessage(
                if (hasWebDav) {
                    R.string.s3_full_webdav_fallback_message
                } else {
                    R.string.s3_full_no_webdav_fallback_message
                }
            )
            if (hasWebDav) {
                positiveButton(R.string.s3_full_webdav_fallback_upload) {
                    retryActiveBackup(uploadCloud = true, uploadWebDavFallback = true)
                }
            }
            negativeButton(R.string.s3_full_webdav_fallback_ignore) {
                retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
            }
            neutralButton(R.string.s3_full_webdav_fallback_never) {
                appCtx.defaultSharedPreferences.edit {
                    putBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, true)
                }
                retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
            }
        }
    }

    private fun retryActiveBackup(uploadCloud: Boolean, uploadWebDavFallback: Boolean) {
        val path = pendingS3FullBackupPath ?: activeBackupPath
        pendingS3FullBackupPath = null
        if (path.isNullOrBlank()) {
            backup(ignoreS3FullPrompt = true)
        } else {
            backup(
                path,
                uploadCloud = uploadCloud,
                uploadWebDavFallback = uploadWebDavFallback,
                checkS3FullPrompt = false
            )
        }
    }
    private fun backupUsePermission(path: String, checkS3FullPrompt: Boolean = true) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path, uploadCloud = true, checkS3FullPrompt = checkS3FullPrompt)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage("Cloud storage error\n${it.localizedMessage}\nRestore from local backup?")
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) {
            ensureCloudStorageForRestore()
            AppCloudStorage.getBackupNames()
        }
        if (AppCloudStorage.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        listView.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Cloud storage backup file not found")
        }
    }

    private suspend fun ensureCloudStorageForRestore() {
        val currentType = CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))
        if (currentType == CloudStorageType.S3 && hasS3Account()) {
            AppCloudStorage.upConfig()
            return
        }
        if (currentType == CloudStorageType.WEBDAV && hasWebDavAccount()) {
            AppCloudStorage.upConfig()
            return
        }
        val fallbackType = when {
            hasS3Account() -> CloudStorageType.S3
            hasWebDavAccount() -> CloudStorageType.WEBDAV
            else -> throw NoStackTraceException("Cloud storage is not configured")
        }
        appCtx.defaultSharedPreferences.edit {
            putString(PreferKey.cloudStorageType, fallbackType.name)
        }
        withContext(Main) {
            updateCloudStorageVisibility()
            upPreferenceSummary(PreferKey.cloudStorageType, fallbackType.name)
        }
        AppCloudStorage.upConfig()
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText(R.string.restore)
        waitDialog.show()
        val task = Coroutine.async {
            AppCloudStorage.restore(name)
        }.onError {
            AppLog.put("云端恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("云端恢复出错\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitDialog.dismiss()
    }

}
