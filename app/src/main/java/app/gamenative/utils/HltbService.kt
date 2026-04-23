package app.gamenative.utils

import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for fetching HowLongToBeat (HLTB) game time statistics.
 *
 * Ported from the hltb-for-deck Steam Deck plugin (https://github.com/morwy/hltb-for-deck).
 *
 * The HLTB website requires an auth handshake before the search API can be used:
 * 1. Bootstrap: fetch homepage → discover Next.js build key + search API path from JS bundles.
 * 2. Auth: hit `{searchPath}/init` → acquire tokens (`x-auth-token`, `x-hp-key`, `x-hp-val`).
 * 3. Search: POST to the search API with auth headers + body fields → get game list with time data.
 * 4. The search results already contain comp_main/comp_plus/comp_100/comp_all in seconds.
 *
 * Uses java.net.HttpURLConnection for the POST because HLTB's CDN (Fastly) returns 404
 * for the search POST when made via OkHttp (HTTP/2 framing difference).
 *
 * Results are cached in memory and persisted to DataStore with a 12-hour TTL.
 */
object HltbService {
    private const val HLTB_BASE_URL = "https://howlongtobeat.com"
    private const val DEFAULT_SEARCH_PATH = "/api/find"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Use OkHttp (HTTP/1.1) for GETs (bootstrap, auth init)
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // --- Data models ---

    @Serializable
    data class HltbGameStats(
        val mainHours: String,
        val mainPlusHours: String,
        val completeHours: String,
        val allStylesHours: String,
        val gameId: Int,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private data class SearchAuth(
        val token: String,
        val hpKey: String,
        val hpVal: String,
    )

    // --- Bootstrap state ---

    private data class BootstrapState(
        var searchPath: String = DEFAULT_SEARCH_PATH,
        var nextJsKey: String? = null,
        var searchAuth: SearchAuth? = null,
        var lastBootstrapTime: Long = 0,
    )

    private val bootstrap = BootstrapState()

    // --- Bootstrap ---

    private suspend fun ensureBootstrapped() {
        val now = System.currentTimeMillis()
        if (bootstrap.nextJsKey != null && bootstrap.searchAuth != null &&
            now - bootstrap.lastBootstrapTime < 6 * 60 * 60 * 1000L
        ) return
        bootstrapHomepage()
    }

    private suspend fun bootstrapHomepage() = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(HLTB_BASE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .header("Origin", HLTB_BASE_URL)
                .header("Referer", "$HLTB_BASE_URL/")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Bootstrap failed: HTTP ${response.code}")
                    return@withContext
                }

                val html = response.body?.string() ?: return@withContext

                // Next.js build key
                val keyMatch = Regex("""/_next/static/([^/]+)/(_ssgManifest|_buildManifest)\.js""").find(html)
                if (keyMatch != null) {
                    bootstrap.nextJsKey = keyMatch.groupValues[1]
                    Timber.tag("HLTB").d("Next.js key: ${bootstrap.nextJsKey}")
                }

                // Search path from JS bundles
                val discovered = discoverSearchPath(html)
                if (discovered != null) bootstrap.searchPath = discovered

                // Auth
                fetchSearchAuth()
            }
            bootstrap.lastBootstrapTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "Bootstrap error")
        }
    }

    private suspend fun discoverSearchPath(html: String): String? = withContext(Dispatchers.IO) {
        try {
            val origin = HLTB_BASE_URL
            val srcPattern = Regex("""\bsrc\s*=\s*"([^"]+\.js)""")
            val scriptUrls = srcPattern.findAll(html)
                .map { it.groupValues[1] }
                .map { if (it.startsWith("http")) it else if (it.startsWith("//")) "https:$it" else "$origin$it" }
                .filter { it.startsWith(origin) }

            val fetchPattern = Regex(
                """fetch\s*\(\s*["']/api/([a-zA-Z0-9_/]+)[^"']*["']\s*,\s*\{[^}]*method:\s*["']POST["']""",
                RegexOption.IGNORE_CASE,
            )

            for (scriptUrl in scriptUrls) {
                try {
                    val req = Request.Builder().url(scriptUrl)
                        .header("User-Agent", USER_AGENT).build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val text = resp.body?.string() ?: return@use
                        val m = fetchPattern.find(text)
                        if (m != null) {
                            val suffix = m.groupValues[1]
                            val base = if (suffix.contains("/")) suffix.substringBefore("/") else suffix
                            val path = "/api/$base"
                            Timber.tag("HLTB").d("Search path: $path")
                            return@withContext path
                        }
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "discoverSearchPath error")
            null
        }
    }

    // --- Auth ---

    private suspend fun fetchSearchAuth(): SearchAuth? = withContext(Dispatchers.IO) {
        try {
            val url = "$HLTB_BASE_URL${bootstrap.searchPath}/init?t=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).get()
                .header("Content-Type", "application/json")
                .header("Origin", HLTB_BASE_URL)
                .header("Referer", "$HLTB_BASE_URL/")
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Auth init failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val data = JSONObject(body)
                val token = data.optString("token", "")
                var hpKey = ""
                var hpVal = ""
                for (fieldName in data.keys()) {
                    val value = data.optString(fieldName, "")
                    val lower = fieldName.lowercase()
                    if (hpKey.isEmpty() && lower.contains("key")) hpKey = value
                    else if (hpVal.isEmpty() && lower.contains("val")) hpVal = value
                }
                if (token.isNotEmpty() && hpKey.isNotEmpty() && hpVal.isNotEmpty()) {
                    val auth = SearchAuth(token, hpKey, hpVal)
                    bootstrap.searchAuth = auth
                    Timber.tag("HLTB").d("Auth acquired")
                    return@withContext auth
                }
                Timber.tag("HLTB").w("Incomplete auth response")
                null
            }
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "fetchSearchAuth error")
            null
        }
    }

    private suspend fun refreshAuth(redoSearchPath: Boolean = false): SearchAuth? {
        if (redoSearchPath) {
            bootstrap.searchPath = DEFAULT_SEARCH_PATH
            bootstrapHomepage()
            return bootstrap.searchAuth
        }
        return fetchSearchAuth()
    }

    // --- Search (uses HttpURLConnection) ---

    private suspend fun searchGame(gameName: String): Int? {
        val result = doSearch(gameName)
        if (result != null) return result

        Timber.tag("HLTB").d("Search returned null, refreshing auth")
        refreshAuth()
        val retry = doSearch(gameName)
        if (retry != null) return retry

        Timber.tag("HLTB").d("Retry failed, full re-bootstrap")
        refreshAuth(redoSearchPath = true)
        return doSearch(gameName)
    }

    /**
     * Execute a search using java.net.HttpURLConnection.
     * HLTB's CDN returns 404 for the POST when using OkHttp.
     */
    private suspend fun doSearch(gameName: String): Int? = withContext(Dispatchers.IO) {
        val auth = bootstrap.searchAuth ?: return@withContext null
        try {
            val postData = JSONObject().apply {
                put("searchType", "games")
                put("searchTerms", org.json.JSONArray(gameName.split(" ")))
                put("searchPage", 1)
                put("size", 20)
                put("searchOptions", JSONObject().apply {
                    put("games", JSONObject().apply {
                        put("userId", 0)
                        put("platform", "")
                        put("sortCategory", "name")
                        put("rangeCategory", "main")
                        put("rangeTime", JSONObject().apply { put("min", 0); put("max", 0) })
                        put("gameplay", JSONObject().apply {
                            put("perspective", ""); put("flow", "")
                            put("genre", ""); put("difficulty", "")
                        })
                        put("modifier", "hide_dlc")
                    })
                    put("users", JSONObject())
                    put("filter", "")
                    put("sort", 0)
                    put("randomizer", 0)
                })
                put(auth.hpKey, auth.hpVal)
            }

            val bodyBytes = postData.toString().toByteArray(Charsets.UTF_8)
            Timber.tag("HLTB").d("Searching '$gameName' via HttpURLConnection (${bodyBytes.size} bytes)")

            val conn = URL("$HLTB_BASE_URL${bootstrap.searchPath}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", HLTB_BASE_URL)
            conn.setRequestProperty("Referer", "$HLTB_BASE_URL/")
            conn.setRequestProperty("x-auth-token", auth.token)
            conn.setRequestProperty("x-hp-key", auth.hpKey)
            conn.setRequestProperty("x-hp-val", auth.hpVal)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(bodyBytes) }

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(200)
                Timber.tag("HLTB").w("Search failed '$gameName': HTTP $code body=$err")
                bootstrap.searchAuth = null
                return@withContext null
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val dataArray = json.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Timber.tag("HLTB").d("No results for '$gameName'")
                return@withContext null
            }

            val normalizedQuery = normalize(gameName)
            var bestId: Int? = null
            var bestDistance = Int.MAX_VALUE
            var bestPopularity = 0

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val gameId = item.optInt("game_id", 0)
                val rawName = item.optString("game_name", "")
                val compCount = item.optInt("comp_all_count", 0)
                val normalizedName = normalize(rawName)
                if (normalizedName == normalizedQuery) {
                    Timber.tag("HLTB").d("Exact match: '$gameName' → $gameId")
                    return@withContext gameId
                }
                val distance = levenshteinDistance(normalizedQuery, normalizedName)
                if (distance < bestDistance || (distance == bestDistance && compCount > bestPopularity)) {
                    bestDistance = distance
                    bestPopularity = compCount
                    bestId = gameId
                }
            }
            Timber.tag("HLTB").d("Best match '$gameName': HLTB ID $bestId (dist=$bestDistance)")
            return@withContext bestId
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "Search error '$gameName'")
            return@withContext null
        }
    }

    // --- Game page data fetch ---

    private suspend fun fetchGameStats(gameId: Int): HltbGameStats? {
        val stats = doFetchGameStats(gameId)
        if (stats != null) return stats
        bootstrap.nextJsKey = null
        bootstrapHomepage()
        return doFetchGameStats(gameId)
    }

    private suspend fun doFetchGameStats(gameId: Int): HltbGameStats? = withContext(Dispatchers.IO) {
        val key = bootstrap.nextJsKey ?: return@withContext null
        try {
            val url = "$HLTB_BASE_URL/_next/data/$key/game/$gameId.json"
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$HLTB_BASE_URL/")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Game fetch $gameId: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val gameList = json.optJSONObject("pageProps")
                    ?.optJSONObject("game")?.optJSONObject("data")
                    ?.optJSONArray("game")
                if (gameList == null || gameList.length() != 1) {
                    Timber.tag("HLTB").w("Unexpected data for $gameId")
                    return@withContext null
                }
                val g = gameList.getJSONObject(0)
                val stats = HltbGameStats(
                    mainHours = formatSeconds(g.optLong("comp_main", 0)),
                    mainPlusHours = formatSeconds(g.optLong("comp_plus", 0)),
                    completeHours = formatSeconds(g.optLong("comp_100", 0)),
                    allStylesHours = formatSeconds(g.optLong("comp_all", 0)),
                    gameId = gameId,
                )
                Timber.tag("HLTB").i("Stats $gameId: Main=${stats.mainHours}h +=${stats.mainPlusHours}h 100%=${stats.completeHours}h")
                return@withContext stats
            }
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "fetchGameStats $gameId")
            return@withContext null
        }
    }

    // --- Public API ---

    suspend fun getStats(gameName: String): HltbGameStats? {
        if (gameName.isBlank()) return null
        HltbCache.get(gameName)?.let { return it }
        val hltbId = searchGame(gameName) ?: return null
        val stats = fetchGameStats(hltbId) ?: return null
        HltbCache.put(gameName, stats)
        return stats
    }

    // --- Utility ---

    private fun formatSeconds(seconds: Long): String {
        if (seconds <= 0) return "--"
        return String.format("%.1f", seconds / 3600.0)
    }

    private fun normalize(str: String): String =
        str.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}

