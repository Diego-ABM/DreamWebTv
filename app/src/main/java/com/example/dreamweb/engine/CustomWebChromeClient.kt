package com.example.dreamweb.engine

import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout

class CustomWebChromeClient(
    private val activity: android.app.Activity,
    private val webViewContainer: View,
    private val fullscreenContainer: FrameLayout,
    private val onFullscreenChanged: (Boolean) -> Unit = {}
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    fun isFullscreen(): Boolean = customView != null

    override fun onShowCustomView(view: View?, requestedOrientation: Int, callback: CustomViewCallback?) {
        onShowCustomView(view, callback)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            onHideCustomView()
            return
        }

        customView = view
        customViewCallback = callback

        webViewContainer.visibility = View.GONE
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.addView(customView)
        onFullscreenChanged(true)

        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enter fullscreen mode
        activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onHideCustomView() {
        if (customView == null) return

        fullscreenContainer.removeView(customView)
        fullscreenContainer.visibility = View.GONE
        webViewContainer.visibility = View.VISIBLE
        onFullscreenChanged(false)

        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        customView = null
        customViewCallback?.onCustomViewHidden()

        // Exit fullscreen mode
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
}
