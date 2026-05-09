package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File

object RestoreJournal {

    private const val STATUS_APPLYING = "applying"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_CRASHED = "crashed"
    private const val VALIDATE_TIMEOUT = 10 * 60 * 1000L

    private val rootDir: File
        get() = appCtx.filesDir.getFile("restore_journal")

    private val snapshotDir: File
        get() = rootDir.getFile("snapshot")

    private val stateFile: File
        get() = rootDir.getFile("state.json")

    data class Entry(
        val targetPath: String,
        val snapshotPath: String,
        val directory: Boolean,
        val existed: Boolean
    )

    data class State(
        var status: String,
        val startedAt: Long = System.currentTimeMillis(),
        val entries: MutableList<Entry> = arrayListOf()
    )

    fun buildSnapshotTargets(backupPath: String): List<File> {
        val path = File(backupPath)
        val targets = arrayListOf<File>()
        fun addIfBackupExists(fileName: String, targetPath: String) {
            if (File(path, fileName).exists()) {
                targets.add(File(targetPath))
            }
        }
        addIfBackupExists(ThemeConfig.configFileName, ThemeConfig.configFilePath)
        addIfBackupExists(ReadBookConfig.configFileName, ReadBookConfig.configFilePath)
        addIfBackupExists(ReadBookConfig.shareConfigFileName, ReadBookConfig.shareConfigFilePath)
        addIfBackupExists("config.xml", sharedPrefsFile("${appCtx.packageName}_preferences").absolutePath)
        addIfBackupExists("videoConfig.xml", sharedPrefsFile(VIDEO_PREF_NAME).absolutePath)
        Restore.backgroundAssetDirNames.forEach { dirName ->
            if (File(path, dirName).isDirectory) {
                targets.add(appCtx.externalFiles.getFile(dirName))
            }
        }
        if (File(path, "themePackages").isDirectory) {
            targets.add(ThemePackageManager.rootDir)
        }
        if (File(path, "navigationBarPackages").isDirectory) {
            targets.add(NavigationBarIconConfig.rootDir)
        }
        return targets.distinctBy { it.absolutePath }
    }

    private fun sharedPrefsFile(name: String): File {
        return File(appCtx.applicationInfo.dataDir, "shared_prefs/$name.xml")
    }

    fun begin(targets: List<File>) {
        clear()
        rootDir.mkdirs()
        snapshotDir.mkdirs()
        val state = State(status = STATUS_APPLYING)
        targets.forEachIndexed { index, target ->
            val snapshot = snapshotDir.getFile(index.toString())
            if (target.exists()) {
                if (target.isDirectory) {
                    FileUtils.copy(target, snapshot)
                } else {
                    snapshot.parentFile?.mkdirs()
                    target.copyTo(snapshot, overwrite = true)
                }
                state.entries.add(
                    Entry(target.absolutePath, snapshot.absolutePath, target.isDirectory, true)
                )
            } else {
                state.entries.add(
                    Entry(target.absolutePath, snapshot.absolutePath, target.isDirectory, false)
                )
            }
        }
        writeState(state)
    }

    fun markPendingValidation() {
        val state = readState() ?: return
        state.status = STATUS_PENDING
        writeState(state)
    }

    fun markStableIfPending() {
        val state = readState() ?: return
        if (state.status == STATUS_PENDING) {
            clear()
        }
    }

    fun markCrash() {
        val state = readState() ?: return
        val now = System.currentTimeMillis()
        if (state.status == STATUS_APPLYING || now - state.startedAt <= VALIDATE_TIMEOUT) {
            state.status = STATUS_CRASHED
            writeState(state)
        }
    }

    fun recoverIfNeeded(reason: String) {
        val state = readState() ?: return
        when (state.status) {
            STATUS_APPLYING, STATUS_CRASHED -> rollback(state, reason)
            STATUS_PENDING -> {
                if (System.currentTimeMillis() - state.startedAt > VALIDATE_TIMEOUT) {
                    clear()
                }
            }
        }
    }

    fun rollbackNow(reason: String) {
        readState()?.let {
            rollback(it, reason)
        }
    }

    private fun rollback(state: State, reason: String) {
        kotlin.runCatching {
            state.entries.asReversed().forEach { entry ->
                val target = File(entry.targetPath)
                if (entry.existed) {
                    val snapshot = File(entry.snapshotPath)
                    if (snapshot.exists()) {
                        if (target.exists()) {
                            FileUtils.delete(target, deleteRootDir = true)
                        }
                        target.parentFile?.mkdirs()
                        FileUtils.copy(snapshot, target)
                    }
                } else if (target.exists()) {
                    FileUtils.delete(target, deleteRootDir = true)
                }
            }
        }.onFailure {
            AppLog.put("恢复备份回滚失败\n${it.localizedMessage}", it)
        }
        clear()
        AppLog.put("恢复备份已回滚: $reason")
    }

    private fun readState(): State? {
        if (!stateFile.exists()) return null
        return GSON.fromJsonObject<State>(stateFile.readText()).getOrNull() ?: run {
            clear()
            null
        }
    }

    private fun writeState(state: State) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(GSON.toJson(state))
    }

    private fun clear() {
        if (rootDir.exists()) {
            FileUtils.delete(rootDir, deleteRootDir = true)
        }
    }

}
