package io.legado.app.help.config

import android.content.Context
import io.legado.app.R
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object ReadTipConfig {

    const val HEADER_MODE_STATUS = 0
    const val HEADER_MODE_SHOW = 1
    const val HEADER_MODE_HIDE = 2
    const val HEADER_MODE_ADVANCED = 3
    const val FOOTER_MODE_SHOW = 0
    const val FOOTER_MODE_HIDE = 1
    const val FOOTER_MODE_ADVANCED = 2

    const val none = 0
    const val chapterTitle = 1
    const val time = 2
    const val battery = 3
    const val batteryPercentage = 10
    const val page = 4
    const val totalProgress = 5
    const val pageAndTotal = 6
    const val bookName = 7
    const val timeBattery = 8
    const val timeBatteryPercentage = 9
    const val totalProgress1 = 11

    val tipValues = arrayOf(
        none, bookName, chapterTitle, time, battery, batteryPercentage, page,
        totalProgress, totalProgress1, pageAndTotal, timeBattery, timeBatteryPercentage
    )
    val tipNames get() = appCtx.resources.getStringArray(R.array.read_tip).toList()

    val tipColorNames get() = appCtx.resources.getStringArray(R.array.tip_color).toList()
    val tipDividerColorNames
        get() = appCtx.resources.getStringArray(R.array.tip_divider_color).toList()

    var tipHeaderLeft: Int
        get() = ReadBookConfig.config.tipHeaderLeft
        set(value) {
            ReadBookConfig.config.tipHeaderLeft = value
        }

    var tipHeaderMiddle: Int
        get() = ReadBookConfig.config.tipHeaderMiddle
        set(value) {
            ReadBookConfig.config.tipHeaderMiddle = value
        }

    var tipHeaderRight: Int
        get() = ReadBookConfig.config.tipHeaderRight
        set(value) {
            ReadBookConfig.config.tipHeaderRight = value
        }

    var tipFooterLeft: Int
        get() = ReadBookConfig.config.tipFooterLeft
        set(value) {
            ReadBookConfig.config.tipFooterLeft = value
        }

    var tipFooterMiddle: Int
        get() = ReadBookConfig.config.tipFooterMiddle
        set(value) {
            ReadBookConfig.config.tipFooterMiddle = value
        }

    var tipFooterRight: Int
        get() = ReadBookConfig.config.tipFooterRight
        set(value) {
            ReadBookConfig.config.tipFooterRight = value
        }

    var headerMode: Int
        get() = ReadBookConfig.config.headerMode
        set(value) {
            ReadBookConfig.config.headerMode = value
        }

    var footerMode: Int
        get() = ReadBookConfig.config.footerMode
        set(value) {
            ReadBookConfig.config.footerMode = value
        }

    var tipColor: Int
        get() = ReadBookConfig.config.tipColor
        set(value) {
            ReadBookConfig.config.tipColor = value
        }

    var tipDividerColor: Int
        get() = ReadBookConfig.config.tipDividerColor
        set(value) {
            ReadBookConfig.config.tipDividerColor = value
        }

    fun getHeaderModes(context: Context): LinkedHashMap<Int, String> {
        return linkedMapOf(
            Pair(HEADER_MODE_STATUS, context.getString(R.string.hide_when_status_bar_show)),
            Pair(HEADER_MODE_SHOW, context.getString(R.string.show)),
            Pair(HEADER_MODE_HIDE, context.getString(R.string.hide)),
            Pair(HEADER_MODE_ADVANCED, context.getString(R.string.advanced_tip_mode)),
        )
    }

    fun getFooterModes(context: Context): LinkedHashMap<Int, String> {
        return linkedMapOf(
            Pair(FOOTER_MODE_SHOW, context.getString(R.string.show)),
            Pair(FOOTER_MODE_HIDE, context.getString(R.string.hide)),
            Pair(FOOTER_MODE_ADVANCED, context.getString(R.string.advanced_tip_mode)),
        )
    }

    fun isHeaderAdvanced(): Boolean = headerMode == HEADER_MODE_ADVANCED
    fun isFooterAdvanced(): Boolean = footerMode == FOOTER_MODE_ADVANCED
}