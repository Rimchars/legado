package io.legado.app.help.config

import android.graphics.Typeface
import com.airbnb.lottie.FontAssetDelegate
import io.legado.app.help.AppFont

/**
 * Resolves Lottie text fonts via [AppFont].
 *
 * You can put `@font:???` (or the bare file name) in the Lottie font fields.
 * If the font is missing, falls back to [preferredTypeface], then reading title font, then system.
 */
internal class AdvancedTitleFontAssetDelegate(
    private val packagedTypeface: (fontFamily: String, fontStyle: String, fontName: String) -> Typeface? =
        { _, _, _ -> null },
    private val preferredTypeface: () -> Typeface? = { null },
) : FontAssetDelegate() {

    override fun fetchFont(fontFamily: String): Typeface = resolve(fontFamily, "", "")

    override fun fetchFont(
        fontFamily: String,
        fontStyle: String,
        fontName: String,
    ): Typeface = resolve(fontFamily, fontStyle, fontName)

    private fun resolve(fontFamily: String, fontStyle: String, fontName: String): Typeface {
        val italic = fontStyle.contains("italic", true) || fontStyle.contains("oblique", true)
        val bold = fontStyle.contains("bold", true)
        val style = AppFont.Style(bold = bold, italic = italic)

        runCatching { packagedTypeface(fontFamily, fontStyle, fontName) }.getOrNull()
            ?.let { return it }

        val candidates = listOf(fontName, fontFamily)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { listOf(it, if (it.startsWith("@font:", true)) it else "@font:$it") }
            .distinct()

        for (candidate in candidates) {
            AppFont.tryTypeface(candidate, style)?.let { return it }
        }

        val preferred = runCatching { preferredTypeface() }.getOrNull()
        return preferred
            ?: AppFont.reader(title = true)
            ?: AppFont.systemDefault()
    }
}
