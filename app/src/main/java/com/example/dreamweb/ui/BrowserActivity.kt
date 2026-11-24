package com.example.dreamweb.ui

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.BuildConfig
import com.example.dreamweb.databinding.ActivityBrowserBinding
import com.example.dreamweb.engine.CustomWebClient
import com.example.dreamweb.engine.CustomWebChromeClient
import com.example.dreamweb.engine.ParsedMessage
import com.example.dreamweb.engine.PlayState
import com.example.dreamweb.engine.Rendition
import com.example.dreamweb.engine.SnifferBridge
import com.example.dreamweb.engine.VideoFinding
import com.example.dreamweb.engine.VideoFindingsRegistry
import com.example.dreamweb.engine.VideoReport
import com.example.dreamweb.engine.VideoSnifferInjector
import com.example.dreamweb.managers.QualityPreferenceMemory
import com.example.dreamweb.managers.VideoInfoProber
import com.example.dreamweb.managers.VideoLaunchPayload
import com.example.dreamweb.managers.VideoPlayerManager
import com.example.dreamweb.security.PlayerHttpClientFactory
import com.example.dreamweb.security.TlsExceptionStore
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private var webView: WebView? = null
    private var chromeClient: CustomWebChromeClient? = null
    private var snifferBridge: SnifferBridge? = null

    private val registry = VideoFindingsRegistry { QualityPreferenceMemory.lastResolutionLabel }
    private lateinit var findingsAdapter: VideoFindingsAdapter
    private var videoInfoProber: VideoInfoProber? = null
    private var panelOpen = false
    private var isFullscreenWeb = false

    /** Último playstate playing recibido (elemento sonando en la página). */
    private var playingState: PlayState? = null
    private val hideHintRunnable = Runnable { hideFullscreenHint() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        findingsAdapter = VideoFindingsAdapter(
            registry,
            onLaunch = { finding, rendition -> onLaunchRequested(finding, rendition) },
            onQualityLaunch = { finding, rendition ->
                // Confirmación explícita de calidad → memoria de sesión (FR-018)
                QualityPreferenceMemory.lastResolutionLabel = rendition.resolutionLabel
                onLaunchRequested(finding, rendition)
            }
        )
        binding.findingsRecycler.layoutManager = LinearLayoutManager(this)
        binding.findingsRecycler.adapter = findingsAdapter

        setupWebView(savedInstanceState)

        binding.favoriteButton.setOnClickListener {
            addCurrentPageToFavorites()
        }

        binding.uaToggleButton.setOnClickListener {
            toggleUserAgent()
        }

        binding.adBlockToggleButton.setOnClickListener {
            toggleAdBlockWhitelist()
        }

        // Contador de bloqueos: visible solo en builds DEBUG (FR-006).
        if (BuildConfig.DEBUG) {
            binding.adBlockCounterText.visibility = View.VISIBLE
        }

        binding.videoIndicatorButton.setOnClickListener {
            toggleFindingsPanel()
        }

        binding.fullscreenHintButton.setOnClickListener {
            onFullscreenHintClicked()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val chrome = chromeClient
                when {
                    // BACK cierra el panel antes que el historial web
                    panelOpen -> closeFindingsPanel()
                    chrome != null && chrome.isFullscreen() -> chrome.onHideCustomView()
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun setupWebView(savedInstanceState: Bundle?) {
        val app = application as BrowserApp
        webView = app.container.webViewPool.getWebView(this).apply {
            webViewClient = CustomWebClient(
                app.container.adBlockManager,
                app.container.historyManager,
                onVideoDetected = { report ->
                    onVideoReport(report)
                },
                onPageFinishedCallback = {
                    updateAdBlockUi()
                },
                onPageStartedCallback = {
                    onMainFrameNavigation()
                }
            )

            snifferBridge = SnifferBridge { message ->
                runOnUiThread {
                    this@BrowserActivity.onParsedMessage(message)
                }
            }.also { bridge ->
                if (BuildConfig.DEBUG) {
                    bridge.logger = { msg -> android.util.Log.d("DreamWebSniffer", msg) }
                }
                addJavascriptInterface(bridge, "DreamWebSniffer")
            }

            chromeClient = CustomWebChromeClient(
                this@BrowserActivity,
                binding.webViewContainer,
                binding.fullscreenContainer,
                onFullscreenChanged = { isFullscreen ->
                    isFullscreenWeb = isFullscreen
                    binding.virtualCursor.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                    binding.favoriteButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                    binding.adBlockCounterText.visibility =
                        if (isFullscreen || !BuildConfig.DEBUG) View.GONE else View.VISIBLE
                    // Fullscreen web: indicador y panel GONE (contrato §2)
                    if (isFullscreen) {
                        closeFindingsPanel()
                        hideFullscreenHint()
                    }
                    refreshIndicator()
                },
                onReceivedTitleCallback = { titleUrl, title ->
                    // Título tardío (SPA / title post-onPageFinished) → upsert
                    if (!titleUrl.isNullOrBlank() && titleUrl != "about:blank") {
                        lifecycleScope.launch {
                            app.container.historyManager.addOrUpdateLatest(titleUrl, title)
                        }
                    }
                }
            )
            webChromeClient = chromeClient

            // Detector de video ANTES de cualquier navegación (contrato §1)
            VideoSnifferInjector.install(this)

            // Restauración de la sesión (contracts/playback-session.md §4)
            when {
                savedInstanceState != null -> {
                    val restored = restoreState(savedInstanceState)
                    if (restored == null) {
                        loadUrl(savedInstanceState.getString("lastPageUrl") ?: DEFAULT_URL)
                    }
                }
                intent.getStringExtra("URL") != null -> {
                    loadUrl(intent.getStringExtra("URL")!!)
                }
                else -> {
                    // Proceso muerto sin bundle ni URL: última URL del
                    // historial, nunca pantalla en blanco (SC-003).
                    lifecycleScope.launch {
                        val last = app.container.historyManager
                            .getRecentHistory(1).firstOrNull()?.url
                        loadUrl(last ?: DEFAULT_URL)
                    }
                }
            }
        }

        binding.webViewContainer.addView(webView)
    }

    // ---------- Detección → registry → UI ----------

    private fun onParsedMessage(message: ParsedMessage) {
        when (message) {
            is VideoReport -> onVideoReport(message)
            is PlayState -> onPlayState(message)
        }
    }

    private fun onPlayState(state: PlayState) {
        if (state.playing) {
            playingState = state
            maybeShowFullscreenHint()
        } else if (playingState?.mediaKey == state.mediaKey ||
            (playingState != null && state.mediaKey == null)
        ) {
            playingState = null
            hideFullscreenHint()
        }
    }

    /* Pill "ver en pantalla completa" (paridad BrowseHere): aparece sobre
     * el <video> que suena cuando hay ≥1 hallazgo lanzable. Con reporte
     * desde iframe el rect es local al frame — ancla fija bottom-center
     * (el iframe del player suele ocupar toda la pantalla). */
    private fun maybeShowFullscreenHint() {
        val state = playingState ?: return
        if (registry.findings().isEmpty() || isFullscreenWeb) return
        val btn = binding.fullscreenHintButton
        if (btn.visibility != View.VISIBLE) {
            btn.visibility = View.VISIBLE
            btn.bringToFront()
        }
        positionHint(state)
        btn.removeCallbacks(hideHintRunnable)
        btn.postDelayed(hideHintRunnable, HINT_AUTO_HIDE_MS)
    }

    private fun positionHint(state: PlayState) {
        val btn = binding.fullscreenHintButton
        val lp = btn.layoutParams as FrameLayout.LayoutParams
        val rect = state.rect
        val vw = state.viewportWidth
        val rootW = binding.root.width
        val rootH = binding.root.height
        if (!state.inIframe && rect != null && vw != null && rootW > 0 && rootH > 0) {
            // CSS px del documento principal → px de la vista (viewport
            // completo encaja por useWideViewPort/loadWithOverviewMode).
            val k = rootW.toFloat() / vw.toFloat()
            btn.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val bw = btn.measuredWidth.toFloat()
            val bh = btn.measuredHeight.toFloat()
            val rx = rect.x.toFloat()
            val ry = rect.y.toFloat()
            val rw = rect.w.toFloat()
            val rh = rect.h.toFloat()
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = ((rx + rw / 2) * k - bw / 2)
                .coerceIn(8f, rootW - bw - 8f).toInt()
            lp.topMargin = ((ry + rh) * k - bh - dp(16))
                .coerceIn(8f, rootH - bh - 8f).toInt()
            lp.bottomMargin = 0
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.bottomMargin = dp(72)
        }
        btn.layoutParams = lp
    }

    private fun hideFullscreenHint() {
        binding.fullscreenHintButton.removeCallbacks(hideHintRunnable)
        binding.fullscreenHintButton.visibility = View.GONE
    }

    private fun onFullscreenHintClicked() {
        val state = playingState ?: return
        val findings = registry.findings()
        // Prioriza el hallazgo del elemento que suena (mediaKey); si el
        // reporte de url llegó sin mediaKey (probe/red), usa el primero.
        val finding = findings.firstOrNull {
            it.mediaKey != null && it.mediaKey == state.mediaKey
        } ?: findings.firstOrNull() ?: return
        hideFullscreenHint()
        onLaunchRequested(finding, registry.defaultOption(finding), state.mediaKey)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onVideoReport(report: VideoReport) {
        val finding = registry.submit(report) ?: return
        refreshIndicator()
        refreshPanelItems()
        maybeProbe(finding)
        // El elemento puede haber empezado antes de detectarse su url.
        maybeShowFullscreenHint()
    }

    /** Indicador no intrusivo: visible iff hallazgos ≥1 y no fullscreen. */
    private fun refreshIndicator() {
        val count = registry.findings().size
        binding.videoIndicatorButton.apply {
            if (count > 0 && !isFullscreenWeb) {
                text = "▶ $count"
                if (visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                    bringToFront()
                }
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun refreshPanelItems() {
        findingsAdapter.submitFindings(registry.findings())
    }

    private fun toggleFindingsPanel() {
        if (panelOpen) closeFindingsPanel() else openFindingsPanel()
    }

    private fun openFindingsPanel() {
        if (registry.findings().isEmpty()) {
            Toast.makeText(this, "No se ha detectado un video aún", Toast.LENGTH_SHORT).show()
            return
        }
        panelOpen = true
        refreshPanelItems()
        binding.findingsPanel.visibility = View.VISIBLE
        binding.findingsPanel.bringToFront()
        // Foco en la primera fila para D-pad
        binding.findingsPanel.post {
            binding.findingsRecycler.findViewHolderForAdapterPosition(0)
                ?.itemView?.requestFocus()
        }
    }

    private fun closeFindingsPanel() {
        panelOpen = false
        binding.findingsPanel.visibility = View.GONE
    }

    /** Navegación de main-frame: hallazgos anteriores se invalidan (FR-004). */
    private fun onMainFrameNavigation() {
        registry.clear()
        videoInfoProber?.cancelAll()
        closeFindingsPanel()
        refreshIndicator()
        playingState = null
        hideFullscreenHint()
        snifferBridge?.currentPageUrl = webView?.url ?: ""
    }

    // ---------- Sondeo acotado (US4, R5) ----------

    /** Una petición máx. por hallazgo, solo si faltan duración Y resolución. */
    private fun maybeProbe(finding: VideoFinding) {
        if (finding.probed) return
        if (finding.durationSec != null) return
        if (finding.renditions.any { it.heightPx != null }) return

        val prober = videoInfoProber ?: VideoInfoProber(
            PlayerHttpClientFactory((application as BrowserApp).container.tlsExceptionStore)
        ).also { videoInfoProber = it }

        val pageUrl = finding.pageUrl.ifEmpty { webView?.url ?: "" }
        registry.markProbed(finding.id)
        prober.probeAsync(
            finding = finding,
            referer = pageUrl,
            userAgent = webView?.settings?.userAgentString ?: VideoLaunchPayload.DEFAULT_USER_AGENT,
            cookies = try { CookieManager.getInstance().getCookie(pageUrl) ?: "" } catch (e: Exception) { "" },
            scope = lifecycleScope
        ) { result ->
            if (result != null) {
                registry.applyProbeResult(finding.id, null, result.variants)
                refreshPanelItems()
            }
        }
    }

    // ---------- Lanzamiento manual ----------

    /**
     * CENTER en fila/opción → recaptura acotada de posición (~300 ms, no
     * bloquea el lanzamiento) y construye el payload JSON v2 desde el
     * hallazgo + la rendición elegida (contrato §4 "Recaptura de posición").
     */
    private fun onLaunchRequested(
        finding: VideoFinding,
        rendition: Rendition,
        recaptureKey: String? = finding.mediaKey
    ) {
        val wv = webView ?: return
        val mediaKey = recaptureKey
        if (mediaKey.isNullOrEmpty()) {
            launchFinding(finding, rendition, finding.positionSec)
            return
        }

        var fired = false
        val proceed = { posSec: Double? ->
            if (!fired) {
                fired = true
                launchFinding(finding, rendition, posSec ?: finding.positionSec)
            }
        }
        val js = "(function(){try{return window.__dwGetPosition ? " +
                "window.__dwGetPosition('${mediaKey.replace("'", "")}') : null;}catch(e){return null;}})()"
        try {
            wv.evaluateJavascript(js) { value ->
                val pos = value
                    ?.takeIf { it != "null" }
                    ?.removeSurrounding("\"")
                    ?.toDoubleOrNull()
                    ?.takeIf { it >= 0 }
                proceed(pos)
            }
            wv.postDelayed({ proceed(null) }, POSITION_RECAPTURE_TIMEOUT_MS)
        } catch (e: Exception) {
            proceed(null)
        }
    }

    private fun launchFinding(finding: VideoFinding, rendition: Rendition, positionSec: Double?) {
        val wv = webView ?: return
        closeFindingsPanel()
        hideFullscreenHint()

        val pageUrl = finding.pageUrl.ifEmpty { wv.url ?: "" }
        val payload = VideoLaunchPayload(
            url = rendition.url,
            referer = pageUrl,
            userAgent = wv.settings.userAgentString ?: VideoLaunchPayload.DEFAULT_USER_AGENT,
            cookies = try { CookieManager.getInstance().getCookie(pageUrl) ?: "" } catch (e: Exception) { "" },
            headers = finding.headers,
            subtitles = finding.subtitles,
            startPositionMs = ((positionSec ?: 0.0) * 1000).toLong().coerceAtLeast(0),
            mimeType = finding.mimeType ?: mimeHintFor(rendition)
        )
        VideoPlayerManager.launchPlayer(wv, payload.toJson())
    }

    /**
     * Hint de contenedor para ExoPlayer: DEBE ser la constante exacta de
     * androidx.media3.common.MimeTypes — en media3 1.2.0 la selección de
     * MediaSource compara por string ("application/x-mpegURL") y variantes
     * como "application/vnd.apple.mpegurl" caen a ProgressiveMediaSource.
     */
    private fun mimeHintFor(rendition: Rendition): String? = when (rendition.format) {
        com.example.dreamweb.engine.StreamFormat.HLS ->
            androidx.media3.common.MimeTypes.APPLICATION_M3U8
        com.example.dreamweb.engine.StreamFormat.DASH ->
            androidx.media3.common.MimeTypes.APPLICATION_MPD
        com.example.dreamweb.engine.StreamFormat.PROGRESSIVE -> "video/mp4"
        com.example.dreamweb.engine.StreamFormat.UNKNOWN -> null
    }

    private fun toggleUserAgent() {
        webView?.let { wv ->
            val currentUA = wv.settings.userAgentString
            val nextUA = com.example.dreamweb.engine.UserAgentConfig.getNext(currentUA)
            wv.settings.userAgentString = nextUA
            wv.reload()

            val name = com.example.dreamweb.engine.UserAgentConfig.getName(nextUA)
            Toast.makeText(this, "Modo: $name", Toast.LENGTH_SHORT).show()
        }
    }

    /** Exceptúa/rehabilita el dominio de la página actual en AdBlock. */
    private fun toggleAdBlockWhitelist() {
        val app = application as BrowserApp
        val host = TlsExceptionStore.normalize(webView?.url ?: "")
        if (host.isEmpty()) {
            Toast.makeText(this, "Sin página cargada", Toast.LENGTH_SHORT).show()
            return
        }
        val store = app.container.adBlockWhitelistStore
        if (store.isExcepted(host)) {
            store.remove(host)
            Toast.makeText(this, "Ads bloqueados en $host", Toast.LENGTH_SHORT).show()
        } else {
            store.add(host)
            Toast.makeText(this, "Ads permitidos en $host", Toast.LENGTH_SHORT).show()
        }
        updateAdBlockUi()
        webView?.reload()
    }

    /**
     * Refresca el contador DEBUG y el estado visual del toggle según si
     * el host de la página actual está exceptuado. Llamado desde el
     * callback onPageFinished del WebViewClient (hilo principal).
     */
    private fun updateAdBlockUi() {
        val app = application as BrowserApp
        if (BuildConfig.DEBUG) {
            binding.adBlockCounterText.text =
                "Ads bloqueados: ${app.container.adBlockManager.blockedCount()}"
        }
        val host = TlsExceptionStore.normalize(webView?.url ?: "")
        val excepted = host.isNotEmpty() && app.container.adBlockWhitelistStore.isExcepted(host)
        binding.adBlockToggleButton.alpha = if (excepted) 1.0f else 0.45f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Degradación nivel 2: pila+página del WebView + última URL (§4)
        webView?.let {
            it.saveState(outState)
            outState.putString("lastPageUrl", it.url)
        }
    }

    override fun onPause() {
        super.onPause()
        // Pausa de la vista (barato, simétrico). pauseTimers() queda
        // exclusivamente en el offloading al player nativo (FR-007, R7).
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        VideoPlayerManager.releasePlayer(webView)
    }

    private fun addCurrentPageToFavorites() {
        val app = application as BrowserApp
        val url = webView?.url
        val title = webView?.title
        if (url != null) {
            lifecycleScope.launch {
                app.container.bookmarkManager.addBookmark(url, title)
                binding.favoriteButton.setImageResource(android.R.drawable.btn_star_big_on)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Panel abierto: la navegación D-pad la gestiona el foco estándar
        // del RecyclerView; MENU cierra; BACK lo cubre onBackPressed.
        if (panelOpen) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
                closeFindingsPanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            toggleFindingsPanel()
            return true
        }
        val app = application as BrowserApp
        webView?.let {
            val nativeViews = listOf(
                binding.favoriteButton,
                binding.uaToggleButton,
                binding.adBlockToggleButton,
                binding.videoIndicatorButton,
                binding.fullscreenHintButton
            )
            if (app.container.dpadHandler.handleKeyEvent(event, it, binding.virtualCursor, nativeViews)) {
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
        videoInfoProber?.cancelAll()
        videoInfoProber = null
        webView?.let {
            val app = application as BrowserApp
            binding.webViewContainer.removeView(it)
            // Anular el callback del bridge ANTES de reciclar el WebView:
            // invocaciones JS ya encoladas se vuelven no-op de inmediato.
            snifferBridge?.release()
            snifferBridge = null
            app.container.webViewPool.releaseWebView(it)
            webView = null
        }
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_URL = "https://www.google.com"
        private const val POSITION_RECAPTURE_TIMEOUT_MS = 300L
        private const val HINT_AUTO_HIDE_MS = 8_000L
    }
}
