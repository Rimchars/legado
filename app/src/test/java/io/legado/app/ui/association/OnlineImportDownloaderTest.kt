package io.legado.app.ui.association

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class OnlineImportDownloaderTest {

    @Test
    fun bubblePackagesUseSeparateSoftAndHardLimits() {
        assertEquals(32L * 1024L * 1024L, OnlineImportPayloadType.BUBBLE_PACKAGE.softLimitBytes)
        assertEquals(256L * 1024L * 1024L, OnlineImportPayloadType.BUBBLE_PACKAGE.maxDownloadBytes)
    }

    @Test
    fun productionClientUsesDirectConnectionsAndManualRedirects() {
        assertEquals(Proxy.NO_PROXY, secureOnlineImportClient.proxy)
        assertFalse(secureOnlineImportClient.followRedirects)
        assertFalse(secureOnlineImportClient.followSslRedirects)
    }

    @Test
    fun preparesHttpUrlAndLegacyUserAgentMarker() {
        val prepared = OnlineImportUrlPolicy.prepare(
            "https://example.com/rules.json#requestWithoutUA"
        )

        assertEquals("https://example.com/rules.json", prepared.url.toString())
        assertTrue(prepared.omitUserAgent)
    }

    @Test
    fun normalUrlKeepsUserAgent() {
        val prepared = OnlineImportUrlPolicy.prepare("http://example.com/bubble.zip")

        assertFalse(prepared.omitUserAgent)
    }

    @Test
    fun networkRequestRemovesAutomaticallyAddedUserAgentWhenRequested() {
        val request = Request.Builder()
            .url("https://example.com/rules.json")
            .header("User-Agent", "okhttp/test")
            .build()

        val sanitized = request.withoutUserAgentIf(omitUserAgent = true)

        assertEquals(null, sanitized.header("User-Agent"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpUrl() {
        OnlineImportUrlPolicy.prepare("file:///sdcard/rules.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLocalhost() {
        OnlineImportUrlPolicy.prepare("http://localhost/rules.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUrlCredentials() {
        OnlineImportUrlPolicy.prepare("https://user:secret@example.com/rules.json")
    }

    @Test(expected = PrivateNetworkConfirmationRequiredException::class)
    fun privateLiteralRequiresConfirmation() {
        OnlineImportUrlPolicy.validateAddresses(
            host = "192.168.1.2",
            addresses = listOf(InetAddress.getByName("192.168.1.2")),
            allowPrivateNetwork = false
        )
    }

    @Test(expected = IOException::class)
    fun loopbackLiteralIsRejectedBeforeConnection() {
        OnlineImportUrlPolicy.validateLiteralHost(
            "http://127.0.0.1/rules.json".toHttpUrl(),
            allowPrivateNetwork = true
        )
    }

    @Test
    fun guardedDnsReturnsTheSameValidatedAddresses() {
        val expected = listOf(InetAddress.getByName("93.184.216.34"))
        val seenPrivate = AtomicBoolean(false)
        val dns = GuardedImportDns(
            delegate = Dns { expected },
            allowPrivateNetwork = false,
            privateNetworkSeen = seenPrivate
        )

        assertEquals(expected, dns.lookup("example.com"))
        assertFalse(seenPrivate.get())
    }

    @Test(expected = IOException::class)
    fun guardedDnsRejectsLoopbackBeforeReturningCandidates() {
        val dns = GuardedImportDns(
            delegate = Dns { listOf(InetAddress.getLoopbackAddress()) },
            allowPrivateNetwork = true,
            privateNetworkSeen = AtomicBoolean(false)
        )

        dns.lookup("example.com")
    }

    @Test(expected = IOException::class)
    fun redirectCannotDowngradeHttps() {
        OnlineImportUrlPolicy.validateRedirect(
            "https://example.com/start".toHttpUrl(),
            "http://example.com/file"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun redirectCannotIntroduceCredentials() {
        OnlineImportUrlPolicy.validateRedirect(
            "https://example.com/start".toHttpUrl(),
            "https://user:secret@example.com/file"
        )
    }

    @Test
    fun copiesAtExactLimit() {
        val data = ByteArray(1024) { it.toByte() }
        val target = tempFile("exact")
        try {
            val copied = ByteArrayInputStream(data).copyToFileLimited(target, data.size.toLong())

            assertEquals(data.size.toLong(), copied)
            assertArrayEquals(data, target.readBytes())
        } finally {
            target.delete()
        }
    }

    @Test
    fun rejectsStreamBeyondLimit() {
        val target = tempFile("oversize")
        try {
            try {
                ByteArrayInputStream(ByteArray(1025)).copyToFileLimited(target, 1024)
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("exceeds limit"))
                return
            }
            throw AssertionError("Expected IOException")
        } finally {
            target.delete()
        }
    }

    @Test
    fun zeroLengthBulkReadDoesNotLoop() {
        val target = tempFile("zero_read")
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(byteArrayOf(1, 2))
            private var returnedZero = false

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }

            override fun read(): Int = delegate.read()
        }
        try {
            assertEquals(2L, input.copyToFileLimited(target, 2))
            assertArrayEquals(byteArrayOf(1, 2), target.readBytes())
        } finally {
            target.delete()
        }
    }

    private fun tempFile(name: String): File {
        return File.createTempFile("online_import_${name}_", ".tmp")
    }
}
