package com.example.dreamweb.engine

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.adblock.AdBlockManager
import com.example.dreamweb.managers.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CustomWebClient(
    private val adBlockManager: AdBlockManager,
    private val historyManager: HistoryManager
) : WebViewClient() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false // Let WebView handle it
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.let {
            val app = it.context.applicationContext as BrowserApp
            app.container.privacyEngine.injectPrivacyScripts(it)
            
            url?.let { currentUrl ->
                if (currentUrl != "about:blank") {
                    scope.launch {
                        historyManager.addHistory(currentUrl, view.title)
                    }
                }
            }
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        
        // Block known heavy or redundant resources
        val path = request.url.path ?: ""
        if (path.endsWith(".ttf") || path.endsWith(".woff") || path.endsWith(".otf")) {
            return WebResourceResponse("text/plain", "UTF-8", null)
        }

        if (adBlockManager.shouldBlock(url)) {
            return WebResourceResponse("text/plain", "UTF-8", null)
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.let {
            injectDarkMode(it)
        }
    }

    private fun injectDarkMode(webView: WebView) {
        // Implementation for older devices if needed, 
        // but for Fase 6 we focus on modern WebView Force Dark if available
        // which is set in WebSettings.
    }
}
