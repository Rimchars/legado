package io.legado.app.ui.image

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityImageCropBinding
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageProcessUtils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class ImageCropActivity : BaseActivity<ActivityImageCropBinding>(
    transparent = true,
    imageBg = false
) {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_ASPECT_WIDTH = "aspectWidth"
        const val EXTRA_ASPECT_HEIGHT = "aspectHeight"
        const val EXTRA_DIR_NAME = "dirName"
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_TARGET_WIDTH = "targetWidth"
        const val EXTRA_OUTPUT_PATH = "outputPath"
        const val EXTRA_RESULT_PATH = "resultPath"
    }

    override val binding by viewBinding(ActivityImageCropBinding::inflate)

    private var sourceBitmap: Bitmap? = null
    private var aspectWidth = 1
    private var aspectHeight = 1
    private var dirName = "images"
    private var prefix = "crop"
    private var targetWidth = 1600
    private var outputPath: String? = null

    override fun setupSystemBar() {
        super.setupSystemBar()
        setLightStatusBar(false)
        setNavigationBarColorAuto(Color.BLACK)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        aspectWidth = intent.getIntExtra(EXTRA_ASPECT_WIDTH, 1).coerceAtLeast(1)
        aspectHeight = intent.getIntExtra(EXTRA_ASPECT_HEIGHT, 1).coerceAtLeast(1)
        dirName = intent.getStringExtra(EXTRA_DIR_NAME).orEmpty().ifBlank { "images" }
        prefix = intent.getStringExtra(EXTRA_PREFIX).orEmpty().ifBlank { "crop" }
        targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, 1600).coerceAtLeast(128)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        binding.cropOverlay.setAspect(aspectWidth, aspectHeight)
        binding.photoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        binding.photoView.setMaxScale(6f)
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnConfirm.setOnClickListener { saveCrop() }
        binding.cropOverlay.post {
            updatePhotoViewport()
        }
        loadImage()
    }

    override fun onDestroy() {
        sourceBitmap?.recycle()
        sourceBitmap = null
        super.onDestroy()
    }

    private fun loadImage() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.error_image_url_empty)))
            finish()
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    decodeBitmapFromStableFile(uri)
                }.onFailure {
                    it.printOnDebug()
                }.getOrNull()
            }
            if (bitmap == null) {
                toastOnUi(getString(R.string.image_crop_failed, getString(R.string.error_decode_bitmap)))
                finish()
                return@launch
            }
            sourceBitmap = bitmap
            binding.photoView.setImageBitmap(bitmap)
            binding.photoView.post {
                updatePhotoViewport()
            }
        }
    }

    private suspend fun decodeBitmapFromStableFile(uri: Uri): Bitmap? {
        val tempDir = File(cacheDir, "image_crop_source").apply { mkdirs() }
        val tempFile = File.createTempFile("source_", ".img", tempDir)
        try {
            copyImageSourceToFile(uri, tempFile)
            if (!tempFile.exists() || tempFile.length() <= 0L) return null
            val metrics = resources.displayMetrics
            val expectHeight = (targetWidth * aspectHeight.toFloat() / aspectWidth)
                .roundToInt()
                .coerceAtLeast(128)
            val decodeWidth = maxOf(metrics.widthPixels, targetWidth).coerceAtLeast(128)
            val decodeHeight = maxOf(metrics.heightPixels, expectHeight).coerceAtLeast(128)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeBitmapWithImageDecoder(tempFile, decodeWidth, decodeHeight, uri)?.let {
                    return it
                }
            }
            val options = decodeBounds(tempFile)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = ImageProcessUtils.calculateSampleSize(
                options.outWidth,
                options.outHeight,
                decodeWidth,
                decodeHeight
            )
            return decodeBitmapWithBitmapFactory(tempFile, sampleSize)
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun copyImageSourceToFile(uri: Uri, target: File) {
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            val analyzeUrl = AnalyzeUrl(uri.toString())
            okHttpClient.newCallResponse(0) {
                addHeaders(analyzeUrl.headerMap)
                url(analyzeUrl.urlNoQuery)
            }.use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return
        }
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Open input stream failed")
    }

    private fun decodeBounds(file: File): BitmapFactory.Options {
        return BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            file.inputStream().use {
                BitmapFactory.decodeStream(it, null, this)
            }
        }
    }

    private fun decodeBitmapWithImageDecoder(
        file: File,
        targetDecodeWidth: Int,
        targetDecodeHeight: Int,
        sourceUri: Uri
    ): Bitmap? {
        return kotlin.runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val sampleSize = ImageProcessUtils.calculateSampleSize(
                    info.size.width,
                    info.size.height,
                    targetDecodeWidth,
                    targetDecodeHeight
                )
                val width = (info.size.width / sampleSize).coerceAtLeast(1)
                val height = (info.size.height / sampleSize).coerceAtLeast(1)
                decoder.setTargetSize(width, height)
            }.copy(Bitmap.Config.ARGB_8888, false)
        }.onFailure {
            AppLog.putDebug(
                "ImageDecoder failed for crop source: uri=$sourceUri, size=${file.length()}",
                it
            )
        }.getOrNull()
    }

    private fun decodeBitmapWithBitmapFactory(file: File, sampleSize: Int): Bitmap? {
        return file.inputStream().use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }

    private fun updatePhotoViewport() {
        val cropRect = binding.cropOverlay.getCropRect()
        if (cropRect.isEmpty) return
        val layoutParams = (binding.photoView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                cropRect.width().roundToInt(),
                cropRect.height().roundToInt()
            )
        layoutParams.width = cropRect.width().roundToInt()
        layoutParams.height = cropRect.height().roundToInt()
        layoutParams.leftMargin = cropRect.left.roundToInt()
        layoutParams.topMargin = cropRect.top.roundToInt()
        binding.photoView.layoutParams = layoutParams
        binding.photoView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        binding.photoView.post {
            binding.photoView.fitInsideRect(
                RectF(
                    0f,
                    0f,
                    binding.photoView.width.toFloat(),
                    binding.photoView.height.toFloat()
                )
            )
        }
    }

    private fun saveCrop() {
        val bitmap = sourceBitmap ?: return
        val cropRect = RectF(0f, 0f, binding.photoView.width.toFloat(), binding.photoView.height.toFloat())
        val matrix = binding.photoView.getDisplayMatrixCopy()
        binding.btnConfirm.isEnabled = false
        lifecycleScope.launch {
            val resultPath = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    val cropped = cropVisibleBitmap(bitmap, cropRect, matrix) ?: return@runCatching null
                    try {
                        ImageProcessUtils.saveBitmapToFile(
                            context = this@ImageCropActivity,
                            bitmap = cropped,
                            aspectWidth = aspectWidth,
                            aspectHeight = aspectHeight,
                            dirName = dirName,
                            prefix = prefix,
                            targetWidth = targetWidth,
                            outputPath = outputPath
                        )
                    } finally {
                        cropped.recycle()
                    }
                }.onFailure {
                    it.printOnDebug()
                }.getOrNull()
            }
            if (resultPath.isNullOrBlank()) {
                binding.btnConfirm.isEnabled = true
                toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
                return@launch
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, resultPath))
            finish()
        }
    }

    private fun cropVisibleBitmap(source: Bitmap, cropRect: RectF, matrix: Matrix): Bitmap? {
        if (cropRect.isEmpty) return null
        val cropWidth = cropRect.width().roundToInt().coerceAtLeast(1)
        val cropHeight = cropRect.height().roundToInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val drawMatrix = Matrix(matrix).apply {
            postTranslate(-cropRect.left, -cropRect.top)
        }
        val drawable = BitmapDrawable(resources, source)
        drawable.setBounds(0, 0, source.width, source.height)
        canvas.concat(drawMatrix)
        drawable.draw(canvas)
        return output
    }
}
