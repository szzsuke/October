/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import timber.log.Timber

class SpotifyOAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        Timber.tag("SpotifyOAuth").i("SpotifyOAuthActivity: uri=%s", uri)

        if (uri != null) {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")
            SpotifyAuth.handleRedirect(
                code = code,
                state = state,
                error = error,
                errorDescription = errorDescription
            )
        } else {
            SpotifyAuth.handleCancel()
        }

        val mainIntent = Intent(this, com.metrolist.music.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
    }
}
