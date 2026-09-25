package com.metrolist.music.reccobeats

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages the "Play next" / radio queue recommendation pipeline using ReccoBeats.
 * When a song starts playing, fetches ReccoBeats candidates, resolves them to playable
 * YouTube items, and enqueues the top 2-3 tracks.
 * Silently falls back to the existing similarity/automix logic if ReccoBeats has no coverage.
 */
object ReccoBeatsQueueManager {

    private const val TAG = "ReccoBeatsQueue"
    private var lastEnqueuedTrackId: String? = null

    suspend fun onTrackStarted(
        service: MusicService,
        mediaId: String,
        spotifyTrackId: String,
        fallback: () -> Unit
    ) = withContext(Dispatchers.IO) {
        if (lastEnqueuedTrackId == mediaId) return@withContext
        lastEnqueuedTrackId = mediaId

        val recommendations = runCatching {
            ReccoBeatsRepository.fetchRecommendations(listOf(spotifyTrackId), limit = 8)
        }.getOrNull()

        if (recommendations.isNullOrEmpty()) {
            Timber.tag(TAG).d("No ReccoBeats recommendations for $spotifyTrackId, falling back to default similarity")
            fallback()
            return@withContext
        }

        val existingMediaIds = mutableSetOf<String>()
        withContext(Dispatchers.Main) {
            for (i in 0 until service.player.mediaItemCount) {
                existingMediaIds.add(service.player.getMediaItemAt(i).mediaId)
            }
        }

        val resolvedItems = mutableListOf<androidx.media3.common.MediaItem>()
        for (candidate in recommendations) {
            if (resolvedItems.size >= 3) break
            val query = "${candidate.artist} ${candidate.title}"
            val searchResult = runCatching {
                YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).getOrNull()
            }.getOrNull()

            val matchedSong = searchResult?.items?.firstOrNull() as? SongItem
            if (matchedSong != null && !existingMediaIds.contains(matchedSong.id)) {
                existingMediaIds.add(matchedSong.id)
                resolvedItems.add(matchedSong.toMediaItem())

                // Associate newly resolved candidate with its Spotify ID
                if (!candidate.spotifyId.isNullOrBlank()) {
                    SpotifyTrackMapper.associate(matchedSong.id, candidate.spotifyId)
                }
            }
        }

        if (resolvedItems.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                val insertIndex = (service.player.currentMediaItemIndex + 1).coerceAtMost(service.player.mediaItemCount)
                service.player.addMediaItems(insertIndex, resolvedItems)
                Timber.tag(TAG).i("Queued ${resolvedItems.size} ReccoBeats recommendations after current track")
            }
        } else {
            fallback()
        }
    }
}
