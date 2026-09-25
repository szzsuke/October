/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.ui.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.music.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.backup.OctoberBackupManager
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.AccountTypeKey
import com.metrolist.music.constants.EntryPathKey
import com.metrolist.music.constants.GoogleIdentityAttachedKey
import com.metrolist.music.constants.IsOnboardedKey
import com.metrolist.music.constants.JustLoggedInKey
import com.metrolist.music.constants.SpotifyLoggedInKey
import com.metrolist.music.constants.UserProfilePicUriKey
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.launch
import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import com.metrolist.innertube.YouTube
import com.metrolist.music.auth.HeadlessGoogleAuth
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeAuthUserKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import java.net.URLEncoder
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ColorSpotifyDark = Color(0xFF121212)
// Pantone Royal Purple 19-3642 TCX
private val ColorPantoneRoyalPurple = Color(0xFF542E71)
private val ColorBorderOutline = Color(0xFF535353)

@Composable
fun WelcomeLoginScreen(
    onContinue: () -> Unit,
    onGoogleSignInClick: () -> Unit = {},
    onSpotifySignInClick: () -> Unit = {},
    isFromSettings: Boolean = false,
    onBack: (() -> Unit)? = null,
    navController: NavController? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val authErrorFlow = remember(navController) {
        navController?.currentBackStackEntry?.savedStateHandle?.getStateFlow<String?>("auth_error", null)
    }
    val authError by authErrorFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    var dismissedError by remember { mutableStateOf<String?>(null) }
    val currentError: String? = if (authError != null && authError != dismissedError) authError else null

    var isSigningInGoogle by remember { mutableStateOf(false) }
    var signingInEmail by remember { mutableStateOf<String?>(null) }

    val (spotifyLoggedIn) = rememberPreference(SpotifyLoggedInKey, false)

    var isRestoringCloudBackup by remember { mutableStateOf(false) }
    var restoreErrorMessage by remember { mutableStateOf<String?>(null) }
    var showYTMusicReauthDialog by remember { mutableStateOf(false) }
    var restoredEmail by remember { mutableStateOf("") }

    val cloudRestorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!selectedEmail.isNullOrBlank()) {
                isRestoringCloudBackup = true
                restoreErrorMessage = null
                coroutineScope.launch {
                    val activity = context as? Activity
                    val name = selectedEmail.substringBefore("@")
                    val token = if (activity != null) {
                        com.metrolist.music.backup.GoogleDriveClient.getDriveAuthTokenInteractive(activity, selectedEmail)
                    } else {
                        com.metrolist.music.backup.GoogleDriveClient.getDriveTokenBackground(context, selectedEmail)
                    }

                    val driveZipBytes = if (token != null) {
                        withContext(Dispatchers.IO) {
                            com.metrolist.music.backup.GoogleDriveClient.downloadBackupFromAppDataFolder(context, token, selectedEmail).getOrNull()
                        }
                    } else null

                    if (driveZipBytes == null || driveZipBytes.isEmpty()) {
                        isRestoringCloudBackup = false
                        restoreErrorMessage = "No backup found for this account"
                        return@launch
                    }

                    val restoreResult = OctoberBackupManager.restoreFromBackupZip(context, database, driveZipBytes)
                    val snapshot = restoreResult.getOrNull()
                    if (restoreResult.isFailure || snapshot == null) {
                        isRestoringCloudBackup = false
                        restoreErrorMessage = "No backup found for this account"
                        return@launch
                    }

                    OctoberBackupManager.saveLocalBackup(context, driveZipBytes)

                    context.safeDataStoreEdit { s ->
                        s[AccountEmailKey] = selectedEmail
                        if (snapshot.displayName.isNotBlank()) {
                            s[AccountNameKey] = snapshot.displayName
                        } else {
                            s[AccountNameKey] = name
                        }
                        s[AccountChannelHandleKey] = "@$name"
                        s[GoogleIdentityAttachedKey] = true
                        s[AccountTypeKey] = "GOOGLE"
                        s[IsOnboardedKey] = true
                        s[JustLoggedInKey] = true
                    }

                    // Trigger 2 (Periodic): Enqueue 48h WorkManager job
                    com.metrolist.music.backup.OctoberBackupWorker.schedulePeriodicBackup(context)
                    isRestoringCloudBackup = false

                    if (snapshot.primaryConnection == "YTMUSIC" || snapshot.accountType == "YTMUSIC") {
                        restoredEmail = selectedEmail
                        showYTMusicReauthDialog = true
                    } else {
                        onContinue()
                    }
                }
            }
        }
    }

    val completeGuestOnboarding: () -> Unit = {
        coroutineScope.launch {
            context.safeDataStoreEdit { settings ->
                settings[IsOnboardedKey] = true
                settings[JustLoggedInKey] = true
                settings[AccountTypeKey] = "GUEST"
                settings[AccountNameKey] = "Guest"
                settings[AccountEmailKey] = ""
                settings[AccountChannelHandleKey] = ""
                settings[UserProfilePicUriKey] = ""
            }
            onContinue()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ColorSpotifyDark)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 1. Atmosphere Video Background Header (fades right at the line below emblem)
            if (!isFromSettings) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(365.dp)
                ) {
                    LoopingVideoBackground(
                        videoResId = R.raw.login_bg_video,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Smoothly fades into pure dark #121212 right at the user's line
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.70f to Color.Transparent,
                                    0.88f to Color(0x66121212),
                                    1.0f to Color(0xFF121212)
                                )
                            )
                    )

                    // October Star Emblem sits right at the bottom of the video
                    Image(
                        painter = painterResource(R.drawable.october_star_transparent),
                        contentDescription = "October Emblem",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .size(76.dp)
                    )
                }
            } else {
                Spacer(Modifier.height(88.dp))
                Image(
                    painter = painterResource(R.drawable.october_star_transparent),
                    contentDescription = "October Emblem",
                    modifier = Modifier.size(76.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // 3. Hero Headline
            Text(
                text = "Millions of songs.\nFree on October.",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
                lineHeight = 38.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(32.dp))

            // 4. Buttons Container
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                if (currentError != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x33E53935),
                        border = BorderStroke(1.dp, Color(0x88E53935)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clickable { dismissedError = currentError }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.warning),
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = currentError ?: "",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = "Dismiss",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (!isFromSettings) {
                    // CONTINUE AS GUEST (Pantone Royal Purple 19-3642 TCX Pill Button)
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            completeGuestOnboarding()
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
                        Text(
                            text = "CONTINUE AS GUEST",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                }

                // 1. IMPORT FROM SPOTIFY (Path A) - Hidden if Spotify is already imported
                if (!spotifyLoggedIn) {
                    PillOutlineButton(
                        iconRes = R.drawable.spotify_brand_logo,
                        text = "IMPORT FROM SPOTIFY",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSpotifySignInClick()
                        }
                    )

                    Spacer(Modifier.height(14.dp))
                }

                // 3. CONTINUE WITH YTMUSIC (renamed from Continue with Google)
                PillOutlineButton(
                    iconRes = R.drawable.google_brand_logo,
                    text = "CONTINUE WITH YTMUSIC",
                    isLoading = false,
                    enabled = !isRestoringCloudBackup,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onGoogleSignInClick()
                    }
                )

                // 4. LOG IN (returning users)
                if (!isFromSettings || !spotifyLoggedIn) {
                    Spacer(Modifier.height(14.dp))

                    PillOutlineButton(
                        iconRes = null,
                        text = if (isRestoringCloudBackup) "LOGGING IN..." else "LOG IN",
                        isLoading = isRestoringCloudBackup,
                        enabled = !isRestoringCloudBackup,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            restoreErrorMessage = null
                            try {
                                val chooseAccountIntent = AccountManager.newChooseAccountIntent(
                                    null,
                                    null,
                                    arrayOf("com.google"),
                                    null,
                                    null,
                                    null,
                                    null
                                )
                                cloudRestorePickerLauncher.launch(chooseAccountIntent)
                            } catch (e: Exception) {
                                restoreErrorMessage = "No backup found for this account"
                            }
                        }
                    )

                    if (!restoreErrorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = restoreErrorMessage!!,
                            color = Color(0xFFFF5252),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Import from Spotify",
                                color = Color(0xFF1DB954),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { onSpotifySignInClick() }
                                    .padding(4.dp)
                            )
                            Text(
                                text = " • ",
                                color = Color.Gray,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = "Continue with YTMusic",
                                color = Color(0xFFBB86FC),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { onGoogleSignInClick() }
                                    .padding(4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Spacer(Modifier.height(36.dp))
            }
        }

        if (showYTMusicReauthDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showYTMusicReauthDialog = false
                    onContinue()
                },
                title = {
                    Text(
                        text = "Re-authorize YouTube Music",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Text(
                        text = "Your account is linked to YouTube Music. Re-authorize now to connect your live playlists.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showYTMusicReauthDialog = false
                            navController?.navigate("login_webview?email=${restoredEmail}")
                                ?: onGoogleSignInClick()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorPantoneRoyalPurple)
                    ) {
                        Text("Re-authorize", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showYTMusicReauthDialog = false
                            onContinue()
                        }
                    ) {
                        Text("Later", color = Color(0xFFAAAAAA))
                    }
                },
                containerColor = Color(0xFF1E1E20),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Top Back Button when opened from settings
        if (isFromSettings && onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun PillOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(25.dp))
            .border(1.dp, ColorBorderOutline, RoundedCornerShape(25.dp))
            .clickable(enabled = enabled && !isLoading, onClick = onClick)
            .padding(horizontal = 16.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        if (!isLoading && iconRes != null) {
            Spacer(Modifier.width(22.dp)) // Equal spacing balance
        }
    }
}

@Composable
private fun LoopingVideoBackground(
    @RawRes videoResId: Int,
    modifier: Modifier = Modifier,
) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        try {
                            val player = MediaPlayer().apply {
                                val videoUri = Uri.parse("android.resource://${ctx.packageName}/$videoResId")
                                setDataSource(ctx, videoUri)
                                setSurface(Surface(surface))
                                isLooping = true
                                setVolume(0f, 0f)
                                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                                prepareAsync()
                                setOnPreparedListener { mp ->
                                    mp.start()
                                }
                            }
                            mediaPlayer = player
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        try {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                        } catch (_: Exception) {}
                        mediaPlayer = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    )
}

