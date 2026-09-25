/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-perfect Concept Music Player UI
 * Replicating Aurora Churchyard reference specification exactly.
 *
 * Screen dimensions reference: 393 x 852.
 * Background: flat #161616.
 */
@Composable
fun ConceptPlayer(
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    repeatMode: Int,
    isFavorite: Boolean,
    rawLyrics: String?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekToPrevious: () -> Unit,
    onSeekToNext: () -> Unit,
    onToggleRepeatMode: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    // Parse synchronized lyrics into entries
    val lyricsEntries = remember(rawLyrics) {
        if (!rawLyrics.isNullOrBlank()) {
            LyricsUtils.parseLyrics(rawLyrics)
        } else {
            emptyList()
        }
    }

    // Active lyric index based on current playback position
    val activeLyricIndex = remember(position, lyricsEntries) {
        if (lyricsEntries.isNotEmpty()) {
            val idx = lyricsEntries.indexOfLast { it.time <= position }
            if (idx >= 0) idx else 0
        } else {
            0
        }
    }

    // Interactive Rotary Telephone Wheel State
    var currentArtRotation by remember { mutableFloatStateOf(0f) }
    val rotationAnimatable = remember { Animatable(0f) }
    var isWheelDragging by remember { mutableStateOf(false) }

    // Tap feedback ripple state
    var showTapFeedback by remember { mutableStateOf(false) }

    // Scrubber bubble state
    var showTimeBubble by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF161616)),
        contentAlignment = Alignment.TopCenter,
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val scaleRatio = (screenWidth / 393.dp).coerceIn(0.8f, 1.2f)

        // =========================================================================
        // 1. HEADER (Center line ≈ 9.8% of height, ~83dp from top)
        // =========================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 61.dp)
                .height(44.dp)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Center Stacked Metadata: Artist (10.5sp uppercase) / Title (15sp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            ) {
                Text(
                    text = (mediaMetadata?.artists?.joinToString { it.name } ?: "AURORA").uppercase(),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.12.sp * 10.5f,
                    color = Color(0xFF6A6A6A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = mediaMetadata?.title ?: "Churchyard",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEDEDED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            // Right: Horizontal "•••" (three solid dots ~4px), 28dp from right edge
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenMenu,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_horiz),
                    contentDescription = "Menu",
                    tint = Color.White,
                    modifier = Modifier.size(23.dp),
                )
            }
        }

        // =========================================================================
        // 2. LYRICS BOX (Top ≈ 127dp, Height ≈ 199dp, Margins ≈ 11dp)
        // Centered active line scrolling up dynamically as track progresses
        // =========================================================================
        val lineHeight = 38.dp
        val boxHeight = 199.dp
        val containerCenter = boxHeight / 2 // 99.5dp

        // Dynamic smooth offset so active line is ALWAYS vertically centered at 99.5dp
        val targetLyricsOffset = remember(activeLyricIndex, lyricsEntries.size) {
            if (lyricsEntries.isNotEmpty()) {
                containerCenter - (lineHeight * activeLyricIndex + lineHeight / 2)
            } else {
                0.dp
            }
        }
        val animatedLyricsOffset by animateDpAsState(
            targetValue = targetLyricsOffset,
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            label = "lyricsScroll",
        )

        Box(
            modifier = Modifier
                .offset(y = 127.dp)
                .padding(horizontal = 11.dp)
                .fillMaxWidth()
                .height(boxHeight)
                .clip(RoundedCornerShape(36.dp))
                .background(Color(0x06FFFFFF)) // rgba(255, 255, 255, 0.025)
                .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(36.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenLyrics,
                )
                // Soft gradient mask so top and bottom lines remain clearly visible
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.5f),
                            0.08f to Color.Black,
                            0.92f to Color.Black,
                            1.0f to Color.Black.copy(alpha = 0.5f),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (lyricsEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = animatedLyricsOffset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    lyricsEntries.forEachIndexed { index, entry ->
                        val distance = abs(index - activeLyricIndex)
                        val textColor = when (distance) {
                            0 -> Color(0xFFD6D6D6) // Active line: brightest
                            1 -> Color(0xFFB0B0B0) // ±1 neighbour
                            2 -> Color(0xFF9A9A9A) // ±2 lines visible
                            else -> Color(0xFF727272)
                        }
                        val fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Medium

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(lineHeight)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = entry.text,
                                fontSize = 15.5.sp,
                                fontWeight = fontWeight,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                // Fallback display when lyrics are not synced / loading
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = mediaMetadata?.title ?: "Lyrics",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFD6D6D6),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8E8E8E),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // =========================================================================
        // 3. CONTROLS (Pause 87dp at 383dp; Rewind/Forward 62dp ±79dp at 412dp)
        // =========================================================================
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Rewind Button (Left, 62dp, center at 50% - 79dp, top at 412dp - 31dp = 381dp)
            Box(
                modifier = Modifier
                    .offset(x = (-79).dp, y = 381.dp)
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0x14FFFFFF), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSeekToPrevious,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Double-triangle rewind icon in #D6D6D6, ~18dp
                Canvas(modifier = Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    // Left triangle: (0, h/2) to (w/2, 0) to (w/2, h)
                    val leftPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, h / 2f)
                        lineTo(w / 2f, 0f)
                        lineTo(w / 2f, h)
                        close()
                    }
                    // Right triangle: (w/2, h/2) to (w, 0) to (w, h)
                    val rightPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w / 2f, h / 2f)
                        lineTo(w, 0f)
                        lineTo(w, h)
                        close()
                    }
                    drawPath(leftPath, Color(0xFFD6D6D6))
                    drawPath(rightPath, Color(0xFFD6D6D6))
                }
            }

            // Forward Button (Right, 62dp, center at 50% + 79dp, top at 381dp)
            Box(
                modifier = Modifier
                    .offset(x = 79.dp, y = 381.dp)
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0x14FFFFFF), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSeekToNext,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Double-triangle forward icon in #D6D6D6, ~18dp
                Canvas(modifier = Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    // Left triangle: (0, 0) to (w/2, h/2) to (0, h)
                    val leftPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, 0f)
                        lineTo(w / 2f, h / 2f)
                        lineTo(0f, h)
                        close()
                    }
                    // Right triangle: (w/2, 0) to (w, h/2) to (w/2, h)
                    val rightPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w / 2f, 0f)
                        lineTo(w, h / 2f)
                        lineTo(w / 2f, h)
                        close()
                    }
                    drawPath(leftPath, Color(0xFFD6D6D6))
                    drawPath(rightPath, Color(0xFFD6D6D6))
                }
            }

            // Hero Pause / Play Button (Center 87dp, #B44828, center at 383dp, top at 383 - 43.5 = 339.5dp)
            Box(
                modifier = Modifier
                    .offset(y = 339.5.dp)
                    .size(87.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB44828))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlayPause,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) {
                    // Two 6x20dp white bars with 6dp gap and 2dp corner radius
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White),
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White),
                        )
                    }
                } else {
                    // Play triangle icon (18x20dp)
                    Canvas(modifier = Modifier.size(width = 18.dp, height = 20.dp).offset(x = 2.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, Color.White)
                    }
                }
            }
        }

        // =========================================================================
        // 4. ALBUM ART CIRCLE (371dp diameter, top at 441dp, fully inside screen)
        // Interactive rotary telephone wheel gesture & concentric progress ring
        // =========================================================================
        val artDiameter = 371.dp

        Box(
            modifier = Modifier
                .offset(y = 441.dp)
                .size(artDiameter)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        var lastAngle = atan2(down.position.y - center.y, down.position.x - center.x)
                        var totalAngleMoved = 0f
                        var isDragging = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break

                            val currentAngle = atan2(change.position.y - center.y, change.position.x - center.x)
                            var delta = currentAngle - lastAngle
                            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                            if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()

                            val dist = abs(change.position.x - down.position.x) + abs(change.position.y - down.position.y)
                            if (!isDragging && dist > 10f) {
                                isDragging = true
                                isWheelDragging = true
                                showTimeBubble = true
                            }

                            if (isDragging) {
                                change.consume()
                                totalAngleMoved += abs(delta)
                                val deltaDeg = Math.toDegrees(delta.toDouble()).toFloat()
                                currentArtRotation += deltaDeg

                                if (duration > 0L) {
                                    val timeDelta = (delta / (2 * Math.PI) * duration).toLong()
                                    val newPos = (position + timeDelta).coerceIn(0L, duration)
                                    onSeek(newPos)
                                }
                            }
                            lastAngle = currentAngle
                        }

                        isWheelDragging = false

                        if (isDragging && totalAngleMoved >= 0.08f) {
                            // Rotary telephone spring-back animation to 0° upright
                            coroutineScope.launch {
                                rotationAnimatable.snapTo(currentArtRotation)
                                rotationAnimatable.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.85f,
                                        stiffness = 250f,
                                    ),
                                )
                                currentArtRotation = 0f
                            }
                        } else {
                            // Quick tap on album art toggles play/pause
                            onPlayPause()
                            showTapFeedback = true
                            coroutineScope.launch {
                                delay(350)
                                showTapFeedback = false
                            }
                        }

                        coroutineScope.launch {
                            delay(900)
                            if (!isWheelDragging) {
                                showTimeBubble = false
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Rotated Album Art Image
            val currentAngleDisplay = if (isWheelDragging) currentArtRotation else rotationAnimatable.value

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .graphicsLayer {
                        rotationZ = currentAngleDisplay
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // Direct Tap Ripple Feedback Overlay
                if (showTapFeedback) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPlaying) {
                            Canvas(modifier = Modifier.size(36.dp)) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(size.width, size.height / 2f)
                                    lineTo(0f, size.height)
                                    close()
                                }
                                drawPath(path, Color.White)
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 6.dp, height = 24.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 6.dp, height = 24.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                }
            }

            // =====================================================================
            // 5. PROGRESS RING (Concentric, inset 16dp inside album art circle)
            // =====================================================================
            val progressFraction = remember(position, duration) {
                if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }

            Canvas(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize(),
            ) {
                val r = size.width / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                val strokeWidth = 2.dp.toPx()

                // Inactive remaining track: white at ~22% opacity
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = r,
                    center = c,
                    style = Stroke(width = strokeWidth),
                )

                // Played portion: white at ~90% opacity, starts at 12 o'clock (-90°)
                val sweepAngle = progressFraction * 360f
                drawArc(
                    color = Color.White.copy(alpha = 0.90f),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )

                // 16dp solid white knob thumb circle
                val thumbRad = Math.toRadians((-90f + sweepAngle).toDouble())
                val thumbX = c.x + r * cos(thumbRad).toFloat()
                val thumbY = c.y + r * sin(thumbRad).toFloat()
                drawCircle(
                    color = Color.White,
                    radius = 8.dp.toPx(),
                    center = Offset(thumbX, thumbY),
                )
            }
        }

        // =========================================================================
        // 6. SIDE ACTION BUTTONS (42dp diameter, center at ~476dp / ~55.9% height)
        // Left center ~37dp from left edge, Right center ~37dp from right edge
        // =========================================================================
        // Left Button: Repeat Mode (Off / All / One)
        val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
        Box(
            modifier = Modifier
                .offset(x = 16.dp, y = 455.dp) // center x = 37dp, center y = 476dp
                .size(42.dp)
                .clip(CircleShape)
                .background(if (repeatActive) Color(0x24FFFFFF) else Color(0xFF1E1E1E))
                .border(1.dp, Color(0x14FFFFFF), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleRepeatMode,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> {
                    Icon(
                        painter = painterResource(R.drawable.repeat_one),
                        contentDescription = "Repeat One",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Player.REPEAT_MODE_ALL -> {
                    Icon(
                        painter = painterResource(R.drawable.repeat),
                        contentDescription = "Repeat All",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                else -> {
                    Icon(
                        painter = painterResource(R.drawable.repeat),
                        contentDescription = "Repeat Off",
                        tint = Color(0xFF6A6A6A),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Right Button: Like / Favorite (Hollow by default, solid filled with pop when liked)
        val likeScale by animateFloatAsState(
            targetValue = if (isFavorite) 1.15f else 1.0f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
            label = "likeScale",
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-16).dp, y = 455.dp) // center x = 393 - 37dp, center y = 476dp
                .size(42.dp)
                .clip(CircleShape)
                .background(if (isFavorite) Color(0x24FFFFFF) else Color(0xFF1E1E1E))
                .border(1.dp, Color(0x14FFFFFF), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleLike,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (isFavorite) R.drawable.favorite else R.drawable.favorite_border,
                ),
                contentDescription = "Favorite",
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .scale(likeScale),
            )
        }

        // Floating Time Bubble while scrubbing
        if (showTimeBubble) {
            Box(
                modifier = Modifier
                    .offset(y = 416.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF0161616))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val posSec = position / 1000L
                val durSec = duration / 1000L
                val posStr = "${posSec / 60}:${(posSec % 60).toString().padStart(2, '0')}"
                val durStr = "${durSec / 60}:${(durSec % 60).toString().padStart(2, '0')}"
                Text(
                    text = "$posStr / $durStr",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.04.sp * 11f,
                    color = Color.White,
                )
            }
        }

        // Home Indicator Pill (140dp x 5dp, below album art with ~24dp gap)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-10).dp)
                .width(140.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(Color.White),
        )
    }
}
