package com.radiant.webtoon.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.size.Precision
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.ui.components.RadiantAsyncImage

/** Pages kept composed above/below the visible viewport. */
private const val PAGES_ABOVE = 1
private const val PAGES_BELOW = 2

@Composable
fun VirtualizedWebtoonColumn(
    pages: List<String>,
    listState: LazyListState,
    headersMap: Map<String, String>,
    screenWidthPx: Int,
    cropBorders: Boolean,
    nextChapter: Chapter?,
    onTap: () -> Unit,
    onNextChapterClick: () -> Unit,
) {
    val density = LocalDensity.current
    val pageHeights = remember { mutableStateMapOf<Int, Int>() }

    val defaultPlaceholderHeightPx = remember(screenWidthPx) {
        (screenWidthPx * 1.5f).toInt()
    }

    LaunchedEffect(cropBorders) {
        pageHeights.clear()
    }

    val renderRange by remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (pages.isEmpty()) {
                IntRange.EMPTY
            } else if (visible.isEmpty()) {
                0..minOf(PAGES_BELOW, pages.lastIndex)
            } else {
                val first = visible.first().index.coerceIn(0, pages.lastIndex)
                val last = visible.last().index.coerceIn(0, pages.lastIndex)
                (first - PAGES_ABOVE).coerceAtLeast(0)..
                    (last + PAGES_BELOW).coerceAtMost(pages.lastIndex)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        itemsIndexed(pages, key = { index, _ -> "page_$index" }) { index, pageUrl ->
            val isInWindow = index in renderRange
            val placeholderHeightPx = pageHeights[index] ?: defaultPlaceholderHeightPx

            if (isInWindow) {
                ReaderPageImage(
                    index = index,
                    pageUrl = pageUrl,
                    headersMap = headersMap,
                    screenWidthPx = screenWidthPx,
                    cropBorders = cropBorders,
                    placeholderHeightPx = placeholderHeightPx,
                    onHeightMeasured = { height ->
                        if (height > 0) {
                            pageHeights[index] = height
                        }
                    },
                )
            } else {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { placeholderHeightPx.toDp() }),
                )
            }
        }

        item(key = "next_chapter") {
            if (nextChapter != null) {
                val chapter = nextChapter
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp, horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OutlinedButton(
                        onClick = onNextChapterClick,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color.White.copy(alpha = 0.5f),
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Default.NavigateNext,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Next: ${chapter.name}")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

@Composable
private fun ReaderPageImage(
    index: Int,
    pageUrl: String,
    headersMap: Map<String, String>,
    screenWidthPx: Int,
    cropBorders: Boolean,
    placeholderHeightPx: Int,
    onHeightMeasured: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val placeholderHeight = with(density) { placeholderHeightPx.toDp() }

    RadiantAsyncImage(
        url = pageUrl,
        contentDescription = "Page ${index + 1}",
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .onGloballyPositioned { coordinates ->
                onHeightMeasured(coordinates.size.height)
            },
        headers = headersMap,
        widthPx = screenWidthPx,
        precision = Precision.INEXACT,
        crossfade = false,
        contentScale = if (cropBorders) ContentScale.FillWidth else ContentScale.Fit,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(placeholderHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    strokeWidth = 2.dp,
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(placeholderHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Failed to load page ${index + 1}",
                    color = Color.Red.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
