package com.metrolist.music.reccobeats

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class ReccoBeatsAudioFeatures(
    val id: String,
    val spotifyId: String? = null,
    val acousticness: Double = 0.0,
    val danceability: Double = 0.0,
    val energy: Double = 0.0,
    val instrumentalness: Double = 0.0,
    val key: Int = 0,
    val liveness: Double = 0.0,
    val loudness: Double = 0.0,
    val mode: Int = 0,
    val speechiness: Double = 0.0,
    val tempo: Double = 0.0,
    val valence: Double = 0.0,
)

data class ReccoBeatsCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val spotifyId: String? = null,
    val durationMs: Long = 0L,
    val href: String? = null,
)

/**
 * ReccoBeats recommendation repository (base URL: https://api.reccobeats.com/v1).
 * Requires no API key.
 * Guarantees:
 * - Silent null / empty returns on 404, 400, or failure (never throws).
 * - Fast in-memory caching for repeat lookups.
 * - Graceful degradation to existing similarity/automix pipelines.
 */
object ReccoBeatsRepository {

    private const val TAG = "ReccoBeats"
    private const val BASE_URL = "https://api.reccobeats.com/v1"
    private const val USER_AGENT = "OctoberMusic/1.0"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    // In-memory cache for audio features: spotifyTrackId -> ReccoBeatsAudioFeatures
    private val audioFeaturesCache = LruCache<String, ReccoBeatsAudioFeatures>(200)

    // In-memory cache for recommendations: seedKey -> List<ReccoBeatsCandidate>
    private val recommendationsCache = LruCache<String, List<ReccoBeatsCandidate>>(100)

    // In-memory mapping from spotifyId -> reccoBeatsTrackId
    private val spotifyToReccoIdMap = LruCache<String, String>(500)

