package com.example.dreamweb.managers

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryManagerTest {

    private lateinit var dbHelper: DbHelper
    private lateinit var manager: HistoryManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DbHelper.DATABASE_NAME)
        dbHelper = DbHelper(context)
        manager = HistoryManager(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun `insert basico guarda url y titulo`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", "Titulo A")

        val history = manager.getRecentHistory(10)
        assertEquals(1, history.size)
        assertEquals("https://a.example", history[0].url)
        assertEquals("Titulo A", history[0].title)
        assertTrue(history[0].id > 0)
    }

    @Test
    fun `titulo nulo se normaliza a cadena vacia`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", null)

        val history = manager.getRecentHistory(10)
        assertEquals("", history[0].title)
    }

    @Test
    fun `dedup consecutivo actualiza titulo sin nueva fila`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", null)
        manager.addOrUpdateLatest("https://a.example", "Titulo tardio")

        val history = manager.getRecentHistory(10)
        assertEquals(1, history.size)
        assertEquals("Titulo tardio", history[0].title)
    }

    @Test
    fun `dedup solo consecutivo A B A genera 3 filas`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", "A")
        manager.addOrUpdateLatest("https://b.example", "B")
        manager.addOrUpdateLatest("https://a.example", "A2")

        val history = manager.getRecentHistory(10)
        assertEquals(3, history.size)
        assertEquals(2, history.count { it.url == "https://a.example" })
    }

    @Test
    fun `orden es timestamp DESC con la entrada mas reciente primero`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", "A")
        manager.addOrUpdateLatest("https://b.example", "B")
        manager.addOrUpdateLatest("https://c.example", "C")

        val history = manager.getRecentHistory(10)
        assertEquals("https://c.example", history[0].url)
        assertEquals("https://b.example", history[1].url)
        assertEquals("https://a.example", history[2].url)
    }

    @Test
    fun `rotacion FIFO a 500 descarta las mas antiguas`() = runBlocking {
        repeat(505) { i ->
            manager.addOrUpdateLatest("https://site-$i.example", "Site $i")
        }

        val history = manager.getRecentHistory(600)
        assertEquals(500, history.size)

        val urls = history.map { it.url }.toSet()
        // Las 5 primeras (ids mas bajos = mas antiguas) rotan fuera
        repeat(5) { i ->
            assertTrue("site-$i debio rotar", "https://site-$i.example" !in urls)
        }
        assertTrue("https://site-5.example" in urls)
        assertTrue("https://site-504.example" in urls)
    }

    @Test
    fun `deleteById elimina la entrada y no reaparece`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", "A")
        manager.addOrUpdateLatest("https://b.example", "B")

        val before = manager.getRecentHistory(10)
        val target = before.first { it.url == "https://a.example" }
        manager.deleteById(target.id)

        val after = manager.getRecentHistory(10)
        assertEquals(1, after.size)
        assertEquals("https://b.example", after[0].url)
    }

    @Test
    fun `deleteById solo borra una fila aunque la url se repita`() = runBlocking {
        manager.addOrUpdateLatest("https://a.example", "A1")
        manager.addOrUpdateLatest("https://b.example", "B")
        manager.addOrUpdateLatest("https://a.example", "A2")

        val before = manager.getRecentHistory(10)
        val newest = before.first { it.url == "https://a.example" }
        manager.deleteById(newest.id)

        val after = manager.getRecentHistory(10)
        assertEquals(2, after.size)
        assertEquals(1, after.count { it.url == "https://a.example" })
        assertEquals("A1", after.first { it.url == "https://a.example" }.title)
    }
}
