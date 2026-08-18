package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/**
 * 服务器
 */
@Parcelize
@Entity(tableName = "servers")
data class Server(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var type: TYPE = TYPE.WEBDAV,
    var config: String? = null,
    var sortNumber: Int = 0
) : Parcelable {

    enum class TYPE {
        WEBDAV,
        SMB
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is Server) {
            return id == other.id
        }
        return false
    }

    fun getConfigJsonObject(): JSONObject? {
        val json = config
        json ?: return null
        return JSONObject(json)
    }

    fun getWebDavConfig(): WebDavConfig? {
        return if (type == TYPE.WEBDAV) GSON.fromJsonObject<WebDavConfig>(config).getOrNull() else null
    }

    fun getSmbConfig(): SmbConfig? {
        return if (type == TYPE.SMB) GSON.fromJsonObject<SmbConfig>(config).getOrNull() else null
    }

    /**
     * 拼接路径后的远程根目录
     */
    fun getSmbRootUrl(): String {
        val config = getSmbConfig() ?: return ""
        return joinRootUrl(config.url, config.path)
    }

    fun getWebDavRootUrl(): String {
        val config = getWebDavConfig() ?: return ""
        return joinRootUrl(config.url, config.path)
    }

    private fun joinRootUrl(baseUrl: String, path: String): String {
        val p = path.replace("\\", "/").trim('/')
        if (p.isBlank()) return baseUrl
        return baseUrl.trimEnd('/') + "/" + p
    }

    @Parcelize
    data class WebDavConfig(
        var url: String,
        var username: String,
        var password: String,
        var path: String = ""
    ) : Parcelable

    /**
     * SMB服务器配置,url格式: smb://host:port/share/path
     */
    @Parcelize
    data class SmbConfig(
        var url: String = "",
        var username: String = "",
        var password: String = "",
        var path: String = ""
    ) : Parcelable

}