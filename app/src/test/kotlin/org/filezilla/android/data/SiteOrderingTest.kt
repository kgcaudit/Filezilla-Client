package org.filezilla.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stored side of the order the user arranges.
 *
 * The pure part of a move -- which two rows swap -- is [SiteOrder]'s, and is
 * tested without a database. What is left here is what only the database can
 * answer: that the list comes back in the order that was written, that a new
 * server goes to the end rather than into the middle of an arrangement
 * somebody made, and that renumbering survives a list whose positions have
 * drifted.
 */
@RunWith(RobolectricTestRunner::class)
class SiteOrderingTest {

    private lateinit var database: AppDatabase
    private val sites get() = database.sites()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun site(id: String, name: String, position: Int = 0) = SiteEntity(
        id = id,
        name = name,
        host = "$id.example",
        port = 21,
        user = "u",
        passwordCipher = "x",
        security = "PLAIN",
        transferMode = "DEFAULT",
        pinnedCertificate = null,
        initialPath = null,
        position = position,
    )

    private fun namesInOrder(): List<String> = runBlocking { sites.all().map { it.name } }

    @Test
    fun `the list comes back in the stored order, not alphabetical`() = runBlocking {
        sites.upsert(site("c", "Charlie", position = 0))
        sites.upsert(site("a", "Alpha", position = 1))
        sites.upsert(site("b", "Bravo", position = 2))

        // The whole point: the user put Charlie first and it stays first.
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), namesInOrder())
    }

    /**
     * Two rows at the same place would otherwise swap about between readings
     * for no reason the user could see, which reads as the list forgetting
     * what they did.
     */
    @Test
    fun `rows sharing a place fall back on the name, stably`() = runBlocking {
        sites.upsert(site("b", "Bravo", position = 0))
        sites.upsert(site("a", "Alpha", position = 0))

        assertEquals(listOf("Alpha", "Bravo"), namesInOrder())
        assertEquals(listOf("Alpha", "Bravo"), namesInOrder())
    }

    @Test
    fun `a new site goes to the end`() = runBlocking {
        sites.upsert(site("a", "Alpha", position = 0))
        sites.upsert(site("b", "Bravo", position = 1))

        assertEquals(2, sites.nextPosition())
    }

    /** The first site of all has somewhere to go, rather than nowhere. */
    @Test
    fun `the first site starts at zero`() = runBlocking {
        assertEquals(0, sites.nextPosition())
    }

    @Test
    fun `reordering writes the order it was given`() = runBlocking {
        sites.upsert(site("a", "Alpha", position = 0))
        sites.upsert(site("b", "Bravo", position = 1))
        sites.upsert(site("c", "Charlie", position = 2))

        sites.reorder(listOf("c", "a", "b"))

        assertEquals(listOf("Charlie", "Alpha", "Bravo"), namesInOrder())
    }

    /**
     * A list that has drifted -- every row left at zero by an older version,
     * or two rows sharing a place after an interrupted write -- comes out of
     * a move numbered properly rather than keeping the damage.
     */
    @Test
    fun `reordering repairs positions that have drifted`() = runBlocking {
        sites.upsert(site("a", "Alpha", position = 0))
        sites.upsert(site("b", "Bravo", position = 0))
        sites.upsert(site("c", "Charlie", position = 0))

        sites.reorder(listOf("b", "c", "a"))

        assertEquals(listOf(0, 1, 2), sites.all().map { it.position })
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), namesInOrder())
    }

    /** Editing a site must not move it; only the move buttons do that. */
    @Test
    fun `saving a site again at its own position leaves the order alone`() = runBlocking {
        sites.upsert(site("a", "Alpha", position = 0))
        sites.upsert(site("b", "Bravo", position = 1))

        val renamed = sites.byId("b")!!.copy(name = "Bravo II")
        sites.upsert(renamed)

        assertEquals(listOf("Alpha", "Bravo II"), namesInOrder())
    }
}
