package com.radiant.webtoon.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.WebtoonApplication
import kotlinx.coroutines.launch

private const val ANILIST_REDIRECT = "webtoonreader://anilist-auth"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as WebtoonApplication }
    val settingsManager = remember { app.settingsManager }
    val trackerService = remember { app.trackerService }
    val scope = rememberCoroutineScope()

    val savedToken by settingsManager.anilistToken.collectAsState(initial = null)
    var tokenInput by remember(savedToken) { mutableStateOf(savedToken.orEmpty()) }
    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracking") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AniList", style = MaterialTheme.typography.titleMedium)
            Text(
                "Link your AniList account to track reading progress. Paste an access token after authorizing, " +
                    "or open the auth URL and copy the token from the redirect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("AniList access token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            val result = trackerService.linkAniListToken(tokenInput)
                            isSaving = false
                            result.fold(
                                onSuccess = {
                                    Toast.makeText(context, "AniList token saved", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = {
                                    Toast.makeText(context, it.message ?: "Failed to save token", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    enabled = !isSaving && tokenInput.isNotBlank()
                ) {
                    Text("Save")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val url = trackerService.getAuthUrl(ANILIST_REDIRECT)
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open auth URL")
                }
            }

            if (!savedToken.isNullOrBlank()) {
                Text(
                    "Token linked",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
