package com.radiant.webtoon.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Precision
import coil.size.Scale
import com.radiant.webtoon.core.model.Webtoon
import okhttp3.Headers

/**
 * Resolves the matching installed source's headers (Referer/User-Agent) for a cover image,
 * the same way [com.radiant.webtoon.ui.reader.ReaderScreen] resolves headers for page images —
 * by [sourceId] directly, instead of relying on the global Coil interceptor's host-guessing,
 * which fails for sites that serve covers from a different CDN host than their `baseUrl`.
 */
@Composable
internal fun rememberSourceImageHeaders(sourceId: String?): Headers {
    val context = LocalContext.current
    return remember(sourceId) {
        if (sourceId.isNullOrBlank()) return@remember Headers.headersOf()
        val app = context.applicationContext as? com.radiant.webtoon.WebtoonApplication
            ?: return@remember Headers.headersOf()
        val source = app.extensionManager.getSource(sourceId) ?: return@remember Headers.headersOf()
        com.radiant.webtoon.core.image.SourceImageHeaders.from(source)
    }
}

@Composable
internal fun CoverErrorPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun CoverCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.72f),
    contentColor: Color = Color.White,
) {
    if (count <= 0) return
    val label = if (count > 999) "999+" else count.toString()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun UnreadCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    CoverCountBadge(count = count, modifier = modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebtoonThumbnailCard(
    webtoon: Webtoon,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    sourceId: String? = null,
    unreadCount: Int = 0,
    downloadedCount: Int = 0,
) {
    val headers = rememberSourceImageHeaders(sourceId)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        label = "libraryCardScale",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            RadiantAsyncImage(
                url = webtoon.thumbnail,
                contentDescription = webtoon.title,
                modifier = Modifier.fillMaxSize(),
                headers = headers,
                widthDp = 360,
                heightDp = 540,
                precision = Precision.INEXACT,
                scale = Scale.FILL,
                contentScale = ContentScale.Crop,
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
            )
            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(percent = 50),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (unreadCount > 999) "999+" else unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            CoverCountBadge(
                count = downloadedCount,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                containerColor = Color(0xCC2E7D32),
            )
        }
        Text(
            text = webtoon.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun SourceHeader(
    name: String,
    lang: String,
    icon: String,
    onBrowseClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(
                icon = icon,
                contentDescription = name,
                size = 32.dp,
                modifier = Modifier.padding(end = 8.dp)
            )

            Column {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = lang.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        TextButton(onClick = onBrowseClick) {
            Text(text = "BROWSE")
        }
    }
}

@Composable
fun ExtensionIcon(extension: com.radiant.webtoon.core.model.Extension) {
    SourceIcon(
        icon = extension.icon,
        contentDescription = extension.name,
        size = 40.dp
    )
}
