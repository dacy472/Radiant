package com.radiant.webtoon.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.ui.reader.viewer.ReadingMode
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ReaderChromeTop(
    title: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = title.ifBlank { "Chapter" },
            modifier = Modifier.weight(1f),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

@Composable
fun ReaderChromeDock(
    pageIndex: Int,
    pageCount: Int,
    cropBorders: Boolean,
    readingMode: ReadingMode,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onSeek: (Int) -> Unit,
    onSeekFinished: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleCrop: () -> Unit,
    onCycleMode: () -> Unit,
) {
    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    val shown = if (dragging) dragIndex else pageIndex.coerceIn(0, lastIndex)
    val progress = if (pageCount <= 1) 1f else shown.toFloat() / lastIndex.toFloat()
    val amber = MaterialTheme.colorScheme.primary
    val paged = readingMode == ReadingMode.LEFT_TO_RIGHT || readingMode == ReadingMode.RIGHT_TO_LEFT

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)),
                ),
            )
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 8.dp),
    ) {
        if (pageCount > 1) {
            ReaderWaveform(
                progress = progress,
                played = amber,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )
            Text(
                text = "${shown + 1} / $pageCount",
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
            )
            Slider(
                value = shown.toFloat(),
                onValueChange = { value ->
                    dragging = true
                    dragIndex = value.roundToInt().coerceIn(0, lastIndex)
                    onSeek(dragIndex)
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeekFinished()
                },
                valueRange = 0f..lastIndex.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = amber,
                    activeTrackColor = amber,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Previous chapter",
                    tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.38f),
                )
            }
            IconButton(onClick = onToggleCrop) {
                Icon(
                    Icons.Default.Crop,
                    contentDescription = if (cropBorders) "Crop borders on" else "Crop borders off",
                    tint = if (cropBorders) amber else Color.White,
                )
            }
            IconButton(onClick = onCycleMode) {
                Icon(
                    if (paged) Icons.Default.SwapHoriz else Icons.Default.SwapVert,
                    contentDescription = "Reading mode: ${readingMode.label}",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Next chapter",
                    tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.38f),
                )
            }
        }
    }
}

@Composable
fun ReaderPagePill(pageNumber: Int, pageCount: Int) {
    if (pageCount <= 0) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.72f),
    ) {
        Text(
            text = "$pageNumber / $pageCount",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ReaderWaveform(
    progress: Float,
    played: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.widthIn(min = 0.dp)) {
        val bars = 42
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(2f)
        val playedBars = (progress.coerceIn(0f, 1f) * (bars - 1)).roundToInt()
        for (index in 0 until bars) {
            val wave = sin(index * 0.55f) * 0.38f + 0.62f
            val barHeight = size.height * wave
            drawRoundRect(
                color = if (index <= playedBars) played else Color.White.copy(alpha = 0.28f),
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
