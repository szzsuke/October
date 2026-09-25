/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import timber.log.Timber
import com.metrolist.music.utils.reportException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.ui.text.input.TextFieldValue
import com.metrolist.music.constants.AccountTypeKey
import com.metrolist.music.constants.CustomAccountNameKey
import com.metrolist.music.constants.CustomAccountUsernameKey
import com.metrolist.music.constants.CustomProfilePicPathKey
import com.metrolist.music.constants.SpotifyAccountNameKey
import com.metrolist.music.constants.SpotifyLoggedInKey
import com.metrolist.music.constants.UserProfilePicUriKey
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import android.accounts.AccountManager
import android.app.Activity
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.IsOnboardedKey
import com.metrolist.music.constants.JustLoggedInKey
import com.metrolist.music.constants.PrimaryConnectionKey
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.GoogleIdentityAttachedKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeAuthUserKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.UseLoginForBrowse
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.Updater
import androidx.compose.runtime.rememberCoroutineScope
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.AccountSettingsViewModel
import com.metrolist.music.viewmodels.HomeViewModel

@Composable
fun AccountSettings(
    navController: NavController,
    onClose: () -> Unit,
    latestVersionName: String
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    val (accountNamePref, onAccountNameChange) = rememberPreference(AccountNameKey, "")
    val (accountEmail, onAccountEmailChange) = rememberPreference(AccountEmailKey, "")
    val (accountChannelHandle, onAccountChannelHandleChange) = rememberPreference(AccountChannelHandleKey, "")
    val (googleIdentityAttached, onGoogleIdentityAttachedChange) = rememberPreference(GoogleIdentityAttachedKey, false)
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (visitorData, onVisitorDataChange) = rememberPreference(VisitorDataKey, "")
    val (dataSyncId, onDataSyncIdChange) = rememberPreference(DataSyncIdKey, "")
    val (authUser, onAuthUserChange) = rememberPreference(InnerTubeAuthUserKey, "0")

    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

    val homeViewModel: HomeViewModel = hiltViewModel()
    val accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel()
    val accountName by homeViewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsStateWithLifecycle()

    val (accountType, onAccountTypeChange) = rememberPreference(AccountTypeKey, "GUEST")
    val (userProfilePicUri, onUserProfilePicUriChange) = rememberPreference(UserProfilePicUriKey, "")
    val (customAccountName, onCustomAccountNameChange) = rememberPreference(CustomAccountNameKey, "")
    val (customAccountUsername, onCustomAccountUsernameChange) = rememberPreference(CustomAccountUsernameKey, "")
    val (customProfilePicPath, onCustomProfilePicPathChange) = rememberPreference(CustomProfilePicPathKey, "")
    val (spotifyLoggedIn, onSpotifyLoggedInChange) = rememberPreference(SpotifyLoggedInKey, false)
    val (spotifyAccountName, onSpotifyAccountNameChange) = rememberPreference(SpotifyAccountNameKey, "")

    val isActuallyLoggedIn = isLoggedIn || (googleIdentityAttached && accountEmail.isNotBlank())

    val connectGoogleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!selectedEmail.isNullOrBlank()) {
                val name = selectedEmail.substringBefore("@")
                scope.launch {
                    val activity = context as? Activity
                    val token = if (activity != null) {
                        com.metrolist.music.backup.GoogleDriveClient.getDriveAuthTokenInteractive(activity, selectedEmail)
                    } else {
                        com.metrolist.music.backup.GoogleDriveClient.getDriveTokenBackground(context, selectedEmail)
                    }

                    context.safeDataStoreEdit { s ->
                        s[AccountEmailKey] = selectedEmail
                        s[AccountNameKey] = name
                        s[AccountChannelHandleKey] = "@$name"
                        s[GoogleIdentityAttachedKey] = true
                        s[AccountTypeKey] = "GOOGLE"
                        s[IsOnboardedKey] = true
                        s[JustLoggedInKey] = true
                    }
                    onAccountEmailChange(selectedEmail)
                    onGoogleIdentityAttachedChange(true)
                    onAccountTypeChange("GOOGLE")

                    // Trigger 1 (Immediate): Backup profile + already imported Spotify playlists
                    com.metrolist.music.backup.OctoberBackupManager.performImmediateSync(
                        context = context,
                        database = database,
                        email = selectedEmail,
                        activity = activity,
                        authToken = token
                    )

                    // Trigger 2 (Periodic 48h):
                    com.metrolist.music.backup.OctoberBackupWorker.schedulePeriodicBackup(context)
                }
            }
        }
    }

    val effectiveName = customAccountName.ifBlank {
        if (isActuallyLoggedIn) {
            accountNamePref.ifBlank {
                if (accountEmail.isNotBlank()) accountEmail.substringBefore("@")
                else accountName.ifBlank { "Google User" }
            }
        } else {
            "Guest"
        }
    }

    val effectiveUsername = customAccountUsername.ifBlank {
        if (isActuallyLoggedIn) {
            val h = accountChannelHandle.ifBlank {
                if (accountEmail.isNotBlank()) "@${accountEmail.substringBefore("@")}" else ""
            }
            if (h.isNotBlank() && !h.startsWith("@")) "@$h" else h
        } else {
            ""
        }
    }

    val effectiveImage = customProfilePicPath.ifBlank {
        userProfilePicUri.ifBlank {
            accountImageUrl
        }
    }

    var showEditProfileDialog by remember { mutableStateOf(false) }



    var showToken by remember { mutableStateOf(false) }
    var showTokenEditor by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.close), contentDescription = null)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Logout confirmation dialog
        if (showLogoutDialog) {
            DefaultDialog(
                onDismiss = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.logout_dialog_title)) },
                content = {
                    Text(
                        text = stringResource(R.string.logout_dialog_message),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                },
                buttons = {
                    TextButton(
                        onClick = {
                            Timber.d("[LOGOUT_CLEAR] User chose to clear data")
                            scope.launch {
                                try {
                                    Timber.d("[LOGOUT_CLEAR] Starting clear and logout process")
                                    // Forget account first (stops all sync), then clear data.
                                    // This prevents background syncs from re-adding songs.
                                    accountSettingsViewModel.logoutAndClearLibraryData(context)
                                    Timber.d("[LOGOUT_CLEAR] Library data cleared and account forgotten")
                                } catch (e: Exception) {
                                    Timber.e(e, "[LOGOUT_CLEAR] Error clearing library data, proceeding with logout")
                                    reportException(e)
                                }
                                onInnerTubeCookieChange("")
                                onAccountTypeChange("GUEST")
                                onSpotifyLoggedInChange(false)
                                onGoogleIdentityAttachedChange(false)
                                onAccountEmailChange("")
                                onCustomAccountNameChange("")
                                onCustomAccountUsernameChange("")
                                onCustomProfilePicPathChange("")
                                onUserProfilePicUriChange("")
                                Timber.d("[LOGOUT_CLEAR] Logout complete")
                                showLogoutDialog = false
                                onClose()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.logout_clear))
                    }
                    TextButton(
                        onClick = {
                            Timber.d("[LOGOUT_KEEP] User chose to keep data")
                            scope.launch {
                                Timber.d("[LOGOUT_KEEP] Starting logout process (keeping data)")
                                accountSettingsViewModel.logoutKeepData(context, onInnerTubeCookieChange)
                                onAccountTypeChange("GUEST")
                                onSpotifyLoggedInChange(false)
                                onGoogleIdentityAttachedChange(false)
                                onAccountEmailChange("")
                                onCustomAccountNameChange("")
                                onCustomAccountUsernameChange("")
                                onCustomProfilePicPathChange("")
                                onUserProfilePicUriChange("")
                                Timber.d("[LOGOUT_KEEP] Logout complete")
                                showLogoutDialog = false
                                onClose()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.logout_keep))
                    }
                }
            )
        }

        if (showTokenEditor) {
            val text = """
                ***INNERTUBE COOKIE*** =$innerTubeCookie
                ***VISITOR DATA*** =$visitorData
                ***DATASYNC ID*** =$dataSyncId
                ***AUTH USER*** =$authUser
                ***ACCOUNT NAME*** =$accountNamePref
                ***ACCOUNT EMAIL*** =$accountEmail
                ***ACCOUNT CHANNEL HANDLE*** =$accountChannelHandle
            """.trimIndent()

            TextFieldDialog(
                initialTextFieldValue = TextFieldValue(text),
                onDone = { data ->
                    var cookie = ""
                    var visitorDataValue = ""
                    var dataSyncIdValue = ""
                    var authUserValue = "0"
                    var accountNameValue = ""
                    var accountEmailValue = ""
                    var accountChannelHandleValue = ""

                    data.split("\n").forEach {
                        when {
                            it.startsWith("***INNERTUBE COOKIE*** =") -> cookie = it.substringAfter("=")
                            it.startsWith("***VISITOR DATA*** =") -> visitorDataValue = it.substringAfter("=")
                            it.startsWith("***DATASYNC ID*** =") -> dataSyncIdValue = it.substringAfter("=")
                            it.startsWith("***AUTH USER*** =") -> authUserValue = it.substringAfter("=")
                            it.startsWith("***ACCOUNT NAME*** =") -> accountNameValue = it.substringAfter("=")
                            it.startsWith("***ACCOUNT EMAIL*** =") -> accountEmailValue = it.substringAfter("=")
                            it.startsWith("***ACCOUNT CHANNEL HANDLE*** =") -> accountChannelHandleValue = it.substringAfter("=")
                        }
                    }
                    // Write all credentials atomically to DataStore and wait for completion
                    // before restarting, preventing the race condition where the process
                    // would be killed before async DataStore coroutines finished writing.
                    accountSettingsViewModel.saveTokenAndRestart(
                        context = context,
                        cookie = cookie,
                        visitorData = visitorDataValue,
                        dataSyncId = dataSyncIdValue,
                        authUser = authUserValue,
                        accountName = accountNameValue,
                        accountEmail = accountEmailValue,
                        accountChannelHandle = accountChannelHandleValue,
                    )
                },
                onDismiss = { showTokenEditor = false },
                singleLine = false,
                maxLines = 20,
                isInputValid = { fullText ->
                    // Extract the cookie value from the formatted template line,
                    // then validate it separately — avoids the bug where parseCookieString
                    // received the entire multi-line template and failed to find "SAPISID"
                    // as a key because the "***INNERTUBE COOKIE*** =" prefix shadowed it.
                    val cookieLine = fullText.lines()
                        .find { it.startsWith("***INNERTUBE COOKIE*** =") }
                    val cookieValue = cookieLine?.substringAfter("***INNERTUBE COOKIE*** =")?.trim() ?: ""
                    cookieValue.isNotEmpty() && "SAPISID" in parseCookieString(cookieValue)
                },
                extraContent = {
                    Spacer(Modifier.height(8.dp))
                    InfoLabel(text = stringResource(R.string.token_adv_login_description))
                }
            )
        }

        // Profile header group
        Material3SettingsGroup(
            items = listOfNotNull(
                Material3SettingsItem(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActuallyLoggedIn && !effectiveImage.isNullOrBlank()) {
                                AsyncImage(
                                    model = effectiveImage,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(CircleShape)
                                )
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(if (isActuallyLoggedIn) R.drawable.person else R.drawable.account),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(14.dp))

                            Column {
                                Text(
                                    text = effectiveName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (isActuallyLoggedIn) {
                                        if (effectiveUsername.isNotBlank()) effectiveUsername else when (accountType) {
                                            "SPOTIFY" -> "Spotify Account"
                                            "GOOGLE" -> "Google Account"
                                            else -> "Signed In"
                                        }
                                    } else {
                                        "Not logged in (Guest)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    trailingContent = {
                        if (isActuallyLoggedIn) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showEditProfileDialog = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.edit),
                                        contentDescription = "Edit Profile",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showLogoutDialog = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Text(stringResource(R.string.action_logout))
                                }
                            }
                        } else {
                            Button(
                                onClick = {
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
                                        connectGoogleLauncher.launch(chooseAccountIntent)
                                    } catch (e: Exception) {
                                        onClose()
                                        navController.navigate("login")
                                    }
                                },
                                shape = CircleShape
                            ) {
                                Text("Connect Google")
                            }
                        }
                    },
                    onClick = {
                        if (isActuallyLoggedIn) {
                            onClose()
                            navController.navigate("account")
                        } else {
                            onClose()
                            navController.navigate("login")
                        }
                    }
                ),
                if (isActuallyLoggedIn && accountType == "GOOGLE") {
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.switch_youtube_channel)) },
                        icon = painterResource(R.drawable.account),
                        onClick = {
                            onClose()
                            navController.navigate("switch_channel")
                        },
                    )
                } else null,
            ),
            useLowContrast = true
        )

        // Edit Profile Dialog
        if (showEditProfileDialog) {
            var editName by remember { mutableStateOf(effectiveName) }
            var editUsername by remember { mutableStateOf(effectiveUsername.removePrefix("@")) }
            var editImageUri by remember { mutableStateOf<String?>(effectiveImage) }

            val imagePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                uri?.let {
                    try {
                        val inputStream = context.contentResolver.openInputStream(it)
                        val file = java.io.File(context.filesDir, "custom_avatar.jpg")
                        val outputStream = java.io.FileOutputStream(file)
                        inputStream?.copyTo(outputStream)
                        inputStream?.close()
                        outputStream.close()
                        editImageUri = file.absolutePath
                    } catch (e: Exception) {
                        editImageUri = it.toString()
                    }
                }
            }

            DefaultDialog(
                onDismiss = { showEditProfileDialog = false },
                title = { Text("Edit Profile") },
                content = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.BottomEnd,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .clickable { imagePickerLauncher.launch("image/*") }
                        ) {
                            if (!editImageUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = editImageUri,
                                    contentDescription = "Profile Picture",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(R.drawable.person),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.edit),
                                        contentDescription = "Change photo",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap photo to change",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(16.dp))

                        androidx.compose.material3.OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))

                        androidx.compose.material3.OutlinedTextField(
                            value = editUsername,
                            onValueChange = { editUsername = it },
                            label = { Text("Username") },
                            prefix = { Text("@") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(14.dp))

                        InfoLabel(
                            text = "Changes are stored in your October Drive sync folder. Your original Google account name and profile photo will never be modified."
                        )
                    }
                },
                buttons = {
                    TextButton(onClick = { showEditProfileDialog = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(
                        onClick = {
                            val trimmedName = editName.trim()
                            val formattedUsername = if (editUsername.isNotBlank()) "@" + editUsername.trim().removePrefix("@") else ""
                            onCustomAccountNameChange(trimmedName)
                            onCustomAccountUsernameChange(formattedUsername)
                            if (editImageUri != null && editImageUri != effectiveImage) {
                                onCustomProfilePicPathChange(editImageUri!!)
                            }
                            runCatching {
                                val driveDir = java.io.File(context.filesDir, "drive_sync").apply { mkdirs() }
                                val metaFile = java.io.File(driveDir, "profile_override.json")
                                metaFile.writeText(
                                    org.json.JSONObject().apply {
                                        put("accountType", accountType)
                                        put("customName", trimmedName)
                                        put("customUsername", formattedUsername)
                                        put("customAvatarPath", editImageUri ?: effectiveImage)
                                        put("updatedAt", System.currentTimeMillis())
                                    }.toString(2)
                                )
                                com.metrolist.music.backup.OctoberBackupWorker.enqueueImmediateBackup(context)
                            }
                            showEditProfileDialog = false
                        }
                    ) {
                        Text("Save")
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        Material3SettingsGroup(
            items = listOf(
                Material3SettingsItem(
                    title = {
                        Text(
                            when {
                                !isLoggedIn -> stringResource(R.string.advanced_login)
                                showToken -> stringResource(R.string.token_shown)
                                else -> stringResource(R.string.token_hidden)
                            }
                        )
                    },
                    icon = painterResource(R.drawable.token),
                    onClick = {
                        if (!isLoggedIn) showTokenEditor = true
                        else if (!showToken) showToken = true
                        else showTokenEditor = true
                    }
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.more_content)) },
                    icon = painterResource(R.drawable.cached),
                    trailingContent = {
                        Switch(
                            enabled = isLoggedIn,
                            checked = useLoginForBrowse,
                            onCheckedChange = {
                                YouTube.useLoginForBrowse = it
                                onUseLoginForBrowseChange(it)
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (useLoginForBrowse) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    enabled = isLoggedIn
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.yt_sync)) },
                    icon = painterResource(R.drawable.cached),
                    trailingContent = {
                        Switch(
                            enabled = isLoggedIn,
                            checked = ytmSync,
                            onCheckedChange = onYtmSyncChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (ytmSync) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    enabled = isLoggedIn
                )
            ),
            useLowContrast = true
        )

        Spacer(Modifier.height(12.dp))

        Material3SettingsGroup(
            items = buildList {
                add(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.settings)) },
                        icon = painterResource(R.drawable.settings),
                        showBadge = BuildConfig.UPDATER_AVAILABLE &&
                            latestVersionName != BuildConfig.BASE_VERSION_NAME,
                        onClick = {
                            onClose()
                            navController.navigate("settings")
                        }
                    )
                )

                if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.BASE_VERSION_NAME) {
                    val releaseInfo = Updater.getCachedLatestRelease()
                    val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }
                    if (downloadUrl != null) {
                        add(
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.new_version_available)) },
                                description = { Text(latestVersionName) },
                                icon = painterResource(R.drawable.update),
                                showBadge = true,
                                onClick = { uriHandler.openUri(downloadUrl) }
                            )
                        )
                    }
                }
            },
            useLowContrast = true
        )
    }
}
