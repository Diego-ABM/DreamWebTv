package com.example.dreamweb.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Medición de efectividad real (SC-001) y latencia de decisión (SC-002)
 * contra las listas completas empaquetadas en `assets/filters/`
 * (quickstart §4). Corre en JVM con `./gradlew test` — el micro-benchmark
 * en dispositivo/emulador TV sigue siendo verificación manual (T028).
 *
 * Imprime a stdout las cifras medidas para registrar en README.
 */
class AdBlockEffectivenessTest {

    companion object {
        private lateinit var blocked: Set<String>
        private lateinit var exceptions: Set<String>
        private lateinit var matcher: DomainMatcher
        private lateinit var stats: List<FilterStats>

        private fun assetLines(name: String): Sequence<String> {
            val candidates = listOf(
                File("src/main/assets/filters/$name"),
                File("app/src/main/assets/filters/$name")
            )
            val file = candidates.first { it.exists() }
            return file.bufferedReader().lineSequence()
        }

        @JvmStatic
        @BeforeClass
        fun loadRealLists() {
            val b = mutableSetOf<String>()
            val e = mutableSetOf<String>()
            val all = mutableListOf<FilterStats>()
            for (asset in listOf("easylist.txt", "easyprivacy.txt")) {
                all += FilterParser.parseLines(asset, assetLines(asset), b, e)
            }
            blocked = b
            exceptions = e
            matcher = DomainMatcher(b, e)
            stats = all
            for (s in all) {
                println(
                    "[AdBlockEffectiveness] ${s.source}: read=${s.linesRead} " +
                        "blocked=${s.acceptedBlocked} exceptions=${s.acceptedExceptions} " +
                        "ignored=${s.ignored}"
                )
            }
            println(
                "[AdBlockEffectiveness] matcher rules: ${matcher.ruleCount} " +
                    "(${blocked.size} blocked / ${exceptions.size} exceptions)"
            )
        }
    }

    @Test
    fun `listas reales cargadas con volumen esperado`() {
        assertTrue("easylist should yield thousands of domain rules", blocked.size > 10_000)
        assertTrue("stats coherent", stats.all {
            it.linesRead == it.acceptedBlocked + it.acceptedExceptions + it.ignored
        })
    }

    @Test
    fun `muestreo de 200 dominios de reglas bloqueado al menos 95 por ciento`() {
        val sample = blocked.toList().let { list ->
            val rnd = Random(42)
            (1..200).map { list[rnd.nextInt(list.size)] }
        }
        val hits = sample.count { matcher.isBlocked(it) }
        val pct = hits * 100.0 / sample.size
        println("[AdBlockEffectiveness] efectividad muestra 200 reglas: $hits/${sample.size} = $pct%")
        assertTrue("efectividad >= 95% (SC-001)", pct >= 95.0)
    }

    @Test
    fun `dominios legitimos top no se bloquean`() {
        val legit = listOf(
            "google.com", "youtube.com", "facebook.com", "wikipedia.org",
            "instagram.com", "amazon.com", "netflix.com", "github.com",
            "stackoverflow.com", "reddit.com", "microsoft.com", "apple.com",
            "linkedin.com", "bing.com", "duckduckgo.com", "yahoo.com",
            "twitch.tv", "spotify.com", "paypal.com", "ebay.com", "cnn.com",
            "bbc.com", "nytimes.com", "wordpress.com", "medium.com",
            "quora.com", "pinterest.com", "vimeo.com", "dropbox.com",
            "slack.com", "zoom.us", "adobe.com", "cloudflare.com",
            "mozilla.org", "w3.org", "archive.org", "imdb.com", "espn.com",
            "weather.com", "nih.gov", "cdc.gov", "un.org", "nasa.gov",
            "mit.edu", "stanford.edu", "harvard.edu", "coursera.org",
            "khanacademy.org", "udemy.com", "outlook.com", "office.com",
            "live.com", "msn.com", "walmart.com", "target.com", "bestbuy.com",
            "etsy.com", "ikea.com", "fedex.com", "ups.com", "booking.com",
            "airbnb.com", "expedia.com", "tripadvisor.com", "yelp.com",
            "zillow.com", "indeed.com", "glassdoor.com", "whatsapp.com",
            "telegram.org", "signal.org", "discord.com", "tiktok.com",
            "roblox.com", "steampowered.com", "playstation.com", "xbox.com",
            "nintendo.com", "samsung.com", "sony.com", "guardian.com",
            "reuters.com", "bloomberg.com", "forbes.com", "bbc.co.uk",
            "elmundo.es", "elpais.com", "marca.com", "as.com", "xataka.com",
            "genbeta.com", "hipertextual.com", "android.com", "chromium.org",
            "kernel.org", "debian.org", "ubuntu.com", "gitlab.com",
            "bitbucket.org", "npmjs.com", "pypi.org", "maven.org"
        )
        val falsePositives = legit.filter { matcher.isBlocked(it) }
        println(
            "[AdBlockEffectiveness] falsos positivos en ${legit.size} dominios legitimos: " +
                "${falsePositives.size} ${falsePositives.takeIf { it.isNotEmpty() } ?: ""}"
        )
        assertEquals("0 falsos positivos (estructura exacta)", 0, falsePositives.size)
    }

    @Test
    fun `latencia de decision p99 menor que 1ms`() {
        val corpus = blocked.toList()
        val rnd = Random(1234)
        // Mezcla realista: dominios listados (hits) y aleatorios (misses)
        val queries = (1..10_000).map {
            if (rnd.nextBoolean()) corpus[rnd.nextInt(corpus.size)]
            else "host${rnd.nextInt(1_000_000)}.legit-${rnd.nextInt(1000)}.com"
        }

        val times = LongArray(queries.size)
        // warmup
        repeat(2_000) { matcher.isBlocked(queries[it % queries.size]) }
        for (i in queries.indices) {
            val t0 = System.nanoTime()
            matcher.isBlocked(queries[i])
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        val p50 = times[times.size / 2]
        val p99 = times[(times.size * 0.99).toInt()]
        println(
            "[AdBlockEffectiveness] latencia isBlocked 10k consultas: " +
                "p50=${p50}ns p99=${p99}ns (${p99 / 1e6}ms)"
        )
        assertTrue("p99 < 1ms (SC-002)", p99 < 1_000_000)
    }
}
