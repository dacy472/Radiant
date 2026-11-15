package com.radiant.webtoon

import android.os.Bundle
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.radiant.webtoon.core.lock.AppLockAuthenticator
import com.radiant.webtoon.core.lock.AppLockPolicy
import com.radiant.webtoon.core.lock.AppLockState
import com.radiant.webtoon.core.receiver.ApkInstallReceiver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import com.radiant.webtoon.ui.components.LocalSharedTransitionScope
import com.radiant.webtoon.ui.components.ProvideNavAnimatedScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.radiant.webtoon.ui.components.AppMotion
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.net.URLEncoder
import java.net.URLDecoder
import com.radiant.webtoon.ui.reader.ReaderActivity
import com.radiant.webtoon.ui.search.SearchScreen
import com.radiant.webtoon.ui.theme.WebtoonTheme
import com.radiant.webtoon.ui.updates.UpdatesScreen
import com.radiant.webtoon.ui.updates.UpdatesViewModel
import com.radiant.webtoon.ui.updates.UpdatesViewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle


sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Library : Screen("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks)
    object Updates : Screen("updates", "Updates", Icons.Filled.Update)
    object History : Screen("history", "History", Icons.AutoMirrored.Filled.List)
    object Browse : Screen("browse", "Browse", Icons.Filled.Search)
    object Queue : Screen("downloads_queue", "Queue", Icons.Filled.Archive)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

class MainActivity : FragmentActivity() {
    private val installReceiver = ApkInstallReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }

        setContent {
            val app = remember { applicationContext as WebtoonApplication }
            val themeMode by app.settingsManager.themeMode.collectAsState(initial = "system")
            val flagSecure by app.settingsManager.flagSecure.collectAsState(initial = false)
            val lockEnabled by produceState<Boolean?>(initialValue = null, app) {
                app.settingsManager.requireBiometrics.collect { value = it }
            }

            DisposableEffect(flagSecure) {
                if (flagSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            WebtoonTheme(themeMode = themeMode) {
                val dbError = remember {
                    runCatching { app.database.openHelper.writableDatabase }.exceptionOrNull()
                }
                if (dbError != null) {
                    DatabaseUpgradeErrorScreen(dbError)
                } else {
                    BiometricLockGate(
                        lockEnabled = lockEnabled,
                        activity = this@MainActivity
                    ) {
                        WebtoonApp()
                    }
                }
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
private fun DatabaseUpgradeErrorScreen(error: Throwable) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Library database needs attention",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error.message
                    ?: "This update cannot migrate the existing library. The original database file was left unchanged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun isReaderHandoff(route: String?): Boolean = route?.startsWith("reader/") == true

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun WebtoonApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { context.applicationContext as WebtoonApplication }
    val repository = remember { app.repository }
    val extensionManager = remember { app.extensionManager }
    val availableExtensionUpdates by extensionManager.availableUpdates.collectAsStateWithLifecycle()

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

    val showBottomBar = currentDestination?.route in items.map { it.route }

    // Each destination owns its top bar and status-bar inset; the shell only reserves the
    // bottom navigation bar, otherwise nested Scaffolds apply the status-bar inset twice.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(AppMotion.spatial()) { it } + fadeIn(AppMotion.press()),
                exit = slideOutVertically(AppMotion.spatial()) { it } + fadeOut(AppMotion.press()),
            ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        items.forEach { screen ->
                            val selected = currentDestination?.route == screen.route
                            val iconScale by animateFloatAsState(
                                targetValue = if (selected) 1.08f else 1f,
                                animationSpec = AppMotion.bouncy(),
                                label = "navIconScale",
                            )
                            val tickWidth by animateDpAsState(
                                targetValue = if (selected) 16.dp else 0.dp,
                                animationSpec = AppMotion.spatial(),
                                label = "navTick",
                            )
                            NavigationBarItem(
                                icon = {
                                    val iconModifier = Modifier.graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                                    val showUpdateBadge = screen == Screen.Settings && availableExtensionUpdates.isNotEmpty()
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            Modifier
                                                .padding(bottom = 4.dp)
                                                .height(3.dp)
                                                .width(tickWidth)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                        if (showUpdateBadge) {
                                            BadgedBox(
                                                badge = {
                                                    Badge {
                                                        Text(
                                                            text = availableExtensionUpdates.size.toString(),
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    screen.icon,
                                                    contentDescription = screen.title,
                                                    modifier = iconModifier,
                                                )
                                            }
                                        } else {
                                            Icon(
                                                screen.icon,
                                                contentDescription = screen.title,
                                                modifier = iconModifier,
                                            )
                                        }
                                    }
                                },
                                label = { Text(screen.title, style = MaterialTheme.typography.labelSmall) },
                                selected = selected,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
    ) { innerPadding ->
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = { fadeIn(tween(220, delayMillis = 70)) },
            exitTransition = {
                if (isReaderHandoff(targetState.destination.route)) ExitTransition.None else fadeOut(tween(90))
            },
            popEnterTransition = {
                if (isReaderHandoff(initialState.destination.route)) EnterTransition.None else fadeIn(tween(220, delayMillis = 70))
            },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable(Screen.Library.route) {
                ProvideNavAnimatedScope(this) {
                com.radiant.webtoon.ui.library.LibraryScreen(
                    repository = repository,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onUpdatesClick = {
                        navController.navigate("updates")
                    },
                    onDownloadQueueClick = { navController.navigate("downloads_queue") },
                )
                }
            }
            composable(
                route = "source/{sourceId}",
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                ProvideNavAnimatedScope(this) {
                com.radiant.webtoon.ui.source.SourceBrowseScreen(
                    extensionManager = extensionManager,
                    sourceId = sourceId,
                    onWebtoonClick = { sid, wid ->
                        navController.navigate("detail/${encodeNav(sid)}/${encodeNav(wid)}")
                    },
                    onBackClick = { navController.popBackStack() },
                    onWebViewClick = { url ->
                        navController.navigate("webview?url=${encodeNav(url)}&sourceId=${encodeNav(sourceId)}")
                    },
                )
                }
            }

            composable(Screen.Browse.route) {
                ProvideNavAnimatedScope(this) {
                com.radiant.webtoon.ui.browse.BrowseScreen(
                    extensionManager = extensionManager,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onBrowseClick = { sourceId ->
                        navController.navigate("source/${encodeNav(sourceId)}")
                    },
                    onSearchClick = {
                        navController.navigate("global_search")
                    },
                    onWebViewClick = { url ->
                        navController.navigate("webview?url=${encodeNav(url)}")
                    },
                    onMigrateSourceClick = { sourceId ->
                        navController.navigate("migrate_list/${encodeNav(sourceId)}")
                    },
                    onDownloadQueueClick = { navController.navigate("downloads_queue") },
                    onSourceSettingsClick = { sourceId ->
                        navController.navigate("source_settings/${encodeNav(sourceId)}")
                    },
                )
                }
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
                        navController.navigate(
                            "global_search?initialQuery=${encodeNav(title)}" +
                                "&migrateFromMangaId=${encodeNav(mangaId)}" +
                                "&migrateFromSourceId=${encodeNav(sourceId)}"
                        )
                    }
                )
            }

            composable(
                route = "webview?url={url}&sourceId={sourceId}",
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("sourceId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val url = decodeNav(backStackEntry.arguments?.getString("url") ?: "")
                val webSourceId = backStackEntry.arguments?.getString("sourceId")?.let { decodeNav(it) }
                val headers = remember(webSourceId) {
                    webSourceId
                        ?.let { extensionManager.getSource(it)?.getWebViewHeaders() }
                        .orEmpty()
                }
                com.radiant.webtoon.ui.webview.WebViewScreen(
                    url = url,
                    headers = headers,
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
                    },
                    onDownloadQueueClick = { navController.navigate("downloads_queue") },
                )
            }

            composable("global_search") {
                ProvideNavAnimatedScope(this) {
                com.radiant.webtoon.ui.search.SearchScreen(
                    repository = repository,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onBrowseClick = { sourceId ->
                        navController.navigate("source/${encodeNav(sourceId)}")
                    },
                    onBackClick = { navController.popBackStack() },
                    onWebViewClick = { sourceId, url ->
                        navController.navigate("webview?url=${encodeNav(url)}&sourceId=${encodeNav(sourceId)}")
                    },
                )
                }
            }

            composable(
                route = "global_search?initialQuery={initialQuery}&migrateFromMangaId={migrateFromMangaId}&migrateFromSourceId={migrateFromSourceId}",
                arguments = listOf(
                    navArgument("initialQuery") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("migrateFromMangaId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("migrateFromSourceId") { type = NavType.StringType; nullable = true; defaultValue = null },
                )
            ) { backStackEntry ->
                val initialQuery = backStackEntry.arguments?.getString("initialQuery")?.let { decodeNav(it) }
                val migrateFromMangaId = backStackEntry.arguments?.getString("migrateFromMangaId")?.let { decodeNav(it) }
                val migrateFromSourceId = backStackEntry.arguments?.getString("migrateFromSourceId")?.let { decodeNav(it) }

                ProvideNavAnimatedScope(this) {
                com.radiant.webtoon.ui.search.SearchScreen(
                    repository = repository,
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                    onBrowseClick = { sourceId ->
                        navController.navigate("source/${encodeNav(sourceId)}")
                    },
                    onBackClick = { navController.popBackStack() },
                    onWebViewClick = { sourceId, url ->
                        navController.navigate("webview?url=${encodeNav(url)}&sourceId=${encodeNav(sourceId)}")
                    },
                    initialQuery = initialQuery,
                    migrateFromMangaId = migrateFromMangaId,
                    migrateFromSourceId = migrateFromSourceId,
                    onMigrationComplete = { newSourceId, newWebtoonId ->
                        navController.navigate("detail/${encodeNav(newSourceId)}/${encodeNav(newWebtoonId)}") {
                            popUpTo(Screen.Browse.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
                }
            }
            composable(
                route = "detail/{sourceId}/{webtoonId}",
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("webtoonId") { type = NavType.StringType }
                ),
                enterTransition = { fadeIn(tween(300)) + slideInVertically(AppMotion.spatial()) { it / 12 } },
                exitTransition = {
                    if (isReaderHandoff(targetState.destination.route)) ExitTransition.None else fadeOut(tween(120))
                },
                popEnterTransition = {
                    if (isReaderHandoff(initialState.destination.route)) EnterTransition.None else fadeIn(tween(220))
                },
                popExitTransition = { fadeOut(tween(200)) + slideOutVertically(AppMotion.spatial()) { it / 12 } },
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                val webtoonId = decodeNav(backStackEntry.arguments?.getString("webtoonId") ?: "")
                ProvideNavAnimatedScope(this) {
                com.radiant.webtoon.ui.detail.WebtoonDetailScreen(
                    repository = repository,
                    sourceId = sourceId,
                    webtoonId = webtoonId,
                    onBackClick = { navController.popBackStack() },
                    onChapterClick = { chapterId, chapterTitle ->
                        navController.navigate("reader/${encodeNav(sourceId)}/${encodeNav(webtoonId)}/${encodeNav(chapterId)}?chapterTitle=${encodeNav(chapterTitle)}")
                    },
                    onWebViewClick = { url ->
                        navController.navigate("webview?url=${encodeNav(url)}&sourceId=${encodeNav(sourceId)}")
                    },
                    onMigrateClick = { title ->
                        navController.navigate(
                            "global_search?initialQuery=${encodeNav(title)}" +
                                "&migrateFromMangaId=${encodeNav(webtoonId)}" +
                                "&migrateFromSourceId=${encodeNav(sourceId)}"
                        )
                    },
                )
                }
            }
            // Settings and Queue below
            composable(Screen.Settings.route) {
                com.radiant.webtoon.ui.settings.SettingsScreen(
                    onAppearanceClick = { navController.navigate("settings/appearance") },
                    onLibraryClick = { navController.navigate("settings/library") },
                    onReaderClick = { navController.navigate("settings/reader") },
                    onDownloadsClick = { navController.navigate("settings/downloads") },
                    onDownloadQueueClick = { navController.navigate("downloads_queue") },
                    onBackupClick = { navController.navigate("settings/backup") },
                    onStorageClick = { navController.navigate("settings/storage") },
                    onTrackingClick = { navController.navigate("settings/tracking") },
                    onSecurityClick = { navController.navigate("settings/security") },
                    onAboutClick = { navController.navigate("settings/about") },
                    onAdvancedClick = { navController.navigate("settings/advanced") },
                    onStatsClick = { navController.navigate("settings/stats") },
                    onUpcomingClick = { navController.navigate("upcoming") },
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
                    },
                    onDownloadQueueClick = { navController.navigate("downloads_queue") },
                )
            }

            composable("settings/appearance") { com.radiant.webtoon.ui.settings.AppearanceSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/library") { com.radiant.webtoon.ui.settings.LibrarySettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/reader") { com.radiant.webtoon.ui.settings.ReaderSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/downloads") { com.radiant.webtoon.ui.settings.DownloadsSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/backup") { com.radiant.webtoon.ui.settings.BackupSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/storage") { com.radiant.webtoon.ui.settings.StorageSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/tracking") { com.radiant.webtoon.ui.settings.TrackingSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/security") { com.radiant.webtoon.ui.settings.SecuritySettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/about") { com.radiant.webtoon.ui.settings.AboutSettingsScreen(onBackClick = { navController.popBackStack() }) }
            composable("settings/stats") {
                com.radiant.webtoon.ui.stats.ReadingStatsScreen(
                    repository = repository,
                    onBackClick = { navController.popBackStack() }
                )
            }
            
            composable("settings/advanced") {
                com.radiant.webtoon.ui.settings.AdvancedSettingsScreen(
                    onBackClick = { navController.popBackStack() },
                    onPageListDebugClick = { navController.navigate("settings/page_list_debug") },
                )
            }

            composable("settings/page_list_debug") {
                com.radiant.webtoon.ui.settings.PageListDebugScreen(
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable(
                route = "source_settings/{sourceId}",
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                com.radiant.webtoon.ui.extensions.SourceSettingsScreen(
                    sourceId = sourceId,
                    extensionManager = extensionManager,
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable("upcoming") {
                com.radiant.webtoon.ui.upcoming.UpcomingScreen(
                    repository = repository,
                    onBackClick = { navController.popBackStack() },
                    onWebtoonClick = { sourceId, webtoonId ->
                        navController.navigate("detail/${encodeNav(sourceId)}/${encodeNav(webtoonId)}")
                    },
                )
            }

            composable(
                route = "reader/{sourceId}/{webtoonId}/{chapterId}?chapterTitle={chapterTitle}",
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("webtoonId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                    navArgument("chapterTitle") { type = NavType.StringType; defaultValue = "" }
                ),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { backStackEntry ->
                val sourceId = decodeNav(backStackEntry.arguments?.getString("sourceId") ?: "")
                val webtoonId = decodeNav(backStackEntry.arguments?.getString("webtoonId") ?: "")
                val chapterId = decodeNav(backStackEntry.arguments?.getString("chapterId") ?: "")
                val chapterTitle = decodeNav(backStackEntry.arguments?.getString("chapterTitle") ?: "")
                // History, detail, and updates still navigate here. The destination starts the
                // view-based reader and leaves the back stack so the Compose reader is not shown.
                LaunchedEffect(sourceId, webtoonId, chapterId, chapterTitle) {
                    context.startActivity(
                        ReaderActivity.intent(
                            context = context,
                            sourceId = sourceId,
                            webtoonId = webtoonId,
                            chapterId = chapterId,
                            chapterTitle = chapterTitle.ifEmpty { "Chapter" },
                        ),
                    )
                    navController.popBackStack()
                }
            }
        }
        }
        }
    }
}

@Composable
private fun BiometricLockGate(
    lockEnabled: Boolean?,
    activity: FragmentActivity,
    content: @Composable () -> Unit
) {
    var unlocked by remember { mutableStateOf(false) }
    var promptShown by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val state: AppLockState = when (lockEnabled) {
        null -> AppLockState.Loading
        false -> AppLockState.Unlocked
        true -> AppLockPolicy.afterSettingsLoaded(lockEnabled = true, alreadyUnlocked = unlocked)
            .let { if (it == AppLockState.Locked && promptShown) AppLockState.Authenticating else it }
    }
    val accessGranted = AppLockPolicy.grantsAccess(state)

    LaunchedEffect(lockEnabled) {
        if (lockEnabled == false) {
            unlocked = true
            promptShown = false
        } else if (lockEnabled == true) {
            unlocked = false
            promptShown = false
        }
    }

    DisposableEffect(lifecycleOwner, lockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && lockEnabled == true) {
                unlocked = false
                promptShown = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state, promptShown) {
        if (lockEnabled == true && !unlocked && !promptShown) {
            promptShown = true
            try {
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            unlocked = true
                            promptShown = false
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            promptShown = false
                            if (AppLockPolicy.staysLockedOnAuthError()) {
                                unlocked = false
                            }
                        }
                    }
                )
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Radiant")
                    .setSubtitle("Confirm your identity to continue")
                    .setAllowedAuthenticators(AppLockAuthenticator.authenticators())
                    .apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            setNegativeButtonText("Cancel")
                        }
                    }
                    .build()
                prompt.authenticate(promptInfo)
            } catch (_: Exception) {
                promptShown = false
                unlocked = false
            }
        }
    }

    BackHandler(enabled = !accessGranted) { }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (!accessGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = true, onClick = {})
                    .semantics { disabled() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = "App locked",
                        modifier = Modifier.padding(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (state == AppLockState.Loading) "Loading" else "App locked",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (state == AppLockState.Loading) {
                            "Checking lock settings"
                        } else {
                            "Authenticate to continue"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lockEnabled == true) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = { promptShown = false }) {
                            Text("Unlock")
                        }
                    }
                }
            }
        }
    }
}

