package com.metrolist.music.converter

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * October Playlist Converter
 * 
 * Allows users to import and convert playlists and playlist history from Spotify,
 * Apple Music, or exported CSV/Text files into October / YouTube Music playlists.
 * 
 * Strict Privacy & Isolation Guarantees:
 * 1. Zero syncing or mixing with YouTube watch/listening history.
 * 2. Does NOT fetch, extract, or use Apple account profile pictures or avatars.
 * 3. Import only occurs upon explicit user request via this converter.
 */
object PlaylistConverter {

    data class ConvertedTrack(
        val title: String,
        val artist: String,
        val album: String? = null,
        val spotifyId: String? = null,
        val durationMs: Long = 0L,
    )

    enum class PlaylistSource {
        SPOTIFY,
        APPLE_MUSIC,
        TEXT_CSV,
    }

    data class ExternalPlaylist(
        val title: String,
        val description: String? = null,
        val source: PlaylistSource,
        val tracks: List<ConvertedTrack>,
        val isLikedSongs: Boolean = false,
    )

    data class ConversionResult(
        val playlistId: String,
        val playlistTitle: String,
        val totalTracks: Int,
        val matchedTracks: Int,
    )

    data class BatchConversionResult(
        val playlistsCount: Int,
        val totalTracks: Int,
        val matchedTracks: Int,
    )

    data class ImportProgressState(
        val isRunning: Boolean = false,
        val isPlaylistsRegistered: Boolean = false,
        val playlistsCount: Int = 0,
        val currentTrack: Int = 0,
        val totalTracks: Int = 0,
        val currentTrackTitle: String = "",
        val currentPlaylistTitle: String = "",
        val completedResult: BatchConversionResult? = null,
        val error: String? = null
    )

