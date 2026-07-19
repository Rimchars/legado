package io.legado.app.ui.association

import android.content.Context
import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

enum class OnlineImportPayloadType(
    val softLimitBytes: Long,
    val maxDownloadBytes: Long,
    val fileSuffix: String
) {
    PARAGRAPH_RULES(
        ParagraphRuleImportPolicy.MAX_EDITABLE_BYTES,
        ParagraphRuleImportPolicy.MAX_PACKAGE_BYTES,
        ".json"
    ),
    BUBBLE_PACKAGE(
        BubbleImportPolicy.SOFT_PACKAGE_BYTES,
        BubbleImportPolicy.MAX_PACKAGE_BYTES,
        ".zip"
    )
}

object BubbleImportPolicy {
    const val SOFT_PACKAGE_BYTES = 32L * 1024L * 1024L
    const val MAX_PACKAGE_BYTES = 256L * 1024L * 1024L
}

data class OnlineImportDownload(
    val file: File,
    val sourceUrl: String,
    val finalUrl: String,
    val contentType: String?,
    val size: Long,
    val privateNetwork: Boolean
) : Closeable {
    override fun close() {
        file.delete()
    }
}

data class PreparedOnlineImportUrl(
    val url: HttpUrl,
    val omitUserAgent: Boolean
)

class PrivateNetworkConfirmationRequiredException(host: String) :
    IOException("Private network import requires confirmation: $host")

object OnlineImportUrlPolicy {

    fun prepare(sourceUrl: String): PreparedOnlineImportUrl {
        val trimmed = sourceUrl.trim()
        val omitUserAgent = trimmed.endsWith(REQUEST_WITHOUT_UA_SUFFIX)
        val requestUrl = if (omitUserAgent) trimmed.removeSuffix(REQUEST_WITHOUT_UA_SUFFIX) else trimmed
        val parsed = requestUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Import source must be an http or https URL")
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw IllegalArgumentException("Import source must be an http or https URL")
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IllegalArgumentException("Import URLs must not contain credentials")
        }
        if (parsed.host.equals("localhost", ignoreCase = true)) {
            throw IllegalArgumentException("Localhost import URLs are not allowed")
        }
        return PreparedOnlineImportUrl(parsed, omitUserAgent)
    }

    internal fun validateAddresses(
        host: String,
        addresses: List<InetAddress>,
        allowPrivateNetwork: Boolean
    ): Boolean {
        if (addresses.isEmpty()) throw IOException("Import host could not be resolved: $host")
        var privateNetwork = false
        addresses.forEach { address ->
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
                throw IOException("Unsafe import address is not allowed: $host")
            }
            if (address.isSiteLocalAddress || address.isCarrierGradeNat() || address.isIpv6UniqueLocal()) {
                privateNetwork = true
            }
        }
        if (privateNetwork && !allowPrivateNetwork) {
            throw PrivateNetworkConfirmationRequiredException(host)
        }
        return privateNetwork
    }

    internal fun validateRedirect(from: HttpUrl, location: String): HttpUrl {
        val resolved = from.resolve(location) ?: throw IOException("Invalid import redirect URL")
        val prepared = prepare(resolved.toString())
        if (from.isHttps && !prepared.url.isHttps) {
            throw IOException("HTTPS import redirects must not downgrade to HTTP")
        }
        return prepared.url
    }

    internal fun validateLiteralHost(url: HttpUrl, allowPrivateNetwork: Boolean): Boolean {
        val host = url.host
        val literal = when {
            host.contains(':') -> true
            host.all { it.isDigit() || it == '.' } && host.contains('.') -> true
            else -> false
        }
        if (!literal) return false
        return validateAddresses(host, listOf(InetAddress.getByName(host)), allowPrivateNetwork)
    }

    private const val REQUEST_WITHOUT_UA_SUFFIX = "#requestWithoutUA"

    private fun InetAddress.isCarrierGradeNat(): Boolean {
        val raw = address
        return raw.size == 4 &&
            (raw[0].toInt() and 0xFF) == 100 &&
            (raw[1].toInt() and 0xFF) in 64..127
    }

    private fun InetAddress.isIpv6UniqueLocal(): Boolean {
        val raw = address
        return raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC
    }
}

internal class GuardedImportDns(
    private val delegate: Dns,
    private val allowPrivateNetwork: Boolean,
    private val privateNetworkSeen: AtomicBoolean
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (OnlineImportUrlPolicy.validateAddresses(hostname, addresses, allowPrivateNetwork)) {
            privateNetworkSeen.set(true)
        }
        return addresses
    }
}

internal val secureOnlineImportClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .build()
}

