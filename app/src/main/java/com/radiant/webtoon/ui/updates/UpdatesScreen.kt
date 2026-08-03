package com.radiant.webtoon.ui.updates

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.UpdateItem
import com.radiant.webtoon.ui.components.CoverErrorPlaceholder
import com.radiant.webtoon.ui.components.DownloadManagerIconButton
import com.radiant.webtoon.ui.components.animatedListRow
import com.radiant.webtoon.ui.components.listItemRippleClickable
import com.radiant.webtoon.ui.components.rememberSourceImageHeaders
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel,
    onWebtoonClick: (String, String) -> Unit, // sourceId, webtoonId
    onChapterClick: (String, String, String) -> Unit, // sourceId, webtoonId, chapterId
    onDownloadQueueClick: () -> Unit = {},
) {
    val updates by viewModel.updates.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val groupedUpdates = remember(updates) {
        groupUpdatesByDate(updates)
    }

    val pullToRefreshState = rememberPullToRefreshState()

    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.refreshManually()
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            if (!pullToRefreshState.isRefreshing) pullToRefreshState.startRefresh()
        } else {
            pullToRefreshState.endRefresh()
        }
    }

    val context = LocalContext.current
    val app = context.applicationContext as WebtoonApplication
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                actions = {
                    IconButton(onClick = { viewModel.refreshManually() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh updates")
                    }
                    DownloadManagerIconButton(onClick = onDownloadQueueClick)
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            if (updates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No new chapters yet. Pull down to check your library for updates.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    groupedUpdates.forEach { group ->
                        stickyHeader(key = "header-${group.bucket.name}") {
                            UpdateSectionHeader(label = group.label)
                        }

                        items(
                            group.items,
                            key = { "${it.chapter.manga_id}|${it.chapter.url}" }
                        ) { update ->
                            UpdateItemRow(
                                modifier = Modifier.animatedListRow(),
                                item = update,
                                onClick = {
                                    onChapterClick(update.sourceId, update.chapter.manga_id, update.chapter.url)
                                },
                                onCoverClick = {
                                    onWebtoonClick(update.sourceId, update.chapter.manga_id)
                                },
                                onDownloadClick = {
                                    app.downloadQueueManager.addDownload(
                                        chapterId = update.chapter.url,
                                        sourceId = update.sourceId,
                                        webtoonId = update.chapter.manga_id,
                                        webtoonTitle = update.webtoonTitle,
                                        chapterTitle = update.chapter.name,
                                    )
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 88.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }

            PullToRefreshContainer(
                modifier = Modifier.align(Alignment.TopCenter),
                state = pullToRefreshState,
            )
        }
    }
}

@Composable
private fun UpdateSectionHeader(label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
fun UpdateItemRow(
    item: UpdateItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onCoverClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .listItemRippleClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverHeaders = rememberSourceImageHeaders(item.sourceId)
        val context = LocalContext.current
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.webtoonThumbnail)
                .apply { coverHeaders.forEach { (key, value) -> addHeader(key, value) } }
                .crossfade(true)
                .build(),
            contentDescription = item.webtoonTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 52.dp, height = 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .listItemRippleClickable(onClick = onCoverClick),
            error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
            success = { SubcomposeAsyncImageContent() },
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.webtoonTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (item.chapter.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = formatChapterNumber(item.chapter),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.chapter.chapter_number >= 0 && item.chapter.name.isNotBlank()) {
                Text(
                    text = item.chapter.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onDownloadClick) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download chapter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal enum class UpdateDateBucket(val label: String, val sortOrder: Int) {
    TODAY("Today", 0),
    YESTERDAY("Yesterday", 1),
    LAST_WEEK("Last Week", 2),
    OLDER("Older", 3),
}

/**
 * Groups chapter updates into Today → Yesterday → Last Week → Older buckets
 * (newest first within each section).
 */
internal fun groupUpdatesByDate(updates: List<UpdateItem>): List<DateUpdateGroup> {
    if (updates.isEmpty()) return emptyList()

    val todayStart = startOfDay(System.currentTimeMillis())
    val yesterdayStart = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }.let { startOfDay(it.timeInMillis) }
    val lastWeekStart = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -7)
    }.let { startOfDay(it.timeInMillis) }

    return updates
        .groupBy { item ->
            resolveUpdateDateBucket(
                dayStart = startOfDay(item.chapter.date_fetch),
                todayStart = todayStart,
                yesterdayStart = yesterdayStart,
                lastWeekStart = lastWeekStart,
            )
        }
        .toList()
        .sortedBy { (bucket, _) -> bucket.sortOrder }
        .map { (bucket, bucketItems) ->
            DateUpdateGroup(
                bucket = bucket,
                label = bucket.label,
                items = bucketItems.sortedByDescending { it.chapter.date_fetch },
            )
        }
}

internal fun resolveUpdateDateBucket(
    dayStart: Long,
    todayStart: Long,
    yesterdayStart: Long,
    lastWeekStart: Long,
): UpdateDateBucket = when {
    dayStart >= todayStart -> UpdateDateBucket.TODAY
    dayStart >= yesterdayStart -> UpdateDateBucket.YESTERDAY
    dayStart >= lastWeekStart -> UpdateDateBucket.LAST_WEEK
    else -> UpdateDateBucket.OLDER
}

internal data class DateUpdateGroup(
    val bucket: UpdateDateBucket,
    val label: String,
    val items: List<UpdateItem>,
)

private fun formatChapterNumber(chapter: Chapter): String {
    val number = chapter.chapter_number
    if (number < 0) return chapter.name.ifBlank { "Chapter" }
    val formatted = if (number == number.toLong().toFloat()) {
        number.toLong().toString()
    } else {
        number.toString()
    }
    return "Ch. $formatted"
}

private fun startOfDay(timestamp: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
