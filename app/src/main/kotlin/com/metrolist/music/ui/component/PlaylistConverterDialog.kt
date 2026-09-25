package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.converter.PlaylistConverter
import kotlinx.coroutines.launch

/**
 * October Playlist Converter Dialog
 * 
 * Allows users to import and convert playlists and playlist history from Spotify,
 * Apple Music, or exported CSV/Text files into October / YouTube Music playlists.
 * 
 * Guarantees:
 * - Pure local October playlist creation without syncing to YouTube history.
 * - Does not use Apple's account profile pic or avatar.
 * - Resolves tracks directly into high quality audio streams.
 */
@Composable
fun PlaylistConverterDialog(
    onDismiss: () -> Unit,
    onPlaylistConverted: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    var inputUrl by rememberSaveable { mutableStateOf("") }
    var isParsing by remember { mutableStateOf(false) }
    var parsedPlaylist by remember { mutableStateOf<PlaylistConverter.ExternalPlaylist?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    var playlistTitle by rememberSaveable { mutableStateOf("") }
    var isConverting by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var currentTrackTitle by remember { mutableStateOf("") }
    var conversionResult by remember { mutableStateOf<PlaylistConverter.ConversionResult?>(null) }
    var conversionError by remember { mutableStateOf<String?>(null) }

    DefaultDialog(
        onDismiss = {
            if (!isConverting) onDismiss()
        },
        icon = {
            Icon(
                painter = painterResource(
                    if (conversionResult != null) R.drawable.library_add_check
                    else R.drawable.sync
                ),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = when {
                    conversionResult != null -> "Playlist Converted"
                    parsedPlaylist != null -> "Confirm Import"
                    else -> "Playlist Converter"
                },
                fontWeight = FontWeight.Bold,
            )
        },
        buttons = {
            when {
                conversionResult != null -> {
                    Button(
                        onClick = {
                            val id = conversionResult!!.playlistId
                            onDismiss()
                            onPlaylistConverted?.invoke(id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("Open Playlist")
                    }
                }
                isConverting -> {
                    // Disable dismiss while converting
                    Text(
                        text = "Converting tracks... please keep app open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                parsedPlaylist != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                parsedPlaylist = null
                                parseError = null
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Back")
                        }
                        Button(
                            onClick = {
                                val currentParsed = parsedPlaylist ?: return@Button
                                isConverting = true
                                conversionError = null
                                coroutineScope.launch {
                                    val result = PlaylistConverter.convert(
                                        database = database,
                                        externalPlaylist = currentParsed,
                                        customTitle = playlistTitle,
                                        onProgress = { cur, tot, trk ->
                                            progressCurrent = cur
                                            progressTotal = tot
                                            currentTrackTitle = trk
                                        },
                                    )
                                    isConverting = false
                                    result.onSuccess { res ->
                                        conversionResult = res
                                    }.onFailure { err ->
                                        conversionError = err.localizedMessage ?: "Conversion failed"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text("Import")
                        }
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                if (inputUrl.isBlank()) {
                                    Toast.makeText(context, "Please paste a link or track list", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isParsing = true
                                parseError = null
                                coroutineScope.launch {
                                    val result = PlaylistConverter.parse(inputUrl)
                                    isParsing = false
                                    result.onSuccess { external ->
                                        parsedPlaylist = external
                                        playlistTitle = external.title
                                    }.onFailure { err ->
                                        parseError = err.localizedMessage ?: "Failed to read playlist"
                                    }
                                }
                            },
                            enabled = !isParsing && inputUrl.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            if (isParsing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text("Preview")
                            }
                        }
                    }
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            when {
                conversionResult != null -> {
                    val res = conversionResult!!
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    ) {
                        Text(
                            text = "'${res.playlistTitle}'",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Imported ${res.matchedTracks} of ${res.totalTracks} tracks into October.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Playable 100% offline & local without YouTube history sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                isConverting -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (progressTotal > 0) {
                            LinearProgressIndicator(
                                progress = { progressCurrent.toFloat() / progressTotal },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Matching $progressCurrent of $progressTotal tracks...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Preparing tracks...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        if (currentTrackTitle.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = currentTrackTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                parsedPlaylist != null -> {
                    val playlist = parsedPlaylist!!
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text(
                                    text = when (playlist.source) {
                                        PlaylistConverter.PlaylistSource.SPOTIFY -> "Spotify"
                                        PlaylistConverter.PlaylistSource.APPLE_MUSIC -> "Apple Music"
                                        PlaylistConverter.PlaylistSource.TEXT_CSV -> "History / Text"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                text = "${playlist.tracks.size} tracks found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = playlistTitle,
                            onValueChange = { playlistTitle = it },
                            label = { Text("Playlist Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "Track Preview:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Spacer(Modifier.height(6.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(8.dp),
                        ) {
                            items(playlist.tracks.take(15)) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.music_note),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (track.artist.isNotBlank()) {
                                            Text(
                                                text = track.artist,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                            if (playlist.tracks.size > 15) {
                                item {
                                    Text(
                                        text = "+ ${playlist.tracks.size - 15} more tracks",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(top = 4.dp, start = 24.dp),
                                    )
                                }
                            }
                        }

                        if (conversionError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = conversionError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Paste a public playlist link or export history below. Tracks will be converted directly into your local October library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = {
                                inputUrl = it
                                parseError = null
                            },
                            placeholder = {
                                Text(
                                    text = "https://open.spotify.com/playlist/...\nhttps://music.apple.com/.../playlist/...\nor paste track list",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )

                        if (parseError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = parseError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "• Spotify playlists, albums & shared links\n• Apple Music public playlists & albums\n• Exported CSV or plain text lists ('Title - Artist')",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
