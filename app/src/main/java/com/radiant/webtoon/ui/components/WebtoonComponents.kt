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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Precision
import coil.size.Scale
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.image.SourceImageHeaders
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.source.Source
import okhttp3.Headers

/**
 * Resolves the matching installed source's headers (Referer/User-Agent) for a cover image,
 * the same way [com.radiant.webtoon.ui.reader.ReaderScreen] resolves headers for page images —
 * by [sourceId] directly, instead of relying on the global Coil interceptor's host-guessing,
 * which fails for sites that serve covers from a different CDN host than their `baseUrl`.
 */
@Composable
fun rememberSourceImageHeaders(
    manga: Manga? = null,
    source: Source? = null,
    sourceId: String? = null,
): Headers {
    val context = LocalContext.current
    val effectiveSourceId = sourceId ?: manga?.source ?: source?.id
    return remember(manga, source, effectiveSourceId) {
        val resolvedSource = source ?: effectiveSourceId?.let { id ->
            (context.applicationContext as? com.radiant.webtoon.WebtoonApplication)
                ?.extensionManager
                ?.getSource(id)
        }
        SourceImageHeaders.from(resolvedSource)
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

/** Cover request size shared by grid cards and the details header so the header hits Coil's memory cache. */
const val COVER_REQUEST_WIDTH = 360
const val COVER_REQUEST_HEIGHT = 540

private val CoverScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.45f to Color.Black.copy(alpha = 0.25f),
    1f to Color.Black.copy(alpha = 0.85f),
)

/**
 * Library / browse cover tile. [compact] overlays the title on a bottom scrim (Mihon "compact grid");
 * otherwise the title sits below the cover. [sharedKey] drives the cover → details morph.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebtoonThumbnailCard(
    webtoon: Webtoon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    sourceId: String? = null,
    manga: Manga? = null,
    unreadCount: Int = 0,
    downloadedCount: Int = 0,
    compact: Boolean = false,
    sharedKey: String? = null,
) {
    val coverHeaders = rememberSourceImageHeaders(manga = manga, sourceId = sourceId ?: manga?.source)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = AppMotion.bouncy(),
        label = "libraryCardScale",
    )
    val coverShape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(coverShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .sharedCover(sharedKey)
                .clip(coverShape),
        ) {
            RadiantAsyncImage(
                url = webtoon.thumbnail,
                contentDescription = webtoon.title,
                modifier = Modifier.fillMaxSize(),
                headers = coverHeaders,
                manga = manga,
                sourceId = sourceId ?: manga?.source,
                widthDp = COVER_REQUEST_WIDTH,
                heightDp = COVER_REQUEST_HEIGHT,
                precision = Precision.INEXACT,
                scale = Scale.FILL,
                contentScale = ContentScale.Crop,
                loading = { Box(Modifier.fillMaxSize().shimmerEffect()) },
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = RoundedCornerShape(percent = 50),
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (unreadCount > 999) "999+" else unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                CoverCountBadge(
                    count = downloadedCount,
                    containerColor = Color(0xCC2E7D32),
                )
            }
            if (compact) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.42f)
                        .background(CoverScrim),
                )
                Text(
                    text = webtoon.title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 4f),
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (!compact) {
            Text(
                text = webtoon.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
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
