package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LottieImageMemoryPolicyTest {

    @Test
    fun calculatesDisplayedAssetSizeFromCompositionScale() {
        assertEquals(
            LottieDecodeSize(563, 188),
            LottieImageMemoryPolicy.decodeSize(600, 200, 900, 300, 1000, 400)
        )
    }

    @Test
    fun capsEdgeAndPixelsWhileKeepingAspectRatio() {
        val size = LottieImageMemoryPolicy.decodeSize(2400, 600, 2400, 600, 2400, 600)!!
        assertEquals(1200, size.width)
        assertEquals(300, size.height)
        assertTrue(size.width.toLong() * size.height <= LottieImageMemoryPolicy.MAX_PIXELS)
    }

    @Test
    fun rejectsInvalidDimensions() {
        assertNull(LottieImageMemoryPolicy.decodeSize(0, 100, 100, 100, 100, 100))
        assertNull(LottieImageMemoryPolicy.decodeSize(100, 100, -1, 100, 100, 100))
    }

    @Test
    fun rasterFitNeverUpscalesSource() {
        assertEquals(
            LottieDecodeSize(400, 200),
            LottieImageMemoryPolicy.fitSourceInto(400, 200, LottieDecodeSize(1000, 1000))
        )
        assertEquals(
            LottieDecodeSize(200, 100),
            LottieImageMemoryPolicy.fitSourceInto(400, 200, LottieDecodeSize(200, 200))
        )
    }

    @Test
    fun cacheBudgetIsBounded() {
        assertEquals(8 * 1024 * 1024, LottieImageMemoryPolicy.cacheBudgetBytes(64L * 1024 * 1024))
        assertEquals(16 * 1024 * 1024, LottieImageMemoryPolicy.cacheBudgetBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun byteChargeUsesAllocationThenSafeFallback() {
        assertEquals(1234, LottieImageMemoryPolicy.chargeBytes(1234, 100, 20))
        assertEquals(2000, LottieImageMemoryPolicy.chargeBytes(0, 100, 20))
        assertEquals(Int.MAX_VALUE, LottieImageMemoryPolicy.chargeBytes(0, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun cacheKeyUsesStableStrongDigestAndDimensions() {
        val firstDigest = LottieImageMemoryPolicy.sourceSha256("data:image/svg+xml;base64,abc")
        val secondDigest = LottieImageMemoryPolicy.sourceSha256("data:image/svg+xml;base64,abd")
        assertEquals(firstDigest, LottieImageMemoryPolicy.sourceSha256("data:image/svg+xml;base64,abc"))
        assertNotEquals(firstDigest, secondDigest)
        assertEquals(64, firstDigest.length)
        assertNotEquals(
            LottieImageCacheKey(firstDigest, 100, 100),
            LottieImageCacheKey(firstDigest, 200, 100)
        )
    }

    @Test
    fun sampleSizeHandlesExtremeAspectRatiosWithoutHugeIntermediateBitmap() {
        val target = LottieImageMemoryPolicy.fitSourceInto(
            100_000,
            1_000,
            LottieDecodeSize(256, 256)
        )!!

        assertEquals(LottieDecodeSize(256, 2), target)
        assertEquals(256, LottieImageMemoryPolicy.sampleSize(100_000, 1_000, target))
    }

    @Test
    fun digestDoesNotReuseKnownStringHashCollision() {
        assertEquals("FB".hashCode(), "Ea".hashCode())
        assertNotEquals(
            LottieImageMemoryPolicy.sourceSha256("FB"),
            LottieImageMemoryPolicy.sourceSha256("Ea")
        )
    }
}
