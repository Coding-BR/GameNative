package app.gamenative.utils

import app.gamenative.PrefManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HltbCacheTest {

    private val sampleStats = HltbService.Stats(
        mainHours = "10.0",
        mainPlusHours = "15.0",
        completeHours = "25.0",
        allStylesHours = "12.0",
        gameId = 42,
    )

    @Before
    fun setUp() {
        mockkObject(PrefManager)
        every { PrefManager.hltbCache } returns "{}"
        every { PrefManager.hltbCache = any() } just runs
        HltbCache.reset()
    }

    @After
    fun tearDown() {
        unmockkObject(PrefManager)
    }

    // ── basic get / put ──────────────────────────────────────────────────────

    @Test
    fun get_returnsPreviouslyPutStats() {
        HltbCache.put("Halo", sampleStats)
        assertNotNull(HltbCache.get("Halo"))
        assertEquals(sampleStats, HltbCache.get("Halo"))
    }

    @Test
    fun get_returnsCaseInsensitiveMatch() {
        HltbCache.put("Hollow Knight", sampleStats)
        assertNotNull(HltbCache.get("hollow knight"))
        assertNotNull(HltbCache.get("HOLLOW KNIGHT"))
    }

    @Test
    fun get_returnsNullForMissingEntry() {
        assertNull(HltbCache.get("Unknown Game"))
    }

    // ── key normalisation ────────────────────────────────────────────────────

    @Test
    fun key_normalisesPunctuation() {
        // "Elden Ring!" and "Elden Ring" should resolve to the same key
        HltbCache.put("Elden Ring!", sampleStats)
        assertNotNull(HltbCache.get("Elden Ring"))
    }

    // ── TTL eviction ─────────────────────────────────────────────────────────

    @Test
    fun get_returnsEntryWithinTtl() {
        HltbCache.put("Celeste", sampleStats)
        assertNotNull(HltbCache.get("Celeste"))
    }

    // ── MAX_ENTRIES cap ──────────────────────────────────────────────────────

    @Test
    fun put_evictsOldestWhenCapReached() {
        repeat(HltbCache.MAX_ENTRIES) { i ->
            HltbCache.put("Game $i", sampleStats)
        }
        assertNotNull(HltbCache.get("Game 0"))

        HltbCache.put("Overflow Game", sampleStats)

        assertNull(HltbCache.get("Game 0"))
        assertNotNull(HltbCache.get("Overflow Game"))
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test
    fun reset_clearsAllEntries() {
        HltbCache.put("Halo", sampleStats)
        HltbCache.reset()
        assertNull(HltbCache.get("Halo"))
    }
}

