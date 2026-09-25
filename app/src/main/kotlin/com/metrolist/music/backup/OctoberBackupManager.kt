package com.metrolist.music.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.AccountTypeKey
import com.metrolist.music.constants.CustomAccountNameKey
import com.metrolist.music.constants.CustomAccountUsernameKey
import com.metrolist.music.constants.CustomProfilePicPathKey
import com.metrolist.music.constants.EntryPathKey
import com.metrolist.music.constants.GoogleIdentityAttachedKey
import com.metrolist.music.constants.IsOnboardedKey
import com.metrolist.music.constants.LastBackupHashKey
import com.metrolist.music.constants.LastBackupTimestampKey
import com.metrolist.music.constants.PrimaryConnectionKey
import com.metrolist.music.constants.UserProfilePicUriKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.reccobeats.SpotifyTrackMapper
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages lightweight (<2 MB) cloud and local backups for October:
 * - profile.json: display name, username, pfp url, account type
 * - playlists.json: October playlists + track snapshot (IDs, titles, artists, Spotify IDs)
 * - avatar.jpg: optional local pfp thumbnail resized <= 200x200
 *
 * Backs up to Google Drive appDataFolder if Google-connected,
 * or Documents/October/backup.zip locally (survives app uninstall).
 */
object OctoberBackupManager {

    private const val PROFILE_ENTRY = "profile.json"
    private const val PLAYLISTS_ENTRY = "playlists.json"
    private const val AVATAR_ENTRY = "avatar.jpg"
    private const val LOCAL_FOLDER = "October"
    private const val BACKUP_FILENAME = "backup.zip"

    data class ProfileSnapshot(
        val displayName: String = "",
        val username: String = "",
        val photoUrl: String = "",
        val accountType: String = "GUEST",
        val entryPath: String = "SPOTIFY",
        val googleIdentityAttached: Boolean = false,
        val primaryConnection: String = "GOOGLE",
    )

    /**
     * Serializes current user profile and playlists into a compact ZIP byte array (<2 MB).
     */
    suspend fun createBackupZip(context: Context, database: MusicDatabase): ByteArray = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val displayName = prefs[CustomAccountNameKey]?.ifBlank { null }
            ?: prefs[AccountNameKey].orEmpty()
        val username = prefs[CustomAccountUsernameKey].orEmpty()
        val photoUrl = prefs[UserProfilePicUriKey].orEmpty()
        val accountType = prefs[AccountTypeKey] ?: "GUEST"
        val entryPath = prefs[EntryPathKey] ?: "SPOTIFY"
        val googleAttached = prefs[GoogleIdentityAttachedKey] ?: false
        val primaryConnection = prefs[PrimaryConnectionKey] ?: (if (accountType == "YTMUSIC") "YTMUSIC" else "GOOGLE")

        val profileJson = JSONObject().apply {
            put("displayName", displayName)
            put("username", username)
            put("photoUrl", photoUrl)
            put("accountType", accountType)
            put("entryPath", entryPath)
            put("googleIdentityAttached", googleAttached)
            put("primaryConnection", primaryConnection)
        }.toString(2)

        val playlists = database.playlistEntitiesByNameAsc()
        val playlistsArray = JSONArray()

