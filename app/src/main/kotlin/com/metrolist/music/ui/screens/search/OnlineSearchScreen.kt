/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.constants.SuggestionItemHeight
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.menu.YouTubeAlbumMenu
import com.metrolist.music.ui.menu.YouTubeArtistMenu
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.OnlineSearchSuggestionViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun OnlineSearchScreen(
    query: String,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    pureBlack: Boolean,
    viewModel: OnlineSearchSuggestionViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val database = LocalDatabase.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val coroutineScope = rememberCoroutineScope()

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()

    val autoRadioQueue by rememberPreference(AutoRadioQueueKey, defaultValue = true)

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        snapshotFlow { query }.debounce(300L).collectLatest {
            viewModel.query.value = it
        }
    }

    val playSong: (SongItem) -> Unit = { item ->
        if (item.id == mediaMetadata?.id) {
            playerConnection.togglePlayPause()
        } else {
            playerConnection.playQueue(
                if (autoRadioQueue) {
                    YouTubeQueue.radio(item.toMediaMetadata())
                } else {
                    ListQueue(
                        title = item.title,
                        items = listOf(item.toMediaItem()),
                    )
                },
            )
            onDismiss()
        }
    }

    val showItemMenu: (YTItem) -> Unit = { item ->
        menuState.show {
            when (item) {
                is SongItem -> {
                    YouTubeSongMenu(
                        song = item,
                        onDismiss = {
                            menuState.dismiss()
                            onDismiss()
                        },
                    )
                }

                is AlbumItem -> {
                    YouTubeAlbumMenu(
                        albumItem = item,
                        onDismiss = {
                            menuState.dismiss()
                            onDismiss()
                        },
                    )
                }

                is ArtistItem -> {
                    YouTubeArtistMenu(
                        artist = item,
                        onDismiss = {
                            menuState.dismiss()
                            onDismiss()
                        },
                    )
                }

                is PlaylistItem -> {
                    YouTubePlaylistMenu(
                        playlist = item,
                        coroutineScope = coroutineScope,
                        onDismiss = {
                            menuState.dismiss()
                            onDismiss()
                        },
                    )
                }

                is PodcastItem -> {
                    YouTubePlaylistMenu(
                        playlist = item.asPlaylistItem(),
                        coroutineScope = coroutineScope,
                        onDismiss = {
                            menuState.dismiss()
                            onDismiss()
                        },
                    )
                }

                is EpisodeItem -> {
                    YouTubeSongMenu(
                        song = item.asSongItem(),
                        onDismiss = {
                            menuState.dismiss()
                            onDismiss()
                        },
                    )
                }

                else -> {}
            }
        }
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues(),
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        // Show parsed URL item at the top if present
        if (viewState.isUrlQuery && viewState.parsedUrlItem != null) {
            item(key = "parsed_url_header") {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.parsed_from_link),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            item(key = "parsed_url_item") {
                val item = viewState.parsedUrlItem!!
                SearchResultItemRow(
                    item = item,
                    isActive =
                        when (item) {
                            is SongItem -> mediaMetadata?.id == item.id
                            is AlbumItem -> mediaMetadata?.album?.id == item.id
                            else -> false
                        },
                    isPlaying = isPlaying,
                    pureBlack = pureBlack,
                    onPlaySong = playSong,
                    onOpenAlbum = {
                        navController.navigate("album/$it")
                        onDismiss()
                    },
                    onOpenArtist = {
                        navController.navigate("artist/$it")
                        onDismiss()
                    },
                    onOpenPlaylist = {
                        navController.navigate("online_playlist/$it")
                        onDismiss()
                    },
                    onShowMenu = { showItemMenu(item) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "parsed_url_divider") {
                HorizontalDivider(
                    modifier =
                        Modifier
                            .padding(vertical = 8.dp)
                            .animateItem(),
                )
            }
        } else {
            // 1. Predicted Top Result / Best Match
            if (viewState.topResult != null) {
                item(key = "top_result_header") {
                    Text(
                        text = stringResource(R.string.top_result),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .animateItem(),
                    )
                }

                item(key = "top_result_card") {
                    val item = viewState.topResult!!
                    TopResultCard(
                        item = item,
                        isActive =
                            when (item) {
                                is SongItem -> mediaMetadata?.id == item.id
                                is AlbumItem -> mediaMetadata?.album?.id == item.id
                                else -> false
                            },
                        isPlaying = isPlaying,
                        pureBlack = pureBlack,
                        onPlaySong = playSong,
                        onOpenAlbum = {
                            navController.navigate("album/$it")
                            onDismiss()
                        },
                        onOpenArtist = {
                            navController.navigate("artist/$it")
                            onDismiss()
                        },
                        onOpenPlaylist = {
                            navController.navigate("online_playlist/$it")
                            onDismiss()
                        },
                        onLongClick = { showItemMenu(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // 2. Predicted Songs
            if (viewState.predictedSongs.isNotEmpty()) {
                item(key = "predicted_songs_header") {
                    Text(
                        text = stringResource(R.string.songs),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .animateItem(),
                    )
                }

                items(viewState.predictedSongs, key = { "pred_song_${it.id}" }) { song ->
                    SearchResultItemRow(
                        item = song,
                        isActive = mediaMetadata?.id == song.id,
                        isPlaying = isPlaying,
                        pureBlack = pureBlack,
                        onPlaySong = playSong,
                        onOpenAlbum = {
                            navController.navigate("album/$it")
                            onDismiss()
                        },
                        onOpenArtist = {
                            navController.navigate("artist/$it")
                            onDismiss()
                        },
                        onOpenPlaylist = {
                            navController.navigate("online_playlist/$it")
                            onDismiss()
                        },
                        onShowMenu = { showItemMenu(song) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // 3. Other Predicted Items (Albums, Artists)
            val otherPredictedItems =
                viewState.items.filter { item ->
                    item.id != viewState.topResult?.id && viewState.predictedSongs.none { it.id == item.id }
                }

            if (otherPredictedItems.isNotEmpty()) {
                items(otherPredictedItems, key = { "other_item_${it.id}" }) { item ->
                    SearchResultItemRow(
                        item = item,
                        isActive =
                            when (item) {
                                is SongItem -> mediaMetadata?.id == item.id
                                is AlbumItem -> mediaMetadata?.album?.id == item.id
                                else -> false
                            },
                        isPlaying = isPlaying,
                        pureBlack = pureBlack,
                        onPlaySong = playSong,
                        onOpenAlbum = {
                            navController.navigate("album/$it")
                            onDismiss()
                        },
                        onOpenArtist = {
                            navController.navigate("artist/$it")
                            onDismiss()
                        },
                        onOpenPlaylist = {
                            navController.navigate("online_playlist/$it")
                            onDismiss()
                        },
                        onShowMenu = { showItemMenu(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            val hasPredictedMusic =
                viewState.topResult != null || viewState.predictedSongs.isNotEmpty() || otherPredictedItems.isNotEmpty()

            if (hasPredictedMusic && (viewState.suggestions.isNotEmpty() || viewState.history.isNotEmpty())) {
                item(key = "search_divider") {
                    HorizontalDivider(
                        modifier =
                            Modifier
                                .padding(vertical = 8.dp)
                                .animateItem(),
                    )
                }
            }

            // 4. Search Suggestions (Text queries)
            if (viewState.suggestions.isNotEmpty()) {
                if (hasPredictedMusic) {
                    item(key = "suggestions_header") {
                        Text(
                            text = stringResource(R.string.suggestions),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .animateItem(),
                        )
                    }
                }

                items(viewState.suggestions, key = { "suggestion_$it" }) { sQuery ->
                    SuggestionItem(
                        query = sQuery,
                        online = true,
                        onClick = {
                            onSearch(sQuery)
                            onDismiss()
                        },
                        onFillTextField = {
                            onQueryChange(TextFieldValue(sQuery, TextRange(sQuery.length)))
                        },
                        modifier = Modifier.animateItem(),
                        pureBlack = pureBlack,
                    )
                }
            }

            // 5. Search History (Recent searches)
            if (viewState.history.isNotEmpty()) {
                if (hasPredictedMusic || viewState.suggestions.isNotEmpty()) {
                    item(key = "history_header") {
                        Text(
                            text = stringResource(R.string.search_history),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .animateItem(),
                        )
                    }
                }

                items(viewState.history, key = { "history_${it.query}" }) { history ->
                    SuggestionItem(
                        query = history.query,
                        online = false,
                        onClick = {
                            onSearch(history.query)
                            onDismiss()
                        },
                        onDelete = {
                            database.query {
                                delete(history)
                            }
                        },
                        onFillTextField = {
                            onQueryChange(TextFieldValue(history.query, TextRange(history.query.length)))
                        },
                        modifier = Modifier.animateItem(),
                        pureBlack = pureBlack,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopResultCard(
    item: YTItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onPlaySong: (SongItem) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)

    Surface(
        shape = shape,
        color = if (pureBlack) Color(0xFF141414) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border =
            BorderStroke(
                width = 0.5.dp,
                color = if (pureBlack) Color(0xFF222222) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(shape)
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> onPlaySong(item)
                            is AlbumItem -> onOpenAlbum(item.id)
                            is ArtistItem -> onOpenArtist(item.id)
                            is PlaylistItem -> onOpenPlaylist(item.id)
                            else -> {}
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
        ) {
            val imageShape = if (item is ArtistItem) CircleShape else RoundedCornerShape(12.dp)
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(68.dp)
                        .clip(imageShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "TOP RESULT",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = Color(0xFFAF52DE),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.title,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitle =
                    when (item) {
                        is SongItem -> {
                            val artistText = item.artists.joinToString { it.name }
                            if (artistText.isNotEmpty()) "Song • $artistText" else "Song"
                        }

                        is ArtistItem -> "Artist"
                        is AlbumItem -> {
                            val artistText = item.artists?.joinToString { it.name }.orEmpty()
                            if (artistText.isNotEmpty()) "Album • $artistText" else "Album"
                        }

                        is PlaylistItem -> "Playlist"
                        else -> ""
                    }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (item is SongItem) {
                IconButton(
                    onClick = { onPlaySong(item) },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isActive && isPlaying) Color(0xFFAF52DE) else Color(0xFFAF52DE).copy(alpha = 0.15f),
                            contentColor = if (isActive && isPlaying) Color.White else Color(0xFFAF52DE),
                        ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(if (isActive && isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultItemRow(
    item: YTItem,
    isActive: Boolean,
    isPlaying: Boolean,
    pureBlack: Boolean,
    onPlaySong: (SongItem) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onShowMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    YouTubeListItem(
        item = item,
        isActive = isActive,
        isPlaying = isPlaying,
        trailingContent = {
            IconButton(onClick = onShowMenu) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                )
            }
        },
        modifier =
            modifier
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> onPlaySong(item)
                            is AlbumItem -> onOpenAlbum(item.id)
                            is ArtistItem -> onOpenArtist(item.id)
                            is PlaylistItem -> onOpenPlaylist(item.id)
                            else -> {}
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShowMenu()
                    },
                ).background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun SuggestionItem(
    modifier: Modifier = Modifier,
    query: String,
    online: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
    onFillTextField: () -> Unit,
    pureBlack: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(SuggestionItemHeight)
                .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
    ) {
        Icon(
            painterResource(if (online) R.drawable.search else R.drawable.history),
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 16.dp).alpha(0.5f),
        )

        Text(
            text = query,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!online) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.alpha(0.5f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                )
            }
        }

        IconButton(
            onClick = onFillTextField,
            modifier = Modifier.alpha(0.5f),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_top_left),
                contentDescription = null,
            )
        }
    }
}
