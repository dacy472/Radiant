package com.radiant.webtoon.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.ui.components.DownloadManagerIconButton
import com.radiant.webtoon.ui.components.bounceClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAppearanceClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onReaderClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onDownloadQueueClick: () -> Unit,
    onBackupClick: () -> Unit,
    onStorageClick: () -> Unit,
    onTrackingClick: () -> Unit,
    onSecurityClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onStatsClick: () -> Unit = {},
    onUpcomingClick: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    DownloadManagerIconButton(onClick = onDownloadQueueClick)
                },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CollapsibleSettingsCard(
                    title = "General",
                    icon = Icons.Default.Settings,
                    items = listOf(
                        SettingsSubItem("Appearance", "Theme, date and time format", Icons.Default.Palette, onAppearanceClick),
                        SettingsSubItem("Library", "Categories", Icons.Default.LibraryBooks, onLibraryClick)
                    )
                )
            }
            item {
                CollapsibleSettingsCard(
                    title = "Data & Storage",
                    icon = Icons.Default.Storage,
                    items = listOf(
                        SettingsSubItem("Backup & Restore", "Export or import library data", Icons.Default.Backup, onBackupClick),
                        SettingsSubItem("Storage", "Completed downloads by title", Icons.Default.Folder, onStorageClick),
                        SettingsSubItem("Download Queue", "View and manage active downloads", Icons.Default.Download, onDownloadQueueClick)
                    )
                )
            }
            item {
                CollapsibleSettingsCard(
                    title = "Reader & Media",
                    icon = Icons.Default.ChromeReaderMode,
                    items = listOf(
                        SettingsSubItem("Reader", "Reading mode, display, navigation", Icons.Default.MenuBook, onReaderClick),
                        SettingsSubItem("Downloads", "Download location", Icons.Default.FileDownload, onDownloadsClick),
                        SettingsSubItem("Tracking", "AniList and progress sync", Icons.Default.Bookmark, onTrackingClick),
                        SettingsSubItem("Reading stats", "Weekly chapters and library totals", Icons.Default.BarChart, onStatsClick),
                        SettingsSubItem("Upcoming", "Chapters releasing in the next 7 days", Icons.Default.DateRange, onUpcomingClick),
                    )
                )
            }
            item {
                CollapsibleSettingsCard(
                    title = "Advanced & Security",
                    icon = Icons.Default.Security,
                    items = listOf(
                        SettingsSubItem("Security", "App lock, secure screen", Icons.Default.Lock, onSecurityClick),
                        SettingsSubItem("Advanced", "Cache, database, logs", Icons.Default.Code, onAdvancedClick)
                    )
                )
            }
            item {
                CollapsibleSettingsCard(
                    title = "About",
                    icon = Icons.Default.Info,
                    items = listOf(
                        SettingsSubItem("Information", "Version, open source licenses", Icons.Default.Help, onAboutClick)
                    )
                )
            }
        }
    }
}

data class SettingsSubItem(val title: String, val summary: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun CollapsibleSettingsCard(
    title: String,
    icon: ImageVector,
    items: List<SettingsSubItem>
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick { expanded = !expanded }
            .animateContentSize()
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    items.forEach { subItem ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { subItem.onClick() },
                            headlineContent = { Text(subItem.title) },
                            supportingContent = { Text(subItem.summary, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(subItem.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}
