package com.radiant.webtoon.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.ui.reader.viewer.ReadingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    viewModel: ReaderViewModel,
    onDismiss: () -> Unit
) {
    val readingMode by viewModel.readingMode.collectAsState()
    val backgroundColor by viewModel.backgroundColor.collectAsState()
    val cropBorders by viewModel.cropBorders.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
        ) {
            Text(
                text = "Reader Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // ── Reading Mode ──
            Text(
                text = "Reading Mode",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            
            ReadingMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = readingMode == mode,
                            onClick = { viewModel.setReadingMode(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = readingMode == mode, onClick = null)
                    Text(mode.label, modifier = Modifier.padding(start = 12.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Background Color ──
            Text(
                text = "Background Color",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colors = listOf(Color.White, Color(0xFF242424), Color.Black)
                colors.forEach { color ->
                    val isSelected = backgroundColor == color
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .clickable { viewModel.setBackgroundColor(color) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Crop Borders ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setCropBorders(!cropBorders) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Crop image borders",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Strips uniform-colour edges while decoding",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = cropBorders,
                    onCheckedChange = { viewModel.setCropBorders(it) }
                )
            }
        }
    }
}
