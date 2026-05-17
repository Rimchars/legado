package io.legado.app.lib.cloud

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.getPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File

class WebDavCloudStorageBackend : CloudStorageBackend {

    private data class WebDavConfig(
        val rootUrl: String,
        val account: String,
        val password: String
    )

    private data class ConfiguredWebDav(
        val config: WebDavConfig,
        val authorization: Authorization,
        val rootReady: Boolean = false
    )

    private val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val configMutex = Mutex()
    @Volatile
    private var configuredWebDav: ConfiguredWebDav? = null
    var defaultBookWebDav: RemoteBookWebDav? = null
        private set

    override val isOk: Boolean
        get() = configuredWebDav != null

    override val isJianGuoYun: Boolean
        get() = (configuredWebDav?.config?.rootUrl ?: normalizedRootUrl()).startsWith(defaultWebDavUrl, true)

    private fun normalizedRootUrl(): String {
        val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
        var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
        if (!url.endsWith("/")) url = "$url/"
        AppConfig.webDavDir?.trim()?.let {
            if (it.isNotEmpty()) url = "$url$it/"
        }
        return url
    }

    private fun latestConfigOrNull(): WebDavConfig? {
        val account = appCtx.getPrefString(PreferKey.webDavAccount)?.trim().orEmpty()
        val password = appCtx.getPrefString(PreferKey.webDavPassword).orEmpty()
        if (account.isEmpty() || password.isEmpty()) return null
        return WebDavConfig(
            rootUrl = normalizedRootUrl(),
            account = account,
            password = password
        )
    }

    override suspend fun upConfig() {
        ensureConfigured(required = false)
    }

    override suspend fun check() {
        val webDav = ensureConfigured()
        checkAuthorization(webDav.config.rootUrl, webDav.authorization)
    }

    private suspend fun checkAuthorization(rootUrl: String, auth: Authorization) {
        if (!WebDav(rootUrl, auth).check()) {
            configuredWebDav = null
            defaultBookWebDav = null
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    private suspend fun makeRootDirs(rootUrl: String, auth: Authorization) {
        listOf("", "bookProgress/", "books/", "background/", "themes/", "navigationBars/", "topBars/", "coverCollections/")
            .forEach { WebDav(rootUrl + it, auth).makeAsDir() }
    }

    override suspend fun makeDir(path: String): Boolean {
        val webDav = ensureConfigured()
        return WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).makeAsDir()
    }

    override suspend fun listFiles(path: String): List<CloudStorageFile> {
        val webDav = ensureConfigured()
        return WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).listFiles().map {
            CloudStorageFile(
                path = it.path,
                displayName = it.displayName,
                size = it.size,
                lastModify = it.lastModify,
                isDir = it.isDir
            )
        }
    }

    override suspend fun exists(path: String): Boolean {
        val webDav = ensureConfigured()
        return WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).exists()
    }

    override suspend fun upload(path: String, localPath: String, contentType: String) {
        val webDav = ensureConfigured()
        WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).upload(localPath, contentType)
    }

    override suspend fun upload(path: String, file: File, contentType: String) {
        val webDav = ensureConfigured()
        WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).upload(file, contentType)
    }

    override suspend fun upload(path: String, byteArray: ByteArray, contentType: String) {
        val webDav = ensureConfigured()
        WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).upload(byteArray, contentType)
    }

    override suspend fun upload(path: String, uri: Uri, contentType: String) {
        val webDav = ensureConfigured()
        WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).upload(uri, contentType)
    }

    override suspend fun download(path: String): ByteArray {
        val webDav = ensureConfigured()
        return WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).download()
    }

    override suspend fun downloadTo(path: String, file: File, replaceExisting: Boolean) {
        file.parentFile?.mkdirs()
        val webDav = ensureConfigured()
        WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).downloadTo(file.absolutePath, replaceExisting)
    }

    override suspend fun delete(path: String): Boolean {
        val webDav = ensureConfigured()
        return WebDav(resolve(webDav.config.rootUrl, path), webDav.authorization).delete()
    }

    private fun resolve(rootUrl: String, path: String): String {
        return rootUrl + path.trimStart('/')
    }

    private suspend fun ensureConfigured(required: Boolean = true): ConfiguredWebDav {
        val latestConfig = latestConfigOrNull()
        if (latestConfig == null) {
            configuredWebDav = null
            defaultBookWebDav = null
            if (required) throw NoStackTraceException("WebDAV not configured")
            return ConfiguredWebDav(WebDavConfig(normalizedRootUrl(), "", ""), Authorization("", ""))
        }
        configuredWebDav?.takeIf { it.config == latestConfig }?.let { configured ->
            if (configured.rootReady) return configured
        }
        return configMutex.withLock {
            val lockedConfig = latestConfigOrNull()
            if (lockedConfig == null) {
                configuredWebDav = null
                defaultBookWebDav = null
                if (required) throw NoStackTraceException("WebDAV not configured")
                return@withLock ConfiguredWebDav(WebDavConfig(normalizedRootUrl(), "", ""), Authorization("", ""))
            }
            configuredWebDav?.takeIf { it.config == lockedConfig }?.let { configured ->
                if (configured.rootReady) return@withLock configured
            }
            val auth = Authorization(lockedConfig.account, lockedConfig.password)
            if (configuredWebDav?.config != lockedConfig) {
                checkAuthorization(lockedConfig.rootUrl, auth)
            }
            makeRootDirs(lockedConfig.rootUrl, auth)
            ConfiguredWebDav(lockedConfig, auth, rootReady = true).also {
                defaultBookWebDav = RemoteBookWebDav("${lockedConfig.rootUrl}books/", auth)
                configuredWebDav = it
            }
        }
    }
}
