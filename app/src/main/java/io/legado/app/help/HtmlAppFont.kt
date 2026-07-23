package io.legado.app.help

import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/**
 * Span that resolves fonts through [AppFont], including `@font:name`.
 */
class AppFontSpan(val ref: String) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    private fun apply(tp: TextPaint) {
        val style = AppFont.Style(
            bold = tp.isFakeBoldText || tp.typeface?.isBold == true,
            italic = tp.textSkewX != 0f || tp.typeface?.isItalic == true,
        )
        tp.typeface = AppFont.typeface(ref, style, fallback = tp.typeface ?: AppFont.reader())
    }
}

/**
 * usehtml helpers: turn CSS `font-family` into `<font face>` then [AppFontSpan].
 */
object HtmlAppFont {

    private val fontFamilyRegex = Regex("""(?i)font-family\s*:\s*([^;]+)""")

    /**
     * Convert `style="font-family: ..."` into `<font face="...">` so Android Html
     * can emit [TypefaceSpan]s. Also rewrites bare `@font:` on existing font tags.
     */
    fun prepare(html: String): String {
        if (html.isBlank()) return html
        val lower = html.lowercase()
        if ("font-family" !in lower && "face=" !in lower && "@font:" !in lower) {
            return html
        }
        val body = Jsoup.parseBodyFragment(html).body()
        // deepest first so nested styles wrap correctly
        body.select("*").toList().asReversed().forEach { injectFace(it) }
        return body.html()
    }

    fun applySpans(spanned: Spannable) {
        val spans = spanned.getSpans(0, spanned.length, TypefaceSpan::class.java)
        for (span in spans) {
            val family = span.family?.trim().orEmpty()
            if (family.isEmpty()) continue
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val flags = spanned.getSpanFlags(span)
            spanned.removeSpan(span)
            val ref = normalizeRef(family)
            if (ref.isEmpty()) continue
            spanned.setSpan(AppFontSpan(ref), start, end, flags)
        }
    }

    fun extractRef(spanned: Spanned, index: Int): String? {
        if (index < 0 || index >= spanned.length) return null
        val spans = spanned.getSpans(index, index + 1, AppFontSpan::class.java)
        return spans.minByOrNull {
            spanned.getSpanEnd(it) - spanned.getSpanStart(it)
        }?.ref
    }

    fun resolveTypeface(
        spanned: Spanned,
        index: Int,
        bold: Boolean,
        italic: Boolean,
    ): Typeface? {
        val ref = extractRef(spanned, index) ?: return null
        // Prefer tryTypeface so missing @font does not silently look identical
        // only when no real file; still fall back to reader for display.
        AppFont.tryTypeface(ref, AppFont.Style(bold = bold, italic = italic))?.let { return it }
        return AppFont.typeface(
            ref,
            AppFont.Style(bold = bold, italic = italic),
            fallback = AppFont.reader(),
        )
    }

    private fun injectFace(el: Element) {
        if (el.normalName() == "appfont") return
        val style = el.attr("style")
        val fromStyle = extractFontFamily(style)
        val fromFace = el.attr("face").takeIf {
            it.isNotBlank() && el.normalName() == "font"
        }
        val family = (fromStyle ?: fromFace)?.trim()?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
            ?: return

        if (fromStyle != null) {
            val newStyle = fontFamilyRegex.replace(style, "")
                .replace(Regex(""";\s*;+"""), ";")
                .trim()
                .trim(';')
                .trim()
            if (newStyle.isEmpty()) {
                el.removeAttr("style")
            } else {
                el.attr("style", newStyle)
            }
        }

        if (el.normalName() == "font") {
            el.attr("face", family)
            return
        }

        // Wrap all current children (including text nodes) with <font face="...">
        if (el.childNodeSize() == 0) {
            // empty element with only own text already moved? skip
            return
        }
        val font = Element(Tag.valueOf("font"), el.baseUri()).attr("face", family)
        while (el.childNodeSize() > 0) {
            font.appendChild(el.childNode(0))
        }
        // if element had no children but has text, TextNode path above covers it
        if (font.childNodeSize() == 0 && el.ownText().isNotEmpty()) {
            font.appendChild(TextNode(el.ownText()))
            el.text("")
        }
        el.appendChild(font)
    }

    private fun extractFontFamily(style: String): String? {
        if (style.isBlank()) return null
        val match = fontFamilyRegex.find(style) ?: return null
        return match.groupValues[1]
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeRef(family: String): String {
        val raw = family.trim()
        if (raw.isEmpty()) return ""
        val lower = raw.lowercase()
        if (lower == "reader" || lower == "reader:body" || lower == "reader:title") return lower
        if (lower == "system" || lower.startsWith("system:")) return lower
        if (lower in setOf(
                "serif", "sans-serif", "monospace", "cursive", "fantasy",
                "system-ui", "ui-sans-serif", "ui-serif", "ui-monospace",
                "inherit", "initial", "unset", "default"
            )
        ) {
            return ""
        }
        return AppFont.canonicalize(raw).ifEmpty { raw }
    }
}
