/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album as DbAlbum
import com.metrolist.music.db.entities.Artist as DbArtist
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        database: MusicDatabase,
    ) : ViewModel() {
        val query = MutableStateFlow("")
        private val _viewState = MutableStateFlow(SearchSuggestionViewState())
        val viewState = _viewState.asStateFlow()

        init {
            viewModelScope.launch {
                query
                    .flatMapLatest { query ->
                        if (query.isEmpty()) {
                            database.searchHistory().map { history ->
                                SearchSuggestionViewState(
                                    history = history,
                                )
                            }
                        } else {
                            // Check if query is a YouTube URL
                            val parsedUrl = YouTubeUrlParser.parse(query)
                            if (parsedUrl != null) {
                                // Fetch content from YouTube URL
                                val parsedItem = fetchParsedUrlItem(parsedUrl)
                                database
                                    .searchHistory(query)
                                    .map { it.take(3) }
                                    .map { history ->
                                        SearchSuggestionViewState(
                                            history = history,
                                            suggestions = emptyList(),
                                            topResult = parsedItem,
                                            predictedSongs = emptyList(),
                                            items = parsedItem?.let { listOf(it) } ?: emptyList(),
                                            parsedUrlItem = parsedItem,
                                            isUrlQuery = true,
                                        )
                                    }
                            } else {
                                flow {
                                    val cleanQuery = query.trim()
                                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)

                                    // 1. Instant Local Library Lookup (0ms latency)
                                    val localHistory = runCatching {
                                        database.searchHistory(cleanQuery).firstOrNull()?.take(3).orEmpty()
                                    }.getOrDefault(emptyList())

                                    val localSongs = runCatching {
                                        database.searchSongsExtended(cleanQuery, 4).firstOrNull().orEmpty()
                                    }.getOrDefault(emptyList())

                                    val localArtists = runCatching {
                                        database.searchArtists(cleanQuery, 2).firstOrNull().orEmpty()
                                    }.getOrDefault(emptyList())

                                    val localAlbums = runCatching {
                                        database.searchAlbums(cleanQuery, 2).firstOrNull().orEmpty()
                                    }.getOrDefault(emptyList())

                                    // Convert local items to YTItems
                                    val localSongItems = localSongs.map { it.toSongItem() }
                                        .filterExplicit(hideExplicit)
                                        .filterVideoSongs(hideVideoSongs)
                                    val localArtistItems = localArtists.map { it.toArtistItem() }
                                    val localAlbumItems = localAlbums.map { it.toAlbumItem() }
                                        .filterExplicit(hideExplicit)

                                    val localCandidates = (localSongItems + localArtistItems + localAlbumItems)

                                    // Score local items
                                    val localScored = scoreAndRankCandidates(
                                        cleanQuery,
                                        candidates = localCandidates,
                                        localIds = localCandidates.map { it.id }.toSet(),
                                    )
                                    val localTopResult = localScored.firstOrNull { it.score >= 50.0 }?.item
                                    val localPredictedSongs = localScored.map { it.item }
                                        .filterIsInstance<SongItem>()
                                        .filter { it.id != localTopResult?.id }
                                        .take(3)

                                    // Emit Tier 1 (Instant Local State with loading indicator)
                                    emit(
                                        SearchSuggestionViewState(
                                            history = localHistory,
                                            suggestions = emptyList(),
                                            topResult = localTopResult,
                                            predictedSongs = localPredictedSongs,
                                            items = listOfNotNull(localTopResult) + localPredictedSongs,
                                            isLoadingPredictions = true,
                                        )
                                    )

                                    // 2. Concurrently fetch online suggestions and catalog predictive results
                                    val (onlineSuggestions, catalogItems) = coroutineScope {
                                        val suggestionsDeferred = async {
                                            YouTube.searchSuggestions(cleanQuery).getOrNull()
                                        }
                                        val catalogDeferred = async {
                                            if (cleanQuery.length >= 2) {
                                                YouTube.searchSummary(cleanQuery).getOrNull()
                                            } else null
                                        }

                                        val suggResult = suggestionsDeferred.await()
                                        val catResult = catalogDeferred.await()
                                        Pair(suggResult, catResult)
                                    }

                                    // Process catalog results (pure music filtering: no podcasts, episodes, shorts, video songs)
                                    val catalogCandidates = mutableListOf<YTItem>()

                                    // Recommended items from suggestions (if any)
                                    onlineSuggestions?.recommendedItems?.let { catalogCandidates.addAll(it) }

                                    // Items from catalog searchSummary
                                    catalogItems?.summaries?.filterNot { s ->
                                        s.title.contains("podcast", ignoreCase = true) ||
                                        s.title.contains("episode", ignoreCase = true) ||
                                        s.title.contains("video", ignoreCase = true) ||
                                        s.title.contains("profile", ignoreCase = true)
                                    }?.forEach { summary ->
                                        catalogCandidates.addAll(summary.items)
                                    }

                                    // If catalog searchSummary had no items, or < 2 items, fallback to FILTER_SONG search
                                    if (catalogCandidates.isEmpty() && cleanQuery.length >= 2) {
                                        val songSearch = YouTube.search(cleanQuery, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                        songSearch?.items?.let { catalogCandidates.addAll(it) }
                                    }

                                    // Filter pure music
                                    val filteredCatalogCandidates = catalogCandidates
                                        .filterNot { it is PodcastItem || it is EpisodeItem }
                                        .filterExplicit(hideExplicit)
                                        .filterVideoSongs(hideVideoSongs)

                                    // 3. Combine Local + Online Candidates & Run Predictive Scoring Engine
                                    val allScored = scoreAndRankCandidates(
                                        cleanQuery,
                                        candidates = localCandidates + filteredCatalogCandidates,
                                        localIds = localCandidates.map { it.id }.toSet(),
                                    )

                                    // Top Result is the highest scoring item with score >= 50
                                    val bestTopResult = allScored.firstOrNull { it.score >= 50.0 }?.item
                                    val predictedSongs = allScored.map { it.item }
                                        .filterIsInstance<SongItem>()
                                        .filter { it.id != bestTopResult?.id }
                                        .take(4)
                                    val otherPredictedItems = allScored.map { it.item }
                                        .filter { it.id != bestTopResult?.id && it !in predictedSongs }
                                        .take(2)

                                    val finalItems = listOfNotNull(bestTopResult) + predictedSongs + otherPredictedItems

                                    // Filter query suggestions (remove ones already present in history)
                                    val finalSuggestions = onlineSuggestions?.queries?.filter { sQuery ->
                                        localHistory.none { it.query.equals(sQuery, ignoreCase = true) }
                                    }.orEmpty()

                                    // Emit Tier 2 (Fully Resolved Predictive State)
                                    emit(
                                        SearchSuggestionViewState(
                                            history = localHistory,
                                            suggestions = finalSuggestions,
                                            topResult = bestTopResult,
                                            predictedSongs = predictedSongs,
                                            items = finalItems,
                                            isLoadingPredictions = false,
                                        )
                                    )
                                }
                            }
                        }
                    }.collect {
                        _viewState.value = it
                    }
            }
        }

        private fun Song.toSongItem(): SongItem =
            SongItem(
                id = song.id,
                title = song.title,
                artists = orderedArtists.map { Artist(name = it.name, id = it.id) },
                album = album?.let { Album(name = it.title, id = it.id) }
                    ?: song.albumId?.let { Album(name = song.albumName.orEmpty(), id = it) },
                duration = song.duration,
                thumbnail = song.thumbnailUrl.orEmpty(),
                explicit = song.explicit,
                endpoint = WatchEndpoint(videoId = song.id),
            )

        private fun DbArtist.toArtistItem(): ArtistItem =
            ArtistItem(
                id = artist.id,
                title = artist.name,
                thumbnail = artist.thumbnailUrl,
                channelId = artist.channelId,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )

        private fun DbAlbum.toAlbumItem(): AlbumItem =
            AlbumItem(
                browseId = album.id,
                playlistId = album.id,
                id = album.id,
                title = album.title,
                artists = artists.map { Artist(name = it.name, id = it.id) },
                year = album.year,
                thumbnail = album.thumbnailUrl.orEmpty(),
                explicit = album.explicit,
            )

        private data class ScoredItem(
            val item: YTItem,
            val score: Double,
        )

        private fun scoreAndRankCandidates(
            query: String,
            candidates: List<YTItem>,
            localIds: Set<String> = emptySet(),
        ): List<ScoredItem> {
            val cleanQuery = query.trim().lowercase()
            if (cleanQuery.isEmpty()) return emptyList()

            return candidates
                .distinctBy { it.id }
                .map { item ->
                    val isLocal = item.id in localIds
                    val score = calculatePredictiveScore(cleanQuery, item, isLocal)
                    ScoredItem(item, score)
                }
                .sortedByDescending { it.score }
        }

        private fun calculatePredictiveScore(
            cleanQuery: String,
            item: YTItem,
            isLocal: Boolean,
        ): Double {
            val title = item.title.trim().lowercase()
            var score = 0.0

            // Exact title match: 100
            if (title == cleanQuery) {
                score += 100.0
            } else if (title.startsWith(cleanQuery)) {
                // Starts with query (prefix match): 80
                score += 80.0
            } else {
                // Check if any word starts with query
                val words = title.split(" ", "-", "(", ")", "[", "]", ",", ".", "/")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (words.any { it == cleanQuery }) {
                    score += 75.0
                } else if (words.any { it.startsWith(cleanQuery) }) {
                    score += 65.0
                } else if (title.contains(cleanQuery)) {
                    score += 40.0
                }
            }

            // Artist name matching
            when (item) {
                is SongItem -> {
                    var bestArtistScore = 0.0
                    item.artists.forEach { artist ->
                        val aName = artist.name.trim().lowercase()
                        when {
                            aName == cleanQuery -> bestArtistScore = maxOf(bestArtistScore, 85.0)
                            aName.startsWith(cleanQuery) -> bestArtistScore = maxOf(bestArtistScore, 70.0)
                            aName.split(" ").any { it.startsWith(cleanQuery) } -> bestArtistScore = maxOf(bestArtistScore, 55.0)
                            aName.contains(cleanQuery) -> bestArtistScore = maxOf(bestArtistScore, 35.0)
                        }
                    }
                    score = maxOf(score, bestArtistScore)

                    // If title and query match well, add bonus for known artist presence
                    if (item.artists.isNotEmpty() && score > 50.0) {
                        score += 5.0
                    }
                }
                is AlbumItem -> {
                    var bestArtistScore = 0.0
                    item.artists?.forEach { artist ->
                        val aName = artist.name.trim().lowercase()
                        when {
                            aName == cleanQuery -> bestArtistScore = maxOf(bestArtistScore, 80.0)
                            aName.startsWith(cleanQuery) -> bestArtistScore = maxOf(bestArtistScore, 65.0)
                            aName.split(" ").any { it.startsWith(cleanQuery) } -> bestArtistScore = maxOf(bestArtistScore, 50.0)
                        }
                    }
                    score = maxOf(score, bestArtistScore)
                }
                is ArtistItem -> {
                    // Direct artist match priority
                    if (title == cleanQuery) {
                        score += 20.0
                    } else if (title.startsWith(cleanQuery)) {
                        score += 10.0
                    }
                }
                else -> {}
            }

            // Local library boost: +15 bonus for user-saved library music
            if (isLocal) {
                score += 15.0
            }

            return score
        }

        private suspend fun fetchParsedUrlItem(parsedUrl: YouTubeUrlParser.ParsedUrl): YTItem? =
            when (parsedUrl) {
                is YouTubeUrlParser.ParsedUrl.Video -> {
                    // Use next() to get the song details from a video ID
                    YouTube
                        .next(WatchEndpoint(videoId = parsedUrl.id))
                        .getOrNull()
                        ?.items
                        ?.firstOrNull()
                }

                is YouTubeUrlParser.ParsedUrl.Playlist -> {
                    // Fetch playlist details
                    YouTube
                        .playlist(parsedUrl.id)
                        .getOrNull()
                        ?.playlist
                }

                is YouTubeUrlParser.ParsedUrl.Album -> {
                    // For albums, we need to get the browseId from the playlist
                    // First, try to get the album page
                    val albumResult = YouTube.album("MPREb_${parsedUrl.id}")
                    if (albumResult.isSuccess) {
                        albumResult.getOrNull()?.album
                    } else {
                        // If that fails, treat it as a playlist
                        YouTube
                            .playlist(parsedUrl.id)
                            .getOrNull()
                            ?.playlist
                    }
                }

                is YouTubeUrlParser.ParsedUrl.Artist -> {
                    // Fetch artist details
                    if (parsedUrl.id.startsWith("MPRE")) {
                        // It's a browse ID
                        YouTube
                            .artist(parsedUrl.id)
                            .getOrNull()
                            ?.artist
                    } else {
                        // It's a channel ID, we need to find the browse ID
                        // For now, try using the channel ID as browse ID
                        YouTube
                            .artist(parsedUrl.id)
                            .getOrNull()
                            ?.artist
                    }
                }
            }
    }

data class SearchSuggestionViewState(
    val history: List<SearchHistory> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val topResult: YTItem? = null,
    val predictedSongs: List<SongItem> = emptyList(),
    val items: List<YTItem> = emptyList(),
    val parsedUrlItem: YTItem? = null,
    val isUrlQuery: Boolean = false,
    val isLoadingPredictions: Boolean = false,
)