class OnlineImportDownloader(
    context: Context,
    private val client: OkHttpClient = secureOnlineImportClient
) {
    private val cacheDir = File(context.cacheDir, "online_import").apply { mkdirs() }

    suspend fun download(
        sourceUrl: String,
        payloadType: OnlineImportPayloadType,
        allowPrivateNetwork: Boolean = false
    ): OnlineImportDownload {
        val target = File(cacheDir, "import_${UUID.randomUUID()}${payloadType.fileSuffix}")
        return try {
            withContext(Dispatchers.IO) {
                downloadToFile(sourceUrl, payloadType, allowPrivateNetwork, target)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                target.delete()
            }
            throw error
        }
    }

    private suspend fun downloadToFile(
        sourceUrl: String,
        payloadType: OnlineImportPayloadType,
        allowPrivateNetwork: Boolean,
        target: File
    ): OnlineImportDownload {
        val prepared = OnlineImportUrlPolicy.prepare(sourceUrl)
        val privateNetworkSeen = AtomicBoolean(false)
        val guardedClient = client.newBuilder()
            .dns(GuardedImportDns(client.dns, allowPrivateNetwork, privateNetworkSeen))
            .connectionPool(ConnectionPool())
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val request = chain.request().withoutUserAgentIf(prepared.omitUserAgent)
                val route = chain.connection()?.route()
                    ?: throw IOException("Import connection route is unavailable")
                if (route.proxy.type() != Proxy.Type.DIRECT) {
                    throw IOException("Proxied import connections are not allowed")
                }
                val address = route.socketAddress.address
                if (OnlineImportUrlPolicy.validateAddresses(request.url.host, listOf(address), allowPrivateNetwork)) {
                    privateNetworkSeen.set(true)
                }
                chain.proceed(request)
            }
            .build()

        var currentUrl = prepared.url
        var redirectCount = 0
        while (true) {
            if (OnlineImportUrlPolicy.validateLiteralHost(currentUrl, allowPrivateNetwork)) {
                privateNetworkSeen.set(true)
            }
            val request = Request.Builder()
                .url(currentUrl)
                .apply {
                    if (!prepared.omitUserAgent) header(AppConst.UA_NAME, AppConfig.userAgent)
                }
                .build()
            val response = guardedClient.newCall(request).await()
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw IOException("Import redirect is missing Location")
                if (++redirectCount > MAX_REDIRECTS) throw IOException("Too many import redirects")
                currentUrl = OnlineImportUrlPolicy.validateRedirect(currentUrl, location)
                continue
            }
            return saveFinalResponse(
                response = response,
                sourceUrl = sourceUrl,
                finalUrl = currentUrl,
                payloadType = payloadType,
                target = target,
                privateNetwork = privateNetworkSeen.get()
            )
        }
    }

    private fun saveFinalResponse(
        response: Response,
        sourceUrl: String,
        finalUrl: HttpUrl,
        payloadType: OnlineImportPayloadType,
        target: File,
        privateNetwork: Boolean
    ): OnlineImportDownload {
        response.use { current ->
            if (!current.isSuccessful) {
                throw IOException("Import download failed with HTTP ${current.code}")
            }
            val body = current.body
            val declaredLength = body.contentLength()
            if (declaredLength > payloadType.maxDownloadBytes) {
                throw IOException(
                    "Import download is too large: $declaredLength > ${payloadType.maxDownloadBytes} bytes"
                )
            }
            val size = body.byteStream().use { input ->
                input.copyToFileLimited(target, payloadType.maxDownloadBytes)
            }
            if (size <= 0L) throw IOException("Import download is empty")
            return OnlineImportDownload(
                file = target,
                sourceUrl = sourceUrl,
                finalUrl = finalUrl.toString(),
                contentType = body.contentType()?.toString(),
                size = size,
                privateNetwork = privateNetwork
            )
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun Request.withoutUserAgentIf(omitUserAgent: Boolean): Request {
    if (!omitUserAgent) return this
    return newBuilder().removeHeader(AppConst.UA_NAME).build()
}

internal fun InputStream.copyToFileLimited(target: File, maxBytes: Long): Long {
    require(maxBytes >= 0L) { "maxBytes must be non-negative" }
    target.parentFile?.mkdirs()
    var total = 0L
    target.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) {
                val value = read()
                if (value < 0) break
                total++
                if (total > maxBytes) {
                    throw IOException("Import download exceeds limit of $maxBytes bytes")
                }
                output.write(value)
                continue
            }
            total += count.toLong()
            if (total > maxBytes) {
                throw IOException("Import download exceeds limit of $maxBytes bytes")
            }
            output.write(buffer, 0, count)
        }
        output.flush()
    }
    return total
}
