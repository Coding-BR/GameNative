package app.gamenative.utils

import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Service for fetching HowLongToBeat (HLTB) game time statistics.
 *
 * Inspired by the hltb-for-deck Steam Deck plugin (https://github.com/morwy/hltb-for-deck).
 * The HLTB website uses a Next.js frontend with a POST-based search API and a
 * separate auth/bootstrap flow. This service implements a simplified version:
 *
 * 1. Fetch the HLTB homepage HTML to discover the Next.js build key.
 * 2. Search for the game by name using the HLTB search API.
 * 3. Fetch detailed stats via the Next.js data endpoint.
 *
 * Results are cached in memory and persisted to DataStore with a 12-hour TTL.
 */
object HltbService {
    private const val HLTB_BASE_URL = "https://howlongtobeat.com"
    private const val DEFAULT_SEARCH_PATH = "/api/find"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = Net.http

    // --- Data models ---

    @Serializable
    data class HltbGameStats(
        val mainHours: String,       // Main Story
        val mainPlusHours: String,   // Main + Extras
        val completeHours: String,   // Completionist
        val allStylesHours: String,  // All Styles
        val gameId: Int,             // HLTB game ID for linking
        val timestamp: Long = System.currentTimeMillis(),
    )

    // --- Bootstrap / Search URL discovery ---

    private data class BootstrapState(
        var searchPath: String = DEFAULT_SEARCH_PATH,
        var nextJsKey: String? = null,
        var lastBootstrapTime: Long = 0,
    )

    private val bootstrap = BootstrapState()

    /**
     * Ensure bootstrap data is fresh (refresh every 6 hours).
     */
    private suspend fun ensureBootstrapped() {
        val now = System.currentTimeMillis()
        if (bootstrap.nextJsKey != null && now - bootstrap.lastBootstrapTime < 6 * 60 * 60 * 1000L) {
            return
        }
        bootstrapHomepage()
    }

    /**
     * Fetch the HLTB homepage to discover the Next.js build key used in
     * data-fetch URLs like `/_next/data/<key>/game/<id>.json`.
     */
    private suspend fun bootstrapHomepage() = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(HLTB_BASE_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Bootstrap failed: HTTP ${response.code}")
                    return@withContext
                }

                val html = response.body?.string() ?: return@withContext

                // Find the Next.js build key from script sources
                val keyPattern = Regex("""/_next/static/([^/]+)/(_ssgManifest|_buildManifest)\.js""")
                val keyMatch = keyPattern.find(html)
                if (keyMatch != null) {
                    bootstrap.nextJsKey = keyMatch.groupValues[1]
                    Timber.tag("HLTB").d("Discovered Next.js key: ${bootstrap.nextJsKey}")
                }

                // Find the search API path from inline script references
                val searchPattern = Regex("""fetch\s*\(\s*["']/api/([a-zA-Z0-9_/]+)""")
                val searchMatch = searchPattern.find(html)
                if (searchMatch != null) {
                    val suffix = searchMatch.groupValues[1]
                    val basePath = if (suffix.contains("/")) suffix.substringBefore("/") else suffix
                    bootstrap.searchPath = "/api/$basePath"
                    Timber.tag("HLTB").d("Discovered search path: ${bootstrap.searchPath}")
                }
            }

            bootstrap.lastBootstrapTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "Bootstrap error")
        }
    }

    // --- API calls ---

    /**
     * Search HLTB for a game by name, returning the best-matching game ID.
     */
    private suspend fun searchGame(gameName: String): Int? = withContext(Dispatchers.IO) {
        ensureBootstrapped()

        try {
            val searchTerms = gameName.split(" ")
            val postData = JSONObject().apply {
                put("searchType", "games")
                put("searchTerms", org.json.JSONArray(searchTerms))
                put("searchPage", 1)
                put("size", 20)
                put("searchOptions", JSONObject().apply {
                    put("games", JSONObject().apply {
                        put("userId", 0)
                        put("platform", "")
                        put("sortCategory", "name")
                        put("rangeCategory", "main")
                        put("modifier", "hide_dlc")
                    })
                    put("users", JSONObject())
                    put("filter", "")
                    put("sort", 0)
                    put("randomizer", 0)
                })
            }

            val mediaType = "application/json".toMediaType()
            val body = postData.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$HLTB_BASE_URL${bootstrap.searchPath}")
                .post(body)
                .header("Content-Type", "application/json")
                .header("Origin", HLTB_BASE_URL)
                .header("Referer", "$HLTB_BASE_URL/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Search failed for '$gameName': HTTP ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                val json = JSONObject(responseBody)
                val dataArray = json.optJSONArray("data") ?: return@withContext null

                if (dataArray.length() == 0) {
                    Timber.tag("HLTB").d("No search results for '$gameName'")
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

                    // Exact match wins immediately
                    if (normalizedName == normalizedQuery) {
                        return@withContext gameId
                    }

                    // Levenshtein-like distance via simple comparison
                    val distance = levenshteinDistance(normalizedQuery, normalizedName)
                    if (distance < bestDistance || (distance == bestDistance && compCount > bestPopularity)) {
                        bestDistance = distance
                        bestPopularity = compCount
                        bestId = gameId
                    }
                }

                Timber.tag("HLTB").d("Best match for '$gameName': HLTB ID $bestId (distance $bestDistance)")
                return@withContext bestId
            }
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "Search error for '$gameName'")
            return@withContext null
        }
    }

    /**
     * Fetch detailed game stats from the HLTB Next.js data endpoint.
     */
    private suspend fun fetchGameStats(gameId: Int): HltbGameStats? = withContext(Dispatchers.IO) {
        ensureBootstrapped()

        val key = bootstrap.nextJsKey
        if (key == null) {
            Timber.tag("HLTB").w("No Next.js key available for game $gameId")
            return@withContext null
        }

        try {
            val url = "$HLTB_BASE_URL/_next/data/$key/game/$gameId.json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "$HLTB_BASE_URL/")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Game data fetch failed for $gameId: HTTP ${response.code}")
                    // Key might be stale, clear it
                    bootstrap.nextJsKey = null
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                val json = JSONObject(responseBody)

                val gameDataList = json
                    .optJSONObject("pageProps")
                    ?.optJSONObject("game")
                    ?.optJSONObject("data")
                    ?.optJSONArray("game")

                if (gameDataList == null || gameDataList.length() != 1) {
                    Timber.tag("HLTB").w("Unexpected game data structure for $gameId")
                    return@withContext null
                }

                val gameData = gameDataList.getJSONObject(0)

                val compMain = gameData.optLong("comp_main", 0)
                val compPlus = gameData.optLong("comp_plus", 0)
                val comp100 = gameData.optLong("comp_100", 0)
                val compAll = gameData.optLong("comp_all", 0)

                val stats = HltbGameStats(
                    mainHours = formatSeconds(compMain),
                    mainPlusHours = formatSeconds(compPlus),
                    completeHours = formatSeconds(comp100),
                    allStylesHours = formatSeconds(compAll),
                    gameId = gameId,
                )

                Timber.tag("HLTB").i("Fetched stats for $gameId: Main=${stats.mainHours}h, Main+=${stats.mainPlusHours}h, 100%=${stats.completeHours}h")
                return@withContext stats
            }
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "Error fetching game stats for $gameId")
            return@withContext null
        }
    }

    // --- Public API ---

    /**
     * Fetch HLTB stats for a game. Uses cache when available (12h TTL).
     *
     * @param gameName The name of the game to look up.
     * @return HLTB stats, or null if not found / on error.
     */
    suspend fun getStats(gameName: String): HltbGameStats? {
        if (gameName.isBlank()) return null

        // Check cache first
        val cached = HltbCache.get(gameName)
        if (cached != null) return cached

        // Search and fetch
        val hltbId = searchGame(gameName) ?: return null
        val stats = fetchGameStats(hltbId) ?: return null

        HltbCache.put(gameName, stats)
        return stats
    }

    // --- Utility ---

    /**
     * Convert seconds (as used by HLTB API) to hours string.
     * HLTB stores comp_main etc. in seconds.
     */
    private fun formatSeconds(seconds: Long): String {
        if (seconds <= 0) return "--"
        val hours = seconds / 3600.0
        return String.format("%.1f", hours)
    }

    /**
     * Normalize a game name for matching (lowercase, strip special chars).
     */
    private fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Simple Levenshtein distance for fuzzy matching.
     */
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
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[a.length][b.length]
    }
}

