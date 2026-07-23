package io.legado.app.help.config

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advanced header/footer Lottie tips with field injection.
 *
 * Supported placeholders: book/title/page/pages/progress/time/battery/author
 * as ${name} or {{name}}.
 *
 * Performance: load raw package JSON once as LottieComposition, then substitute
 * placeholders via TextDelegate on page turns (no re-parse of large packages).
 */
object AdvancedTipConfig {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    data class TipContext(
        val book: String = "",
        val title: String = "",
        val page: String = "",
        val pages: String = "",
        val progress: String = "",
        val time: String = "",
        val battery: String = "",
        val author: String = ""
    )

    fun rawTemplate(slot: AdvancedTipSlot): String? {
        return AdvancedTipPackageManager.of(slot).currentTemplate().takeIf { it.isNotBlank() }
    }

    /** Stable cache key for raw package composition (not rendered page text). */
    fun compositionCacheKey(slot: AdvancedTipSlot): String {
        val mgr = AdvancedTipPackageManager.of(slot)
        val id = mgr.activeId()
        val stamp = mgr.templateStamp()
        return "advanced_tip_raw:" + slot.name + ":" + id + ":" + stamp
    }

    fun render(slot: AdvancedTipSlot, context: TipContext): String? {
        val raw = rawTemplate(slot) ?: return null
        val rendered = AdvancedTitleConfig.replaceTemplateVariables(raw, variables(context))
        return rendered.takeIf { AdvancedTitleConfig.hasRenderableLayers(it) }
    }

    fun substituteText(input: String, variables: Map<String, String>): String {
        if (input.isEmpty() || variables.isEmpty()) return input
        return AdvancedTitleConfig.replaceTemplateVariables(input, variables)
    }

    fun currentTimeText(): String = timeFormat.format(Date(System.currentTimeMillis()))

    @SuppressLint("DefaultLocale")
    fun variables(context: TipContext): Map<String, String> {
        return mapOf(
            "book" to context.book,
            "bookName" to context.book,
            "title" to context.title,
            "page" to context.page,
            "pages" to context.pages,
            "progress" to context.progress,
            "time" to context.time.ifBlank { currentTimeText() },
            "battery" to context.battery,
            "author" to context.author
        )
    }
}
