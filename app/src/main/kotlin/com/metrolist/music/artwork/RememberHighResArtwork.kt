package com.metrolist.music.artwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Composable that displays [fallbackUrl] immediately and seamlessly updates
 * to the high-resolution artwork (MusicBrainz/CAA -> iTunes -> Deezer) once resolved.
 */
@Composable
fun rememberHighResArtwork(
    artist: String?,
    album: String?,
    fallbackUrl: String? = null
): State<String?> {
    val context = LocalContext.current
    val state = remember(artist, album, fallbackUrl) {
        mutableStateOf(fallbackUrl)
    }

    LaunchedEffect(artist, album, fallbackUrl) {
        HighResArtworkRepository.init(context)
        val highRes = HighResArtworkRepository.resolveArtwork(artist, album, fallbackUrl)
        if (!highRes.isNullOrBlank() && highRes != state.value) {
            state.value = highRes
        }
    }

    return state
}