/**
 * Persistent cache for HLTB game stats with a 12-hour TTL.
 */
object HltbCache {
    private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L // 12 hours

    private val inMemoryCache = mutableMapOf<String, HltbService.HltbGameStats>()
    private var cacheLoaded = false

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CachedEntry(
        val stats: HltbService.HltbGameStats,
        val timestamp: Long,
    )

    private fun loadCache() {
        if (cacheLoaded) return
        try {
            val cacheJson = PrefManager.hltbCache
            if (cacheJson.isEmpty() || cacheJson == "{}") {
                cacheLoaded = true
                return
            }
            val cacheMap = json.decodeFromString<Map<String, CachedEntry>>(cacheJson)
            val now = System.currentTimeMillis()
            cacheMap.forEach { (name, entry) ->
                if (now - entry.timestamp < CACHE_TTL_MS) {
                    inMemoryCache[name] = entry.stats
                }
            }
            Timber.tag("HLTBCache").d("Loaded ${inMemoryCache.size} cached entries")
            cacheLoaded = true
        } catch (e: Exception) {
            Timber.tag("HLTBCache").e(e, "Failed to load cache")
            cacheLoaded = true
        }
    }

    private fun saveCache() {
        try {
            val now = System.currentTimeMillis()
            val cacheMap = inMemoryCache.mapValues { (_, stats) ->
                CachedEntry(stats, now)
            }
            PrefManager.hltbCache = json.encodeToString(cacheMap)
        } catch (e: Exception) {
            Timber.tag("HLTBCache").e(e, "Failed to save cache")
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

    private fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
