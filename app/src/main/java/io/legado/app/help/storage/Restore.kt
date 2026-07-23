package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.cloud.S3ContainerManager
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * 恢复
 */
object Restore {

    private val mutex = Mutex()

    private const val TAG = "Restore"
    private const val RESTORE_INSERT_BATCH_SIZE = 500

    internal val backgroundAssetDirNames = arrayOf(
        PreferKey.bgImage,
        PreferKey.bgImageN,
        PreferKey.bookInfoBgImage,
        PreferKey.bookInfoBgImageN,
        PreferKey.panelBgImage,
        PreferKey.panelBgImageN
    )

    suspend fun restore(context: Context, uri: Uri) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            if (uri.isContentScheme()) {
                val tempArchive = File(context.cacheDir, "restore_${UUID.randomUUID()}.zip")
                try {
                    DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use { input ->
                        BackupArchiveExtractor.copyToTemporaryFile(input, tempArchive)
                    }
                    BackupArchiveExtractor.extract(tempArchive, File(Backup.backupPath))
                } finally {
                    tempArchive.delete()
                }
            } else {
                BackupArchiveExtractor.extract(File(uri.path!!), File(Backup.backupPath))
            }
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreLocked(Backup.backupPath)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    suspend fun restoreLocked(path: String) {
        mutex.withLock {
            RestoreJournal.begin(RestoreJournal.buildSnapshotTargets(path))
            try {
                restore(path)
                RestoreJournal.markPendingValidation()
            } catch (e: Throwable) {
                RestoreJournal.rollbackNow("恢复过程异常: ${e.localizedMessage}")
                throw e
            }
        }
    }

