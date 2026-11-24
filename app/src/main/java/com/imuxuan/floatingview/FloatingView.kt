package com.imuxuan.floatingview

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Drop-in replacement for the jcenter artifact `com.imuxuan:floatingview:1.6`,
 * which disappeared when jcenter was shut down.
 *
 * Only the surface actually used by BlackBox is reproduced:
 *  - [FloatingView.get] singleton with [customView], [attach] and [detach]
 *  - [FloatingMagnetView] as the base class that hosts the custom view
 *  - magnet-style dragging (snaps to the nearest screen edge on release)
 *  - runtime permission behaviour compatible with Android 8.0 ... 16
 *
 * The implementation uses a `TYPE_APPLICATION_OVERLAY` window on API 26+ and
 * `TYPE_PHONE` below that, matching the original library's strategy.
 */
class FloatingView private constructor() {

    companion object {
        private val sInstance: FloatingView by lazy { FloatingView() }

        @JvmStatic
        fun get(): FloatingView = sInstance
    }

    private var windowManager: WindowManager? = null
    private var customView: FloatingMagnetView? = null

    /** The currently attached magnet view, or null when detached. */
    val view: FloatingMagnetView?
        get() = customView

    /**
     * Replaces the view that will be shown. Safe to call before [attach]; the
     * view is only added to the window once an Activity is available.
     */
    fun customView(view: FloatingMagnetView) {
        customView = view
    }

    fun customView(view: View) {
        if (view is FloatingMagnetView) {
            customView = view
        } else {
            throw IllegalArgumentException("custom view must extend FloatingMagnetView")
        }
    }

    /**
     * Attaches the custom view to [activity]'s window. Calling this repeatedly
     * (one call per started Activity) is expected; the window is simply moved
     * to the latest Activity's WindowManager.
     */
    fun attach(activity: Activity?) {
        val magnet = customView ?: return
        if (activity == null || activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return

        detachInternal()

        val wm = activity.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val params = magnet.overlayLayoutParams ?: magnet.buildOverlayParams()

        try {
            wm.addView(magnet, params)
            magnet.onAttached()
        } catch (e: Exception) {
            // Some ROMs reject overlay windows for background contexts. Failing
            // to show the rocker must never take the host app down.
            windowManager = null
        }
    }

    fun detach(activity: Activity?) {
        detachInternal()
    }

    private fun detachInternal() {
        val magnet = customView ?: return
        val wm = windowManager ?: return
        try {
            if (magnet.parent != null) {
                wm.removeViewImmediate(magnet)
            }
        } catch (ignored: Exception) {
            // Window already gone (Activity destroyed first).
        } finally {
            windowManager = null
            magnet.onDetached()
        }
    }
}

/**
 * Minimal magnet-floating window. Position is remembered across Activity
 * switches via [overlayLayoutParams].
 */
open class FloatingMagnetView @JvmOverloads constructor(
    context: Context
) : FrameLayout(context) {

    private var lastX = 0
    private var lastY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var touching = false

    private var _overlayLayoutParams: WindowManager.LayoutParams? = null

    internal val overlayLayoutParams: WindowManager.LayoutParams?
        get() = _overlayLayoutParams

    internal fun onAttached() {
        if (_overlayLayoutParams == null) {
            _overlayLayoutParams = buildOverlayParams()
        }
    }

    internal fun onDetached() {
        touching = false
    }

    internal fun buildOverlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        params.gravity = (layoutParams as? FrameLayout.LayoutParams)?.gravity
            ?: (Gravity.START or Gravity.CENTER)
        return params
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val params = _overlayLayoutParams
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                lastX = params?.x ?: 0
                lastY = params?.y ?: 0
                downRawX = event.rawX
                downRawY = event.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touching || params == null) return false
                lastX = params.x
                lastY = params.y
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                params.x = lastX + dx
                params.y = lastY + dy
                updateViewLayout(params)
                // Reset the drag origin so movement is incremental.
                downRawX = event.rawX
                downRawY = event.rawY
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (_overlayLayoutParams != null) {
                    snapToEdge(_overlayLayoutParams!!)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** Magnet behaviour: animate to whichever horizontal edge is closest. */
    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics ?: DisplayMetrics()
        val screenWidth = metrics.widthPixels
        val viewWidth = width.takeIf { it > 0 } ?: measuredWidth
        val targetX = if (params.x + viewWidth / 2 < screenWidth / 2) 0 else screenWidth - viewWidth

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateViewLayout(params)
            }
            start()
        }
    }

    private fun updateViewLayout(params: WindowManager.LayoutParams) {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.updateViewLayout(this, params)
        } catch (ignored: Exception) {
        }
    }
}
