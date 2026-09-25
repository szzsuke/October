/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

object GoogleDriveClient {

    private const val TAG = "GoogleDriveClient"
    const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val BACKUP_FILE_NAME = "october_backup.zip"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Coroutine-based interactive OAuth2 token retrieval with consent handling.
     */
    suspend fun getDriveAuthTokenInteractive(
        activity: Activity,
        accountName: String
    ): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        requestDriveAuthToken(activity, accountName) { token ->
            if (cont.isActive) {
                cont.resumeWith(Result.success(token))
            }
        }
    }

    /**
     * Request OAuth2 token interactively. If authorization is needed, Android's
     * AccountManager shows Google's consent dialog directly.
     */
    fun requestDriveAuthToken(
        activity: Activity,
        accountName: String,
        onResult: (token: String?) -> Unit
    ) {
        val am = AccountManager.get(activity)
        val account = findGoogleAccount(am, accountName)
        if (account == null) {
            Timber.tag(TAG).w("No matching Google account found for %s", accountName)
            onResult(null)
            return
        }

        am.getAuthToken(
            account,
            "oauth2:$SCOPE_DRIVE_APPDATA",
            null,
            activity,
            { future ->
                try {
                    val bundle = future.result
                    val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                    Timber.tag(TAG).i("Interactive drive.appdata token retrieved successfully: %s", token != null)
                    onResult(token)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to get interactive drive.appdata auth token")
                    onResult(null)
                }
            },
            null
        )
    }

    /**
     * Gets cached/silently refreshed OAuth2 token in background without UI prompt.
     */
    suspend fun getDriveTokenBackground(context: Context, accountName: String): String? = withContext(Dispatchers.IO) {
        val am = AccountManager.get(context)
        val account = findGoogleAccount(am, accountName) ?: return@withContext null
        try {
            am.blockingGetAuthToken(account, "oauth2:$SCOPE_DRIVE_APPDATA", true)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to get background drive.appdata token for %s", accountName)
            null
        }
    }

    private fun findGoogleAccount(am: AccountManager, accountName: String): Account? {
        return am.getAccountsByType("com.google").find {
            it.name.equals(accountName, ignoreCase = true)
        }
    }

    /**
     * Checks if backup exists in appDataFolder, uploading new file (multipart) or patching existing file.
     */
    suspend fun uploadBackupToAppDataFolder(
        context: Context,
        token: String,
        accountName: String,
        zipBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var currentToken = token
            var existingFileId = findBackupFileId(currentToken)

            // If 401, invalidate and retry once
            if (existingFileId == null && isUnauthorized(currentToken)) {
                AccountManager.get(context).invalidateAuthToken("com.google", currentToken)
                val refreshed = getDriveTokenBackground(context, accountName)
                if (refreshed != null) {
                    currentToken = refreshed
                    existingFileId = findBackupFileId(currentToken)
                }
            }

            if (existingFileId != null) {
                // Update existing file content
                Timber.tag(TAG).i("Updating existing backup file in appDataFolder (fileId=%s)", existingFileId)
                val patchUrl = "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=media"
                val request = Request.Builder()
                    .url(patchUrl)
                    .addHeader("Authorization", "Bearer $currentToken")
                    .patch(zipBytes.toRequestBody("application/zip".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        throw IOException("Drive PATCH failed (${response.code}): $body")
                    }
                    existingFileId
                }
            } else {
                // Create new file in appDataFolder using multipart upload
                Timber.tag(TAG).i("Creating new backup file in appDataFolder")
                val metadata = JSONObject().apply {
                    put("name", BACKUP_FILE_NAME)
                    put("parents", JSONArray().apply { put("appDataFolder") })
                }.toString()

                val multipartBody = MultipartBody.Builder()
                    .setType("multipart/related".toMediaType())
                    .addPart(
                        Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                        metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())
                    )
                    .addPart(
                        Headers.headersOf("Content-Type", "application/zip"),
                        zipBytes.toRequestBody("application/zip".toMediaType())
                    )
                    .build()

                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $currentToken")
                    .post(multipartBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("Drive POST multipart failed (${response.code}): $body")
                    }
                    val json = JSONObject(body)
                    json.getString("id")
                }
            }
        }
    }

    /**
     * Downloads october_backup.zip from appDataFolder if present.
     */
    suspend fun downloadBackupFromAppDataFolder(
        context: Context,
        token: String,
        accountName: String
    ): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            var currentToken = token
            var fileId = findBackupFileId(currentToken)

            if (fileId == null && isUnauthorized(currentToken)) {
                AccountManager.get(context).invalidateAuthToken("com.google", currentToken)
                val refreshed = getDriveTokenBackground(context, accountName)
                if (refreshed != null) {
                    currentToken = refreshed
                    fileId = findBackupFileId(currentToken)
                }
            }

            if (fileId == null) {
                Timber.tag(TAG).i("No backup file found in appDataFolder")
                return@runCatching null
            }

            Timber.tag(TAG).i("Downloading backup file from appDataFolder (fileId=%s)", fileId)
            val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw IOException("Drive download failed (${response.code}): $body")
                }
                response.body?.bytes()
            }
        }
    }

    private fun findBackupFileId(token: String): String? {
        val queryUrl = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D'$BACKUP_FILE_NAME'+and+trashed%3Dfalse&fields=files(id,name)"
        val request = Request.Builder()
            .url(queryUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        files.getJSONObject(0).getString("id")
                    } else null
                } else null
            }
        }.getOrNull()
    }

    private fun isUnauthorized(token: String): Boolean {
        val queryUrl = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1"
        val request = Request.Builder()
            .url(queryUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                response.code == 401
            }
        }.getOrDefault(false)
    }
}
