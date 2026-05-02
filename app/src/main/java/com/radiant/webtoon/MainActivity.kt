package com.radiant.webtoon

import android.os.Bundle
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.radiant.webtoon.core.receiver.ApkInstallReceiver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.net.URLEncoder
import java.net.URLDecoder
import com.radiant.webtoon.ui.reader.ReaderScreen
import com.radiant.webtoon.ui.search.SearchScreen
import com.radiant.webtoon.ui.theme.WebtoonTheme
import com.radiant.webtoon.ui.updates.UpdatesScreen
import com.radiant.webtoon.ui.updates.UpdatesViewModel
import com.radiant.webtoon.ui.updates.UpdatesViewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme


sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Library : Screen("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks)
    object Updates : Screen("updates", "Updates", Icons.Filled.Update)
    object History : Screen("history", "History", Icons.AutoMirrored.Filled.List)
    object Browse : Screen("browse", "Browse", Icons.Filled.Search)
    object Queue : Screen("downloads_queue", "Queue", Icons.Filled.Archive)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    private val installReceiver = ApkInstallReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }

        setContent {
            val app = remember { applicationContext as WebtoonApplication }
            val themeMode by app.settingsManager.themeMode.collectAsState(initial = "system")
            WebtoonTheme(themeMode = themeMode) {
                WebtoonApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installReceiver)
    }
}

/** URL-encode a string for safe use in Navigation routes. */
private fun encodeNav(value: String): String = URLEncoder.encode(value, "UTF-8")
/** URL-decode a Navigation argument back to its original form. */
private fun decodeNav(value: String): String = URLDecoder.decode(value, "UTF-8")

