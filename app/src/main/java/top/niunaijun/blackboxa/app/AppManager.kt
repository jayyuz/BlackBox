package top.niunaijun.blackboxa.app

import android.content.Context
import android.content.SharedPreferences
import top.niunaijun.blackboxa.view.main.BlackBoxLoader

object AppManager {
    @JvmStatic
    val mBlackBoxLoader by lazy {
        BlackBoxLoader()
    }

    @JvmStatic
    val mBlackBoxCore by lazy {
        mBlackBoxLoader.getBlackBoxCore()
    }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        App.getContext().getSharedPreferences("UserRemark",Context.MODE_PRIVATE)
    }

    /**
     * Per-app "share the device's real media library" switch.
     *
     * Guests are sandboxed by default, so a gallery app inside the container sees
     * an empty /sdcard and reports "0 photos" even with all permissions granted.
     * Turning this on for a package keeps DCIM / Pictures / Movies / Music /
     * Download / Documents pointing at the real filesystem.
     */
    private val mMediaSharedPreferences: SharedPreferences by lazy {
        App.getContext().getSharedPreferences("MediaShared", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun isMediaShared(packageName: String): Boolean {
        return mMediaSharedPreferences.getBoolean(packageName, false)
    }

    @JvmStatic
    fun setMediaShared(packageName: String, shared: Boolean) {
        mMediaSharedPreferences.edit().putBoolean(packageName, shared).apply()
    }

    fun doAttachBaseContext(context: Context) {
        try {
            mBlackBoxLoader.attachBaseContext(context)
            mBlackBoxLoader.addLifecycleCallback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun doOnCreate(context: Context) {
        mBlackBoxLoader.doOnCreate(context)
        initThirdService(context)
    }

    private fun initThirdService(context: Context) {}
}
