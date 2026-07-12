package com.example.dreamweb.ui

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.databinding.ActivityBrowserBinding
import com.example.dreamweb.engine.CustomWebClient

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("URL") ?: "https://www.google.com"
        
        setupWebView(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView(url: String) {
        val app = application as BrowserApp
        webView = app.container.webViewPool.getWebView(this).apply {
            webViewClient = CustomWebClient(app.container.adBlockManager, app.container.historyManager)
            loadUrl(url)
        }
        
        binding.webViewContainer.addView(webView)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as BrowserApp
        webView?.let {
            if (app.container.dpadHandler.handleKeyEvent(event, it, binding.virtualCursor)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            webView?.let {
                // Low-level memory clearing
                it.clearCache(false)
            }
        }
    }

    override fun onDestroy() {
        webView?.let {
            val app = application as BrowserApp
            binding.webViewContainer.removeView(it)
            app.container.webViewPool.releaseWebView(it)
            webView = null
        }
        super.onDestroy()
    }
}
