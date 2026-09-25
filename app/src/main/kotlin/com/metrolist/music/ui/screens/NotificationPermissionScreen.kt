/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R

@Composable
fun NotificationPermissionScreen(
    onContinue: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onContinue()
    }

    BackHandler(onBack = onContinue)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top Back/Dismiss Button
        IconButton(
            onClick = onContinue,
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
        ) {
            Spacer(Modifier.weight(0.9f))

            // Stacked Notification Cards Illustration
            StackedNotificationCardIllustration()

            Spacer(Modifier.height(48.dp))

            // Headline
            Text(
                text = "Turn on push notifications.",
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp
            )

            Spacer(Modifier.height(14.dp))

            // Subtitle
            Text(
                text = "Get updates about new music, special offers, events and more.",
                color = Color(0xFFA7A7A7),
                fontSize = 14.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(40.dp))

            // Primary CTA: Turn on notifications (White Pill)
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onContinue()
                    }
                },
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Turn on notifications",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(18.dp))

            // Secondary CTA: Not now
            Text(
                text = "Not now",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onContinue)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(Modifier.weight(1f))

            // Footer Note
            Text(
                text = "Manage your notification categories in Setting at any time.",
                color = Color(0xFF7A7A7A),
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun StackedNotificationCardIllustration() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        // Back Card (Card 3)
        Box(
            modifier = Modifier
                .offset(x = 18.dp, y = (-10).dp)
                .size(width = 240.dp, height = 64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF333333))
        )

        // Middle Card (Card 2)
        Box(
            modifier = Modifier
                .offset(x = 9.dp, y = (-5).dp)
                .size(width = 240.dp, height = 64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF777777))
        )

        // Front Card (Card 1)
        Box(
            modifier = Modifier
                .size(width = 244.dp, height = 66.dp)
                .shadow(12.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.october_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "OCTOBER",
                        color = Color(0xFF6A6A6A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Skeleton line 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(5.5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFD6D6D6))
                )

                Spacer(Modifier.height(4.dp))

                // Skeleton line 2
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(5.5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFE5E5E5))
                )
            }
        }
    }
}