    private suspend fun restore(path: String) {
        val aes = BackupAES()
        restoreBooks(path)
        fileToListT<Bookmark>(path, "bookmark.json")?.let {
            insertRestored(it) { items -> appDb.bookmarkDao.insert(*items) }
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let {
            insertRestored(it) { items -> appDb.bookGroupDao.insert(*items) }
        }
        fileToListT<BookSource>(path, "bookSource.json")?.let {
            insertRestored(it) { items -> appDb.bookSourceDao.insert(*items) }
        } ?: run {
            val bookSourceFile = File(path, "bookSource.json")
            if (bookSourceFile.exists()) {
                val json = bookSourceFile.readText()
                ImportOldData.importOldSource(json)
            }
        }
        fileToListT<RssSource>(path, "rssSources.json")?.let {
            insertRestored(it) { items -> appDb.rssSourceDao.insert(*items) }
        }
        fileToListT<RssStar>(path, "rssStar.json")?.let {
            insertRestored(it) { items -> appDb.rssStarDao.insert(*items) }
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
            insertRestored(it) { items -> appDb.replaceRuleDao.insert(*items) }
        }
        fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
            insertRestored(it) { items -> appDb.searchKeywordDao.insert(*items) }
        }
        fileToListT<RuleSub>(path, "sourceSub.json")?.let {
            insertRestored(it) { items -> appDb.ruleSubDao.insert(*items) }
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
            insertRestored(it) { items -> appDb.txtTocRuleDao.insert(*items) }
        }
        fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
            insertRestored(it) { items -> appDb.httpTTSDao.insert(*items) }
        }
        fileToListT<DictRule>(path, "dictRule.json")?.let {
            insertRestored(it) { items -> appDb.dictRuleDao.insert(*items) }
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
            insertRestored(it) { items -> appDb.keyboardAssistsDao.insert(*items) }
        }
        fileToListT<ReadRecord>(path, "readRecord.json")?.let {
            it.forEach { readRecord ->
                //判断是不是本机记录
                if (readRecord.deviceId != androidId) {
                    appDb.readRecordDao.insert(readRecord)
                } else {
                    val time = appDb.readRecordDao
                        .getReadTime(readRecord.deviceId, readRecord.bookName)
                    if (time == null || time < readRecord.readTime) {
                        appDb.readRecordDao.insert(readRecord)
                    }
                }
            }
        }
        restoreBookCharacters(path)
        File(path, "servers.json").takeIf {
            it.exists()
        }?.runCatching {
            var json = readText()
            if (!json.isJsonArray()) {
                json = aes.decryptStr(json)
            }
            GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                insertRestored(it) { items -> appDb.serverDao.insert(*items) }
            }
        }?.onFailure {
            AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.isFile
        }?.let { ruleFile ->
            runCatching {
                require(ruleFile.length() <= DirectLinkUpload.MAX_RULE_JSON_BYTES.toLong()) {
                    "备份中的直链上传规则超过大小限制"
                }
                ruleFile.readText(Charsets.UTF_8)
            }
                .mapCatching { json -> DirectLinkUpload.importRule(json).getOrThrow() }
                .onFailure { error ->
                    AppLog.put(
                        "备份中的直链上传规则无效，已保留当前配置\n" +
                            (error.localizedMessage ?: "未知错误"),
                        error
                    )
                    appCtx.toastOnUi("备份中的直链上传规则无效，已保留当前配置")
                }
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
        }?.onFailure {
            AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                ReadBookConfig.initConfigs()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                ReadBookConfig.initShareConfig()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
        }
        restoreBackgroundAssets(path)
        restoreThemePackages(path)
        restoreNavigationIcons(path)
        restoreTopBarPackages(path)
        restoreCoverCollections(path)
        restoreVisualResourcePackages(path)
        restoreSourceRuntime(path)
        appCtx.getSharedPreferences(path, "config")?.all?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword, PreferKey.s3SecretKey, PreferKey.s3SessionToken -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(key).isNullOrBlank()) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }

                        PreferKey.s3Containers -> {
                            edit.putString(key, S3ContainerManager.restoreEncryptedBackupJson(value.toString(), aes))
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.commit()
        }
        normalizeBackgroundPrefs()
        normalizeStringPrefs()
        refreshWebDavAfterRestore()
        restoreReadConfigBackgrounds()
        restoreAppliedUiPackages()
        appCtx.getSharedPreferences(path, "videoConfig")?.all?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                apply()
            }
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    private fun restoreBookCharacters(path: String) {
        val characters = fileToListT<BookCharacter>(path, Backup.bookCharactersFileName) ?: return
        kotlin.runCatching {
            restoreBookCharacterAvatars(path)
            val idMap = hashMapOf<Long, Long>()
            characters.forEach { character ->
                val oldId = character.id
                val normalized = character.copy(
                    avatar = restoreBookCharacterAvatarPath(character.avatar),
                    updatedAt = character.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
                val exists = appDb.bookCharacterDao.getCharacter(normalized.bookUrl, normalized.name)
                val newId = if (exists != null) {
                    appDb.bookCharacterDao.updateCharacter(normalized.copy(id = exists.id))
                    exists.id
                } else {
                    appDb.bookCharacterDao.insertCharacter(normalized.copy(id = 0L))
                }
                if (oldId > 0L) {
                    idMap[oldId] = newId
                }
            }
            fileToListT<BookCharacterRelation>(path, Backup.bookCharacterRelationsFileName)
                ?.forEach { relation ->
                    val fromId = idMap[relation.fromCharacterId] ?: return@forEach
                    val toId = idMap[relation.toCharacterId] ?: return@forEach
                    val saving = relation.copy(
                        id = 0L,
                        fromCharacterId = fromId,
                        toCharacterId = toId,
                        relationName = relation.relationName.ifBlank { "关系" },
                        updatedAt = relation.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                    val exists = appDb.bookCharacterDao.getRelation(
                        saving.bookUrl,
                        saving.fromCharacterId,
                        saving.toCharacterId,
                        saving.relationName
                    )
                    if (exists != null) {
                        appDb.bookCharacterDao.updateRelation(saving.copy(id = exists.id))
                    } else {
                        appDb.bookCharacterDao.insertRelation(saving)
                    }
                }
        }.onFailure {
            AppLog.put("恢复角色资料出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreBookCharacterAvatars(path: String) {
        val sourceDir = File(path, Backup.bookCharacterAvatarsDirName)
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = appCtx.externalFiles.getFile("bookCharacters", "avatars")
        copyDir(sourceDir, targetDir)
    }

    private fun restoreBookCharacterAvatarPath(avatar: String): String {
        if (avatar.isBlank() || avatar.startsWith("http", ignoreCase = true)) {
            return avatar
        }
        val fileName = File(avatar).name.takeIf { it.isNotBlank() } ?: return avatar
        val restoredFile = appCtx.externalFiles.getFile("bookCharacters", "avatars", fileName)
        return restoredFile.takeIf { it.exists() }?.absolutePath ?: avatar
    }

    private fun restoreSourceRuntime(path: String) {
        if (BackupConfig.ignoreSourceRuntime) return
        File(path, "sourceRuntime.json").takeIf { it.exists() }?.runCatching {
            val backup = GSON.fromJsonObject<SourceRuntimeBackup>(readText()).getOrNull()
                ?: return@runCatching
            backup.items.forEach { item ->
                val source = when (item.sourceType) {
                    "bookSource" -> appDb.bookSourceDao.getBookSource(item.sourceKey)
                    "rssSource" -> appDb.rssSourceDao.getByKey(item.sourceKey)
                    "httpTts" -> item.sourceKey.substringAfter("httpTts:").toLongOrNull()
                        ?.let { appDb.httpTTSDao.get(it) }
                    else -> null
                } ?: return@forEach
                restoreSourceRuntimeItem(source, item)
            }
        }?.onFailure {
            AppLog.put("恢复源运行期数据出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreSourceRuntimeItem(source: BaseSource, item: SourceRuntimeBackupItem) {
        item.loginInfo?.takeIf { it.isNotEmpty() }?.let {
            source.putLoginInfo(GSON.toJson(it))
        }
        source.putVariable(item.sourceVariable?.takeIf { it.isNotBlank() })
        item.sourceValues.forEach { (key, value) ->
            source.put(key, value)
        }
    }

    private inline fun <reified T> insertRestored(
        items: List<T>,
        insert: (Array<T>) -> Unit
    ) {
        items.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { chunk ->
            insert(chunk.toTypedArray())
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    private fun restoreBooks(path: String) {
        val fileName = "bookshelf.json"
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                val pendingNewBooks = ArrayList<Book>(RESTORE_INSERT_BATCH_SIZE)
                val ignoreLocalBook = BackupConfig.ignoreLocalBook
                var restoredCount = 0
                fun flushNewBooks() {
                    if (pendingNewBooks.isEmpty()) return
                    appDb.bookDao.insert(*pendingNewBooks.toTypedArray())
                    pendingNewBooks.clear()
                }
                forEachBookBackup(file) { index, book ->
                    book.upType()
                    if (book.isLocal) {
                        book.coverUrl = LocalBook.getCoverPath(book)
                        if (ignoreLocalBook) return@forEachBookBackup
                    }
                    if (appDb.bookDao.has(book.bookUrl)) {
                        try {
                            appDb.bookDao.update(book)
                        } catch (_: SQLiteConstraintException) {
                            appDb.bookDao.insert(book)
                        }
                    } else {
                        pendingNewBooks.add(book)
                        if (pendingNewBooks.size >= RESTORE_INSERT_BATCH_SIZE) {
                            flushNewBooks()
                        }
                    }
                    restoredCount++
                }
                flushNewBooks()
                LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 $restoredCount")
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
    }

    internal fun forEachBookBackup(
        file: File,
        onError: (Int, Throwable) -> Unit = { index, error ->
            AppLog.put(
                "bookshelf.json 第${index + 1}项读取失败\n${error.localizedMessage}",
                error
            )
        },
        onBook: (Int, Book) -> Unit
    ) {
        file.reader(Charsets.UTF_8).buffered().use { input ->
            JsonReader(input).use { reader ->
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    val currentIndex = index++
                    val element = JsonParser.parseReader(reader)
                    val bookJson = element.deepCopy()
                    sanitizeBookJson(bookJson)
                    runCatching {
                        GSON.fromJson(bookJson, Book::class.java)
                    }.onSuccess { book ->
                        if (book != null) onBook(currentIndex, book)
                    }.onFailure { error ->
                        onError(currentIndex, error)
                    }
                }
                reader.endArray()
            }
        }
    }

    private fun sanitizeBookJson(element: JsonElement) {
        if (!element.isJsonObject) {
            return
        }
        val bookJson = element.asJsonObject
        val readConfig = bookJson.get("readConfig") ?: return
        if (!readConfig.isJsonObject) {
            bookJson.remove("readConfig")
            return
        }
        sanitizeReadConfigJson(readConfig.asJsonObject)
    }

    private fun sanitizeReadConfigJson(readConfig: JsonObject) {
        val startDate = readConfig.get("startDate") ?: return
        if (startDate.isJsonPrimitive && startDate.asJsonPrimitive.isString) {
            val legacyStartDate = runCatching {
                JsonParser.parseString(startDate.asString)
            }.getOrNull()
            if (legacyStartDate?.isJsonObject == true && legacyStartDate.asJsonObject.isValidLocalDateJson()) {
                readConfig.add("startDate", legacyStartDate)
            } else {
                readConfig.remove("startDate")
            }
        } else if (startDate.isJsonObject && !startDate.asJsonObject.isValidLocalDateJson()) {
            readConfig.remove("startDate")
        }
    }

    private fun JsonObject.isValidLocalDateJson(): Boolean {
        val year = getIntOrNull("year") ?: return true
        val month = getIntOrNull("month") ?: return true
        val day = getIntOrNull("day") ?: return true
        return year > 0 && month in 1..12 && day in 1..31
    }

    private fun JsonObject.getIntOrNull(name: String): Int? {
        val value = get(name)?.takeIf { it.isJsonPrimitive } ?: return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun restoreBackgroundAssets(path: String) {
        backgroundAssetDirNames.forEach { dirName ->
            val sourceDir = File(path, dirName)
            if (!sourceDir.exists() || !sourceDir.isDirectory) return@forEach
            val targetDir = appCtx.externalFiles.getFile(dirName)
            kotlin.runCatching {
                FileUtils.delete(targetDir, deleteRootDir = true)
                copyDir(sourceDir, targetDir)
            }.onFailure {
                AppLog.put("恢复背景图片出错 $dirName\n${it.localizedMessage}", it)
            }
        }
    }

    private suspend fun restoreReadConfigBackgrounds() {
        val names = linkedSetOf<String>()
        fun collect(config: ReadBookConfig.Config) {
            readConfigBgFileName(config.bgType, config.bgStr)?.let(names::add)
            readConfigBgFileName(config.bgTypeNight, config.bgStrNight)?.let(names::add)
            readConfigBgFileName(config.bgTypeEInk, config.bgStrEInk)?.let(names::add)
        }
        ReadBookConfig.configList.forEach(::collect)
        collect(ReadBookConfig.shareConfig)
        if (names.isEmpty()) return
        val restored = AppCloudStorage.downBgs(names)
        if (restored.isEmpty()) return
        var changed = false
        fun normalize(config: ReadBookConfig.Config) {
            if (normalizeReadConfigBg(config, restored)) {
                changed = true
            }
        }
        ReadBookConfig.configList.forEach(::normalize)
        normalize(ReadBookConfig.shareConfig)
        if (!changed) return
        runCatching {
            FileUtils.delete(ReadBookConfig.configFilePath)
            FileUtils.createFileIfNotExist(ReadBookConfig.configFilePath)
                .writeText(GSON.toJson(ReadBookConfig.configList))
            FileUtils.delete(ReadBookConfig.shareConfigFilePath)
            FileUtils.createFileIfNotExist(ReadBookConfig.shareConfigFilePath)
                .writeText(GSON.toJson(ReadBookConfig.shareConfig))
            ReadBookConfig.initConfigs()
            ReadBookConfig.initShareConfig()
        }.onFailure {
            AppLog.put("恢复正文背景图片配置出错\n${it.localizedMessage}", it)
        }
    }

    private fun normalizeReadConfigBg(
        config: ReadBookConfig.Config,
        restored: Map<String, File>
    ): Boolean {
        var changed = false
        fun restorePath(bgType: Int, bgStr: String): String? {
            val fileName = readConfigBgFileName(bgType, bgStr) ?: return null
            return restored[fileName]?.absolutePath
        }
        restorePath(config.bgType, config.bgStr)?.let {
            if (config.bgStr != it) {
                config.bgStr = it
                changed = true
            }
        }
        restorePath(config.bgTypeNight, config.bgStrNight)?.let {
            if (config.bgStrNight != it) {
                config.bgStrNight = it
                changed = true
            }
        }
        restorePath(config.bgTypeEInk, config.bgStrEInk)?.let {
            if (config.bgStrEInk != it) {
                config.bgStrEInk = it
                changed = true
            }
        }
        return changed
    }

    private fun readConfigBgFileName(bgType: Int, bgStr: String?): String? {
        if (bgType != 2) return null
        val value = bgStr?.trim().orEmpty()
        if (value.isBlank() || value.startsWith("http", ignoreCase = true)) return null
        return File(value).name.takeIf { it.isNotBlank() }
    }

    private fun restoreThemePackages(path: String) {
        val sourceDir = File(path, "themePackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = ThemePackageManager.rootDir
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复主题包出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreNavigationIcons(path: String) {
        val sourceDir = File(path, "navigationBarPackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = NavigationBarIconConfig.rootDir
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复导航栏图标出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreTopBarPackages(path: String) {
        val sourceDir = File(path, "topBarPackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = TopBarConfig.rootDir
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复顶栏包出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreVisualResourcePackages(path: String) {
        restorePackageDirectory(
            sourceDir = File(path, Backup.advancedTitlePackagesDirName),
            targetDir = AdvancedTitlePackageManager.rootDir
        ) {
            AdvancedTitlePackageManager.invalidate()
        }
        restorePackageDirectory(
            sourceDir = File(path, Backup.bubblePackagesDirName),
            targetDir = BubblePackageManager.rootDir
        ) {
            BubblePackageManager.invalidateCurrentEntry()
        }
    }

    private fun restorePackageDirectory(
        sourceDir: File,
        targetDir: File,
        afterRestore: () -> Unit
    ) {
        if (!sourceDir.isDirectory) return
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
            afterRestore()
        }.onFailure {
            AppLog.put("Restore visual resource packages failed: ${it.localizedMessage}", it)
        }
    }

    private suspend fun refreshWebDavAfterRestore() {
        kotlin.runCatching {
            AppCloudStorage.upConfig()
        }.onFailure {
            AppLog.put("refresh WebDAV after restore failed\n${it.localizedMessage}", it)
        }
    }

    private suspend fun restoreAppliedUiPackages() {
        kotlin.runCatching {
            ThemePackageManager.restoreAppliedThemes(appCtx)
        }.onFailure {
            AppLog.put("恢复主题包出错\n${it.localizedMessage}", it)
        }
        listOf(false, true).forEach { isNight ->
            kotlin.runCatching { TopBarConfig.restoreApplied(isNight) }.onFailure {
                AppLog.put("恢复顶栏包出错\n${it.localizedMessage}", it)
            }
            kotlin.runCatching { NavigationBarIconConfig.restoreApplied(isNight) }.onFailure {
                AppLog.put("恢复底栏包出错\n${it.localizedMessage}", it)
            }
        }
        postEvent(EventBus.RECREATE, "")
        postEvent(EventBus.TOP_BAR_CHANGED, AppConfig.isNightTheme)
        postEvent(EventBus.NAVIGATION_BAR_CHANGED, AppConfig.isNightTheme)
    }
    private fun restoreCoverCollections(path: String) {
        val sourceDir = File(path, "coverCollections")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = CoverCollectionManager.rootDir
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复封面图集出错\n${it.localizedMessage}", it)
        }
    }

    private fun normalizeBackgroundPrefs() {
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        backgroundAssetDirNames.forEach { key ->
            val current = appCtx.getPrefString(key) ?: return@forEach
            if (current.isBlank() || current.startsWith("http")) return@forEach
            if (File(current).exists()) return@forEach
            val fileName = File(current).name.takeIf { it.isNotBlank() } ?: return@forEach
            val restoredFile = appCtx.externalFiles.getFile(key, fileName)
            if (restoredFile.exists()) {
                edit.putString(key, restoredFile.absolutePath)
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    private fun normalizeStringPrefs() {
        val stringKeys = setOf(
            PreferKey.language,
            PreferKey.themeMode,
            PreferKey.userAgent,
            PreferKey.customHosts,
            PreferKey.bookGroupStyle,
            PreferKey.bookshelfHiddenTags,
            PreferKey.bookshelfGroupTags,
            PreferKey.ttsEngine,
            PreferKey.prevKeys,
            PreferKey.nextKeys,
            PreferKey.mergedDiscoveryRssTarget,
            PreferKey.modernDiscoverySourceUrl,
            PreferKey.modernRssSourceUrl,
            PreferKey.aiProviderList,
            PreferKey.aiCurrentProviderId,
            PreferKey.aiModelConfigList,
            PreferKey.aiCurrentModelId,
            PreferKey.aiMcpServerList,
            PreferKey.aiChatSessionList,
            PreferKey.aiReadHistoryList,
            PreferKey.themePackageSyncTasks,
            PreferKey.aiCurrentChatSessionId,
            PreferKey.aiChatCompanionList,
            PreferKey.aiCurrentChatCompanionId,
            PreferKey.aiChatAutoSpeakEnabled,
            PreferKey.aiSystemPrompt,
            PreferKey.aiSkillPrompt,
            PreferKey.aiSkillList,
            PreferKey.aiWorldBookList,
            PreferKey.aiTavilyApiKey,
            PreferKey.aiTavilyBaseUrl,
            PreferKey.aiTavilySearchDepth,
            PreferKey.aiTavilyTopic,
            PreferKey.aiBaseUrl,
            PreferKey.aiApiKey,
            PreferKey.aiCurrentModel,
            PreferKey.aiModelList,
            PreferKey.bookshelfLayout,
            PreferKey.bookshelfSort,
            PreferKey.bookshelfReturnToTopAfterRead,
            PreferKey.bookExportFileName,
            PreferKey.bookImportFileName,
            PreferKey.episodeExportFileName,
            PreferKey.fontFolder,
            PreferKey.backupPath,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir,
            PreferKey.cloudStorageType,
            PreferKey.s3Endpoint,
            PreferKey.s3Region,
            PreferKey.s3Bucket,
            PreferKey.s3Prefix,
            PreferKey.s3AccessKey,
            PreferKey.s3SecretKey,
            PreferKey.s3SessionToken,
            PreferKey.s3Containers,
            PreferKey.s3ContainerSelections,
            PreferKey.exportType,
            PreferKey.chineseConverterType,
            PreferKey.launcherIcon,
            PreferKey.systemTypefaces,
            PreferKey.uiFontPath,
            PreferKey.uiFontPathN,
            PreferKey.titleFontPath,
            PreferKey.titleFontPathN,
            PreferKey.uiFontColor,
            PreferKey.uiFontColorN,
            PreferKey.titleFontColor,
            PreferKey.titleFontColorN,
            PreferKey.bottomBarEffectMode,
            PreferKey.bottomBarLayoutMode,
            PreferKey.bottomBarSidebarGravity,
            PreferKey.uiCornerScale,
            PreferKey.uiCornerScaleN,
            PreferKey.themeCardColor,
            PreferKey.themeCardColorN,
            PreferKey.themeMutedColor,
            PreferKey.themeMutedColorN,
            PreferKey.themeSearchFieldBackgroundColor,
            PreferKey.themeSearchFieldBackgroundColorN,
            PreferKey.themeTabBackgroundColor,
            PreferKey.themeTabBackgroundColorN,
            PreferKey.themeShelfColor,
            PreferKey.themeShelfColorN,
            PreferKey.uiCornerEffectMode,
            PreferKey.bookCoverShadow,
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark,
            PreferKey.screenOrientation,
            PreferKey.exportCharset,
            PreferKey.mangaFooterConfig,
            PreferKey.mangaColorFilter,
            PreferKey.contentSelectMenuConfig,
            PreferKey.contentSelectDefaultOpen,
            PreferKey.contentSelectSearchEngines,
            PreferKey.contentSelectSearchEngineId,
            PreferKey.advancedTitleConfig,
            PreferKey.advancedTitleLottieJson,
            PreferKey.advancedTitleLottiePath,
            PreferKey.advancedTitlePackage,
            PreferKey.advancedTitleHeightFactor,
            PreferKey.doublePageHorizontal,
            PreferKey.defaultBookTreeUri,
            PreferKey.readRecordComponents,
            PreferKey.readRecordRecentSnapshots,
            PreferKey.readRecordGoalConfig,
            PreferKey.localBookImportSort,
            PreferKey.welcomeImage,
            PreferKey.welcomeImageDark,
            PreferKey.welcomeShowText,
            PreferKey.welcomeShowTextDark,
            PreferKey.progressBarBehavior,
            PreferKey.webDavDeviceName,
            PreferKey.defaultHomePage,
            PreferKey.clickImgWay,
            PreferKey.updateToVariant,
            PreferKey.dThemeName,
            PreferKey.dNThemeName,
            PreferKey.bgImage,
            PreferKey.bookInfoBgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImageN,
            PreferKey.panelBgImage,
            PreferKey.panelBgImageN,
            PreferKey.navigationBarPackageDay,
            PreferKey.navigationBarPackageNight
        )
        val all = appCtx.defaultSharedPreferences.all
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        stringKeys.forEach { key ->
            val value = all[key] ?: return@forEach
            if (value !is String) {
                edit.putString(key, value.toString())
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    private fun copyDir(source: File, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, targetFile)
            } else {
                targetFile.parentFile?.mkdirs()
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }

}
