package com.offlinecyberdeck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinecyberdeck.archive.ArchiveReaderScreen
import com.offlinecyberdeck.bookmarks.BookmarksScreen
import com.offlinecyberdeck.bookmarks.HistoryScreen
import com.offlinecyberdeck.browser.BrowserScreen
import com.offlinecyberdeck.browser.BrowserViewModel
import com.offlinecyberdeck.core.database.entity.AppSettingsEntity
import com.offlinecyberdeck.core.model.LibraryFileType
import com.offlinecyberdeck.core.model.MainDestination
import com.offlinecyberdeck.core.model.ThemeMode
import com.offlinecyberdeck.downloads.DownloadsScreen
import com.offlinecyberdeck.library.LibraryScreen
import com.offlinecyberdeck.library.PdfViewerScreen
import com.offlinecyberdeck.library.TextDocumentReaderScreen
import com.offlinecyberdeck.media.MediaPlayerScreen
import com.offlinecyberdeck.media.MediaVaultScreen
import com.offlinecyberdeck.search.UnifiedSearchScreen
import com.offlinecyberdeck.settings.SettingsScreen
import com.offlinecyberdeck.ui.theme.CyberDeckTheme
import com.offlinecyberdeck.ui.theme.DeckGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface OverlayReader {
    data class ArchiveReader(val archiveId: String) : OverlayReader
    data class PdfReader(val itemId: String) : OverlayReader
    data class TextReader(val itemId: String) : OverlayReader
    data class MediaPlayer(val mediaId: String) : OverlayReader
}

class MainActivity : ComponentActivity() {

    private var pendingExternalUrl by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as CyberDeckApplication
        setContent {
            val settings by app.settingsRepository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = AppSettingsEntity())
            val browserVm: BrowserViewModel = viewModel(factory = BrowserViewModel.Factory(this))

            LaunchedEffect(pendingExternalUrl) {
                val url = pendingExternalUrl
                if (!url.isNullOrBlank()) {
                    browserVm.createNewTab(url = url, isIncognito = false)
                    pendingExternalUrl = null
                }
            }

            val isDarkNow = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            LaunchedEffect(
                settings.immersiveFullscreen,
                settings.brightnessOverrideEnabled,
                settings.screenBrightnessPercent,
                isDarkNow
            ) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDarkNow
                controller.isAppearanceLightNavigationBars = !isDarkNow
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (settings.immersiveFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
                val lp = window.attributes
                lp.screenBrightness = if (settings.brightnessOverrideEnabled) {
                    (settings.screenBrightnessPercent.coerceIn(10, 100) / 100f).coerceIn(0.05f, 1f)
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.attributes = lp
            }

            LaunchedEffect(settings.developerModeEnabled, settings.webViewDebuggingEnabled) {
                WebView.setWebContentsDebuggingEnabled(
                    settings.developerModeEnabled && settings.webViewDebuggingEnabled
                )
            }

            CyberDeckTheme(
                themeMode = settings.themeMode,
                accentPreset = settings.accentPreset,
                uiFontScale = settings.uiFontScale
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CyberDeckRootShell(
                        app = app,
                        browserViewModel = browserVm,
                        settings = settings
                    )
                    if (settings.nightDimmerPercent > 0) {
                        val alpha = (settings.nightDimmerPercent.coerceIn(0, 65) / 100f) * 0.85f
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = alpha))
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.dataString
            if (!uri.isNullOrBlank() && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                pendingExternalUrl = uri
            }
        }
    }
}

