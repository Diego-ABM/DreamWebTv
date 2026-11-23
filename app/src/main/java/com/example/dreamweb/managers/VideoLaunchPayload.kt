package com.example.dreamweb.managers

/**
 * Payload parseado del extra EXTRA_VIDEO_URL de NativeVideoActivity.
 * Formato: "url|REFERER|referer|UA|userAgent|COOKIES|cookies" (todos los
 * sufijos opcionales). Extraído del parsing inline de NativeVideoActivity
 * para hacerlo testeable (Principio IV).
 */
data class VideoLaunchPayload(
    val url: String,
    val referer: String,
    val userAgent: String,
    val cookies: String
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K VH2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

        fun parse(videoData: String): VideoLaunchPayload {
            val partsCookies = videoData.split("|COOKIES|")
            val mainWithUA = partsCookies[0]
            val cookies = if (partsCookies.size > 1) partsCookies[1] else ""

            val partsUA = mainWithUA.split("|UA|")
            val mainData = partsUA[0]
            val userAgent = if (partsUA.size > 1) partsUA[1] else DEFAULT_USER_AGENT

            val parts = mainData.split("|REFERER|")
            val url = parts[0]
            val referer = if (parts.size > 1) parts[1] else ""

            return VideoLaunchPayload(url, referer, userAgent, cookies)
        }
    }
}
