package io.legado.app.help.config

import android.graphics.Typeface
import com.airbnb.lottie.FontAssetDelegate

/**
 * Prevents Lottie from falling back to a non-existent assets/fonts/<family>.ttf file.
 *
 * Lottie 6 calls the three-argument overload first and only then the legacy overload,
 * so both must return a typeface to keep drawing safe across imported animations.
 */
internal class AdvancedTitleFontAssetDelegate(
    private val preferredTypeface: () -> Typeface? = { null },
    private val packagedTypeface: (fontFamily: String, fontStyle: String, fontName: String) -> Typeface? =
        { _, _, _ -> null }
) : FontAssetDelegate() {

    override fun fetchFont(fontFamily: String): Typeface = resolve(fontFamily, "", "")

    override fun fetchFont(
        fontFamily: String,
        fontStyle: String,
        fontName: String
    ): Typeface = resolve(fontFamily, fontStyle, fontName)

    private fun resolve(fontFamily: String, fontStyle: String, fontName: String): Typeface {
        runCatching { packagedTypeface(fontFamily, fontStyle, fontName) }
            .getOrNull()
            ?.let { return it }
        runCatching { preferredTypeface() }.getOrNull()?.let { return it }
        val systemFamily = fontFamily.trim().ifEmpty { "sans-serif" }
        return runCatching { Typeface.create(systemFamily, Typeface.NORMAL) }
            .getOrNull()
            ?: Typeface.DEFAULT
    }
}