        for (playlist in playlists) {
            val songs = database.playlistSongs(playlist.id).first()
            val tracksArray = JSONArray()
            for (playlistSong in songs) {
                val songItem = playlistSong.song
                val songId = playlistSong.map.songId
                val spotifyId = SpotifyTrackMapper.getSpotifyTrackId(songId)
                val trackObj = JSONObject().apply {
                    put("id", songId)
                    put("title", songItem.song.title)
                    put("artist", songItem.artists.joinToString(", ") { it.name })
                    put("album", songItem.song.albumName)
                    put("duration", songItem.song.duration)
                    if (!spotifyId.isNullOrBlank()) {
                        put("spotifyId", spotifyId)
                    }
                }
                tracksArray.put(trackObj)
            }

            val isLiked = playlist.name.contains("Liked", ignoreCase = true)
            val playlistObj = JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("isLiked", isLiked)
                put("tracks", tracksArray)
            }
            playlistsArray.put(playlistObj)
        }

        val playlistsJson = playlistsArray.toString(2)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // 1. profile.json
            zos.putNextEntry(ZipEntry(PROFILE_ENTRY))
            zos.write(profileJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. playlists.json
            zos.putNextEntry(ZipEntry(PLAYLISTS_ENTRY))
            zos.write(playlistsJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. avatar.jpg (if custom avatar exists, scale <= 200x200)
            val customAvatar = File(context.filesDir, "custom_avatar.jpg")
            if (customAvatar.exists()) {
                runCatching {
                    val rawBmp = BitmapFactory.decodeFile(customAvatar.absolutePath)
                    if (rawBmp != null) {
                        val maxDim = 200
                        val scaled = if (rawBmp.width > maxDim || rawBmp.height > maxDim) {
                            val ratio = rawBmp.width.toFloat() / rawBmp.height.toFloat()
                            val w = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt()
                            val h = if (ratio >= 1f) (maxDim / ratio).toInt() else maxDim
                            Bitmap.createScaledBitmap(rawBmp, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
                        } else rawBmp

                        val thumbBaos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, thumbBaos)
                        zos.putNextEntry(ZipEntry(AVATAR_ENTRY))
                        zos.write(thumbBaos.toByteArray())
                        zos.closeEntry()
                    }
                }
            }
        }

        baos.toByteArray()
    }

    /**
     * Saves backup to Documents/October/backup.zip (SAF / MediaStore friendly)
     * and internal files directory.
     */
    suspend fun saveLocalBackup(context: Context, zipBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Save to internal app files
            val internalFile = File(context.filesDir, BACKUP_FILENAME)
            FileOutputStream(internalFile).use { it.write(zipBytes) }

            // Save to Documents/October/backup.zip so it survives app uninstalls
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val octoberDir = File(docsDir, LOCAL_FOLDER)
            if (!octoberDir.exists()) octoberDir.mkdirs()
            val externalFile = File(octoberDir, BACKUP_FILENAME)
            FileOutputStream(externalFile).use { it.write(zipBytes) }
            true
        }.getOrDefault(false)
    }

    /**
     * Reads local backup.zip from Documents/October/backup.zip or internal fallback.
     */
    suspend fun readLocalBackup(context: Context): ByteArray? = withContext(Dispatchers.IO) {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val externalFile = File(File(docsDir, LOCAL_FOLDER), BACKUP_FILENAME)
        if (externalFile.exists() && externalFile.length() > 0) {
            return@withContext runCatching { FileInputStream(externalFile).use { it.readBytes() } }.getOrNull()
        }
        val internalFile = File(context.filesDir, BACKUP_FILENAME)
        if (internalFile.exists() && internalFile.length() > 0) {
            return@withContext runCatching { FileInputStream(internalFile).use { it.readBytes() } }.getOrNull()
        }
        null
    }

    /**
     * Computes SHA-256 hash of a string.
     */
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Restores library, playlists, and user profile from backup ZIP bytes.
     */
    suspend fun restoreFromBackupZip(
        context: Context,
        database: MusicDatabase,
        zipBytes: ByteArray
    ): Result<ProfileSnapshot> = withContext(Dispatchers.IO) {
        try {
            var profileJsonStr: String? = null
            var playlistsJsonStr: String? = null
            var avatarBytes: ByteArray? = null

            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        PROFILE_ENTRY -> profileJsonStr = zis.readBytes().toString(Charsets.UTF_8)
                        PLAYLISTS_ENTRY -> playlistsJsonStr = zis.readBytes().toString(Charsets.UTF_8)
                        AVATAR_ENTRY -> avatarBytes = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (playlistsJsonStr == null) {
                return@withContext Result.failure(IllegalStateException("No backup found for this account"))
            }

            var snapshot = ProfileSnapshot()
            if (profileJsonStr != null) {
                val p = JSONObject(profileJsonStr)
                val primaryConn = p.optString("primaryConnection", if (p.optString("accountType") == "YTMUSIC") "YTMUSIC" else "GOOGLE")
                snapshot = ProfileSnapshot(
                    displayName = p.optString("displayName", ""),
                    username = p.optString("username", ""),
                    photoUrl = p.optString("photoUrl", ""),
                    accountType = p.optString("accountType", "GUEST"),
                    entryPath = p.optString("entryPath", "SPOTIFY"),
                    googleIdentityAttached = p.optBoolean("googleIdentityAttached", false),
                    primaryConnection = primaryConn,
                )

                context.safeDataStoreEdit { settings ->
                    if (snapshot.displayName.isNotBlank()) {
                        settings[AccountNameKey] = snapshot.displayName
                        settings[CustomAccountNameKey] = snapshot.displayName
                    }
                    if (snapshot.username.isNotBlank()) {
                        settings[CustomAccountUsernameKey] = snapshot.username
                    }
                    if (snapshot.photoUrl.isNotBlank()) {
                        settings[UserProfilePicUriKey] = snapshot.photoUrl
                    }
                    settings[AccountTypeKey] = snapshot.accountType
                    settings[EntryPathKey] = snapshot.entryPath
                    settings[GoogleIdentityAttachedKey] = snapshot.googleIdentityAttached
                    settings[PrimaryConnectionKey] = snapshot.primaryConnection
                    settings[IsOnboardedKey] = true
                }
            }

            if (avatarBytes != null && avatarBytes!!.isNotEmpty()) {
                val avatarFile = File(context.filesDir, "custom_avatar.jpg")
                FileOutputStream(avatarFile).use { it.write(avatarBytes!!) }
            }

            // Parse and restore playlists and songs
            val playlistsArray = JSONArray(playlistsJsonStr)
            val allSpotifyIds = mutableListOf<String>()

            for (i in 0 until playlistsArray.length()) {
                val plObj = playlistsArray.getJSONObject(i)
                val pid = plObj.getString("id")
                val name = plObj.getString("name")
                val isLiked = plObj.optBoolean("isLiked", false)

                val playlistEntity = PlaylistEntity(
                    id = pid,
                    name = name,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true
                )
                database.insert(playlistEntity)

                val tracksArray = plObj.getJSONArray("tracks")
                for (j in 0 until tracksArray.length()) {
                    val t = tracksArray.getJSONObject(j)
                    val songId = t.getString("id")
                    val title = t.getString("title")
                    val artist = t.optString("artist", "Unknown Artist")
                    val album = t.optString("album").takeIf { !it.isNullOrBlank() }
                    val duration = t.optInt("duration", 0)
                    val spotifyId = t.optString("spotifyId").takeIf { !it.isNullOrBlank() }

                    val metadata = MediaMetadata(
                        id = songId,
                        title = title,
                        artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
                        duration = duration,
                        album = album?.let { MediaMetadata.Album(id = "album_${songId}", title = it) }
                    )

                    database.transaction {
                        if (isLiked) {
                            insert(metadata) { it.copy(liked = true, likedDate = LocalDateTime.now()) }
                        } else {
                            insert(metadata)
                        }
                        insert(
                            PlaylistSongMap(
                                songId = songId,
                                playlistId = pid,
                                position = j
                            )
                        )
                    }

                    if (!spotifyId.isNullOrBlank()) {
                        SpotifyTrackMapper.associate(songId, spotifyId)
                        allSpotifyIds.add(spotifyId)
                    }
                }
            }

            if (allSpotifyIds.isNotEmpty()) {
                SpotifyTrackMapper.addSeedSpotifyIds(allSpotifyIds)
            }

            Result.success(snapshot)
        } catch (e: Exception) {
            Timber.tag("OctoberBackup").e(e, "Restore failed")
            Result.failure(e)
        }
    }

    /**
     * TRIGGER 1 — ONE-TIME, IMMEDIATE, runs exactly once per account link:
     * The instant Google authorization succeeds and drive.appdata scope is granted,
     * this runs synchronously/awaited right there in that callback:
     * 1. zipFile = buildBackupZip(currentLocalProfile, currentLocalPlaylists)
     * 2. uploadToAppDataFolder(zipFile) (blocking or awaited — must complete before proceeding)
     * 3. saveLastBackupTimestamp(now()) & saveLastSavedHash(hash)
     * 4. showUIConfirmation("Backed up ✓")
     *
     * This fires even if the user has zero playlists yet.
     * Completely independent of WorkManager's periodic scheduling.
     */
    suspend fun performImmediateSync(
        context: Context,
        database: MusicDatabase,
        email: String,
        activity: android.app.Activity? = null,
        authToken: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zipBytes = createBackupZip(context, database)
            val hash = sha256(zipBytes.toString(Charsets.ISO_8859_1))
            saveLocalBackup(context, zipBytes)

            val token = authToken ?: if (activity != null) {
                GoogleDriveClient.getDriveAuthTokenInteractive(activity, email)
            } else {
                GoogleDriveClient.getDriveTokenBackground(context, email)
            }

            if (token != null) {
                val uploadResult = GoogleDriveClient.uploadBackupToAppDataFolder(context, token, email, zipBytes)
                uploadResult.fold(
                    onSuccess = {
                        val now = System.currentTimeMillis()
                        context.safeDataStoreEdit { settings ->
                            settings[LastBackupHashKey] = hash
                            settings[LastBackupTimestampKey] = now
                        }
                        Timber.tag("OctoberBackup").i("Trigger 1: Immediate Drive appDataFolder sync successful (hash=%s, timestamp=%d)", hash, now)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Backed up ✓", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = { err ->
                        Timber.tag("OctoberBackup").e(err, "Trigger 1: Immediate Drive upload failed")
                    }
                )
            }
        }
    }

    /**
     * Checks if current playlist state changed; if so, re-serializes, writes backup, and uploads to Drive.
     */
    suspend fun backupIfChanged(context: Context, database: MusicDatabase) = withContext(Dispatchers.IO) {
        runCatching {
            val zipBytes = createBackupZip(context, database)
            val hash = sha256(zipBytes.toString(Charsets.ISO_8859_1))

            val currentHash = context.dataStore.data.first()[LastBackupHashKey]
            if (hash != currentHash) {
                saveLocalBackup(context, zipBytes)
                context.safeDataStoreEdit { settings ->
                    settings[LastBackupHashKey] = hash
                }
                Timber.tag("OctoberBackup").i("Backup updated (hash changed to $hash)")

                // Also trigger immediate background upload to Drive
                OctoberBackupWorker.enqueueImmediateBackup(context)
            }
        }
    }
}
