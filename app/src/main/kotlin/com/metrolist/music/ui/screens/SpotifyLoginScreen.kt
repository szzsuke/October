/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.AccountTypeKey
import com.metrolist.music.constants.EntryPathKey
import com.metrolist.music.constants.GoogleIdentityAttachedKey
import com.metrolist.music.constants.IsOnboardedKey
import com.metrolist.music.constants.SpotifyLoggedInKey
import com.metrolist.music.converter.PlaylistConverter
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.launch

private val ColorDarkBg = Color(0xFF121212)
private val ColorSurfaceCard = Color(0xFF242426)
private val ColorHelperText = Color(0xFFA7A7A7)
private val ColorChipBorder = Color(0xFF444444)
private val ColorSpotifyGreen = Color(0xFF1DB954)
private val ColorRoyalPurple = Color(0xFF542E71)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(
    navController: NavController,
    isFromSettings: Boolean = false,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    val (spotifyLoggedIn) = rememberPreference(SpotifyLoggedInKey, false)

    val importProgress by PlaylistConverter.importProgress.collectAsStateWithLifecycle()
    var localError by remember { mutableStateOf<String?>(null) }
    var isStartingImport by remember { mutableStateOf(false) }
    val isImportingZip = isStartingImport || (importProgress?.isRunning == true)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isStartingImport = true
            localError = null

            coroutineScope.launch {
                runCatching {
                    // Safe validated stream parsing of Exportify ZIP/CSV
                    val parseResult = PlaylistConverter.parseExportifyUri(context, uri)
                    val playlists = parseResult.getOrThrow()

                    // User is NOT logged in yet — same restricted state as Guest
                    context.safeDataStoreEdit { settings ->
                        settings[SpotifyLoggedInKey] = true
                        settings[AccountTypeKey] = "GUEST"
                        settings[EntryPathKey] = "SPOTIFY"
                        settings[GoogleIdentityAttachedKey] = false
                        if (!isFromSettings) {
                            settings[IsOnboardedKey] = true
                        }
                    }

                    // Register playlists in Room DB and seed ReccoBeats
                    PlaylistConverter.startFastImportExportify(database, playlists)

                    // On success: user lands directly on home screen
                    if (isFromSettings) {
                        navController.navigateUp()
                    } else {
                        navController.navigate(Screens.Home.route) {
                            popUpTo(Screens.WelcomeLogin.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }.onFailure {
                    // Show exact error text: "Invalid file type" — nothing more elaborate
                    localError = "Invalid file type"
                }
                isStartingImport = false
            }
        }
    }

    BackHandler(enabled = !isImportingZip) {
        navController.navigateUp()
    }

    Scaffold(
        containerColor = ColorDarkBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Import from Spotify",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                        enabled = !isImportingZip,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = ColorDarkBg,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Import Spotify Library",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )

            Text(
                text = "Export your playlists and Liked Songs from Spotify via Exportify, then select the downloaded file below.",
                style = MaterialTheme.typography.bodySmall,
                color = ColorHelperText,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Step 1: Open exportify.app
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ColorSurfaceCard,
                border = BorderStroke(1.dp, ColorChipBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = ColorRoyalPurple,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("1", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Export from Spotify",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Open exportify.app in your browser, log in with Spotify, and click 'Export All' to download a ZIP of your playlists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorHelperText,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val customTabsIntent = CustomTabsIntent.Builder().build()
                            try {
                                customTabsIntent.launchUrl(context, "https://exportify.app".toUri())
                            } catch (_: Exception) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://exportify.app".toUri()))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorRoyalPurple,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.link), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open exportify.app", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2: Choose ZIP / CSV file
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ColorSurfaceCard,
                border = BorderStroke(1.dp, ColorChipBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = ColorRoyalPurple,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("2", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Select Exported File",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Choose the exported ZIP or CSV file downloaded from Exportify.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorHelperText,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            filePickerLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/octet-stream",
                                    "*/*",
                                )
                            )
                        },
                        enabled = !isImportingZip,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.upload), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Select Exported ZIP / CSV", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Error display: Exactly "Invalid file type"
            if (!localError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF2E1A1A),
                    border = BorderStroke(1.dp, Color(0xFF6E2828)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = localError!!,
                        color = Color(0xFFFF5252),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Import progress indication
            if (isStartingImport) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E281E),
                    border = BorderStroke(1.dp, Color(0xFF2E5E2E)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = ColorSpotifyGreen,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Validating and importing playlists...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                }
            }

            val progress = importProgress
            if (progress != null && progress.isRunning && progress.totalTracks > 0) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF142416),
                    border = BorderStroke(1.dp, ColorSpotifyGreen),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LinearProgressIndicator(
                            progress = { progress.currentTrack.toFloat() / progress.totalTracks },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = ColorSpotifyGreen,
                            trackColor = Color(0xFF2C3E2C),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Matching ${progress.currentTrack} of ${progress.totalTracks} songs in background...",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
