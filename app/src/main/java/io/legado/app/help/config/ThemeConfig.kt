package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.DefaultData
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.BookCover
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.CenterCropBitmapDrawable
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import java.io.FileOutputStream
import java.util.Locale

@Keep
object ThemeConfig {

    private var usableBgImageCacheKey: String? = null
    private var usableBgImageCacheValue: Boolean = false
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    val configList: ArrayList<Config> by lazy {
        val cList = getConfigs() ?: DefaultData.themeConfigs
        ArrayList(cList)
    }

    private var needClearImg = true

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun isDarkTheme(): Boolean {
        return getTheme() == Theme.Dark
    }

    fun applyDayNight(context: Context) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightInit(context: Context) {
        applyTheme(context)
        initNightMode()
    }

    private fun initNightMode() {
        val targetMode =
            if (AppConfig.isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    /**
     * 获取链接获取图片文件名
     */
    private fun getUrlToFile(url: String): String {
        val suffix = when {
            url.contains(".9.png", ignoreCase = true) -> ".9.png"
            url.contains(".png", ignoreCase = true) -> ".png"
            url.contains(".gif", ignoreCase = true) -> ".gif"
            url.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        return MD5Utils.md5Encode16(url) + suffix
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? {
        val themeMode = getTheme()
        val preferenceKey = when (themeMode) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return  null
        }
        var path = context.getPrefString(preferenceKey)
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) {
            val name = getUrlToFile(path)
            val fileRoot = context.externalFiles
            val filePath = FileUtils.getPath(fileRoot, preferenceKey, name)
            if (!FileUtils.exist(filePath)) return null
            path = filePath
        }
        if (path.endsWith(".9.png")) {
            val bgDrawable = BitmapUtils.decodeNinePatchDrawable(path)
            return bgDrawable
        }
        val bgImgBlu = when (themeMode) {
            Theme.Light -> context.getPrefInt(PreferKey.bgImageBlurring, 0)
            Theme.Dark -> context.getPrefInt(PreferKey.bgImageNBlurring, 0)
            else -> 0
        }
        val bgImage = BitmapUtils
            .decodeBitmap(path, metrics.widthPixels, metrics.heightPixels)
        if (bgImgBlu == 0) {
            return bgImage?.let { CenterCropBitmapDrawable(context.resources, it) }
        }
        return bgImage?.stackBlur(bgImgBlu)?.let { CenterCropBitmapDrawable(context.resources, it) }
    }

    fun hasUsableBgImage(context: Context): Boolean {
        val preferenceKey = when (getTheme()) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return false
        }
        val path = context.getPrefString(preferenceKey)?.takeIf { it.isNotBlank() } ?: return false
        val cacheKey = "$preferenceKey|$path"
        if (usableBgImageCacheKey == cacheKey) {
            return usableBgImageCacheValue
        }
        if (path.startsWith("http", ignoreCase = true)) {
            val filePath = FileUtils.getPath(context.externalFiles, preferenceKey, getUrlToFile(path))
            return FileUtils.exist(filePath).also {
                usableBgImageCacheKey = cacheKey
                usableBgImageCacheValue = it
            }
        }
        return File(path).exists().also {
            usableBgImageCacheKey = cacheKey
            usableBgImageCacheValue = it
        }
    }

    fun getFallbackBackgroundColor(context: Context): Int {
        return when {
            AppConfig.isEInkMode -> Color.WHITE
            AppConfig.isNightTheme -> context.getPrefInt(
                PreferKey.cNBackground,
                context.getCompatColor(R.color.md_grey_900)
            )
            else -> context.getPrefInt(
                PreferKey.cBackground,
                context.getCompatColor(R.color.md_grey_100)
            )
        }
    }

    fun upConfig() {
        addConfigs(getConfigs())
    }

    fun save() {
        val json = GSON.toJson(configList)
        FileUtils.delete(configFilePath)
        FileUtils.createFileIfNotExist(configFilePath).writeText(json)
    }

    fun delConfig(index: Int) {
        configList.removeAt(index)
        save()
    }

    fun addConfig(json: String): Boolean {
        GSON.fromJsonObject<Config>(json.trim { it < ' ' }).getOrNull()
            ?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    return true
                }
            }
        return false
    }

    fun addConfig(newConfig: Config) {
        if (!validateConfig(newConfig)) {
            return
        }
        var hasTheme = false
        configList.forEachIndexed { index, config ->
            if (newConfig.themeName == config.themeName) {
                configList[index] = newConfig
                hasTheme = true
                return@forEachIndexed
            }
        }
        if (!hasTheme) {
            configList.add(newConfig)
        }
        save()
    }

    fun addConfigs(newConfigs: List<Config>?) {
        val newConfigs = newConfigs?.filter{
            validateConfig(it)
        }
        if (newConfigs.isNullOrEmpty()) {
            return
        }
        newConfigs.forEach { newConfig ->
            val existingIndex = configList.indexOfFirst { it.themeName == newConfig.themeName }
            if (existingIndex != -1) {
                configList[existingIndex] = newConfig
            } else {
                configList.add(newConfig)
            }
        }
        save()
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun getConfigs(): List<Config>? {
        val configFile = File(configFilePath)
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }.onFailure {
                it.printOnDebug()
            }
        }
        return null
    }

    fun applyConfig(
        context: Context,
        config: Config,
        switchNightMode: Boolean = true,
        notify: Boolean = true
    ) {
        try {
            if (needClearImg) {
                needClearImg = false
                clearBg(context)
            }
            val primary = config.primaryColor.toColorInt()
            val accent = config.accentColor.toColorInt()
            val background = config.backgroundColor.toColorInt()
            val bBackground = config.bottomBackground.toColorInt()
            val isNightTheme = config.isNightTheme
            val backgroundPath = config.backgroundImgPath
            val bookInfoBackgroundPath = config.bookInfoBackgroundImgPath
            config.uiCornerScale?.let {
                context.putPrefString(PreferKey.uiCornerScale, it.coerceIn(0f, 3f).toPlainScale())
            }
            config.uiLayoutAlpha?.let {
                context.putPrefInt(PreferKey.uiLayoutAlpha, it.coerceIn(0, 100))
            }
            config.uiCornerSearchFollow?.let {
                context.putPrefBoolean(PreferKey.uiCornerSearchFollow, it)
            }
            config.uiCornerReplyFollow?.let {
                context.putPrefBoolean(PreferKey.uiCornerReplyFollow, it)
            }
            config.fontScale?.let {
                context.putPrefInt(PreferKey.fontScale, it.coerceIn(0, 16))
            }
            context.putPrefString(PreferKey.uiFontPath, config.uiFontPath.orEmpty())
            context.putPrefString(PreferKey.titleFontPath, config.titleFontPath.orEmpty())
            if (backgroundPath != null && backgroundPath.startsWith("http")) {
                val fileRoot = context.externalFiles
                val preferenceKey = if (isNightTheme) {
                    PreferKey.bgImageN
                } else {
                    PreferKey.bgImage
                }
                val name = getUrlToFile(backgroundPath)
                val fileFold = File(fileRoot, preferenceKey)
                if (!fileFold.exists()) {
                    fileFold.mkdirs()
                }
                val fileImg = File(fileFold, name)
                if (!fileImg.exists()) {
                    appCtx.toastOnUi(R.string.theme_background_downloading)
                    Coroutine.async {
                        kotlin.runCatching {
                            val res = okHttpClient.newCallResponse(0) {
                                url(backgroundPath)
                            }
                            res.body.byteStream().use { inputStream ->
                                FileOutputStream(fileImg).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }.onSuccess {
                            appCtx.toastOnUi(R.string.theme_background_downloaded)
                            if (notify) {
                                postEvent(EventBus.RECREATE, "")
                            }
                        }.onFailure {
                            appCtx.toastOnUi(it.localizedMessage)
                        }
                    }
                }
            }
            val backgroundBlur = config.backgroundImgBlur
            if (isNightTheme) {
                context.putPrefString(PreferKey.dNThemeName, config.themeName)
                context.putPrefInt(PreferKey.cNPrimary, primary)
                context.putPrefInt(PreferKey.cNAccent, accent)
                context.putPrefInt(PreferKey.cNBackground, background)
                context.putPrefInt(PreferKey.cNBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBarN, true)
                context.putPrefString(PreferKey.bgImageN, backgroundPath)
                context.putPrefInt(PreferKey.bgImageNBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bookInfoBgImageN, bookInfoBackgroundPath)
            } else {
                context.putPrefString(PreferKey.dThemeName, config.themeName)
                context.putPrefInt(PreferKey.cPrimary, primary)
                context.putPrefInt(PreferKey.cAccent, accent)
                context.putPrefInt(PreferKey.cBackground, background)
                context.putPrefInt(PreferKey.cBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBar, true)
                context.putPrefString(PreferKey.bgImage, backgroundPath)
                context.putPrefInt(PreferKey.bgImageBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bookInfoBgImage, bookInfoBackgroundPath)
            }
            if (switchNightMode) {
                AppConfig.isNightTheme = isNightTheme
            }
            if (!notify) {
                return
            }
            if (switchNightMode) {
                applyDayNight(context)
            } else {
                applyTheme(context)
                BookCover.upDefaultCover()
                postEvent(EventBus.RECREATE, "")
            }
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    fun getDurConfig(context: Context): Config {
        val isNight = AppConfig.isNightTheme
        val name = if (isNight) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNight) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    fun getThemeConfig(context: Context, isNightTheme: Boolean): Config {
        val name = if (isNightTheme) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNightTheme) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    private fun getDayTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(PreferKey.cPrimary, context.getCompatColor(R.color.md_brown_500))
        val accent =
            context.getPrefInt(PreferKey.cAccent, context.getCompatColor(R.color.md_red_600))
        val background =
            context.getPrefInt(PreferKey.cBackground, context.getCompatColor(R.color.md_grey_100))
        val bBackground =
            context.getPrefInt(PreferKey.cBBackground, context.getCompatColor(R.color.md_grey_200))
        val bgImgPath =
            context.getPrefString(PreferKey.bgImage)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageBlurring, 0)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImage)
        val stored = configList.firstOrNull {
            it.themeName == name && !it.isNightTheme
        }

        return mergeStoredThemeAssets(
            Config(
                themeName = name,
                isNightTheme = false,
                primaryColor = "#${primary.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${background.hexString}",
                bottomBackground = "#${bBackground.hexString}",
                transparentNavBar = true,
                backgroundImgPath = bgImgPath,
                backgroundImgBlur = bgImgBlur,
                bookInfoBackgroundImgPath = bookInfoBgImgPath,
                uiCornerScale = stored?.uiCornerScale ?: AppConfig.uiCornerScale,
                uiLayoutAlpha = stored?.uiLayoutAlpha ?: AppConfig.uiLayoutAlpha,
                uiCornerSearchFollow = stored?.uiCornerSearchFollow ?: AppConfig.uiCornerSearchFollow,
                uiCornerReplyFollow = stored?.uiCornerReplyFollow ?: AppConfig.uiCornerReplyFollow,
                fontScale = stored?.fontScale ?: appCtx.getPrefInt(PreferKey.fontScale, 0),
                uiFontPath = stored?.uiFontPath ?: AppConfig.uiFontPath,
                titleFontPath = stored?.titleFontPath ?: AppConfig.titleFontPath
            )
        )
    }

    fun saveDayTheme(context: Context, name: String) {
        val config = getDayTheme(context, name)
        addConfig(config)
    }

    private fun getNightTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(
                PreferKey.cNPrimary,
                context.getCompatColor(R.color.md_blue_grey_600)
            )
        val accent =
            context.getPrefInt(
                PreferKey.cNAccent,
                context.getCompatColor(R.color.md_deep_orange_800)
            )
        val background =
            context.getPrefInt(PreferKey.cNBackground, context.getCompatColor(R.color.md_grey_900))
        val bBackground =
            context.getPrefInt(PreferKey.cNBBackground, context.getCompatColor(R.color.md_grey_850))
        val bgImgPath =
            context.getPrefString(PreferKey.bgImageN)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageNBlurring, 0)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImageN)
        val stored = configList.firstOrNull {
            it.themeName == name && it.isNightTheme
        }
        return mergeStoredThemeAssets(
            Config(
                themeName = name,
                isNightTheme = true,
                primaryColor = "#${primary.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${background.hexString}",
                bottomBackground = "#${bBackground.hexString}",
                transparentNavBar = true,
                backgroundImgPath = bgImgPath,
                backgroundImgBlur = bgImgBlur,
                bookInfoBackgroundImgPath = bookInfoBgImgPath,
                uiCornerScale = stored?.uiCornerScale ?: AppConfig.uiCornerScale,
                uiLayoutAlpha = stored?.uiLayoutAlpha ?: AppConfig.uiLayoutAlpha,
                uiCornerSearchFollow = stored?.uiCornerSearchFollow ?: AppConfig.uiCornerSearchFollow,
                uiCornerReplyFollow = stored?.uiCornerReplyFollow ?: AppConfig.uiCornerReplyFollow,
                fontScale = stored?.fontScale ?: appCtx.getPrefInt(PreferKey.fontScale, 0),
                uiFontPath = stored?.uiFontPath ?: AppConfig.uiFontPath,
                titleFontPath = stored?.titleFontPath ?: AppConfig.titleFontPath
            )
        )
    }

    private fun mergeStoredThemeAssets(config: Config): Config {
        if (config.themeName.isBlank()) return config
        val stored = configList.firstOrNull {
            it.themeName == config.themeName && it.isNightTheme == config.isNightTheme
        } ?: return config
        return config.copy(
            backgroundImgPath = preferThemeAsset(config.backgroundImgPath, stored.backgroundImgPath),
            bookInfoBackgroundImgPath = preferThemeAsset(
                config.bookInfoBackgroundImgPath,
                stored.bookInfoBackgroundImgPath
            ),
            backgroundImgBlur = if (config.backgroundImgPath.isNullOrBlank() && !stored.backgroundImgPath.isNullOrBlank()) {
                stored.backgroundImgBlur
            } else {
                config.backgroundImgBlur
            },
            uiCornerScale = config.uiCornerScale ?: stored.uiCornerScale,
            uiLayoutAlpha = config.uiLayoutAlpha ?: stored.uiLayoutAlpha,
            uiCornerSearchFollow = config.uiCornerSearchFollow ?: stored.uiCornerSearchFollow,
            uiCornerReplyFollow = config.uiCornerReplyFollow ?: stored.uiCornerReplyFollow,
            fontScale = config.fontScale ?: stored.fontScale,
            uiFontPath = config.uiFontPath ?: stored.uiFontPath,
            titleFontPath = config.titleFontPath ?: stored.titleFontPath
        )
    }

    private fun preferThemeAsset(current: String?, fallback: String?): String? {
        if (!current.isNullOrBlank()) {
            if (current.startsWith("http", ignoreCase = true)) return current
            if (File(current).exists()) return current
        }
        return fallback?.takeIf {
            it.startsWith("http", ignoreCase = true) || File(it).exists()
        } ?: current
    }

    private fun Float.toPlainScale(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    fun saveNightTheme(context: Context, name: String) {
        val config = getNightTheme(context, name)
        addConfig(config)
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context) = with(context) {
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .transparentNavBar(true)
                    .apply()
            }

            AppConfig.isNightTheme -> {
                val primary =
                    getPrefInt(PreferKey.cNPrimary, getCompatColor(R.color.md_blue_grey_600))
                val accent =
                    getPrefInt(PreferKey.cNAccent, getCompatColor(R.color.md_deep_orange_800))
                val background =
                    getPrefInt(PreferKey.cNBackground, getCompatColor(R.color.md_grey_900))
                val bBackground =
                    getPrefInt(PreferKey.cNBBackground, getCompatColor(R.color.md_grey_850))
                val appBackground =
                    if (hasUsableBgImage(this)) Color.TRANSPARENT else ColorUtils.withAlpha(background, 1f)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(appBackground)
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(true)
                    .apply()
            }

            else -> {
                val primary =
                    getPrefInt(PreferKey.cPrimary, getCompatColor(R.color.md_brown_500))
                val accent =
                    getPrefInt(PreferKey.cAccent, getCompatColor(R.color.md_red_600))
                val background =
                    getPrefInt(PreferKey.cBackground, getCompatColor(R.color.md_grey_100))
                val bBackground =
                    getPrefInt(PreferKey.cBBackground, getCompatColor(R.color.md_grey_200))
                val appBackground =
                    if (hasUsableBgImage(this)) Color.TRANSPARENT else ColorUtils.withAlpha(background, 1f)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(appBackground)
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(true)
                    .apply()
            }
        }
    }

    fun clearBg(context: Context) {
        val (nightConfigs, dayConfigs) = configList.partition { it.isNightTheme }
        val fileRoot = context.externalFiles
        val nightBackgroundImgPaths = nightConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImageN, name)
            } else {
                path
            }
        }
        val dayBackgroundImgPaths = dayConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImage, name)
            } else {
                path
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (!dayBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (!nightBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int,
        var bookInfoBackgroundImgPath: String? = null,
        var uiCornerScale: Float? = null,
        var uiLayoutAlpha: Int? = null,
        var uiCornerSearchFollow: Boolean? = null,
        var uiCornerReplyFollow: Boolean? = null,
        var fontScale: Int? = null,
        var uiFontPath: String? = null,
        var titleFontPath: String? = null
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
                        && other.transparentNavBar == transparentNavBar
                        && other.backgroundImgPath == backgroundImgPath
                        && other.backgroundImgBlur == backgroundImgBlur
                        && other.bookInfoBackgroundImgPath == bookInfoBackgroundImgPath
                        && other.uiCornerScale == uiCornerScale
                        && other.uiLayoutAlpha == uiLayoutAlpha
                        && other.uiCornerSearchFollow == uiCornerSearchFollow
                        && other.uiCornerReplyFollow == uiCornerReplyFollow
                        && other.fontScale == fontScale
                        && other.uiFontPath == uiFontPath
                        && other.titleFontPath == titleFontPath
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur,
            "bookInfoBackgroundImgPath" to bookInfoBackgroundImgPath,
            "uiCornerScale" to uiCornerScale,
            "uiLayoutAlpha" to uiLayoutAlpha,
            "uiCornerSearchFollow" to uiCornerSearchFollow,
            "uiCornerReplyFollow" to uiCornerReplyFollow,
            "fontScale" to fontScale,
            "uiFontPath" to uiFontPath,
            "titleFontPath" to titleFontPath
        )

    }

}
