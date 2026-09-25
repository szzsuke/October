/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LoginPromptManager {
    private val _showPrompt = MutableStateFlow(false)
    val showPrompt = _showPrompt.asStateFlow()

    @Volatile
    var isUserLoggedInState: Boolean = false

    fun triggerLoginPrompt() {
        _showPrompt.value = true
    }

    fun dismiss() {
        _showPrompt.value = false
    }

    inline fun checkLoggedInOrPrompt(onLoggedIn: () -> Unit) {
        if (isUserLoggedInState) {
            onLoggedIn()
        } else {
            triggerLoginPrompt()
        }
    }
}

private val ColorDarkSurface = Color(0xFF1E1E20)
private val ColorPantoneRoyalPurple = Color(0xFF542E71)
private val ColorSpotifyGreen = Color(0xFF1DB954)
private val ColorSecondaryText = Color(0xFFAAAAAA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPromptBottomSheet(
    onImportSpotify: () -> Unit,
    onContinueYTMusic: () -> Unit,
    onLogIn: () -> Unit,
    onDismiss: () -> Unit = { LoginPromptManager.dismiss() }
) {
    val show by LoginPromptManager.showPrompt.collectAsState()
    if (!show) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ColorDarkSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = ColorPantoneRoyalPurple.copy(alpha = 0.25f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.favorite),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Log in to save songs and playlists",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Connect an account to like tracks, create playlists, and back up your music library to the cloud.",
                color = ColorSecondaryText,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // 1. CONTINUE WITH YTMUSIC
            Button(
                onClick = {
                    onDismiss()
                    onContinueYTMusic()
                },
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorPantoneRoyalPurple,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.google_brand_logo),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.Unspecified
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "CONTINUE WITH YTMUSIC",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // 2. IMPORT FROM SPOTIFY
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onImportSpotify()
                },
                shape = RoundedCornerShape(25.dp),
                border = BorderStroke(1.dp, Color(0xFF444444)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.spotify_brand_logo),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "IMPORT FROM SPOTIFY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // 3. LOG IN (RETURNING USERS)
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onLogIn()
                },
                shape = RoundedCornerShape(25.dp),
                border = BorderStroke(1.dp, Color(0xFF444444)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "LOG IN (RETURNING USERS)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "MAYBE LATER",
                    color = ColorSecondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