@Composable
fun WebtoonApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { context.applicationContext as WebtoonApplication }
    val repository = remember { app.repository }
    val extensionManager = remember { app.extensionManager }

    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val items = listOf(
        Screen.Library,
        Screen.Updates,
        Screen.History,
        Screen.Browse,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            if (currentDestination?.route in items.map { it.route }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter
                ) {
                    NavigationBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        tonalElevation = 8.dp
                    ) {
                        items.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.title) },
                                label = { Text(screen.title, style = MaterialTheme.typography.labelSmall) },
                                selected = currentDestination?.route == screen.route,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, // Forces visible gray in light mode
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) },
            exitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) }
        ) {
            composable(Screen.Library.route) {
                com.radiant.webtoon.ui.library.LibraryScreen(
                    repository = repository,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onUpdatesClick = {
                        navController.navigate("updates")
                    }
                )
            }
            composable(
                route = "source/{sourceId}",
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val sourceId = backStackEntry.arguments?.getString("sourceId") ?: ""
                com.radiant.webtoon.ui.source.SourceBrowseScreen(
                    extensionManager = extensionManager,
                    sourceId = sourceId,
                    onWebtoonClick = { sid, wid ->
                        navController.navigate("detail/${encodeNav(sid)}/${encodeNav(wid)}")
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Browse.route) {
                com.radiant.webtoon.ui.browse.BrowseScreen(
                    extensionManager = extensionManager,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onBrowseClick = { sourceId ->
                        navController.navigate("source/$sourceId")
                    },
                    onSearchClick = {
                        navController.navigate("global_search")
                    },
                    onWebViewClick = { url ->
                        navController.navigate("webview/${encodeNav(url)}")
                    },
                    onMigrateSourceClick = { sourceId ->
                        navController.navigate("migrate_list/${encodeNav(sourceId)}")
                    }
                )
            }

            composable(
                route = "migrate_list/{sourceId}",
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                com.radiant.webtoon.ui.browse.MigrationListScreen(
                    sourceId = sourceId,
                    repository = repository,
                    onBackClick = { navController.popBackStack() },
                    onMangaClick = { mangaId, title ->
                        navController.navigate("global_search?initialQuery=${encodeNav(title)}&migrateFromMangaId=${encodeNav(mangaId)}")
                    }
                )
            }

            composable(
                route = "webview/{url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedUrl = backStackEntry.arguments?.getString("url") ?: ""
                val url = decodeNav(encodedUrl)
                com.radiant.webtoon.ui.webview.WebViewScreen(
                    url = url,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.History.route) {
                com.radiant.webtoon.ui.history.HistoryScreen(
                    repository = repository,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onChapterClick = { sourceId, webtoonId, chapterId, chapterTitle ->
                        navController.navigate("reader/${encodeNav(sourceId)}/${encodeNav(webtoonId)}/${encodeNav(chapterId)}?chapterTitle=${encodeNav(chapterTitle)}")
                    }
                )
            }

            composable(
                route = "global_search?initialQuery={initialQuery}&migrateFromMangaId={migrateFromMangaId}",
                arguments = listOf(
                    navArgument("initialQuery") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("migrateFromMangaId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val initialQuery = backStackEntry.arguments?.getString("initialQuery")?.let { decodeNav(it) }
                val migrateFromMangaId = backStackEntry.arguments?.getString("migrateFromMangaId")?.let { decodeNav(it) }
                
                com.radiant.webtoon.ui.search.SearchScreen(
                    repository = repository,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onBrowseClick = { sourceId ->
                        navController.navigate("source/$sourceId")
                    },
                    initialQuery = initialQuery,
                    migrateFromMangaId = migrateFromMangaId,
                    onMigrationComplete = {
                        // After migration, go back to Library or Browse
                        navController.popBackStack("browse", inclusive = false)
                    }
                )
            }
            composable(
                route = "detail/{sourceId}/{webtoonId}",
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("webtoonId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                val webtoonId = decodeNav(backStackEntry.arguments?.getString("webtoonId") ?: "")
                com.radiant.webtoon.ui.detail.WebtoonDetailScreen(
                    repository = repository,
                    sourceId = sourceId,
                    webtoonId = webtoonId,
                    onBackClick = { navController.popBackStack() },
                    onChapterClick = { chapterId, chapterTitle ->
                        navController.navigate("reader/${encodeNav(sourceId)}/${encodeNav(webtoonId)}/${encodeNav(chapterId)}?chapterTitle=${encodeNav(chapterTitle)}")
                    },
                    onWebViewClick = { url ->
                        navController.navigate("webview/${encodeNav(url)}")
                    }
                )
            }
            // Settings and Queue below
            composable(Screen.Settings.route) {
                com.radiant.webtoon.ui.settings.SettingsScreen(
                    onAppearanceClick = { navController.navigate("settings/appearance") },
                    onLibraryClick = { navController.navigate("settings/library") },
                    onReaderClick = { navController.navigate("settings/reader") },
                    onDownloadsClick = { navController.navigate("settings/downloads") },
                    onDownloadQueueClick = { navController.navigate("downloads_queue") },
                    onSecurityClick = { navController.navigate("settings/security") },
                    onAboutClick = { navController.navigate("settings/about") },
                    onAdvancedClick = { navController.navigate("settings/advanced") }
                )
            }
            
            composable("downloads_queue") {
                com.radiant.webtoon.ui.downloads.DownloadQueueScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }
            
            composable(Screen.Updates.route) {
                val updatesViewModel: com.radiant.webtoon.ui.updates.UpdatesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = com.radiant.webtoon.ui.updates.UpdatesViewModelFactory(context.applicationContext as WebtoonApplication, repository)
                )
                com.radiant.webtoon.ui.updates.UpdatesScreen(
                    viewModel = updatesViewModel,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onChapterClick = { sourceId, webtoonId, chapterId ->
                        navController.navigate("reader/${encodeNav(sourceId)}/${encodeNav(webtoonId)}/${encodeNav(chapterId)}?chapterTitle=Chapter")
                    }
                )
            }

            composable("settings/appearance") { com.radiant.webtoon.ui.settings.AppearanceSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/library") { com.radiant.webtoon.ui.settings.LibrarySettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/reader") { com.radiant.webtoon.ui.settings.ReaderSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/downloads") { com.radiant.webtoon.ui.settings.DownloadsSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/security") { com.radiant.webtoon.ui.settings.SecuritySettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/about") { com.radiant.webtoon.ui.settings.AboutSettingsScreen(onBackClick = { navController.popBackStack() }) }
            
            composable("settings/advanced") {
                com.radiant.webtoon.ui.settings.AdvancedSettingsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(
                route = "reader/{sourceId}/{webtoonId}/{chapterId}?chapterTitle={chapterTitle}",
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("webtoonId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                    navArgument("chapterTitle") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                val webtoonId = decodeNav(backStackEntry.arguments?.getString("webtoonId") ?: "")
                val chapterId = decodeNav(backStackEntry.arguments?.getString("chapterId") ?: "")
                val chapterTitle = decodeNav(backStackEntry.arguments?.getString("chapterTitle") ?: "")
                com.radiant.webtoon.ui.reader.ReaderScreen(
                    repository = repository,
                    sourceId = sourceId,
                    webtoonId = webtoonId,
                    chapterId = chapterId,
                    chapterTitle = chapterTitle.ifEmpty { "Chapter $chapterId" },
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
