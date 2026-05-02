package io.legado.app.model.localBook

import android.graphics.Typeface
import java.io.Serializable

internal data class EpubLayoutDocument(
    val href: String,
    val pages: List<EpubLayoutPage>,
    val snapshotId: Int
) : Serializable

internal data class EpubLayoutPage(
    val index: Int,
    val commands: List<EpubDrawCommand>,
    val height: Float,
    val snapshotId: Int
) : Serializable

internal sealed class EpubDrawCommand : Serializable {
    abstract val sourcePath: String
}

internal data class EpubPageColor(
    val color: Int,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubTextRun(
    val text: String,
    val x: Float,
    val y: Float,
    val baseline: Float,
    val width: Float,
    val height: Float,
    val size: Float,
    val color: Int?,
    val backgroundColor: Int?,
    val bold: Boolean,
    val italic: Boolean,
    @Transient
    val typeface: Typeface?,
    val underline: Boolean,
    val overline: Boolean,
    val strikeThrough: Boolean,
    val decorationColor: Int?,
    val decorationStyle: String?,
    val baselineShift: Float,
    val backgroundRadius: Float,
    val backgroundPaddingLeft: Float,
    val backgroundPaddingTop: Float,
    val backgroundPaddingRight: Float,
    val backgroundPaddingBottom: Float,
    val shadow: EpubShadow?,
    val linkHref: String?,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubImageBox(
    val src: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val isBackground: Boolean,
    override val sourcePath: String,
    val backgroundSize: String? = null,
    val backgroundPosition: String? = null,
    val backgroundRepeat: String? = null,
    val filterBrightness: Float? = null,
    val objectFit: String? = null,
    val objectPosition: String? = null,
    val linkHref: String? = null
) : EpubDrawCommand()

internal data class EpubLinkArea(
    val href: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubBlockBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val clipTop: Boolean,
    val clipBottom: Boolean,
    val backgroundColor: Int?,
    val borderColor: Int?,
    val borderWidth: Float,
    val borderStyle: String?,
    val border: EpubBorder?,
    val radius: Float,
    val shadow: EpubShadow?,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubShadow(
    val dx: Float,
    val dy: Float,
    val blur: Float,
    val color: Int
) : Serializable

internal data class EpubBorder(
    val top: EpubBorderSide,
    val right: EpubBorderSide,
    val bottom: EpubBorderSide,
    val left: EpubBorderSide,
    val radius: EpubBorderRadius
) : Serializable

internal data class EpubBorderSide(
    val width: Float,
    val color: Int?,
    val style: String?
) : Serializable

internal data class EpubBorderRadius(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
) : Serializable

internal data class EpubRuleLine(
    val x: Float,
    val y: Float,
    val width: Float,
    val strokeWidth: Float,
    val color: Int?,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubBullet(
    val text: String,
    val x: Float,
    val baseline: Float,
    val size: Float,
    val color: Int?,
    override val sourcePath: String
) : EpubDrawCommand()