/**
 * Persistent cache for HLTB game stats with 12-hour TTL.
 */
object HltbCache {
    private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L

    private val inMemoryCache = mutableMapOf<String, HltbService.HltbGameStats>()
    private var cacheLoaded = false
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CachedEntry(val stats: HltbService.HltbGameStats, val timestamp: Long)

    private fun loadCache() {
        if (cacheLoaded) return
        try {
            val raw = PrefManager.hltbCache
            if (raw.isEmpty() || raw == "{}") { cacheLoaded = true; return }
            val map = json.decodeFromString<Map<String, CachedEntry>>(raw)
            val now = System.currentTimeMillis()
            map.forEach { (name, entry) ->
                if (now - entry.timestamp < CACHE_TTL_MS) inMemoryCache[name] = entry.stats
            }
            cacheLoaded = true
        } catch (e: Exception) {
            Timber.tag("HLTBCache").e(e, "Load error")
            cacheLoaded = true
        }
    }

    private fun saveCache() {
        try {
            val now = System.currentTimeMillis()
            PrefManager.hltbCache = json.encodeToString(
                inMemoryCache.mapValues { CachedEntry(it.value, now) }
            )
        } catch (e: Exception) {
            Timber.tag("HLTBCache").e(e, "Save error")
        }
    }

    fun get(gameName: String): HltbService.HltbGameStats? {
        loadCache()
        return inMemoryCache[normalize(gameName)]
    }

    fun put(gameName: String, stats: HltbService.HltbGameStats) {
        loadCache()
        inMemoryCache[normalize(gameName)] = stats
        saveCache()
    }

    private fun normalize(str: String) =
        str.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()
}
