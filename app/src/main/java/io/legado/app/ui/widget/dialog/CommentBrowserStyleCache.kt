package io.legado.app.ui.widget.dialog

import android.content.Context
import android.net.Uri
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.security.MessageDigest
import java.util.Locale

object CommentBrowserStyleCache {

    private const val PREF_NAME = "comment_browser_style"
    private const val KEY_PREFIX = "style_"
    private const val MAX_RECORDS = 64

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getForPreOpen(sourceKey: String, bookType: Int, click: String): String? {
        return get(clickKey(sourceKey, bookType, click))
            ?: get(sourceKeyKey(sourceKey, bookType))
    }

    fun remember(
        sourceKey: String,
        bookType: Int,
        click: String?,
        url: String,
        config: String?
    ) {
        val style = sanitizeConfig(config) ?: return
        val record = GSON.toJson(StyleRecord(style))
        val editor = prefs.edit()
        urlKey(sourceKey, bookType, url)?.let { key ->
            editor.putString(key, record)
        }
        if (!click.isNullOrBlank()) {
            editor.putString(clickKey(sourceKey, bookType, click), record)
        }
        editor.putString(sourceKeyKey(sourceKey, bookType), record)
        editor.apply()
        trimIfNeeded()
    }

    private fun get(key: String): String? {
        val record = prefs.getString(key, null)?.let {
            GSON.fromJsonObject<StyleRecord>(it).getOrNull()
        } ?: return null
        return record.config.takeIf { it.isNotBlank() }
    }

    private fun sanitizeConfig(config: String?): String? {
        if (config.isNullOrBlank()) return null
        val raw = GSON.fromJsonObject<BottomWebViewDialog.Config>(config).getOrNull() ?: return null
        if (!raw.hasSheetStyle()) return null
        val style = BottomWebViewDialog.Config(
            state = raw.state,
            peekHeight = raw.peekHeight,
            isHideable = raw.isHideable,
            skipCollapsed = raw.skipCollapsed,
            setHalfExpandedRatio = raw.setHalfExpandedRatio,
            setExpandedOffset = raw.setExpandedOffset,
            setFitToContents = raw.setFitToContents,
            isDraggable = raw.isDraggable,
            isDraggableOnNestedScroll = raw.isDraggableOnNestedScroll,
            significantVelocityThreshold = raw.significantVelocityThreshold,
            hideFriction = raw.hideFriction,
            maxWidth = raw.maxWidth,
            maxHeight = raw.maxHeight,
            isGestureInsetBottomIgnored = raw.isGestureInsetBottomIgnored,
            expandedCornersRadius = raw.expandedCornersRadius,
            setUpdateImportantForAccessibilityOnSiblings = raw.setUpdateImportantForAccessibilityOnSiblings,
            backgroundDimAmount = raw.backgroundDimAmount,
            shouldDimBackground = raw.shouldDimBackground,
            webViewInitialScale = raw.webViewInitialScale,
            dismissOnTouchOutside = raw.dismissOnTouchOutside,
            hardwareAccelerated = raw.hardwareAccelerated,
            isNestedScrollingEnabled = raw.isNestedScrollingEnabled,
            widthPercentage = raw.widthPercentage,
            heightPercentage = raw.heightPercentage,
            responsiveBreakpoint = raw.responsiveBreakpoint,
            dialogHeight = raw.dialogHeight,
            longClickSaveImg = raw.longClickSaveImg,
            scrollNoDraggable = raw.scrollNoDraggable
        )
        return GSON.toJson(style)
    }

    private fun BottomWebViewDialog.Config.hasSheetStyle(): Boolean {
        return state != null
            || peekHeight != null
            || setHalfExpandedRatio != null
            || setExpandedOffset != null
            || setFitToContents != null
            || maxWidth != null
            || maxHeight != null
            || expandedCornersRadius != null
            || widthPercentage != null
            || heightPercentage != null
            || responsiveBreakpoint != null
            || dialogHeight != null
    }

    private fun urlKey(sourceKey: String, bookType: Int, url: String): String? {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return "$KEY_PREFIX$urlKeyPrefix$sourceKey|$bookType|$host"
    }

    private fun clickKey(sourceKey: String, bookType: Int, click: String): String {
        return "$KEY_PREFIX$clickKeyPrefix$sourceKey|$bookType|${sha256(click)}"
    }

    private fun sourceKeyKey(sourceKey: String, bookType: Int): String {
        return "$KEY_PREFIX$sourceKeyPrefix$sourceKey|$bookType"
    }

    private fun trimIfNeeded() {
        val entries = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val record = (value as? String)?.let {
                GSON.fromJsonObject<StyleRecord>(it).getOrNull()
            } ?: return@mapNotNull null
            key to record.updatedAt
        }
        if (entries.size <= MAX_RECORDS) return
        val removeKeys = entries
            .sortedBy { it.second }
            .take(entries.size - MAX_RECORDS)
            .map { it.first }
        prefs.edit().apply {
            removeKeys.forEach { remove(it) }
        }.apply()
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private data class StyleRecord(
        val config: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private const val urlKeyPrefix = "url|"
    private const val clickKeyPrefix = "click|"
    private const val sourceKeyPrefix = "source|"
}