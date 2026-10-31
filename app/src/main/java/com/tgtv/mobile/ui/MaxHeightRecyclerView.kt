package com.tgtv.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical list as tall as its content, capped at four detail rows
 * (84dp each) - taller groups scroll inside the cap, shorter groups shrink
 * to fit so no blank space is left under them.
 *
 * Touch plumbing for living inside the vertical outer grid:
 *  - On ACTION_DOWN it claims the gesture ("disallow intercept"), which
 *    stops the outer grid from stealing vertical drags before this list
 *    can scroll.
 *  - What this list cannot consume (at its start/end, or when the group
 *    fits without scrolling) is chained to the nearest RecyclerView above
 *    it: RecyclerView only FORWARDS nested scroll upward, it never scrolls
 *    itself, so without this the drag would die as soon as this list ran
 *    out of room.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    private val maxHeightPx = (4 * 84 * resources.displayMetrics.density).toInt()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean {
        val handled = super.dispatchNestedScroll(
            dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type,
        )
        if (dyUnconsumed != 0) {
            nearestScrollableAncestor(if (dyUnconsumed > 0) 1 else -1)?.scrollBy(0, dyUnconsumed)
        }
        return handled
    }

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean): Boolean {
        val handled = super.dispatchNestedFling(velocityX, velocityY, consumed)
        if (!consumed && velocityY != 0f) {
            nearestScrollableAncestor(if (velocityY > 0) 1 else -1)
                ?.fling(0, velocityY.toInt())
        }
        return handled
    }

    /** Nearest RecyclerView ancestor with scroll room left in [direction] (+1/-1). */
    private fun nearestScrollableAncestor(direction: Int): RecyclerView? {
        var p: ViewParent? = parent
        while (p != null) {
            if (p is RecyclerView && p.canScrollVertically(direction)) return p
            p = p.parent
        }
        return null
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        super.onMeasure(
            ViewGroup.getChildMeasureSpec(
                widthSpec,
                paddingLeft + paddingRight,
                layoutParams.width,
            ),
            capped,
        )
    }
}
