package com.radiant.webtoon.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.DownloadStatus

/**
 * Discrete shortcut to the download queue. Pulses gently and shows a slow
 * progress ring while downloads are pending or in progress.
 */
@Composable
fun DownloadManagerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val queueManager = remember {
        (context.applicationContext as WebtoonApplication).downloadQueueManager
    }
    val activeDownloads by queueManager.activeDownloads.collectAsStateWithLifecycle()
    val isQueueActive = activeDownloads.any {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
    }

    IconButton(onClick = onClick, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp),
        ) {
            if (isQueueActive) {
                ActiveDownloadRing()
            }
            if (isQueueActive) {
                PulsingDownloadIcon()
            } else {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download Manager",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadRing() {
    val infiniteTransition = rememberInfiniteTransition(label = "downloadRing")
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
        ),
        label = "ringRotation",
    )

    CircularProgressIndicator(
        progress = { 0.72f },
        modifier = Modifier
            .size(30.dp)
            .graphicsLayer { rotationZ = ringRotation },
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha),
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    )
}

@Composable
private fun PulsingDownloadIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "downloadIconPulse")
    val iconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "iconAlpha",
    )

    Icon(
        imageVector = Icons.Default.Download,
        contentDescription = "Download Manager",
        tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
    )
}
