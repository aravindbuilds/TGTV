package com.tgtv.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical list as tall as its content, capped at four detail rows
 * (84dp each) - taller groups scroll inside the cap, shorter groups shrink
 * to fit so no blank space is left under them.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    private val maxHeightPx = (4 * 84 * resources.displayMetrics.density).toInt()

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
