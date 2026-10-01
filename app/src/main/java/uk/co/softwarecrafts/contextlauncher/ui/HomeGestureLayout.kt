package uk.co.softwarecrafts.contextlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root of the home screen. Feeds every touch to the swipe detector, whichever
 * row the finger lands on, and takes the gesture over from that row once it has
 * clearly moved sideways (or vertically when there is nothing to scroll). Rows
 * keep their taps; swipes work from anywhere.
 */
class HomeGestureLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var gestures: View.OnTouchListener? = null
    /** True while the scroll view below can scroll, so vertical drags stay with it. */
    var verticalScrollable: () -> Boolean = { false }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var intercepting = false
    private var fedDownAt = -1L

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; intercepting = false
                feed(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                feed(ev)
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > slop && dx > dy) intercepting = true
                else if (dy > slop && dy > dx && !verticalScrollable()) intercepting = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> feed(ev)
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        feed(ev)
        return true
    }

    private fun feed(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (ev.eventTime == fedDownAt) return // already seen in onInterceptTouchEvent
            fedDownAt = ev.eventTime
        }
        gestures?.onTouch(this, ev)
    }
}
