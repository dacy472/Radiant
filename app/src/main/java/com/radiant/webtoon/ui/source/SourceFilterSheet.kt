package com.radiant.webtoon.ui.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Minimal Compose UI for Tachiyomi FilterList (Select / CheckBox / Text / TriState / Header).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFilterSheet(
    filters: FilterList,
    onDismiss: () -> Unit,
    onApply: (FilterList) -> Unit,
) {
    val working = remember(filters) { filters }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            working.forEach { filter ->
                when (filter) {
                    is Filter.Header -> {
                        Text(
                            filter.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    is Filter.Separator -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    is Filter.CheckBox -> {
                        var checked by remember { mutableStateOf(filter.state) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(filter.name)
                            Switch(checked = checked, onCheckedChange = {
                                checked = it
                                filter.state = it
                            })
                        }
                    }
                    is Filter.Text -> {
                        var text by remember { mutableStateOf(filter.state) }
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                filter.state = it
                            },
                            label = { Text(filter.name) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true
                        )
                    }
                    is Filter.Select<*> -> {
                        var expanded by remember { mutableStateOf(false) }
                        var selected by remember { mutableIntStateOf(filter.state) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = filter.values.getOrNull(selected)?.toString() ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(filter.name) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                filter.values.forEachIndexed { index, value ->
                                    DropdownMenuItem(
                                        text = { Text(value.toString()) },
                                        onClick = {
                                            selected = index
                                            filter.state = index
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    is Filter.TriState -> {
                        var state by remember { mutableIntStateOf(filter.state) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(filter.name)
                            FilterChip(
                                selected = state != Filter.TriState.STATE_IGNORE,
                                onClick = {
                                    state = when (state) {
                                        Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
                                        Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
                                        else -> Filter.TriState.STATE_IGNORE
                                    }
                                    filter.state = state
                                },
                                label = {
                                    Text(
                                        when (state) {
                                            Filter.TriState.STATE_INCLUDE -> "Include"
                                            Filter.TriState.STATE_EXCLUDE -> "Exclude"
                                            else -> "Ignore"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    else -> {
                        Text(filter.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onApply(working); onDismiss() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
