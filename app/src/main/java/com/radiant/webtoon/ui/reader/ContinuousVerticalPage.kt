package com.radiant.webtoon.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.core.concurrent.AppWorkQueues
import com.radiant.webtoon.utils.ImageSlicer
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import uy.kohesive.injekt.injectLazy

@Composable
fun ContinuousVerticalPage(
    pageUrl: String,
    pageIndex: Int,
    headers: Headers,
    screenWidthPx: Int,
    viewportHeightPx: Int,
    placeholderHeightPx: Int,
    onHeightMeasured: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val networkHelper: NetworkHelper by injectLazy()
    var layouts by remember(pageUrl) { mutableStateOf<List<ReaderSegmentLayout>?>(null) }
    var errorMsg by remember(pageUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(pageUrl, screenWidthPx) {
        layouts = null
        errorMsg = null
        val loaded = AppWorkQueues.withImageFetch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(pageUrl)
                        .headers(headers)
                        .build()
                    val response = networkHelper.client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty response body")
                    body.byteStream().use { stream ->
                        val slices = ImageSlicer.sliceWithMetadata(stream)
                        if (slices.isEmpty()) {
                            error("Failed to decode image")
                        }
                        val bubbles = slices.map { slice ->
                            TextBubbleDetector.detectBottomBubble(slice.bitmap)
                        }
                        VerticalReaderLayout.build(
                            slices = slices,
                            screenWidthPx = screenWidthPx,
                            bubbles = bubbles,
                        )
                    }
                }.getOrElse { throwable ->
                    errorMsg = throwable.message ?: "Failed to load page"
                    null
                }
            }
        }
        layouts = loaded
    }

    DisposableEffect(pageUrl) {
        onDispose {
            layouts?.forEach { layout ->
                if (!layout.slice.bitmap.isRecycled) {
                    layout.slice.bitmap.recycle()
                }
            }
        }
    }

    val totalHeightPx = remember(layouts) {
        layouts?.let { VerticalReaderLayout.totalScrollExtent(it) } ?: placeholderHeightPx
    }

    LaunchedEffect(totalHeightPx) {
        if (totalHeightPx > 0) {
            onHeightMeasured(totalHeightPx)
        }
    }

    when {
        errorMsg != null -> {
            PageMessage(
                text = "Failed to load page ${pageIndex + 1}: $errorMsg",
                color = Color.Red.copy(alpha = 0.7f),
                heightPx = placeholderHeightPx,
            )
        }
        layouts == null -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { placeholderHeightPx.toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(32.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    strokeWidth = 2.dp,
                )
            }
        }
        else -> {
            ContinuousVerticalStack(
                layouts = layouts!!,
                viewportHeightPx = viewportHeightPx,
                onHeightMeasured = onHeightMeasured,
            )
        }
    }
}

@Composable
private fun ContinuousVerticalStack(
    layouts: List<ReaderSegmentLayout>,
    viewportHeightPx: Int,
    onHeightMeasured: (Int) -> Unit,
) {
    val density = LocalDensity.current
    var bubbleClampYPx by remember(layouts) { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                onHeightMeasured(VerticalReaderLayout.totalScrollExtent(layouts))
            },
    ) {
        layouts.forEach { layout ->
            SegmentItem(
                layout = layout,
                bubbleClampYPx = if (layout.bubble != null) bubbleClampYPx else 0f,
                onBubbleBottomInWindow = { bubbleBottomInWindow ->
                    if (layout.bubble != null && viewportHeightPx > 0) {
                        bubbleClampYPx = VerticalReaderLayout.clampBubbleOffset(
                            bubbleBottomInWindowPx = bubbleBottomInWindow,
                            viewportBottomPx = viewportHeightPx.toFloat(),
                            marginPx = with(density) { 12.dp.toPx() },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun SegmentItem(
    layout: ReaderSegmentLayout,
    bubbleClampYPx: Float,
    onBubbleBottomInWindow: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = layout.translationYPx.coerceAtLeast(0f)
    val gapDp = with(density) { gapPx.toDp() }
    val imageHeightDp = with(density) { layout.displayHeightPx.toDp() }
    val containerHeightDp = with(density) {
        (layout.displayHeightPx + gapPx.toInt()).toDp()
    }
    val imageBitmap = remember(layout.slice) { layout.slice.bitmap.asImageBitmap() }
    val bubbleScale = layout.displayHeightPx.toFloat() / layout.slice.bitmap.height.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(containerHeightDp)
            .graphicsLayer {
                translationY = bubbleClampYPx
            }
            .onGloballyPositioned { coordinates ->
                layout.bubble?.let { bubble ->
                    val bubbleBottom = coordinates.boundsInWindow().top +
                        gapPx +
                        bubble.bottomPx * bubbleScale
                    onBubbleBottomInWindow(bubbleBottom)
                }
            },
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeightDp)
                .graphicsLayer {
                    translationY = gapPx
                },
            contentScale = ContentScale.FillWidth,
        )
    }
}

@Composable
private fun PageMessage(
    text: String,
    color: Color,
    heightPx: Int,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
