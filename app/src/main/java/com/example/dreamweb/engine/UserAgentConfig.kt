package com.example.dreamweb.engine

object UserAgentConfig {
    const val MOBILE_TV = "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K VH2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    
    const val DESKTOP_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    
    const val IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun getNext(current: String?): String {
        return when (current) {
            DESKTOP_CHROME -> IPHONE_SAFARI
            IPHONE_SAFARI -> MOBILE_TV
            else -> DESKTOP_CHROME
        }
    }
    
    fun getName(ua: String?): String {
        return when (ua) {
            DESKTOP_CHROME -> "Desktop"
            IPHONE_SAFARI -> "iPhone/iOS"
            else -> "Android TV"
        }
    }
}
