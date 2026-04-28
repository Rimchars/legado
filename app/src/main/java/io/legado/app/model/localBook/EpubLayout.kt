package io.legado.app.model.localBook

internal data class EpubLayoutDocument(
    val href: String,
    val pages: List<EpubLayoutPage>
)

internal data class EpubLayoutPage(
    val index: Int,
    val commands: List<EpubDrawCommand>,
    val height: Float
)

internal sealed class EpubDrawCommand {
    abstract val sourcePath: String
}

internal data class EpubPageColor(
    val color: Int,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubTextRun(
    val text: String,
    val x: Float,
    val baseline: Float,
    val size: Float,
    val color: Int?,
    val bold: Boolean,
    val italic: Boolean,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubImageBox(
    val src: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val isBackground: Boolean,
    override val sourcePath: String
) : EpubDrawCommand()

internal data class EpubBlockBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val backgroundColor: Int?,
    val borderColor: Int?,
    val borderWidth: Float,
    val radius: Float,
    override val sourcePath: String
) : EpubDrawCommand()
