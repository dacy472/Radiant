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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
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
import androidx.compose.ui.unit.sp
import com.radiant.webtoon.ui.components.CoverErrorPlaceholder
import com.radiant.webtoon.ui.components.DownloadManagerIconButton
import com.radiant.webtoon.ui.components.RadiantAsyncImage
import com.radiant.webtoon.ui.components.animatedListRow
import com.radiant.webtoon.ui.components.listItemRippleClickable
import com.radiant.webtoon.ui.components.rememberSourceImageHeaders
import coil.size.Scale
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.UpdateItem
import com.radiant.webtoon.core.util.ChapterDates
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val context = LocalContext.current
    val app = context.applicationContext as WebtoonApplication
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                scrollBehavior = scrollBehavior,
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
                .pullToRefresh(
                    isRefreshing = isRefreshing,
                    state = pullToRefreshState,
                    onRefresh = { viewModel.refreshManually() },
                )
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
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    groupedUpdates.forEach { group ->
                        stickyHeader(key = "header-${group.dayStart}") {
                            UpdateSectionHeader(label = group.label)
                        }

                        items(
                            group.items,
                            key = { "${it.sourceId}|${it.chapter.manga_id}|${it.chapter.url}" }
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

            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun UpdateSectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun UpdateItemRow(
    item: UpdateItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onCoverClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
) {
    val coverHeaders = rememberSourceImageHeaders(sourceId = item.sourceId)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .listItemRippleClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadiantAsyncImage(
            url = item.webtoonThumbnail.orEmpty(),
            contentDescription = item.webtoonTitle,
            headers = coverHeaders,
            sourceId = item.sourceId,
            scale = Scale.FILL,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 52.dp, height = 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .listItemRippleClickable(onClick = onCoverClick),
            error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
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

/**
 * Groups chapter updates by calendar day (Mihon-style): Today, Yesterday,
 * then a specific date such as "August 16, 2026" — newest days first.
 */
internal fun groupUpdatesByDate(
    updates: List<UpdateItem>,
    nowMillis: Long = System.currentTimeMillis(),
): List<DateUpdateGroup> {
    if (updates.isEmpty()) return emptyList()

    val todayStart = startOfDay(nowMillis)
    val yesterdayStart = startOfDay(todayStart - 1L)

    return updates
        .groupBy { item -> startOfDay(item.chapter.date_fetch) }
        .toList()
        .sortedByDescending { (dayStart, _) -> dayStart }
        .map { (dayStart, dayItems) ->
            DateUpdateGroup(
                dayStart = dayStart,
                label = formatUpdateDayLabel(dayStart, todayStart, yesterdayStart),
                items = dayItems.sortedByDescending { it.chapter.date_fetch },
            )
        }
}

internal fun formatUpdateDayLabel(
    dayStart: Long,
    todayStart: Long,
    yesterdayStart: Long,
): String = when {
    dayStart >= todayStart -> "Today"
    dayStart >= yesterdayStart -> "Yesterday"
    else -> ChapterDates.formatExact(dayStart, pattern = "MMMM d, yyyy") ?: "Older"
}

internal data class DateUpdateGroup(
    val dayStart: Long,
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
