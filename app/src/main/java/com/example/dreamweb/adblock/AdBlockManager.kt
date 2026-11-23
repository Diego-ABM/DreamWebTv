package com.example.dreamweb.adblock

import android.content.Context
import android.util.Log
import com.example.dreamweb.BuildConfig
import com.example.dreamweb.security.TlsExceptionStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Orquestador del motor AdBlock real (FR-001..FR-008).
 *
 * `init()` lanza la carga one-shot de `filters/easylist.txt` y
 * `filters/easyprivacy.txt` en un `Executors.newSingleThreadExecutor`
 * propiedad del manager: parsea ~135K líneas, construye el
 * `DomainMatcher` y lo publica en un swap `@Volatile`. El executor
 * recibe `shutdown()` tras el swap — vida acotada estrictamente al
 * parseo (Principio V, research R3).
 *
 * Mientras carga, `engine = DomainMatcher.EMPTY` → `shouldBlock`
 * devuelve false: modo degradado documentado (US2), nunca bloquea
 * el hilo de red del WebView.
 *
 * Flujo de decisión de `shouldBlock(requestUrl, pageUrl)` (data-model.md):
 *  1. `whitelist.isExcepted(pageHost)` -> ALLOW (nada se filtra en la página)
 *  2. `engine.isBlocked(requestHost)`  -> BLOCK + contador + log DEBUG
 *  3. ALLOW
 *
 * El log por petición bloqueada vive SOLO aquí dentro y tras
 * `BuildConfig.DEBUG` (Principio I): `CustomWebClient` no loguea,
 * solo traduce el boolean.
 */
class AdBlockManager(
    context: Context,
    private val whitelist: AdBlockWhitelistStore
) {

    enum class LoadState { LOADING, READY, FAILED }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var engine: DomainMatcher = DomainMatcher.EMPTY

    @Volatile
    var loadState: LoadState = LoadState.LOADING
        private set

    @Volatile
    var stats: List<FilterStats> = emptyList()
        private set

    val isReady: Boolean
        get() = loadState == LoadState.READY

    private val blockedRequests = AtomicLong(0)

    fun blockedCount(): Long = blockedRequests.get()

    /** Encola la carga en background y retorna de inmediato (µs). */
    fun init() {
        executor.execute(::loadEngine)
    }

    private fun loadEngine() {
        try {
            val blocked = HashSet<String>()
            val exceptions = HashSet<String>()
            val allStats = mutableListOf<FilterStats>()

            for (asset in FILTER_ASSETS) {
                appContext.assets.open(asset).use { input ->
                    BufferedReader(InputStreamReader(input)).use { reader ->
                        allStats += FilterParser.parseLines(
                            asset.substringAfterLast('/'),
                            reader.lineSequence(),
                            blocked,
                            exceptions
                        )
                    }
                }
            }

            // Swap atómico: el matcher se construye entero antes de publicarse.
            engine = DomainMatcher(blocked, exceptions)
            stats = allStats
            loadState = LoadState.READY
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "engine ready, ${blocked.size} blocked / ${exceptions.size} exceptions")
            }
        } catch (e: Exception) {
            // FAILED solo si un asset no se puede abrir; las líneas
            // corruptas cuentan como `ignored` dentro del parser.
            loadState = LoadState.FAILED
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "engine load failed", e)
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Decisión de bloqueo por petición. Llamado desde
     * `CustomWebClient.shouldInterceptRequest` (hilo de red del WebView)
     * y `shouldOverrideUrlLoading` — nunca realiza I/O ni espera.
     */
    fun shouldBlock(requestUrl: String, pageUrl: String?): Boolean {
        val pageHost = TlsExceptionStore.normalize(pageUrl ?: "")
        if (pageHost.isNotEmpty() && whitelist.isExcepted(pageHost)) {
            return false
        }
        val requestHost = TlsExceptionStore.normalize(requestUrl)
        if (requestHost.isEmpty()) return false

        if (engine.isBlocked(requestHost)) {
            blockedRequests.incrementAndGet()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "blocked $requestHost (page: $pageHost)")
            }
            return true
        }
        return false
    }

    private companion object {
        const val TAG = "DreamWebAdBlock"
        val FILTER_ASSETS = listOf("filters/easylist.txt", "filters/easyprivacy.txt")
    }
}
