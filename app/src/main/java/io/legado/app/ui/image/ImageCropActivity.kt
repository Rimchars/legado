package io.legado.app.ui.image

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityImageCropBinding
import io.legado.app.utils.ImageProcessUtils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                    val metrics = resources.displayMetrics
                    val expectHeight = (targetWidth * aspectHeight.toFloat() / aspectWidth)
                        .roundToInt()
                        .coerceAtLeast(128)
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                    val decodeWidth = maxOf(metrics.widthPixels, targetWidth).coerceAtLeast(128)
                    val decodeHeight = maxOf(metrics.heightPixels, expectHeight).coerceAtLeast(128)
                    val sampleSize = ImageProcessUtils.calculateSampleSize(
                        options.outWidth,
                        options.outHeight,
                        decodeWidth,
                        decodeHeight
                    )
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        )
                    }
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
                binding.photoView.fitInsideRect(binding.cropOverlay.getCropRect())
            }
        }
    }

    private fun saveCrop() {
        val bitmap = sourceBitmap ?: return
        val cropRect = binding.cropOverlay.getCropRect()
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
