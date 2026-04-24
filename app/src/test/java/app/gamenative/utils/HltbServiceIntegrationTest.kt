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
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": [
                    {
                      "game_name": "Halo Wars",
                      "comp_main": 7200,
                      "comp_plus": 10800,
                      "comp_100": 14400,
                      "comp_all": 18000,
                      "game_id": 2
                    },
                    {
                      "game_name": "Halo",
                      "comp_main": 3600,
                      "comp_plus": 5400,
                      "comp_100": 7200,
                      "comp_all": 9000,
                      "game_id": 1
                    }
                  ]
                }
                """.trimIndent(),
            ),
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
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": [
                    {
                      "game_name": "Celeste",
                      "comp_main": 14400,
                      "comp_plus": 21600,
                      "comp_100": 28800,
                      "comp_all": 32400,
                      "game_id": 99
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

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
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": [
                    {
                      "game_name": "Empty Game",
                      "comp_main": 0,
                      "comp_plus": 0,
                      "comp_100": 0,
                      "comp_all": 0,
                      "game_id": 404
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertNull(HltbService.getStats("Empty Game"))
        assertEquals(2, server.requestCount)
    }

    private fun enqueueAuthResponse() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "token": "token-123",
                  "session_key": "hp-key",
                  "session_val": "hp-val"
                }
                """.trimIndent(),
            ),
        )
    }
}
