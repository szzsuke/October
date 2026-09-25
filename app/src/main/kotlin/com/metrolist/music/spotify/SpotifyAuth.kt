/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.spotify

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class SpotifyProfile(
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
)

sealed class SpotifyAuthException(message: String) : Exception(message) {
    class InvalidClient(message: String = "Spotify rejected the connection — make sure the Redirect URI in your Spotify app settings is exactly october://spotify-callback.") : SpotifyAuthException(message)
    class MalformedClientId(message: String = "That doesn't look like a valid Client ID — double check you copied the whole thing.") : SpotifyAuthException(message)
    class UserCancelled(message: String = "User cancelled") : SpotifyAuthException(message)
    class Generic(message: String) : SpotifyAuthException(message)
}

internal data class SpotifyAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

object SpotifyAuth {
    private const val TAG = "SpotifyAuth"
    const val REDIRECT_URI = "october://spotify-callback"
    private const val SCOPES = "user-read-private user-read-email playlist-read-private playlist-read-collaborative user-library-read user-top-read"

    @Volatile
    private var pendingDeferred: CompletableDeferred<SpotifyAuthCallback>? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    internal fun handleRedirect(code: String?, state: String?, error: String?, errorDescription: String?) {
        Timber.tag(TAG).i("handleRedirect: code=${code != null}, error=$error")
        pendingDeferred?.complete(
            SpotifyAuthCallback(
                code = code,
                state = state,
                error = error,
                errorDescription = errorDescription,
            )
        )
    }

    internal fun handleCancel() {
        Timber.tag(TAG).i("handleCancel")
        pendingDeferred?.complete(
            SpotifyAuthCallback(
                code = null,
                state = null,
                error = "access_denied",
                errorDescription = "User cancelled",
            )
        )
    }

    fun isWaitingForCallback(): Boolean {
        val d = pendingDeferred
        return d != null && !d.isCompleted
    }

    suspend fun authorize(
        activity: Activity,
        clientIdRaw: String,
    ): SpotifyProfile = withContext(Dispatchers.IO) {
        val clientId = clientIdRaw.trim()
        if (clientId.isEmpty() || !isValidClientId(clientId)) {
            throw SpotifyAuthException.MalformedClientId()
        }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        val deferred = CompletableDeferred<SpotifyAuthCallback>()
        pendingDeferred = deferred

        val authUrl = StringBuilder("https://accounts.spotify.com/authorize")
            .append("?client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            .append("&response_type=code")
            .append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            .append("&code_challenge_method=S256")
            .append("&code_challenge=").append(URLEncoder.encode(challenge, "UTF-8"))
            .append("&state=").append(URLEncoder.encode(state, "UTF-8"))
            .append("&scope=").append(URLEncoder.encode(SCOPES, "UTF-8"))
            .toString()

        Timber.tag(TAG).i("Launching Spotify Custom Tab authorization...")

        withContext(Dispatchers.Main) {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            try {
                customTabsIntent.launchUrl(activity, authUrl.toUri())
            } catch (e: ActivityNotFoundException) {
                val browserIntent = Intent(Intent.ACTION_VIEW, authUrl.toUri())
                activity.startActivity(browserIntent)
            }
        }

        // Wait up to 5 minutes for user interaction in Chrome Custom Tab
        val callback = try {
            withTimeoutOrNull(300_000L) {
                deferred.await()
            } ?: throw SpotifyAuthException.UserCancelled()
        } catch (e: CancellationException) {
            throw SpotifyAuthException.UserCancelled()
        } finally {
            pendingDeferred = null
        }

        // Check for error in redirect
        if (callback.error != null) {
            val err = callback.error.lowercase()
            val desc = callback.errorDescription.orEmpty().lowercase()
            Timber.tag(TAG).w("Spotify auth error: %s (%s)", callback.error, callback.errorDescription)

            if (err == "access_denied") {
                throw SpotifyAuthException.UserCancelled()
            }
            if (err.contains("invalid_client") || err.contains("redirect_uri_mismatch") ||
                desc.contains("redirect") || desc.contains("invalid_client")
            ) {
                throw SpotifyAuthException.InvalidClient()
            }
            throw SpotifyAuthException.Generic(callback.errorDescription ?: callback.error)
        }

        val code = callback.code ?: throw SpotifyAuthException.UserCancelled()

        // State check
        if (callback.state != null && callback.state != state) {
            Timber.tag(TAG).w("State mismatch during Spotify auth")
            throw SpotifyAuthException.Generic("State verification failed. Please try again.")
        }

        // Exchange code for tokens
        val (accessToken, refreshToken) = exchangeCodeForTokens(
            context = activity,
            clientId = clientId,
            code = code,
            verifier = verifier,
        )

        // Store refresh token securely in Android KeyStore-backed storage
        if (refreshToken.isNotBlank()) {
            SecureTokenStorage.saveRefreshToken(activity, refreshToken)
        }

        // Fetch user profile from /v1/me
        val profile = fetchUserProfile(accessToken)
        Timber.tag(TAG).i("Spotify auth successful for user: %s (%s)", profile.displayName, profile.id)
        profile
    }

    private fun exchangeCodeForTokens(
        context: Context,
        clientId: String,
        code: String,
        verifier: String,
    ): Pair<String, String> {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Token exchange network error")
            throw SpotifyAuthException.Generic("Network error during connection. Please check your internet.")
        }

        val bodyString = response.body?.string().orEmpty()
        Timber.tag(TAG).i("Token response code: %d", response.code)

        if (!response.isSuccessful) {
            Timber.tag(TAG).w("Token exchange failed: %s", bodyString)
            val json = runCatching { JSONObject(bodyString) }.getOrNull()
            val error = json?.optString("error").orEmpty()
            val errorDesc = json?.optString("error_description").orEmpty()

            if (error.contains("invalid_client", ignoreCase = true) ||
                errorDesc.contains("redirect", ignoreCase = true) ||
                errorDesc.contains("client", ignoreCase = true)
            ) {
                throw SpotifyAuthException.InvalidClient()
            }
            throw SpotifyAuthException.Generic(if (errorDesc.isNotBlank()) errorDesc else "Failed to authenticate with Spotify ($error)")
        }

        val json = JSONObject(bodyString)
        val accessToken = json.getString("access_token")
        val refreshToken = json.optString("refresh_token", "")
        return Pair(accessToken, refreshToken)
    }

    private fun fetchUserProfile(accessToken: String): SpotifyProfile {
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Profile fetch network error, using fallback profile")
            return SpotifyProfile(id = "user", displayName = "Spotify User", avatarUrl = null)
        }

        val bodyString = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Timber.tag(TAG).w("Profile fetch failed (%d): %s, using fallback profile", response.code, bodyString)
            return SpotifyProfile(id = "user", displayName = "Spotify User", avatarUrl = null)
        }

        return try {
            val json = JSONObject(bodyString)
            val id = json.getString("id")
            val displayName = json.optString("display_name").ifBlank { id }
            val images = json.optJSONArray("images")
            val avatarUrl = if (images != null && images.length() > 0) {
                images.getJSONObject(0).optString("url")
            } else null

            SpotifyProfile(
                id = id,
                displayName = displayName,
                avatarUrl = avatarUrl,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error parsing Spotify profile JSON, using fallback profile")
            SpotifyProfile(id = "user", displayName = "Spotify User", avatarUrl = null)
        }
    }

    private fun isValidClientId(clientId: String): Boolean {
        // Spotify Client IDs are 32-character hexadecimal strings
        if (clientId.length < 16 || clientId.length > 64) return false
        return clientId.all { it.isLetterOrDigit() }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
