package com.roger.catloadinglibrary

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment

/**
 * Drop-in replacement for the jcenter artifact
 * `com.roger.catloadinglibrary:catloadinglibrary:1.0.9`, which is no longer
 * resolvable after jcenter was shut down.
 *
 * Reproduces the API BlackBox relies on:
 *  - [setBackgroundColor] (accepts either a color int or a color resource id)
 *  - [show], [dismiss], [isAdded]
 *  - [setClickCancelAble]
 *  - [dialog] (exposed for the back-key interceptor in LoadingActivity)
 *
 * The original rendered a "cat" animation; a Material progress indicator is
 * used instead so the module stays dependency free.
 */
class CatLoadingView : DialogFragment() {

    private var clickCancelAble = false
    private var backgroundColor: Int = Color.TRANSPARENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the dialog alive across configuration changes instead of
        // crashing with IllegalStateException on the original implementation.
        isCancelable = false
        setStyle(STYLE_NO_FRAME, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(inflater.context)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val progress = ProgressBar(inflater.context, null, android.R.attr.progressBarStyle).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val size = (48 * resources.displayMetrics.density).toInt()
        val params = FrameLayout.LayoutParams(size, size)
        params.gravity = Gravity.CENTER
        root.addView(progress, params)

        root.setOnClickListener {
            if (clickCancelAble) {
                dismissAllowingStateLoss()
            }
        }
        return root
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.2f)
        if (backgroundColor != Color.TRANSPARENT) {
            window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        }
    }

    /**
     * Mirrors the original signature: accepts a color int, but tolerates a
     * color resource id (which is what LoadingActivity passes).
     */
    fun setBackgroundColor(color: Int) {
        backgroundColor = resolveColor(color)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(backgroundColor))
    }

    private fun resolveColor(color: Int): Int = try {
        ContextCompat.getColor(requireContext(), color)
    } catch (ignored: Exception) {
        color
    }

    fun setClickCancelAble(cancelAble: Boolean) {
        clickCancelAble = cancelAble
        isCancelable = cancelAble
    }
}
