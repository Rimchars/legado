package io.legado.app.ui.widget.text

import android.annotation.SuppressLint
import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/**
 * 嵌套惯性滚动 TextView
 */
class ScrollTextView(context: Context, attrs: AttributeSet?) :
    AppCompatTextView(context, attrs) {

    //是否到顶或者到底的标志
    private var disallowIntercept = true

    private val scrollStateIdle = 0
    private val scrollStateDragging = 1
    val scrollStateSettling = 2

    private val mViewFling: ViewFling by lazy { ViewFling() }
    private val velocityTracker: VelocityTracker by lazy { VelocityTracker.obtain() }
    private var mScrollState = scrollStateIdle
    private var mLastTouchY: Int = 0
    private var mTouchSlop: Int = 0
    private var mMinFlingVelocity: Int = 0
    private var mMaxFlingVelocity: Int = 0

    //滑动距离的最大边界
    private var mOffsetHeight: Int = 0
    var onScrollInterceptChange: ((Boolean) -> Unit)? = null

    //f(x) = (x-1)^5 + 1
    private val sQuinticInterpolator = Interpolator {
        var t = it
        t -= 1.0f
        t * t * t * t * t + 1.0f
    }

    init {
        val vc = ViewConfiguration.get(context)
        mTouchSlop = vc.scaledTouchSlop
        mMinFlingVelocity = vc.scaledMinimumFlingVelocity
        mMaxFlingVelocity = vc.scaledMaximumFlingVelocity
        movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        initOffsetHeight()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh || w != oldw) {
            initOffsetHeight()
        }
    }

    override fun onTextChanged(
        text: CharSequence,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        initOffsetHeight()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        velocityTracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setScrollState(scrollStateIdle)
                mLastTouchY = (event.y + 0.5f).toInt()
                updateParentIntercept(canScroll())
            }
            MotionEvent.ACTION_MOVE -> {
                val y = (event.y + 0.5f).toInt()
                var dy = mLastTouchY - y
                val canScrollInDirection = canScrollVertically(dy)
                updateParentIntercept(canScrollInDirection)
                if (mScrollState != scrollStateDragging) {
                    var startScroll = false

                    if (abs(dy) > mTouchSlop) {
                        if (dy > 0) {
                            dy -= mTouchSlop
                        } else {
                            dy += mTouchSlop
                        }
                        startScroll = canScrollInDirection
                    }
                    if (startScroll) {
                        setScrollState(scrollStateDragging)
                    }
                }
                if (mScrollState == scrollStateDragging) {
                    mLastTouchY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity.toFloat())
                val yVelocity = velocityTracker.yVelocity
                if (abs(yVelocity) > mMinFlingVelocity && canScrollVertically(-yVelocity.toInt())) {
                    mViewFling.fling(-yVelocity.toInt())
                } else {
                    setScrollState(scrollStateIdle)
                }
                resetTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                resetTouch()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return when {
            direction > 0 -> scrollY < mOffsetHeight
            direction < 0 -> scrollY > 0
            else -> canScroll()
        }
    }

    private fun updateParentIntercept(disallow: Boolean) {
        disallowIntercept = disallow
        parent.requestDisallowInterceptTouchEvent(disallow)
        onScrollInterceptChange?.invoke(disallow)
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, min(y, mOffsetHeight))
    }

    private fun initOffsetHeight() {
        val mLayoutHeight: Int

        //获得内容面板
        val mLayout = layout ?: return
        //获得内容面板的高度
        mLayoutHeight = mLayout.height
        //获取上内边距
        val paddingTop: Int = totalPaddingTop
        //获取下内边距
        val paddingBottom: Int = totalPaddingBottom

        //获得控件的实际高度
        val mHeight: Int = measuredHeight

        //计算滑动距离的边界
        mOffsetHeight = mLayoutHeight + paddingTop + paddingBottom - mHeight
        if (mOffsetHeight <= 0) {
            scrollTo(0, 0)
        }
    }

    private fun canScroll(): Boolean {
        return mOffsetHeight > 0
    }

    fun refreshScrollBounds() {
        initOffsetHeight()
    }

    private fun resetTouch() {
        updateParentIntercept(false)
        velocityTracker.clear()
    }

    private fun setScrollState(state: Int) {
        if (state == mScrollState) {
            return
        }
        mScrollState = state
        if (state != scrollStateSettling) {
            mViewFling.stop()
        }
    }

    /**
     * 惯性滚动
     */
    private inner class ViewFling : Runnable {

        private var mLastFlingY = 0
        private val mScroller: OverScroller = OverScroller(context, sQuinticInterpolator)
        private var mEatRunOnAnimationRequest = false
        private var mReSchedulePostAnimationCallback = false

        override fun run() {
            disableRunOnAnimationRequests()
            val scroller = mScroller
            if (scroller.computeScrollOffset()) {
                val y = scroller.currY
                val dy = y - mLastFlingY
                mLastFlingY = y
                if (dy < 0 && scrollY > 0) {
                    scrollBy(0, max(dy, -scrollY))
                } else if (dy > 0 && scrollY < mOffsetHeight) {
                    scrollBy(0, min(dy, mOffsetHeight - scrollY))
                }
                postOnAnimation()
            }
            enableRunOnAnimationRequests()
        }

        fun fling(velocityY: Int) {
            mLastFlingY = 0
            setScrollState(scrollStateSettling)
            mScroller.fling(
                0,
                0,
                0,
                velocityY,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE
            )
            postOnAnimation()
        }

        fun stop() {
            removeCallbacks(this)
            mScroller.abortAnimation()
        }

        private fun disableRunOnAnimationRequests() {
            mReSchedulePostAnimationCallback = false
            mEatRunOnAnimationRequest = true
        }

        private fun enableRunOnAnimationRequests() {
            mEatRunOnAnimationRequest = false
            if (mReSchedulePostAnimationCallback) {
                postOnAnimation()
            }
        }

        @Suppress("DEPRECATION")
        fun postOnAnimation() {
            if (mEatRunOnAnimationRequest) {
                mReSchedulePostAnimationCallback = true
            } else {
                removeCallbacks(this)
                ViewCompat.postOnAnimation(this@ScrollTextView, this)
            }
        }
    }

}
