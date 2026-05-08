package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogImageBlurringBinding
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.applyTint
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream


@Suppress("SameParameterValue")
class ThemeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val requestCodeBgLight = 121
    private val requestCodeBgDark = 122
    private val requestCodeBookInfoBg = 123
    private val requestCodeBookInfoBgDark = 124
    private var pendingImageCropRequest: ImageCropHelper.Request? = null
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            handleSelectedImage(uri, it.requestCode)
        }
    }
    private val cropImage = registerForActivityResult(ImageCropContract()) { result ->
        val request = pendingImageCropRequest ?: return@registerForActivityResult
        pendingImageCropRequest = null
        if (result == null) {
            return@registerForActivityResult
        }
        if (java.io.File(result).exists()) {
            applyCroppedImage(request.requestCode, result)
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }

    private fun handleSelectedImage(uri: Uri, requestCode: Int) {
        if (uri.scheme?.lowercase() !in listOf("http", "https")) {
            startImageCrop(uri, requestCode)
            return
        }
        when (requestCode) {
            requestCodeBgLight -> setBgFromUri(uri, PreferKey.bgImage) {
                upPreferenceSummary(PreferKey.bgImage, getPrefString(PreferKey.bgImage))
                upTheme(false)
            }

            requestCodeBgDark -> setBgFromUri(uri, PreferKey.bgImageN) {
                upPreferenceSummary(PreferKey.bgImageN, getPrefString(PreferKey.bgImageN))
                upTheme(true)
            }

            requestCodeBookInfoBg -> setBgFromUri(uri, PreferKey.bookInfoBgImage) {
                upPreferenceSummary(PreferKey.bookInfoBgImage, getPrefString(PreferKey.bookInfoBgImage))
                recreateActivities()
            }

            requestCodeBookInfoBgDark -> setBgFromUri(uri, PreferKey.bookInfoBgImageN) {
                upPreferenceSummary(PreferKey.bookInfoBgImageN, getPrefString(PreferKey.bookInfoBgImageN))
                recreateActivities()
            }

            else -> startImageCrop(uri, requestCode)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_theme)
        if (Build.VERSION.SDK_INT < 26) {
            preferenceScreen.removePreferenceRecursively(PreferKey.launcherIcon)
        }
        upPreferenceSummary(PreferKey.bgImage, getPrefString(PreferKey.bgImage))
        upPreferenceSummary(PreferKey.bgImageN, getPrefString(PreferKey.bgImageN))
        upPreferenceSummary(PreferKey.bookInfoBgImage, getPrefString(PreferKey.bookInfoBgImage))
        upPreferenceSummary(PreferKey.bookInfoBgImageN, getPrefString(PreferKey.bookInfoBgImageN))
        findPreference<ColorPreference>(PreferKey.cBackground)?.let {
            it.onSaveColor = { color ->
                if (!ColorUtils.isColorLight(color)) {
                    toastOnUi(R.string.day_background_too_dark)
                    true
                } else {
                    false
                }
            }
        }
        findPreference<ColorPreference>(PreferKey.cNBackground)?.let {
            it.onSaveColor = { color ->
                if (ColorUtils.isColorLight(color)) {
                    toastOnUi(R.string.night_background_too_light)
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.theme_config, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_theme_mode -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(requireContext())
                return true
            }
        }
        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.mainTransparentStatusBar -> recreateActivities()
            PreferKey.transparentStatusBar -> recreateActivities()
            PreferKey.immNavigationBar -> recreateActivities()
            PreferKey.cPrimary,
            PreferKey.cAccent,
            PreferKey.cBackground,
            PreferKey.cBBackground,
            PreferKey.tNavBar-> {
                upTheme(false)
            }

            PreferKey.cNPrimary,
            PreferKey.cNAccent,
            PreferKey.cNBackground,
            PreferKey.cNBBackground,
            PreferKey.tNavBarN -> {
                upTheme(true)
            }

            PreferKey.bgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImage,
            PreferKey.bookInfoBgImageN -> {
                upPreferenceSummary(key, getPrefString(key))
            }

        }

    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (val key = preference.key) {
            PreferKey.bgImage -> selectBgAction(false)
            PreferKey.bgImageN -> selectBgAction(true)
            PreferKey.bookInfoBgImage -> selectBookInfoBgAction(false)
            PreferKey.bookInfoBgImageN -> selectBookInfoBgAction(true)
            "themeList" -> startActivity<ThemeManageActivity>()
            "theme_manage" -> startActivity<ThemeManageActivity>()
            "navigation_bar_manage" -> startActivity<NavigationBarManageActivity>()
            "saveDayTheme",
            "saveNightTheme" -> alertSaveTheme(key)

            "coverConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }

        }
        return super.onPreferenceTreeClick(preference)
    }

    @SuppressLint("InflateParams")
    private fun alertSaveTheme(key: String) {
        alert(R.string.theme_name) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "name"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { themeName ->
                    when (key) {
                        "saveDayTheme" -> {
                            ThemeConfig.saveDayTheme(requireContext(), themeName)
                        }

                        "saveNightTheme" -> {
                            ThemeConfig.saveNightTheme(requireContext(), themeName)
                        }
                    }
                }
            }
            cancelButton()
        }
    }

    private fun selectBgAction(isNight: Boolean) {
        val bgKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurringKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        val actions = arrayListOf(
            getString(R.string.background_image_blurring),
            getString(R.string.select_image)
        )
        if (!getPrefString(bgKey).isNullOrEmpty()) {
            actions.add(getString(R.string.delete))
        }
        context?.selector(items = actions) { _, i ->
            when (i) {
                0 -> alertImageBlurring(blurringKey) {
                    upTheme(isNight)
                }

                1 -> {
                    if (isNight) {
                        selectImage.launch {
                            requestCode = requestCodeBgDark
                            mode = HandleFileContract.IMAGE
                        }
                    } else {
                        selectImage.launch {
                            requestCode = requestCodeBgLight
                            mode = HandleFileContract.IMAGE
                        }
                    }
                }

                2 -> {
                    removePref(bgKey)
                    upTheme(isNight)
                }
            }
        }
    }

    private fun selectBookInfoBgAction(isNight: Boolean) {
        val bgKey = if (isNight) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage
        val actions = arrayListOf(getString(R.string.select_image))
        if (!getPrefString(bgKey).isNullOrEmpty()) {
            actions.add(getString(R.string.delete))
        }
        context?.selector(items = actions) { _, i ->
            when (i) {
                0 -> selectImage.launch {
                    requestCode = if (isNight) requestCodeBookInfoBgDark else requestCodeBookInfoBg
                    mode = HandleFileContract.IMAGE
                }

                1 -> {
                    removePref(bgKey)
                    upPreferenceSummary(bgKey, null)
                    recreateActivities()
                }
            }
        }
    }

    private fun alertImageBlurring(preferKey: String, success: () -> Unit) {
        alert(R.string.background_image_blurring) {
            val alertBinding = DialogImageBlurringBinding.inflate(layoutInflater).apply {
                getPrefInt(preferKey, 0).let {
                    seekBar.progress = it
                    textViewValue.text = it.toString()
                }
                seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        textViewValue.text = progress.toString()
                    }
                })
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.seekBar.progress.let {
                    putPrefInt(preferKey, it)
                    success.invoke()
                }
            }
            cancelButton()
        }
    }

    private fun upTheme(isNightTheme: Boolean) {
        if (AppConfig.isNightTheme == isNightTheme) {
            listView.post {
                ThemeConfig.applyTheme(requireContext())
                recreateActivities()
            }
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.bgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImage,
            PreferKey.bookInfoBgImageN -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            else -> preference.summary = value
        }
    }

    private fun startImageCrop(uri: Uri, requestCode: Int) {
        val aspect = ImageCropHelper.screenAspect(requireContext())
        val prefix = when (requestCode) {
            requestCodeBgLight -> "read_day"
            requestCodeBgDark -> "read_night"
            requestCodeBookInfoBg -> "book_info_day"
            requestCodeBookInfoBgDark -> "book_info_night"
            else -> "theme"
        }
        val request = ImageCropHelper.buildRequest(
            context = requireContext(),
            sourceUri = uri,
            requestCode = requestCode,
            aspectWidth = aspect.first,
            aspectHeight = aspect.second,
            dirName = "themeCroppedImages",
            prefix = prefix,
            targetWidth = 1600
        )
        pendingImageCropRequest = request
        cropImage.launch(request.params)
    }

    private fun applyCroppedImage(requestCode: Int, path: String) {
        when (requestCode) {
            requestCodeBgLight -> {
                putPrefString(PreferKey.bgImage, path)
                upPreferenceSummary(PreferKey.bgImage, path)
                upTheme(false)
            }

            requestCodeBgDark -> {
                putPrefString(PreferKey.bgImageN, path)
                upPreferenceSummary(PreferKey.bgImageN, path)
                upTheme(true)
            }

            requestCodeBookInfoBg -> {
                putPrefString(PreferKey.bookInfoBgImage, path)
                upPreferenceSummary(PreferKey.bookInfoBgImage, path)
                recreateActivities()
            }

            requestCodeBookInfoBgDark -> {
                putPrefString(PreferKey.bookInfoBgImageN, path)
                upPreferenceSummary(PreferKey.bookInfoBgImageN, path)
                recreateActivities()
            }
        }
    }

    private fun setBgFromUri(uri: Uri, preferenceKey: String, success: () -> Unit) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载背景图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    putPrefString(preferenceKey, file.absolutePath)
                    if (isAdded && context != null) {
                        success()
                    }
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
                success()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
