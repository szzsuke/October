package com.metrolist.music.reccobeats

import android.content.Context
import timber.log.Timber
import java.io.File
import org.json.JSONObject

/**
 * Persists mapping between YouTube Music song IDs and Spotify track IDs
 * so ReccoBeats recommendations can seamlessly trigger during playback
 * and populate Home feed sections.
 */
object SpotifyTrackMapper {

    private const val FILE_NAME = "spotify_track_map.json"
    private val memoryMap = mutableMapOf<String, String>()
    private var mapFile: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (mapFile == null) {
                mapFile = File(context.filesDir, FILE_NAME)
                loadFromDisk()
            }
        }
    }

    fun associate(songId: String, spotifyTrackId: String) {
        if (songId.isBlank() || spotifyTrackId.isBlank()) return
        synchronized(lock) {
            memoryMap[songId] = spotifyTrackId
            saveToDisk()
        }
    }

    fun getSpotifyTrackId(songId: String): String? {
        synchronized(lock) {
            return memoryMap[songId]
        }
    }

    /**
     * Reverse lookup to dedupe by Spotify URI/ID against existing DB tracks before any network calls.
     */
    fun getSongIdForSpotify(spotifyTrackId: String): String? {
        val clean = spotifyTrackId.trim().removePrefix("spotify:track:")
        synchronized(lock) {
            return memoryMap.entries.firstOrNull { (k, v) -> !k.startsWith("seed_") && v == clean }?.key
        }
    }

    fun getAllSpotifyTrackIds(): List<String> {
        synchronized(lock) {
            return memoryMap.values.distinct()
        }
    }

    /**
     * Instantly adds a collection of Spotify track IDs as seeds for ReccoBeats.
     * Operates in O(1) perceived time to populate personalized home feeds immediately.
     */
    fun addSeedSpotifyIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            var added = 0
            for (id in ids) {
                val clean = id.trim().removePrefix("spotify:track:")
                if (clean.isNotBlank()) {
                    memoryMap["seed_$clean"] = clean
                    added++
                }
            }
            if (added > 0) {
                saveToDisk()
            }
        }
    }

    private fun loadFromDisk() {
        runCatching {
            val file = mapFile ?: return
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    memoryMap[key] = json.getString(key)
                }
            }
        }.onFailure {
            Timber.tag("SpotifyTrackMapper").d("Failed to load map: ${it.message}")
        }
    }

    private fun saveToDisk() {
        runCatching {
            val file = mapFile ?: return
            val json = JSONObject()
            memoryMap.forEach { (k, v) -> json.put(k, v) }
            file.writeText(json.toString())
        }.onFailure {
            Timber.tag("SpotifyTrackMapper").d("Failed to save map: ${it.message}")
        }
    }
}
