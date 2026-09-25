package com.metrolist.music.artwork

import android.content.Context
import android.util.LruCache
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * High-Resolution Album Art Repository
 * 
 * Implements a multi-tier pipeline to replace low-resolution YouTube Music thumbnails
 * with label-quality uncompressed artwork:
 * 1. MusicBrainz + Cover Art Archive (front-1200 or original uncompressed)
 * 2. iTunes Search API (1200x1200bb / 3000x3000bb)
 * 3. Deezer Public Search API (cover_xl 1000x1000)
 * 4. YouTube Music fallback (resize 1080x1080)
 */
object HighResArtworkRepository {

    private const val TAG = "HighResArtwork"
    private const val USER_AGENT = "OctoberMusic/1.0 ( support@october.internal )"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // In-memory LRU cache for 500 recently resolved URLs
    private val memoryCache = LruCache<String, String>(500)

    // Persistent disk cache file
    private var cacheDir: File? = null
    private val diskCacheLock = Any()

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, "artwork_highres").apply { mkdirs() }
        }
    }

    /**
     * Resolves the best available high-res artwork for an artist and album.
     * Guaranteed never to throw; always returns either high-res or fallback URL.
     */
    suspend fun resolveArtwork(
        artist: String?,
        album: String?,
        fallbackUrl: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val cleanArtist = artist?.trim().orEmpty()
        val cleanAlbum = album?.trim().orEmpty()

        if (cleanArtist.isBlank() && cleanAlbum.isBlank()) {
            return@withContext fallbackUrl?.resize(1080, 1080)
        }

        val cacheKey = "${cleanArtist.lowercase()}|${cleanAlbum.lowercase()}"

        // 1. Check in-memory cache
        memoryCache.get(cacheKey)?.let { cached ->
            return@withContext cached
        }

        // 2. Check disk cache
        readFromDiskCache(cacheKey)?.let { cached ->
            memoryCache.put(cacheKey, cached)
            return@withContext cached
        }

        // Execute pipeline: MusicBrainz/CAA -> iTunes -> Deezer -> YouTube fallback
        var resolvedUrl: String? = null

        // Step 1: MusicBrainz + Cover Art Archive
        if (cleanArtist.isNotBlank() && cleanAlbum.isNotBlank()) {
            resolvedUrl = fetchMusicBrainzCoverArt(cleanArtist, cleanAlbum)
        }

        // Step 2: iTunes Search API fallback
        if (resolvedUrl == null) {
            val searchTerm = if (cleanArtist.isNotBlank() && cleanAlbum.isNotBlank()) {
                "$cleanArtist $cleanAlbum"
            } else {
                cleanAlbum.ifBlank { cleanArtist }
            }
            resolvedUrl = fetchITunesArtwork(searchTerm)
        }

        // Step 3: Deezer Search API fallback
        if (resolvedUrl == null) {
            val searchTerm = if (cleanArtist.isNotBlank() && cleanAlbum.isNotBlank()) {
                "$cleanArtist $cleanAlbum"
            } else {
                cleanAlbum.ifBlank { cleanArtist }
            }
            resolvedUrl = fetchDeezerArtwork(searchTerm)
        }

        // Step 4: Fallback to YouTube Music enlarged thumbnail
        val finalUrl = resolvedUrl ?: fallbackUrl?.resize(1080, 1080)

        if (finalUrl != null) {
            memoryCache.put(cacheKey, finalUrl)
            writeToDiskCache(cacheKey, finalUrl)
        }

        finalUrl
    }

    /**
     * 1. MusicBrainz + Cover Art Archive
     */
    private fun fetchMusicBrainzCoverArt(artist: String, album: String): String? {
        return runCatching {
            val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.name())
            val encodedAlbum = URLEncoder.encode(album, StandardCharsets.UTF_8.name())
            val url = "https://musicbrainz.org/ws/2/release/?query=artist:$encodedArtist%20AND%20release:$encodedAlbum&fmt=json&limit=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val releases = root.optJSONArray("releases") ?: return null
                if (releases.length() == 0) return null

                val mbid = releases.getJSONObject(0).optString("id")
                if (mbid.isNullOrBlank()) return null

                // Query Cover Art Archive for this MBID
                fetchCoverArtArchive(mbid)
            }
        }.getOrNull()
    }

    private fun fetchCoverArtArchive(mbid: String): String? {
        return runCatching {
            val caaUrl = "https://coverartarchive.org/release/$mbid"
            val request = Request.Builder()
                .url(caaUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val images = root.optJSONArray("images") ?: return null

                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    if (img.optBoolean("front", false)) {
                        val thumbnails = img.optJSONObject("thumbnails")
                        // Prefer 1200px front thumbnail, fallback to original full image
                        return thumbnails?.optString("1200")?.takeIf { it.isNotBlank() }
                            ?: thumbnails?.optString("large")?.takeIf { it.isNotBlank() }
                            ?: img.optString("image").takeIf { it.isNotBlank() }
                    }
                }
                null
            }
        }.getOrNull()
    }

    /**
     * 2. iTunes Search API (1200x1200bb / 3000x3000bb)
     */
    private fun fetchITunesArtwork(searchTerm: String): String? {
        return runCatching {
            val encoded = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8.name())
            val url = "https://itunes.apple.com/search?term=$encoded&entity=album&limit=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val results = root.optJSONArray("results") ?: return null
                if (results.length() == 0) return null

                val artwork100 = results.getJSONObject(0).optString("artworkUrl100")
                if (artwork100.isNullOrBlank()) return null

                // Classic uncompressed Apple label artwork trick: replace 100x100bb.jpg with 1200x1200bb.jpg
                artwork100.replace("100x100bb.jpg", "1200x1200bb.jpg")
                    .replace("100x100bb.png", "1200x1200bb.png")
            }
        }.getOrNull()
    }

    /**
     * 3. Deezer Public Search API (cover_xl 1000x1000)
     */
    private fun fetchDeezerArtwork(searchTerm: String): String? {
        return runCatching {
            val encoded = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8.name())
            val url = "https://api.deezer.com/search/album?q=$encoded&limit=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val data = root.optJSONArray("data") ?: return null
                if (data.length() == 0) return null

                val first = data.getJSONObject(0)
                first.optString("cover_xl").takeIf { it.isNotBlank() }
                    ?: first.optString("cover_big").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun readFromDiskCache(key: String): String? {
        return synchronized(diskCacheLock) {
            runCatching {
                val file = getDiskCacheFile(key)
                if (file.exists()) {
                    file.readText().trim().takeIf { it.isNotBlank() }
                } else null
            }.getOrNull()
        }
    }

    private fun writeToDiskCache(key: String, url: String) {
        synchronized(diskCacheLock) {
            runCatching {
                val file = getDiskCacheFile(key)
                file.parentFile?.mkdirs()
                file.writeText(url)
            }
        }
    }

    private fun getDiskCacheFile(key: String): File {
        val safeFileName = key.hashCode().toString() + ".txt"
        return File(cacheDir ?: File("."), safeFileName)
    }
}
