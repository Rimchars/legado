package io.legado.app.help

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Html
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.lang.ref.WeakReference
import io.legado.app.utils.lifecycle

class GlideImageGetter(
    context: Context,
    textView: TextView,
    private  val lifecycle: Lifecycle
) : Html.ImageGetter, Drawable.Callback {
    private val textViewRef = WeakReference(textView)
    private val contextRef = WeakReference(context)
    private val gifDrawables = mutableSetOf<GlideUrlDrawable>()
    private val emptyDrawable by lazy {
        object : Drawable() {
            override fun draw(canvas: Canvas) = Unit
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSPARENT
            override fun getIntrinsicWidth(): Int = 0
            override fun getIntrinsicHeight(): Int = 0
        }
    }

    override fun getDrawable(source: String?): Drawable {
        val context = contextRef.get()
        if (context == null || source.isNullOrBlank()) {
            return emptyDrawable
        }
        val urlDrawable = GlideUrlDrawable()
        val target = ImageTarget(urlDrawable)
        Glide.with(context).lifecycle(lifecycle)
            .load(source)
            .into(target)
        return urlDrawable
    }

    override fun invalidateDrawable(who: Drawable) {
        textViewRef.get()?.invalidate()
    }

    override fun scheduleDrawable(
        who: Drawable,
        what: Runnable,
        `when`: Long
    ) {
    }

    override fun unscheduleDrawable(
        who: Drawable,
        what: Runnable
    ) {
    }

    private inner class GlideUrlDrawable(): Drawable(), Drawable.Callback {
        private var mDrawable: Drawable? = null
        fun setDrawable(drawable: Drawable?) {
            mDrawable?.callback = null
            drawable?.callback = this
            mDrawable = drawable
        }

        fun clear() {
            (mDrawable as? GifDrawable)?.apply{
                stop()
                clearAnimationCallbacks()
            }
            mDrawable?.callback = null
            mDrawable = null
        }

        override fun draw(canvas: Canvas) {
            mDrawable?.draw(canvas)
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            return mDrawable?.opacity ?: PixelFormat.TRANSLUCENT
        }

        override fun setAlpha(alpha: Int) {
            mDrawable?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            mDrawable?.colorFilter = colorFilter
        }

        override fun invalidateDrawable(who: Drawable) {
            callback?.invalidateDrawable(who)
        }

        override fun scheduleDrawable(
            who: Drawable,
            what: Runnable,
            `when`: Long
        ) {
            callback?.scheduleDrawable(who, what, `when`)
        }

        override fun unscheduleDrawable(
            who: Drawable,
            what: Runnable
        ) {
            callback?.unscheduleDrawable(who, what)
        }
    }

    private inner class ImageTarget(
        private val urlDrawable: GlideUrlDrawable
    ) : CustomTarget<Drawable>() {

        override fun onResourceReady(
            drawable: Drawable,
            transition: Transition<in Drawable>?
        ) {
            val textView = textViewRef.get() ?: return
            val context = contextRef.get() ?: return
            val availableWidth = if (textView.width > 0) {
                textView.width - textView.paddingLeft - textView.paddingRight
            } else {
                val displayMetrics = context.resources.displayMetrics
                (displayMetrics.widthPixels * 0.8).toInt()
            }
            val maxWidth = availableWidth.takeIf { it > 0 } ?: 700
            val drawableWidth = drawable.intrinsicWidth.coerceAtLeast(1)
            val drawableHeight = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = if (drawableWidth > maxWidth) {
                maxWidth.toFloat() / drawableWidth
            } else {
                1f
            }
            val width = (drawableWidth * scale).toInt()
            val height = (drawableHeight * scale).toInt()
            drawable.setBounds(0, 0, width, height)
            urlDrawable.setBounds(0, 0, width, height)
            urlDrawable.setDrawable(drawable)
            if (drawable is GifDrawable) {
                urlDrawable.callback = this@GlideImageGetter
                drawable.start()
                gifDrawables.add(urlDrawable)
            }
            textView.text = textView.text
            textView.invalidate()
        }

        override fun onLoadCleared(placeholder: Drawable?) {
            urlDrawable.setDrawable(placeholder)
        }

        override fun onLoadFailed(errorDrawable: Drawable?) {
            urlDrawable.setDrawable(errorDrawable)
        }
    }

    fun clear() {
        gifDrawables.forEach {
            it.clear()
        }
        gifDrawables.clear()
        contextRef.clear()
        textViewRef.clear()
    }
}