package io.legado.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import java.io.File
import kotlin.math.abs

@Suppress("DEPRECATION")
object ImageCropHelper {

    data class Request(
        val requestCode: Int,
        val outputPath: String,
        val options: CropImageContractOptions
    )

    fun buildRequest(
        context: Context,
        sourceUri: Uri,
        requestCode: Int,
        aspectWidth: Int,
        aspectHeight: Int,
        dirName: String,
        prefix: String,
        targetWidth: Int
    ): Request {
        val aspect = normalizeAspect(aspectWidth, aspectHeight)
        val file = createOutputFile(context, dirName, prefix)
        val targetHeight = (targetWidth * aspect.second / aspect.first).coerceAtLeast(1)
        val cropOptions = CropImageOptions(
            fixAspectRatio = true,
            aspectRatioX = aspect.first,
            aspectRatioY = aspect.second,
            guidelines = CropImageView.Guidelines.ON,
            scaleType = CropImageView.ScaleType.CENTER_CROP,
            multiTouchEnabled = true,
            centerMoveEnabled = true,
            maxZoom = 6,
            initialCropWindowPaddingRatio = 0.05f,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 92,
            outputRequestWidth = targetWidth,
            outputRequestHeight = targetHeight,
            outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_INSIDE,
            customOutputUri = Uri.fromFile(file),
            activityTitle = context.getString(R.string.image_crop_title),
            activityBackgroundColor = context.backgroundColor,
            toolbarColor = context.backgroundColor,
            toolbarTitleColor = context.primaryTextColor,
            toolbarBackButtonColor = context.primaryTextColor,
            toolbarTintColor = context.accentColor,
            progressBarColor = context.accentColor,
            activityMenuTextColor = context.accentColor,
            borderLineColor = Color.argb(210, 255, 255, 255),
            borderCornerColor = Color.WHITE,
            guidelinesColor = Color.argb(130, 255, 255, 255),
            backgroundColor = Color.argb(145, 0, 0, 0),
            cropMenuCropButtonTitle = context.getString(R.string.confirm)
        )
        return Request(
            requestCode = requestCode,
            outputPath = file.absolutePath,
            options = CropImageContractOptions(sourceUri, cropOptions)
        )
    }

    fun screenAspect(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return normalizeAspect(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }

    fun resultPath(resultUri: Uri?, fallbackPath: String?): String? {
        fallbackPath?.takeIf { File(it).exists() }?.let { return it }
        return resultUri?.path?.takeIf { File(it).exists() }
    }

    private fun createOutputFile(context: Context, dirName: String, prefix: String): File {
        val dir = context.externalFiles.getFile(dirName).apply { mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    }

    private fun normalizeAspect(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = abs(width).coerceAtLeast(1)
        val safeHeight = abs(height).coerceAtLeast(1)
        val divisor = gcd(safeWidth, safeHeight)
        return safeWidth / divisor to safeHeight / divisor
    }

    private tailrec fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }
}
