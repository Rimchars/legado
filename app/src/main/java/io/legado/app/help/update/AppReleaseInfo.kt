package io.legado.app.help.update

import android.os.Build
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String
) {
    private val apkNameRegex = Regex("""^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$""")
    private val match = apkNameRegex.matchEntire(name)
    val versionName: String = match?.groupValues?.getOrNull(1).orEmpty()
    val versionCode: Long = match?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
    val abi: String? = when {
        name.contains("arm64-v8a", ignoreCase = true) -> "arm64-v8a"
        name.contains("armeabi-v7a", ignoreCase = true) -> "armeabi-v7a"
        name.contains("x86_64", ignoreCase = true) -> "x86_64"
        name.contains("x86", ignoreCase = true) -> "x86"
        else -> null
    }

    fun supportsDeviceAbi(): Boolean {
        val assetAbi = abi ?: return true
        return Build.SUPPORTED_ABIS.any { it.equals(assetAbi, ignoreCase = true) }
    }
}

enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASES,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }

}

@Keep
data class GithubRelease(
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val isPreRelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(isPreRelease, body) }
    }
}
@Keep
data class GiteeRelease(
    val assets: List<GiteeAsset>?,
    val body: String,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("prerelease")
    val prerelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(prerelease, body, createdAt) }
    }
}

@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()

        val appVariant = when {
            preRelease && name.contains("releaseA") -> AppVariant.BETA_RELEASEA
            preRelease && name.contains("releaseS") -> AppVariant.BETA_RELEASES
            preRelease && name.contains("release") -> AppVariant.BETA_RELEASE
            else -> AppVariant.OFFICIAL
        }

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, url)
    }
}

@Keep
data class GiteeAsset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("created_at")
    val createdAt: String? = null,
) {
    val isValid: Boolean
        get() = apkUrl.contains(".apk")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String, releaseCreatedAt: String?): AppReleaseInfo {
        val timestamp = runCatching {
            Instant.parse(createdAt ?: releaseCreatedAt).toEpochMilli()
        }.getOrDefault(0L)

        val appVariant = when {
            name.contains("releaseA") -> AppVariant.BETA_RELEASEA
            name.contains("releaseS") -> AppVariant.BETA_RELEASES
            name.contains("release") -> AppVariant.BETA_RELEASE //preRelease &&
            else -> AppVariant.OFFICIAL
        }

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, apkUrl)
    }
}
