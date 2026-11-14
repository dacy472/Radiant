package com.radiant.webtoon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Two pages with an amber slit of light. Matches the Radiant mark in the design file. */
@Composable
fun RadiantMark(modifier: Modifier = Modifier) {
    val back = MaterialTheme.colorScheme.surfaceVariant
    val paper = MaterialTheme.colorScheme.onBackground
    val amber = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(28.dp)) {
        val s = size.minDimension
        drawRoundRect(
            color = back,
            topLeft = Offset(s * 0.04f, s * 0.04f),
            size = Size(s * 0.56f, s * 0.56f),
            cornerRadius = CornerRadius(s * 0.12f),
        )
        drawRoundRect(
            color = paper,
            topLeft = Offset(s * 0.28f, s * 0.26f),
            size = Size(s * 0.66f, s * 0.66f),
            cornerRadius = CornerRadius(s * 0.16f),
        )
        val beam = Path().apply {
            moveTo(s * 0.50f, s * 0.26f)
            lineTo(s * 0.66f, s * 0.26f)
            lineTo(s * 0.60f, s * 0.92f)
            lineTo(s * 0.52f, s * 0.92f)
            close()
        }
        drawPath(beam, amber)
    }
}

@Composable
fun RadiantScreenTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadiantMark()
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