@Composable
private fun CyberDeckRootShell(
    app: CyberDeckApplication,
    browserViewModel: BrowserViewModel,
    settings: AppSettingsEntity
) {
    var currentDest by remember { mutableStateOf(MainDestination.BROWSER) }
    var libraryInitialTab by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<OverlayReader?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val useRail = config.screenWidthDp >= 720
    val hideNavChrome = overlay != null ||
        (currentDest == MainDestination.BROWSER && settings.hideNavChromeInBrowser)

    LaunchedEffect(Unit) {
        browserViewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val showMessage: (String) -> Unit = { msg ->
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    val openUrlInBrowser: (String, Boolean) -> Unit = { url, incognito ->
        overlay = null
        currentDest = MainDestination.BROWSER
        if (incognito) {
            browserViewModel.createNewTab(url = url, isIncognito = true)
        } else {
            browserViewModel.navigateFromInput(url)
        }
    }

    val openLibraryItemById: (String) -> Unit = { itemId ->
        scope.launch {
            val item = app.database.libraryDao().getById(itemId)
            if (item == null) {
                showMessage("Library item not found.")
            } else if (!app.vaultRepository.isItemAvailable(item)) {
                showMessage("File unavailable — reconnect Deck Drive.")
            } else {
                when (item.fileType) {
                    LibraryFileType.PDF -> overlay = OverlayReader.PdfReader(item.id)
                    LibraryFileType.EPUB,
                    LibraryFileType.HTML,
                    LibraryFileType.TEXT -> overlay = OverlayReader.TextReader(item.id)
                    else -> {
                        val intent = app.vaultRepository.buildExternalViewIntent(item)
                        if (intent != null) {
                            runCatching { app.startActivity(intent) }
                                .onFailure { showMessage("No installed app handles ${item.fileType.label}") }
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = overlay != null || currentDest != MainDestination.BROWSER) {
        if (overlay != null) {
            overlay = null
        } else {
            currentDest = MainDestination.BROWSER
        }
    }

    val navItems = listOf(
        MainDestination.BROWSER to Icons.Filled.Public,
        MainDestination.LIBRARY to Icons.Filled.MenuBook,
        MainDestination.MEDIA to Icons.Filled.VideoLibrary,
        MainDestination.DOWNLOADS to Icons.Filled.Download,
        MainDestination.BOOKMARKS to Icons.Filled.Bookmark,
        MainDestination.HISTORY to Icons.Filled.History,
        MainDestination.SEARCH to Icons.Filled.Search,
        MainDestination.SETTINGS to Icons.Filled.Settings
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .let { if (settings.immersiveFullscreen) it else it.safeDrawingPadding() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!useRail && !hideNavChrome) {
                NavigationBar {
                    val compactItems = listOf(
                        MainDestination.BROWSER to Icons.Filled.Public,
                        MainDestination.LIBRARY to Icons.Filled.MenuBook,
                        MainDestination.MEDIA to Icons.Filled.VideoLibrary,
                        MainDestination.DOWNLOADS to Icons.Filled.Download,
                        MainDestination.SEARCH to Icons.Filled.Search,
                        MainDestination.SETTINGS to Icons.Filled.Settings
                    )
                    compactItems.forEach { (dest, icon) ->
                        NavigationBarItem(
                            selected = currentDest == dest,
                            onClick = {
                                if (dest == MainDestination.LIBRARY) libraryInitialTab = 0
                                currentDest = dest
                            },
                            icon = { Icon(icon, contentDescription = dest.label) },
                            label = {
                                Text(dest.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (useRail && !hideNavChrome) {
                NavigationRail {
                    navItems.forEach { (dest, icon) ->
                        NavigationRailItem(
                            selected = currentDest == dest,
                            onClick = {
                                if (dest == MainDestination.LIBRARY) libraryInitialTab = 0
                                currentDest = dest
                            },
                            icon = { Icon(icon, contentDescription = dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                val activeOverlay = overlay
                if (activeOverlay != null) {
                    when (activeOverlay) {
                        is OverlayReader.ArchiveReader -> {
                            ArchiveReaderScreen(
                                app = app,
                                archiveId = activeOverlay.archiveId,
                                onBack = { overlay = null },
                                onOpenArchiveId = { nextId -> overlay = OverlayReader.ArchiveReader(nextId) },
                                onOpenLiveUrl = { url -> openUrlInBrowser(url, false) }
                            )
                        }
                        is OverlayReader.PdfReader -> {
                            PdfViewerScreen(
                                app = app,
                                itemId = activeOverlay.itemId,
                                onBack = { overlay = null }
                            )
                        }
                        is OverlayReader.TextReader -> {
                            TextDocumentReaderScreen(
                                app = app,
                                itemId = activeOverlay.itemId,
                                onBack = { overlay = null }
                            )
                        }
                        is OverlayReader.MediaPlayer -> {
                            MediaPlayerScreen(
                                app = app,
                                mediaId = activeOverlay.mediaId,
                                onBack = { overlay = null },
                                onSelectOtherMedia = { nextId -> overlay = OverlayReader.MediaPlayer(nextId) }
                            )
                        }
                    }
                } else {
                    when (currentDest) {
                        MainDestination.BROWSER -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                BrowserScreen(
                                    viewModel = browserViewModel,
                                    onOpenDownloads = { currentDest = MainDestination.DOWNLOADS },
                                    onOpenUnifiedSearch = { currentDest = MainDestination.SEARCH },
                                    onOpenArchives = {
                                        libraryInitialTab = 1
                                        currentDest = MainDestination.LIBRARY
                                    },
                                    onOpenBookmarks = { currentDest = MainDestination.BOOKMARKS },
                                    onOpenHistory = { currentDest = MainDestination.HISTORY },
                                    onOpenSettings = { currentDest = MainDestination.SETTINGS },
                                    onShowMessage = showMessage
                                )
                            }
                        }
                        MainDestination.LIBRARY -> {
                            LibraryScreen(
                                app = app,
                                initialTabIndex = libraryInitialTab,
                                onOpenArchiveReader = { id -> overlay = OverlayReader.ArchiveReader(id) },
                                onOpenPdfReader = { id -> overlay = OverlayReader.PdfReader(id) },
                                onOpenTextReader = { id -> overlay = OverlayReader.TextReader(id) },
                                onOpenBrowserUrl = { url -> openUrlInBrowser(url, false) },
                                onShowMessage = showMessage
                            )
                        }
                        MainDestination.MEDIA -> {
                            MediaVaultScreen(
                                app = app,
                                onPlayMedia = { id -> overlay = OverlayReader.MediaPlayer(id) },
                                onShowMessage = showMessage
                            )
                        }
                        MainDestination.DOWNLOADS -> {
                            DownloadsScreen(app = app, onShowMessage = showMessage)
                        }
                        MainDestination.BOOKMARKS -> {
                            BookmarksScreen(
                                app = app,
                                onOpenUrl = openUrlInBrowser
                            )
                        }
                        MainDestination.HISTORY -> {
                            HistoryScreen(
                                app = app,
                                onOpenUrl = { url -> openUrlInBrowser(url, false) }
                            )
                        }
                        MainDestination.SEARCH -> {
                            UnifiedSearchScreen(
                                app = app,
                                onOpenArchive = { id -> overlay = OverlayReader.ArchiveReader(id) },
                                onOpenLibraryItem = openLibraryItemById,
                                onOpenMediaItem = { id -> overlay = OverlayReader.MediaPlayer(id) },
                                onOpenUrl = { url -> openUrlInBrowser(url, false) }
                            )
                        }
                        MainDestination.SETTINGS -> {
                            SettingsScreen(app = app, onShowMessage = showMessage)
                        }
                    }
                }

                if (settings.developerModeEnabled && settings.showDevHud) {
                    var heapUsedMb by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            val rt = Runtime.getRuntime()
                            heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
                            delay(1500L)
                        }
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "DEV • ${config.screenWidthDp}x${config.screenHeightDp}dp • WV:${browserViewModel.webViewPool.getLiveCount()} • RAM:${heapUsedMb}MB • ${currentDest.label}",
                            color = DeckGreen,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
