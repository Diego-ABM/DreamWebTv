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
class BookmarkManagerTest {

    private lateinit var dbHelper: DbHelper
    private lateinit var manager: BookmarkManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DbHelper.DATABASE_NAME)
        dbHelper = DbHelper(context)
        manager = BookmarkManager(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun `addBookmark inserta y getAllBookmarks lo devuelve con id`() = runBlocking {
        manager.addBookmark("https://a.example", "Titulo A")

        val bookmarks = manager.getAllBookmarks()
        assertEquals(1, bookmarks.size)
        assertEquals("https://a.example", bookmarks[0].url)
        assertEquals("Titulo A", bookmarks[0].title)
        assertTrue(bookmarks[0].id > 0)
    }

    @Test
    fun `getAllBookmarks ordena por titulo ASC`() = runBlocking {
        manager.addBookmark("https://c.example", "Charlie")
        manager.addBookmark("https://a.example", "Alpha")
        manager.addBookmark("https://b.example", "Bravo")

        val bookmarks = manager.getAllBookmarks()
        assertEquals(3, bookmarks.size)
        assertEquals("Alpha", bookmarks[0].title)
        assertEquals("Bravo", bookmarks[1].title)
        assertEquals("Charlie", bookmarks[2].title)
    }

    @Test
    fun `deleteById elimina el favorito y no reaparece`() = runBlocking {
        manager.addBookmark("https://a.example", "A")
        manager.addBookmark("https://b.example", "B")

        val target = manager.getAllBookmarks().first { it.url == "https://a.example" }
        manager.deleteById(target.id)

        val after = manager.getAllBookmarks()
        assertEquals(1, after.size)
        assertEquals("https://b.example", after[0].url)
    }
}
