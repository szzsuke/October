package com.metrolist.music.auth

import android.content.Context
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * YouTube Data API v3 Client
 * Handles authenticated read-only access with scope https://www.googleapis.com/auth/youtube.readonly
 * Queries playlists, playlistItems, and liked videos, persisting directly into Room database.
 */
object YouTubeDataClient {

    private const val TAG = "YouTubeDataClient"
    const val SCOPE_YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"

    val YouTubeAccessTokenKey = stringPreferencesKey("yt_data_access_token")
    val YouTubeRefreshTokenKey = stringPreferencesKey("yt_data_refresh_token")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class YouTubePlaylist(
        val id: String,
        val title: String,
        val itemCount: Int,
        val thumbnailUrl: String?,
    )

    data class YouTubeTrack(
        val videoId: String,
        val title: String,
        val channelTitle: String,
        val thumbnailUrl: String?,
        val durationSeconds: Int = 0,
    )

    /**
     * Fetches user's YouTube playlists (mine=true)
     */
    suspend fun fetchPlaylists(accessToken: String): Result<List<YouTubePlaylist>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Playlists request failed with code ${response.code}: ${response.body?.string()}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val items = json.optJSONArray("items") ?: return@runCatching emptyList()
                val list = mutableListOf<YouTubePlaylist>()
                for (i in 0 until items.length()) {
                    val obj = items.getJSONObject(i)
                    val id = obj.getString("id")
                    val snippet = obj.getJSONObject("snippet")
                    val title = snippet.getString("title")
                    val thumbnails = snippet.optJSONObject("thumbnails")
                    val thumbUrl = thumbnails?.optJSONObject("high")?.optString("url")
                        ?: thumbnails?.optJSONObject("default")?.optString("url")
                    val contentDetails = obj.optJSONObject("contentDetails")
                    val count = contentDetails?.optInt("itemCount", 0) ?: 0
                    list.add(YouTubePlaylist(id = id, title = title, itemCount = count, thumbnailUrl = thumbUrl))
                }
                list
            }
        }
    }

    /**
     * Fetches items for a specific playlist
     */
    suspend fun fetchPlaylistItems(accessToken: String, playlistId: String): Result<List<YouTubeTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId=$playlistId&maxResults=50"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Playlist items failed with code ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val items = json.optJSONArray("items") ?: return@runCatching emptyList()
                val list = mutableListOf<YouTubeTrack>()
                for (i in 0 until items.length()) {
                    val obj = items.getJSONObject(i)
                    val snippet = obj.getJSONObject("snippet")
                    val resourceId = snippet.optJSONObject("resourceId")
                    val videoId = resourceId?.optString("videoId").orEmpty()
                    if (videoId.isBlank()) continue
                    val title = snippet.optString("title", "Unknown Title")
                    val channel = snippet.optString("videoOwnerChannelTitle", "Unknown Artist")
                    val thumbnails = snippet.optJSONObject("thumbnails")
                    val thumbUrl = thumbnails?.optJSONObject("high")?.optString("url")
                        ?: thumbnails?.optJSONObject("default")?.optString("url")
                    list.add(YouTubeTrack(videoId = videoId, title = title, channelTitle = channel, thumbnailUrl = thumbUrl))
                }
                list
            }
        }
    }

    /**
     * Fetches user's liked videos (myRating=like)
     */
    suspend fun fetchLikedVideos(accessToken: String): Result<List<YouTubeTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&myRating=like&maxResults=50"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Liked videos failed with code ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val items = json.optJSONArray("items") ?: return@runCatching emptyList()
                val list = mutableListOf<YouTubeTrack>()
                for (i in 0 until items.length()) {
                    val obj = items.getJSONObject(i)
                    val id = obj.getString("id")
                    val snippet = obj.getJSONObject("snippet")
                    val title = snippet.optString("title", "Unknown Title")
                    val channel = snippet.optString("channelTitle", "Unknown Artist")
                    val thumbnails = snippet.optJSONObject("thumbnails")
                    val thumbUrl = thumbnails?.optJSONObject("high")?.optString("url")
                        ?: thumbnails?.optJSONObject("default")?.optString("url")
                    val durationIso = obj.optJSONObject("contentDetails")?.optString("duration").orEmpty()
                    val durationSec = parseIsoDuration(durationIso)
                    list.add(YouTubeTrack(videoId = id, title = title, channelTitle = channel, thumbnailUrl = thumbUrl, durationSeconds = durationSec))
                }
                list
            }
        }
    }

    /**
     * Syncs YouTube Data API playlists and liked videos into Room database.
     */
    suspend fun syncToDatabase(
        database: MusicDatabase,
        accessToken: String,
        onProgress: (message: String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Sync Playlists
            onProgress("Fetching YouTube Playlists...")
            val playlists = fetchPlaylists(accessToken).getOrDefault(emptyList())
            for (pl in playlists) {
                val playlistEntity = PlaylistEntity(
                    id = pl.id,
                    name = pl.title,
                    thumbnailUrl = pl.thumbnailUrl,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = false
                )
                database.insert(playlistEntity)

                val items = fetchPlaylistItems(accessToken, pl.id).getOrDefault(emptyList())
                items.forEachIndexed { idx, track ->
                    val metadata = MediaMetadata(
                        id = track.videoId,
                        title = track.title,
                        artists = listOf(MediaMetadata.Artist(id = null, name = track.channelTitle)),
                        duration = track.durationSeconds,
                        thumbnailUrl = track.thumbnailUrl
                    )
                    database.transaction {
                        insert(metadata)
                        insert(PlaylistSongMap(songId = track.videoId, playlistId = pl.id, position = idx))
                    }
                }
            }

            // 2. Sync Liked Videos
            onProgress("Fetching Liked Songs...")
            val liked = fetchLikedVideos(accessToken).getOrDefault(emptyList())
            for (track in liked) {
                val metadata = MediaMetadata(
                    id = track.videoId,
                    title = track.title,
                    artists = listOf(MediaMetadata.Artist(id = null, name = track.channelTitle)),
                    duration = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                    liked = true,
                    likedDate = LocalDateTime.now()
                )
                database.transaction {
                    insert(metadata) { it.copy(liked = true, likedDate = LocalDateTime.now()) }
                }
            }
        }
    }

    private fun parseIsoDuration(iso: String): Int {
        // Simple ISO-8601 duration parser (e.g. PT3M45S)
        if (iso.isBlank() || !iso.startsWith("PT")) return 0
        var seconds = 0
        val regex = Regex("""(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
        val match = regex.find(iso.removePrefix("PT")) ?: return 0
        val (h, m, s) = match.destructured
        seconds += (h.toIntOrNull() ?: 0) * 3600
        seconds += (m.toIntOrNull() ?: 0) * 60
        seconds += (s.toIntOrNull() ?: 0)
        return seconds
    }
}
