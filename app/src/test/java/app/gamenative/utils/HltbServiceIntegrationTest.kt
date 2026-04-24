package app.gamenative.utils

import app.gamenative.PrefManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HltbServiceIntegrationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        mockkObject(PrefManager)
        every { PrefManager.hltbCache } returns "{}"
        every { PrefManager.hltbCache = any() } just runs

        HltbCache.reset()
        HltbService.setApiBaseUrlForTesting(server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        HltbService.resetForTesting()
        HltbCache.reset()
        unmockkObject(PrefManager)
        server.shutdown()
    }

    @Test
    fun getStats_fetchesAndFormatsBestStubbedMatch() = runBlocking {
        enqueueAuthResponse()
        enqueueSearchResponse(
            game("Halo Wars", 7200, 10800, 14400, 18000, 2),
            game("Halo", 3600, 5400, 7200, 9000, 1),
        )

        val stats = HltbService.getStats("Halo")

        assertNotNull(stats)
        assertEquals("1.0", stats?.mainHours)
        assertEquals("1.5", stats?.mainPlusHours)
        assertEquals("2.0", stats?.completeHours)
        assertEquals("2.5", stats?.allStylesHours)
        assertEquals(1, stats?.gameId)

        val initRequest = server.takeRequest()
        assertEquals("GET", initRequest.method)
        assertEquals("http://localhost:${server.port}", initRequest.getHeader("Origin"))
        assertEquals("http://localhost:${server.port}/", initRequest.getHeader("Referer"))

        val searchRequest = server.takeRequest()
        assertEquals("POST", searchRequest.method)
        assertEquals("token-123", searchRequest.getHeader("x-auth-token"))
        assertEquals("hp-key", searchRequest.getHeader("x-hp-key"))
        assertEquals("hp-val", searchRequest.getHeader("x-hp-val"))

        val payload = JSONObject(searchRequest.body.readUtf8())
        assertEquals("games", payload.getString("searchType"))
        assertEquals("Halo", payload.getJSONArray("searchTerms").getString(0))
        assertEquals("hp-val", payload.getString("hp-key"))
    }

    @Test
    fun getStats_usesCacheOnRepeatedCalls() = runBlocking {
        enqueueAuthResponse()
        enqueueSearchResponse(game("Celeste", 14400, 21600, 28800, 32400, 99))

        val first = HltbService.getStats("Celeste")
        val requestCountAfterFirstCall = server.requestCount
        val second = HltbService.getStats("Celeste")

        assertEquals(first, second)
        assertEquals(2, requestCountAfterFirstCall)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun getStats_returnsNullForZeroValueStubbedMatch() = runBlocking {
        enqueueAuthResponse()
        enqueueSearchResponse(game("Empty Game", 0, 0, 0, 0, 404))

        assertNull(HltbService.getStats("Empty Game"))
        assertEquals(2, server.requestCount)
    }

    private fun enqueueAuthResponse() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject()
                    .put("token", "token-123")
                    .put("session_key", "hp-key")
                    .put("session_val", "hp-val")
                    .toString(),
            ),
        )
    }

    private fun enqueueSearchResponse(vararg games: JSONObject) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put("data", JSONArray(games.asList())).toString(),
            ),
        )
    }

    private fun game(
        name: String,
        main: Long,
        plus: Long,
        complete: Long,
        allStyles: Long,
        id: Int,
    ) = JSONObject()
        .put("game_name", name)
        .put("comp_main", main)
        .put("comp_plus", plus)
        .put("comp_100", complete)
        .put("comp_all", allStyles)
        .put("game_id", id)
}