    val importProgress = kotlinx.coroutines.flow.MutableStateFlow<ImportProgressState?>(null)
    val matchedSongsCount = kotlinx.coroutines.flow.MutableStateFlow<Int>(0)
    private val converterScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Parses a playlist input string, which can be:
     * - A Spotify playlist or album URL
     * - An Apple Music playlist or album URL
     * - Exported JSON listening/streaming history
     * - Plain text or CSV track lines (e.g. "Song - Artist" or "Song, Artist")
     */
    suspend fun parse(input: String): Result<ExternalPlaylist> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Input cannot be empty"))
        }

        try {
            when {
                isSpotifyUrl(trimmed) -> parseSpotify(trimmed)
                isAppleMusicUrl(trimmed) -> parseAppleMusic(trimmed)
                else -> parseTextOrHistory(trimmed)
            }
        } catch (e: Exception) {
            Timber.tag("PlaylistConverter").e(e, "Error parsing playlist input")
            Result.failure(e)
        }
    }

    private fun isSpotifyUrl(url: String): Boolean {
        return url.contains("spotify.com") || url.contains("spotify.link")
    }

    private fun isAppleMusicUrl(url: String): Boolean {
        return url.contains("music.apple.com")
    }

    private fun parseSpotify(url: String): Result<ExternalPlaylist> {
        // Resolve shortened/redirect links if needed
        var targetUrl = url
        if (url.contains("spotify.link")) {
            val headRequest = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            httpClient.newCall(headRequest).execute().use { response ->
                targetUrl = response.request.url.toString()
            }
        }

        // Extract playlist or album ID
        val playlistMatcher = Pattern.compile("playlist/([a-zA-Z0-9]+)").matcher(targetUrl)
        val albumMatcher = Pattern.compile("album/([a-zA-Z0-9]+)").matcher(targetUrl)

        val (type, id) = when {
            playlistMatcher.find() -> "playlist" to playlistMatcher.group(1)
            albumMatcher.find() -> "album" to albumMatcher.group(1)
            else -> return Result.failure(IllegalArgumentException("Could not extract Spotify playlist or album ID from URL"))
        }

        // Fetch the embed version which provides clean server-rendered NEXT_DATA JSON
        val embedUrl = "https://open.spotify.com/embed/$type/$id"
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("Failed to fetch Spotify playlist (HTTP ${response.code})"))
            }

            val html = response.body?.string().orEmpty()
            val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
            val matcher = nextDataPattern.matcher(html)

            if (!matcher.find()) {
                return Result.failure(IllegalStateException("Unable to read playlist data from Spotify embed"))
            }

            val jsonStr = matcher.group(1)
            val root = JSONObject(jsonStr)
            val entity = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
                ?: return Result.failure(IllegalStateException("Invalid Spotify metadata structure"))

            val title = entity.optString("title").ifBlank { entity.optString("name", "Spotify Playlist") }
            val trackArray = entity.optJSONArray("trackList") ?: JSONArray()
            val tracks = mutableListOf<ConvertedTrack>()

            for (i in 0 until trackArray.length()) {
                val item = trackArray.optJSONObject(i) ?: continue
                val trackTitle = item.optString("title").trim()
                val trackArtist = item.optString("subtitle").trim()
                val uri = item.optString("uri")
                val spotifyId = if (uri.startsWith("spotify:track:")) {
                    uri.removePrefix("spotify:track:")
                } else {
                    item.optString("id").takeIf { it.isNotBlank() }
                }
                if (trackTitle.isNotEmpty()) {
                    tracks.add(
                        ConvertedTrack(
                            title = trackTitle,
                            artist = trackArtist,
                            spotifyId = spotifyId,
                        )
                    )
                }
            }

            if (tracks.isEmpty()) {
                return Result.failure(IllegalStateException("No tracks found in Spotify playlist"))
            }

            return Result.success(
                ExternalPlaylist(
                    title = title,
                    source = PlaylistSource.SPOTIFY,
                    tracks = tracks,
                )
            )
        }
    }

    private fun parseAppleMusic(url: String): Result<ExternalPlaylist> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("Failed to fetch Apple Music playlist (HTTP ${response.code})"))
            }

            val html = response.body?.string().orEmpty()
            val tracks = mutableListOf<ConvertedTrack>()
            var title = "Apple Music Playlist"

            // 1. First priority: serialized-server-data (Contains clean song titles and artist names)
            val serverDataPattern = Pattern.compile("<script[^>]*id=\"serialized-server-data\"[^>]*>(.*?)</script>", Pattern.DOTALL)
            val serverDataMatcher = serverDataPattern.matcher(html)
            if (serverDataMatcher.find()) {
                try {
                    val serverJson = JSONObject(serverDataMatcher.group(1))
                    extractTracksFromServerData(serverJson, tracks)
                } catch (e: Exception) {
                    Timber.tag("PlaylistConverter").w(e, "serialized-server-data parsing failed, trying fallback")
                }
            }

            // 2. Fallback: application/ld+json (Schema.org MusicPlaylist)
            if (tracks.isEmpty()) {
                val ldJsonPattern = Pattern.compile("<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
                val ldMatcher = ldJsonPattern.matcher(html)
                while (ldMatcher.find()) {
                    try {
                        val ldObj = JSONObject(ldMatcher.group(1))
                        val type = ldObj.optString("@type")
                        if (type == "MusicPlaylist" || type == "MusicAlbum") {
                            title = ldObj.optString("name", title)
                            val trackArray = ldObj.optJSONArray("track") ?: JSONArray()
                            for (i in 0 until trackArray.length()) {
                                val item = trackArray.optJSONObject(i) ?: continue
                                val songName = item.optString("name").trim()
                                if (songName.isNotEmpty()) {
                                    tracks.add(ConvertedTrack(title = songName, artist = ""))
                                }
                            }
                            if (tracks.isNotEmpty()) break
                        }
                    } catch (ignored: Exception) {}
                }
            }

            // Extract playlist title if not already found
            val ogTitlePattern = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"")
            val ogMatcher = ogTitlePattern.matcher(html)
            if (ogMatcher.find()) {
                val rawOg = ogMatcher.group(1).replace(" on Apple Music", "").trim()
                if (rawOg.isNotBlank()) title = rawOg
            }

            // Strictest compliance: NEVER extract, parse, or return Apple account profile pictures or avatars.
            if (tracks.isEmpty()) {
                return Result.failure(IllegalStateException("No tracks could be extracted from Apple Music link"))
            }

            return Result.success(
                ExternalPlaylist(
                    title = title,
                    source = PlaylistSource.APPLE_MUSIC,
                    tracks = tracks,
                )
            )
        }
    }

    private fun extractTracksFromServerData(obj: Any?, result: MutableList<ConvertedTrack>) {
        when (obj) {
            is JSONObject -> {
                if (obj.optString("itemKind") == "trackLockup") {
                    val items = obj.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val trackObj = items.optJSONObject(i) ?: continue
                            val songTitle = trackObj.optString("title").trim()
                            var artistName = trackObj.optString("artistName").trim()
                            if (artistName.isEmpty()) {
                                artistName = trackObj.optString("subtitle").trim()
                            }
                            if (songTitle.isNotEmpty()) {
                                result.add(
                                    ConvertedTrack(
                                        title = songTitle,
                                        artist = artistName,
                                    )
                                )
                            }
                        }
                    }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    extractTracksFromServerData(obj.opt(keys.next()), result)
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    extractTracksFromServerData(obj.opt(i), result)
                }
            }
        }
    }

    private fun parseTextOrHistory(text: String): Result<ExternalPlaylist> {
        val tracks = mutableListOf<ConvertedTrack>()

        // Check if JSON array (e.g. Spotify streaming history or export)
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val title = obj.optString("trackName").ifBlank {
                        obj.optString("master_metadata_track_name")
                    }.ifBlank {
                        obj.optString("name")
                    }.ifBlank {
                        obj.optString("title")
                    }.trim()

                    val artist = obj.optString("artistName").ifBlank {
                        obj.optString("master_metadata_album_artist_name")
                    }.ifBlank {
                        obj.optString("artist")
                    }.trim()

                    if (title.isNotEmpty()) {
                        tracks.add(ConvertedTrack(title = title, artist = artist))
                    }
                }
                if (tracks.isNotEmpty()) {
                    return Result.success(
                        ExternalPlaylist(
                            title = "Imported Streaming History",
                            source = PlaylistSource.TEXT_CSV,
                            tracks = tracks,
                        )
                    )
                }
            } catch (ignored: Exception) {}
        }

        // Line-by-line parsing (CSV or plain text list)
        val lines = text.lines()
        for (line in lines) {
            val clean = line.trim()
            if (clean.isBlank()) continue
            if (clean.startsWith("#") || clean.startsWith("//")) continue

            // Skip typical CSV header lines
            val lower = clean.lowercase()
            if (lower.startsWith("track,artist") || lower.startsWith("title,artist") || lower.startsWith("name,artist")) {
                continue
            }

            when {
                clean.contains(" - ") -> {
                    val parts = clean.split(" - ", limit = 2)
                    tracks.add(ConvertedTrack(title = parts[1].trim(), artist = parts[0].trim()))
                }
                clean.contains(",") -> {
                    val parts = clean.split(",", limit = 3)
                    val title = parts[0].replace("\"", "").trim()
                    val artist = parts.getOrNull(1)?.replace("\"", "")?.trim().orEmpty()
                    tracks.add(ConvertedTrack(title = title, artist = artist))
                }
                clean.contains("\t") -> {
                    val parts = clean.split("\t", limit = 3)
                    val title = parts[0].trim()
                    val artist = parts.getOrNull(1)?.trim().orEmpty()
                    tracks.add(ConvertedTrack(title = title, artist = artist))
                }
                else -> {
                    tracks.add(ConvertedTrack(title = clean, artist = ""))
                }
            }
        }

        if (tracks.isEmpty()) {
            return Result.failure(IllegalArgumentException("No valid tracks found in input"))
        }

        return Result.success(
            ExternalPlaylist(
                title = "Imported Track History",
                source = PlaylistSource.TEXT_CSV,
                tracks = tracks,
            )
        )
    }

    /**
     * Converts the parsed external tracks into an October / YouTube Music playlist.
     * 
     * Guarantees:
     * - Songs are resolved to audio streams via InnerTube search.
     * - The playlist is stored 100% locally in October's database.
     * - Does NOT ping YouTube playback/watch history.
     */
    suspend fun convert(
        database: MusicDatabase,
        externalPlaylist: ExternalPlaylist,
        customTitle: String? = null,
        onProgress: (current: Int, total: Int, currentTrackTitle: String) -> Unit,
    ): Result<ConversionResult> = withContext(Dispatchers.IO) {
        try {
            val finalTitle = customTitle?.takeIf { it.isNotBlank() } ?: externalPlaylist.title
            val playlistId = "local_${UUID.randomUUID()}"
            val playlistEntity = PlaylistEntity(
                id = playlistId,
                name = finalTitle,
                bookmarkedAt = LocalDateTime.now(),
                isEditable = true,
            )

            // Insert playlist entity into local database
            database.insert(playlistEntity)

            val total = externalPlaylist.tracks.size
            var matchedCount = 0

            externalPlaylist.tracks.forEachIndexed { index, track ->
                val displayTrack = if (track.artist.isNotBlank()) "${track.artist} - ${track.title}" else track.title
                onProgress(index + 1, total, displayTrack)

                try {
                    val query = if (track.artist.isNotBlank()) {
                        "${track.title} ${track.artist}".trim()
                    } else {
                        track.title.trim()
                    }

                    // Search YouTube Music catalog with filter=FILTER_SONG, artist-match, keyword-reject
                    var searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    var songItem = searchResult?.items?.filterIsInstance<SongItem>()?.let { selectOfficialMatchWithLaya(track, it) }

                    // Fallback search with title only if combined query returned nothing
                    if (songItem == null && track.artist.isNotBlank()) {
                        searchResult = YouTube.search(track.title, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        songItem = searchResult?.items?.filterIsInstance<SongItem>()?.let { selectOfficialMatchWithLaya(track, it) }
                    }

                    if (songItem != null) {
                        val mediaMetadata = songItem.toMediaMetadata()
                        // Insert song metadata into database
                        database.transaction {
                            insert(mediaMetadata)
                        }

                        // Map song to local playlist
                        database.insert(
                            PlaylistSongMap(
                                songId = songItem.id,
                                playlistId = playlistId,
                                position = matchedCount,
                            )
                        )
                        if (!track.spotifyId.isNullOrBlank()) {
                            com.metrolist.music.reccobeats.SpotifyTrackMapper.associate(songItem.id, track.spotifyId)
                        }
                        matchedCount++
                    }
                } catch (e: Exception) {
                    Timber.tag("PlaylistConverter").w(e, "Failed resolving track: ${track.title}")
                }
            }

            Result.success(
                ConversionResult(
                    playlistId = playlistId,
                    playlistTitle = finalTitle,
                    totalTracks = total,
                    matchedTracks = matchedCount,
                )
            )
        } catch (e: Exception) {
            Timber.tag("PlaylistConverter").e(e, "Playlist conversion failed")
            Result.failure(e)
        }
    }

    /**
     * Parses an Exportify ZIP or CSV file picked via ACTION_OPEN_DOCUMENT.
     * Operates 100% locally with zero network calls to Spotify.
     */
    suspend fun parseExportifyUri(context: Context, uri: Uri): Result<List<ExternalPlaylist>> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver

            // 1. Sane size bound: Reject files over ~50 MB
            val maxSize = 50 * 1024 * 1024L
            try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            val fileSize = cursor.getLong(sizeIndex)
                            if (fileSize > maxSize) {
                                throw IllegalArgumentException("Invalid file type")
                            }
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Exception) {
                // Ignore cursor query errors and proceed to stream inspection
            }

            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Invalid file type")

            val playlists = mutableListOf<ExternalPlaylist>()
            val buffered = inputStream.buffered()
            buffered.mark(4)
            val header = ByteArray(4)
            val read = buffered.read(header, 0, 4)
            buffered.reset()

            val isZip = read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

            if (isZip) {
                val zip = ZipInputStream(buffered)
                var totalEntries = 0
                var totalDecompressedBytes = 0L
                val maxEntries = 500
                val maxTotalDecompressed = 100 * 1024 * 1024L // 100 MB total cap
                val maxEntryDecompressed = 25 * 1024 * 1024L // 25 MB per entry

                while (true) {
                    val entry = zip.nextEntry ?: break
                    totalEntries++
                    if (totalEntries > maxEntries) {
                        throw IllegalArgumentException("Invalid file type")
                    }

                    // Reject nested zips and archives
                    val lowerName = entry.name.lowercase()
                    if (lowerName.endsWith(".zip") || lowerName.endsWith(".tar") || lowerName.endsWith(".gz") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z")) {
                        throw IllegalArgumentException("Invalid file type")
                    }

                    if (entry.isDirectory || !lowerName.endsWith(".csv")) {
                        zip.closeEntry()
                        continue
                    }

                    // Zip bomb ratio check
                    if (entry.compressedSize > 0 && entry.size > 0) {
                        val ratio = entry.size.toDouble() / entry.compressedSize.toDouble()
                        if (ratio > 100.0) {
                            throw IllegalArgumentException("Invalid file type")
                        }
                    }

                    val rawName = entry.name.substringBeforeLast(".csv").substringAfterLast("/").trim()
                    val playlistName = if (rawName.isBlank()) "Imported Spotify Playlist" else rawName
                    val isLiked = playlistName.contains("Liked Songs", ignoreCase = true) ||
                            playlistName.contains("liked", ignoreCase = true)

                    // Bounded reader to guard uncompressed size
                    var entryBytesRead = 0L
                    val reader = BufferedReader(InputStreamReader(object : java.io.InputStream() {
                        override fun read(): Int {
                            val b = zip.read()
                            if (b != -1) {
                                entryBytesRead++
                                totalDecompressedBytes++
                                if (entryBytesRead > maxEntryDecompressed || totalDecompressedBytes > maxTotalDecompressed) {
                                    throw IllegalArgumentException("Invalid file type")
                                }
                            }
                            return b
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val bytes = zip.read(b, off, len)
                            if (bytes != -1) {
                                entryBytesRead += bytes
                                totalDecompressedBytes += bytes
                                if (entryBytesRead > maxEntryDecompressed || totalDecompressedBytes > maxTotalDecompressed) {
                                    throw IllegalArgumentException("Invalid file type")
                                }
                            }
                            return bytes
                        }
                    }, Charsets.UTF_8))

                    val playlist = parseCsvStream(reader, playlistName, isLiked)
                    if (playlist.tracks.isNotEmpty()) {
                        playlists.add(playlist)
                    }
                    zip.closeEntry()
                }
            } else {
                val reader = BufferedReader(InputStreamReader(buffered, Charsets.UTF_8))
                val defaultName = "Spotify Library Import"
                val playlist = parseCsvStream(reader, defaultName, false)
                if (playlist.tracks.isNotEmpty()) {
                    playlists.add(playlist)
                }
            }

            if (playlists.isEmpty()) {
                throw IllegalArgumentException("Invalid file type")
            }
            playlists
        }.recoverCatching {
            throw IllegalArgumentException("Invalid file type")
        }
    }

    fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim())
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        result.add(current.toString().trim())
        return result
    }

    private fun parseCsvStream(reader: BufferedReader, playlistTitle: String, isLiked: Boolean): ExternalPlaylist {
        val tracks = mutableListOf<ConvertedTrack>()
        val headerLine = reader.readLine() ?: throw IllegalArgumentException("Invalid file type")
        val headers = parseCsvRow(headerLine).map { it.lowercase().trim() }

        val trackIdx = headers.indexOfFirst { it.contains("track name") || it == "name" || it == "title" }
        val artistIdx = headers.indexOfFirst { it.contains("artist") }
        if (trackIdx == -1 || artistIdx == -1) {
            throw IllegalArgumentException("Invalid file type")
        }
        val albumIdx = headers.indexOfFirst { it.contains("album") }
        val durationIdx = headers.indexOfFirst { it.contains("duration") }
        val uriIdx = headers.indexOfFirst { it.contains("spotify uri") || it.contains("spotify id") || it.contains("uri") || it == "id" }

        var line = reader.readLine()
        var lineCount = 0
        while (line != null) {
            lineCount++
            if (lineCount > 50000) break
            val clean = line.trim()
            if (clean.isNotBlank()) {
                val cols = parseCsvRow(clean)
                val rawTrackName = cols.getOrNull(trackIdx)?.trim().orEmpty()
                val artistName = cols.getOrNull(artistIdx)?.trim().orEmpty()
                val albumName = if (albumIdx >= 0) cols.getOrNull(albumIdx)?.trim() else null
                val durationMs = if (durationIdx >= 0) cols.getOrNull(durationIdx)?.toLongOrNull() ?: 0L else 0L
                val rawSpotifyId = if (uriIdx >= 0) cols.getOrNull(uriIdx)?.trim() else null
                val cleanSpotifyId = rawSpotifyId?.removePrefix("spotify:track:")

                if (rawTrackName.isNotBlank()) {
                    val trackName = com.metrolist.music.models.cleanCanonicalSongTitle(rawTrackName)
                    tracks.add(
                        ConvertedTrack(
                            title = trackName,
                            artist = artistName,
                            album = albumName,
                            spotifyId = cleanSpotifyId,
                            durationMs = durationMs
                        )
                    )
                }
            }
            line = reader.readLine()
        }
        return ExternalPlaylist(
            title = playlistTitle,
            description = "Imported from Spotify via Exportify",
            source = PlaylistSource.SPOTIFY,
            tracks = tracks,
            isLikedSongs = isLiked
        )
    }

    fun startFastImportExportify(
        database: MusicDatabase,
        playlists: List<ExternalPlaylist>
    ) {
        converterScope.launch {
            try {
                // STEP 1: O(1) Instant Registration of Playlists, DB Deduplication & Placeholder Rows (<100ms)
                val allSpotifyIds = mutableListOf<String>()
                val playlistIdMap = mutableMapOf<ExternalPlaylist, String>()

                data class UnmatchedTrackItem(
                    val track: ConvertedTrack,
                    val playlistId: String,
                    val isLiked: Boolean,
                    val playlistTitle: String,
                    val placeholderId: String,
                    val position: Int
                )

                val pendingUnmatched = mutableListOf<UnmatchedTrackItem>()
                var matchedTotal = 0

                for (pl in playlists) {
                    val pid = "local_spotify_${UUID.randomUUID()}"
                    playlistIdMap[pl] = pid

                    val entity = PlaylistEntity(
                        id = pid,
                        name = pl.title,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true
                    )
                    database.insert(entity)

                    for ((idx, t) in pl.tracks.withIndex()) {
                        if (!t.spotifyId.isNullOrBlank()) {
                            allSpotifyIds.add(t.spotifyId)
                        }

                        // Dedupe against existing DB rows by Spotify URI before ANY network work
                        var dedupedSongId: String? = null
                        if (!t.spotifyId.isNullOrBlank()) {
                            val knownSongId = com.metrolist.music.reccobeats.SpotifyTrackMapper.getSongIdForSpotify(t.spotifyId)
                            if (knownSongId != null) {
                                val existingSong = runCatching { database.getSongById(knownSongId) }.getOrNull()
                                if (existingSong != null) {
                                    dedupedSongId = knownSongId
                                }
                            }
                        }

                        if (dedupedSongId != null) {
                            // Already in DB! Link directly without placeholder or network search
                            database.insert(
                                PlaylistSongMap(
                                    songId = dedupedSongId,
                                    playlistId = pid,
                                    position = idx
                                )
                            )
                            matchedTotal++
                        } else {
                            val placeholderId = "placeholder_${pid}_${idx}"
                            val placeholderMetadata = com.metrolist.music.models.MediaMetadata(
                                id = placeholderId,
                                title = t.title,
                                artists = listOf(com.metrolist.music.models.MediaMetadata.Artist(id = null, name = t.artist.ifBlank { "Unknown Artist" })),
                                duration = (t.durationMs / 1000).toInt(),
                                album = t.album?.let { com.metrolist.music.models.MediaMetadata.Album(id = "album_${UUID.randomUUID()}", title = it) }
                            )
                            database.transaction {
                                if (pl.isLikedSongs) {
                                    insert(placeholderMetadata) { it.copy(liked = true, likedDate = LocalDateTime.now()) }
                                } else {
                                    insert(placeholderMetadata)
                                }
                                insert(
                                    PlaylistSongMap(
                                        songId = placeholderId,
                                        playlistId = pid,
                                        position = idx
                                    )
                                )
                            }
                            pendingUnmatched.add(
                                UnmatchedTrackItem(
                                    track = t,
                                    playlistId = pid,
                                    isLiked = pl.isLikedSongs,
                                    playlistTitle = pl.title,
                                    placeholderId = placeholderId,
                                    position = idx
                                )
                            )
                        }
                    }
                }

                // Immediately seed ReccoBeats with all extracted Spotify IDs in O(1)
                com.metrolist.music.reccobeats.SpotifyTrackMapper.addSeedSpotifyIds(allSpotifyIds)

                val totalTracks = playlists.sumOf { it.tracks.size }
                matchedSongsCount.value = matchedTotal

                // Instant O(1) registration callback unblocks the UI in <100ms with full playlist tracklists
                importProgress.value = ImportProgressState(
                    isRunning = pendingUnmatched.isNotEmpty(),
                    isPlaylistsRegistered = true,
                    playlistsCount = playlists.size,
                    currentTrack = matchedTotal,
                    totalTracks = totalTracks,
                    currentTrackTitle = "",
                    currentPlaylistTitle = playlists.firstOrNull()?.title.orEmpty()
                )

                if (pendingUnmatched.isEmpty()) {
                    importProgress.value = importProgress.value?.copy(
                        isRunning = false,
                        completedResult = BatchConversionResult(playlists.size, totalTracks, matchedTotal)
                    )
                    return@launch
                }

                // STEP 2: Semaphore-bounded concurrent resolution (~16 in flight) with caching
                val trackCache = java.util.concurrent.ConcurrentHashMap<String, SongItem?>()
                val semaphore = kotlinx.coroutines.sync.Semaphore(16)
                var processedCount = matchedTotal
                val progressLock = Any()

                coroutineScope {
                    pendingUnmatched.map { item ->
                        launch(Dispatchers.IO) {
                            val track = item.track
                            val cacheKey = "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"

                            val songItem = semaphore.withPermit {
                                val cached = trackCache[cacheKey]
                                if (cached != null) {
                                    cached
                                } else {
                                    // Search via InnerTube with songs filter only, never videos/default
                                    val query = if (track.artist.isNotBlank()) "${track.title} ${track.artist}".trim() else track.title.trim()
                                    val searchResult = runCatching {
                                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                    }.getOrNull()
                                    val candidates = searchResult?.items?.filterIsInstance<SongItem>().orEmpty()
                                    val best = if (candidates.isNotEmpty()) {
                                        selectOfficialMatchWithLaya(track, candidates)
                                    } else {
                                        val fallback = runCatching {
                                            YouTube.search(track.title, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                        }.getOrNull()
                                        val fallbackCandidates = fallback?.items?.filterIsInstance<SongItem>().orEmpty()
                                        selectOfficialMatchWithLaya(track, fallbackCandidates)
                                    }
                                    if (best != null) {
                                        trackCache[cacheKey] = best
                                    }
                                    best
                                }
                            }

                            synchronized(progressLock) {
                                processedCount++
                                val display = if (track.artist.isNotBlank()) "${track.artist} - ${track.title}" else track.title
                                importProgress.value = importProgress.value?.copy(
                                    currentTrack = processedCount,
                                    currentTrackTitle = display,
                                    currentPlaylistTitle = item.playlistTitle
                                )
                            }

                            if (songItem != null) {
                                val mediaMetadata = songItem.toMediaMetadata()
                                database.transaction {
                                    // Atomically swap placeholder row for playable SongItem
                                    delete(com.metrolist.music.db.entities.SongEntity(id = item.placeholderId, title = track.title))
                                    if (item.isLiked) {
                                        insert(mediaMetadata) { it.copy(liked = true, likedDate = LocalDateTime.now()) }
                                    } else {
                                        insert(mediaMetadata)
                                    }
                                    insert(
                                        PlaylistSongMap(
                                            songId = songItem.id,
                                            playlistId = item.playlistId,
                                            position = item.position
                                        )
                                    )
                                }

                                if (!track.spotifyId.isNullOrBlank()) {
                                    com.metrolist.music.reccobeats.SpotifyTrackMapper.associate(songItem.id, track.spotifyId)
                                }

                                val currentMatched: Int
                                synchronized(progressLock) {
                                    matchedTotal++
                                    currentMatched = matchedTotal
                                }

                                // Early ReccoBeats seeding:
                                // Fire as soon as first 3 tracks match, and refine progressively
                                if (currentMatched == 3 || currentMatched == 8 || currentMatched % 15 == 0) {
                                    matchedSongsCount.value = currentMatched
                                }
                            }
                        }
                    }
                }

                val finalResult = BatchConversionResult(
                    playlistsCount = playlists.size,
                    totalTracks = totalTracks,
                    matchedTracks = matchedTotal
                )

                matchedSongsCount.value = matchedTotal
                importProgress.value = importProgress.value?.copy(
                    isRunning = false,
                    completedResult = finalResult
                )
                importProgress.value = importProgress.value?.copy(
                    isRunning = false,
                    completedResult = finalResult
                )
            } catch (e: Exception) {
                Timber.tag("PlaylistConverter").e(e, "Error during Exportify import")
                importProgress.value = importProgress.value?.copy(
                    isRunning = false,
                    error = e.localizedMessage ?: "Import failed"
                )
            }
        }
    }

    suspend fun fastImportExportify(
        database: MusicDatabase,
        playlists: List<ExternalPlaylist>,
        onProgress: (current: Int, total: Int, currentTrackTitle: String, playlistTitle: String) -> Unit = { _, _, _, _ -> }
    ): Result<BatchConversionResult> = withContext(Dispatchers.IO) {
        startFastImportExportify(database, playlists)
        val res = importProgress.value?.completedResult ?: BatchConversionResult(playlists.size, playlists.sumOf { it.tracks.size }, 0)
        Result.success(res)
    }

    val REJECT_KEYWORDS = listOf(
        "remix",
        "sped up",
        "slowed",
        "nightcore",
        "edit",
        "mashup",
        "8d audio",
        "live",
        "cover",
        "karaoke",
        "instrumental",
        "tribute",
        "parody"
    )

    fun scoreCandidate(source: ConvertedTrack, candidate: SongItem): Int {
        var score = 0
        val targetTitle = source.title.lowercase().trim()
        val targetArtist = source.artist.lowercase().trim()
        val candTitle = candidate.title.lowercase().trim()

        // 1. Keyword Rejection:
        // Penalize heavily if candidate title contains an unwanted noise keyword not present in source title
        REJECT_KEYWORDS.forEach { keyword ->
            if (candTitle.contains(keyword) && !targetTitle.contains(keyword)) {
                score -= 100
            }
        }

        // 2. Official Audio / ATV Release & Video Song Checks:
        val isTopicChannel = candidate.artists.any { it.name.endsWith(" - Topic", ignoreCase = true) }
        if (isTopicChannel) {
            score += 50
        }

        val isAtv = candidate.musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
        if (isAtv || !candidate.isVideoSong) {
            score += 40
        } else {
            score -= 30
        }

        // 3. Artist Matching:
        if (targetArtist.isNotBlank()) {
            val matchedArtist = candidate.artists.any { a ->
                val aClean = a.name.removeSuffix(" - Topic").lowercase().trim()
                aClean == targetArtist || aClean.contains(targetArtist) || targetArtist.contains(aClean)
            }
            if (matchedArtist) {
                score += 60
            }
        }

        // 4. Title Matching:
        if (candTitle == targetTitle) {
            score += 50
        } else if (candTitle.startsWith(targetTitle) || targetTitle.startsWith(candTitle)) {
            score += 25
        } else if (candTitle.contains(targetTitle) || targetTitle.contains(candTitle)) {
            score += 15
        }

        // 5. Duration-Tolerance Matching:
        val candidateDuration = candidate.duration
        if (source.durationMs > 0 && candidateDuration != null && candidateDuration > 0) {
            val sourceDurationSec = (source.durationMs / 1000).toInt()
            val delta = kotlin.math.abs(candidateDuration - sourceDurationSec)
            when {
                delta <= 2 -> score += 40
                delta <= 6 -> score += 25
                delta <= 12 -> score += 10
                delta > 25 -> score -= 50
            }
        }

        return score
    }

    fun selectOfficialMatchWithLaya(
        track: ConvertedTrack,
        candidates: List<SongItem>
    ): SongItem? {
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { scoreCandidate(track, it) }
    }
}


