/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.auth

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.innertube.utils.parseCookieString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.URLEncoder

data class GoogleAuthData(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
)

class HeadlessJsInterface {
    var onVisitorDataReceived: ((String?) -> Unit)? = null
    var onDataSyncIdReceived: ((String?) -> Unit)? = null
    var onAuthUserReceived: ((String?) -> Unit)? = null

    @JavascriptInterface
    fun onRetrieveVisitorData(visitorData: String?) {
        onVisitorDataReceived?.invoke(visitorData)
    }

    @JavascriptInterface
    fun onRetrieveDataSyncId(dataSyncId: String?) {
        onDataSyncIdReceived?.invoke(dataSyncId)
    }

    @JavascriptInterface
    fun onRetrieveAuthUser(authUser: String?) {
        onAuthUserReceived?.invoke(authUser)
    }
}

object HeadlessGoogleAuth {
    private const val TAG = "HeadlessGoogleAuth"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun authenticate(
        context: Context,
        email: String,
        timeoutMs: Long = 12_000L,
    ): Result<GoogleAuthData> = withContext(Dispatchers.Main) {
        Timber.tag(TAG).i("Starting headless Google authentication for email=%s", email)

        val jsInterface = HeadlessJsInterface()
        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            visibility = View.GONE
        }

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            }

            webView.addJavascriptInterface(jsInterface, "Android")

            var requiresInteractive = false
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.contains("/challenge/") || url.contains("/signin/v2/challenge") || url.contains("/rejected")) {
                        Timber.tag(TAG).w("Headless auth encountered challenge/interactive page: %s", url)
                        requiresInteractive = true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager.flush()
                    val pageUri = url?.let(android.net.Uri::parse)
                    if (pageUri?.host == "accounts.google.com" &&
                        (pageUri.path?.contains("/signin") == true ||
                         pageUri.path?.contains("/identifier") == true ||
                         pageUri.path?.contains("/challenge") == true ||
                         pageUri.path?.contains("/rejected") == true)
                    ) {
                        Timber.tag(TAG).w("Headless auth landed on interactive Google page: %s", url)
                        requiresInteractive = true
                    }
                }
            }

            val authUrl = "https://accounts.google.com/AccountChooser?Email=" +
                URLEncoder.encode(email, "UTF-8") +
                "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

            webView.loadUrl(authUrl)

            val startTime = System.currentTimeMillis()
            var resultData: GoogleAuthData? = null

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (requiresInteractive) {
                    return@withContext Result.failure(
                        IllegalStateException("Interactive login / 2FA verification required.")
                    )
                }

                val cookie = cookieManager.getCookie("https://music.youtube.com").orEmpty()
                val cookieMap = runCatching { parseCookieString(cookie) }.getOrDefault(emptyMap())

                if ("SAPISID" in cookieMap || "__Secure-3PAPISID" in cookieMap) {
                    val visitorDataDeferred = CompletableDeferred<String?>()
                    val dataSyncIdDeferred = CompletableDeferred<String?>()
                    val authUserDeferred = CompletableDeferred<String?>()

                    jsInterface.onVisitorDataReceived = { visitorData ->
                        if (!visitorDataDeferred.isCompleted) visitorDataDeferred.complete(visitorData)
                    }
                    jsInterface.onDataSyncIdReceived = { dataSyncId ->
                        if (!dataSyncIdDeferred.isCompleted) dataSyncIdDeferred.complete(dataSyncId)
                    }
                    jsInterface.onAuthUserReceived = { authUser ->
                        if (!authUserDeferred.isCompleted) authUserDeferred.complete(authUser)
                    }

                    webView.loadUrl(
                        "javascript:Android.onRetrieveVisitorData(" +
                            "window.yt&&window.yt.config_?window.yt.config_.VISITOR_DATA:null)",
                    )
                    webView.loadUrl(
                        "javascript:Android.onRetrieveDataSyncId(" +
                            "window.yt&&window.yt.config_?window.yt.config_.DATASYNC_ID:null)",
                    )
                    webView.loadUrl(
                        "javascript:Android.onRetrieveAuthUser(" +
                            "window.yt&&window.yt.config_?String(window.yt.config_.SESSION_INDEX||0):'0')",
                    )

                    val pageAuth = withTimeoutOrNull(1_200L) {
                        Triple(
                            visitorDataDeferred.await(),
                            dataSyncIdDeferred.await(),
                            authUserDeferred.await().orEmpty(),
                        )
                    }

                    val visitorData = pageAuth?.first
                    if (!visitorData.isNullOrBlank()) {
                        resultData = GoogleAuthData(
                            cookie = cookie,
                            visitorData = visitorData,
                            dataSyncId = pageAuth.second.orEmpty().substringBefore("||"),
                            authUser = pageAuth.third.filter(Char::isDigit).ifBlank { "0" },
                        )
                        break
                    }
                }

                delay(400L)
            }

            if (resultData != null) {
                Timber.tag(TAG).i("Headless Google auth succeeded for email=%s", email)
                Result.success(resultData)
            } else {
                Timber.tag(TAG).w("Headless Google auth timed out waiting for session cookies")
                Result.failure(IllegalStateException("Headless session acquisition timed out."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Headless Google auth exception")
            Result.failure(e)
        } finally {
            try {
                webView.stopLoading()
                webView.removeJavascriptInterface("Android")
                webView.clearHistory()
                webView.destroy()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error destroying headless WebView")
            }
        }
    }
}
