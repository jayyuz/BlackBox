package top.niunaijun.blackboxa.view.base

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 *
 * @Description:BaseActivity
 * @Author: wukaicheng
 * @CreateDate: 2021/5/4 15:58
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ (Android 15+) enforces edge-to-edge: the window is laid out
        // behind the status and navigation bars, and android:statusBarColor no
        // longer applies. Without the inset handling below the toolbar slides up
        // under the status bar, so its action menu (the settings button) overlaps
        // the battery and clock and the taps go to the system instead.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The content behind the status bar is the primary-coloured toolbar, so the
        // system icons must be light. Without this they stay dark-on-dark and
        // disappear on the branded app bar.
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false
    }

    /**
     * Applies the system bar insets to [root] as padding.
     *
     * Call this right after setContentView(). The listener keeps working when the
     * insets change (rotation, IME, cutout) and consumes nothing, so children that
     * request their own insets still receive the dispatch.
     */
    protected fun applySystemBarInsets(root: View) {
        // Padding the layout already declares is preserved; only the system bar
        // sizes are added on top of it.
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + bars.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    protected fun initToolbar(toolbar: Toolbar,title:Int, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        setSupportActionBar(toolbar)
        toolbar.setTitle(title)
        if (showBack) {
            supportActionBar?.let {
                it.setDisplayHomeAsUpEnabled(true)
                toolbar.setNavigationOnClickListener {
                    if (onBack != null) {
                        onBack()
                    }
                    finish()
                }
            }
        }
    }

    protected fun currentUserID():Int{
        return intent.getIntExtra("userID", 0)
    }
}