    /**
     * Resolves a Spotify track ID to its ReccoBeats UUID.
     */
    private suspend fun resolveSpotifyTrackId(spotifyTrackId: String): String? = withContext(Dispatchers.IO) {
        val cleanId = spotifyTrackId.trim().removePrefix("spotify:track:")
        if (cleanId.isBlank()) return@withContext null

        spotifyToReccoIdMap.get(cleanId)?.let { return@withContext it }

        runCatching {
            val url = "$BASE_URL/track?ids=${URLEncoder.encode(cleanId, StandardCharsets.UTF_8.name())}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val root = JSONObject(body)
                val content = root.optJSONArray("content") ?: return@runCatching null
                if (content.length() == 0) return@runCatching null

                val item = content.getJSONObject(0)
                val reccoId = item.optString("id").takeIf { it.isNotBlank() }
                if (reccoId != null) {
                    spotifyToReccoIdMap.put(cleanId, reccoId)
                }
                reccoId
            }
        }.getOrNull()
    }

    /**
     * Resolves a Spotify track ID by matching track title and artist against Spotify's catalog.
     * Used to seed ReccoBeats during playback for songs not originally imported from Spotify.
     */
    suspend fun resolveTrackSpotifyIdByMetadata(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val cacheKey = "meta:${title.trim()}:${artist.trim()}".lowercase()
        spotifyToReccoIdMap.get(cacheKey)?.let { return@withContext it }

        runCatching {
            val query = "site:open.spotify.com/track $title $artist".trim()
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val html = response.body?.string().orEmpty()
                val regex = Regex("""open\.spotify\.com/track/([a-zA-Z0-9]{22})""")
                val match = regex.find(html)
                val spotifyId = match?.groupValues?.getOrNull(1)
                if (spotifyId != null) {
                    spotifyToReccoIdMap.put(cacheKey, spotifyId)
                }
                spotifyId
            }
        }.getOrNull()
    }

    /**
     * Resolves multiple Spotify track IDs to their ReccoBeats UUIDs in a single batch request.
     */
    private suspend fun resolveBatchSpotifyIds(spotifyTrackIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val cleanIds = spotifyTrackIds.map { it.trim().removePrefix("spotify:track:") }.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return@withContext emptyMap()

        val result = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()

        for (id in cleanIds) {
            val cached = spotifyToReccoIdMap.get(id)
            if (cached != null) {
                result[id] = cached
            } else {
                missing.add(id)
            }
        }

        if (missing.isEmpty()) return@withContext result

        // Batch up to 20 IDs per request
        missing.chunked(20).forEach { chunk ->
            runCatching {
                val queryIds = chunk.joinToString(",") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }
                val url = "$BASE_URL/track?ids=$queryIds"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val root = JSONObject(body)
                        val content = root.optJSONArray("content")
                        if (content != null) {
                            for (i in 0 until content.length()) {
                                val item = content.getJSONObject(i)
                                val reccoId = item.optString("id")
                                val href = item.optString("href")
                                val itemSpotifyId = href.substringAfterLast("/").takeIf { it.isNotBlank() }
                                if (!reccoId.isNullOrBlank() && itemSpotifyId != null) {
                                    spotifyToReccoIdMap.put(itemSpotifyId, reccoId)
                                    result[itemSpotifyId] = reccoId
                                }
                            }
                        }
                    }
                }
            }
        }

        result
    }

    /**
     * Fetches audio features for a Spotify track ID (energy, valence, danceability, tempo, etc.).
     * Returns null on 404 or failure instead of throwing.
     */
    suspend fun fetchAudioFeatures(spotifyTrackId: String): ReccoBeatsAudioFeatures? = withContext(Dispatchers.IO) {
        val cleanId = spotifyTrackId.trim().removePrefix("spotify:track:")
        if (cleanId.isBlank()) return@withContext null

        audioFeaturesCache.get(cleanId)?.let { return@withContext it }

        val reccoId = resolveSpotifyTrackId(cleanId) ?: return@withContext null

        runCatching {
            val url = "$BASE_URL/track/$reccoId/audio-features"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val root = JSONObject(body)

                val features = ReccoBeatsAudioFeatures(
                    id = root.optString("id", reccoId),
                    spotifyId = cleanId,
                    acousticness = root.optDouble("acousticness", 0.0),
                    danceability = root.optDouble("danceability", 0.0),
                    energy = root.optDouble("energy", 0.0),
                    instrumentalness = root.optDouble("instrumentalness", 0.0),
                    key = root.optInt("key", 0),
                    liveness = root.optDouble("liveness", 0.0),
                    loudness = root.optDouble("loudness", 0.0),
                    mode = root.optInt("mode", 0),
                    speechiness = root.optDouble("speechiness", 0.0),
                    tempo = root.optDouble("tempo", 0.0),
                    valence = root.optDouble("valence", 0.0),
                )
                audioFeaturesCache.put(cleanId, features)
                features
            }
        }.onFailure {
            Timber.tag(TAG).d("Failed to fetch audio features for $cleanId: ${it.message}")
        }.getOrNull()
    }

    /**
     * Calls the ReccoBeats Recommendation endpoint with seed Spotify track IDs.
     * Returns candidate tracks (with their Spotify IDs + basic metadata).
     */
    suspend fun fetchRecommendations(
        seedSpotifyTrackIds: List<String>,
        limit: Int = 20
    ): List<ReccoBeatsCandidate> = withContext(Dispatchers.IO) {
        if (seedSpotifyTrackIds.isEmpty()) return@withContext emptyList()

        val cleanSeeds = seedSpotifyTrackIds
            .map { it.trim().removePrefix("spotify:track:") }
            .filter { it.isNotBlank() }
            .take(5) // ReccoBeats recommendations accept up to 5 seeds

        val cacheKey = cleanSeeds.sorted().joinToString(",") + ":$limit"
        recommendationsCache.get(cacheKey)?.let { return@withContext it }

        // Resolve seeds to ReccoBeats IDs
        val resolvedMap = resolveBatchSpotifyIds(cleanSeeds)
        val seedReccoIds = cleanSeeds.mapNotNull { resolvedMap[it] }

        if (seedReccoIds.isEmpty()) {
            Timber.tag(TAG).d("Could not resolve any seed IDs in ReccoBeats database")
            return@withContext emptyList()
        }

        runCatching {
            val seedsParam = seedReccoIds.joinToString(",")
            val url = "$BASE_URL/track/recommendation?seeds=$seedsParam&size=$limit"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Recommendation endpoint returned status ${response.code}")
                    return@runCatching emptyList()
                }
                val body = response.body?.string() ?: return@runCatching emptyList()
                val root = JSONObject(body)
                val content = root.optJSONArray("content") ?: return@runCatching emptyList()

                val candidates = mutableListOf<ReccoBeatsCandidate>()
                for (i in 0 until content.length()) {
                    val item = content.getJSONObject(i)
                    val id = item.optString("id")
                    val title = item.optString("trackTitle")
                    val artistsArray = item.optJSONArray("artists")
                    val artistName = artistsArray?.optJSONObject(0)?.optString("name").orEmpty()
                    val href = item.optString("href")
                    val candidateSpotifyId = href.substringAfterLast("/").takeIf { it.isNotBlank() }

                    if (title.isNotBlank() && artistName.isNotBlank()) {
                        candidates.add(
                            ReccoBeatsCandidate(
                                id = id,
                                title = title,
                                artist = artistName,
                                spotifyId = candidateSpotifyId,
                                durationMs = item.optLong("durationMs", 0L),
                                href = href,
                            )
                        )
                    }
                }

                recommendationsCache.put(cacheKey, candidates)
                candidates
            }
        }.onFailure {
            Timber.tag(TAG).d("ReccoBeats recommendation query failed: ${it.message}")
        }.getOrDefault(emptyList())
    }
}
