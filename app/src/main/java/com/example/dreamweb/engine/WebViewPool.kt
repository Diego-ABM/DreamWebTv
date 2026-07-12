package com.example.dreamweb.engine

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import com.example.dreamweb.BrowserApp

class WebViewPool(private val context: Context) {
    private var cachedWebView: WebView? = null

    fun preload() {
        if (cachedWebView == null) {
            val wrapper = MutableContextWrapper(context)
            cachedWebView = createWebView(wrapper)
        }
    }

    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            val app = context.applicationContext as BrowserApp
            app.container.privacyEngine.applyPrivacySettings(settings)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                textZoom = 110 // Scale up for TV
                
                // Anti-popup and performance settings
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    forceDark = WebSettings.FORCE_DARK_ON
                }
            }
        }
    }

    fun getWebView(activityContext: Context): WebView {
        val wv = cachedWebView ?: createWebView(MutableContextWrapper(activityContext))
        (wv.context as? MutableContextWrapper)?.baseContext = activityContext
        cachedWebView = null
        return wv
    }

    fun releaseWebView(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
        }
        (webView.context as? MutableContextWrapper)?.baseContext = context
        cachedWebView = webView
    }
}
