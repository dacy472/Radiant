package com.radiant.webtoon.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.radiant.webtoon.core.model.Webtoon

/**
 * Resolves the matching installed source's headers (Referer/User-Agent) for a cover image,
 * the same way [com.radiant.webtoon.ui.reader.ReaderScreen] resolves headers for page images —
 * by [sourceId] directly, instead of relying on the global Coil interceptor's host-guessing,
 * which fails for sites that serve covers from a different CDN host than their `baseUrl`.
 */
@Composable
internal fun rememberSourceImageHeaders(sourceId: String?): Map<String, String> {
    val context = LocalContext.current
    return remember(sourceId) {
        if (sourceId.isNullOrBlank()) return@remember emptyMap()
        val app = context.applicationContext as? com.radiant.webtoon.WebtoonApplication
            ?: return@remember emptyMap()
        val source = app.extensionManager.getSource(sourceId) ?: return@remember emptyMap()
        val headers = source.getWebViewHeaders().toMutableMap()
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) } && source.baseUrl.isNotBlank()) {
            headers["Referer"] = source.baseUrl.trimEnd('/') + "/"
        }
        headers
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
fun UnreadCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val label = if (count > 999) "999+" else count.toString()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebtoonThumbnailCard(
    webtoon: Webtoon,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    sourceId: String? = null,
    unreadCount: Int = 0,
) {
    val context = LocalContext.current
    val headers = rememberSourceImageHeaders(sourceId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.bounceClick(onClick = onClick)
                }
            )
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .height(180.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(webtoon.thumbnail)
                    .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                    .size(360, 540)
                    .precision(Precision.INEXACT)
                    .scale(Scale.FILL)
                    .crossfade(true)
                    .build(),
                contentDescription = webtoon.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
                success = { SubcomposeAsyncImageContent() },
            )
            UnreadCountBadge(
                count = unreadCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
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
