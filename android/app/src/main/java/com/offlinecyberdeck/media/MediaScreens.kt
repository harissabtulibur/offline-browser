package com.offlinecyberdeck.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.offlinecyberdeck.CyberDeckApplication
import com.offlinecyberdeck.core.database.entity.MediaItemEntity
import com.offlinecyberdeck.core.database.entity.PlaylistEntity
import com.offlinecyberdeck.core.format.Formatters
import com.offlinecyberdeck.core.model.MediaAvailability
import com.offlinecyberdeck.ui.theme.DeckAmber
import com.offlinecyberdeck.ui.theme.DeckGreen
import com.offlinecyberdeck.ui.theme.DeckRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MediaFilterTab(val label: String) {
    ALL("Home"),
    AVAILABLE("Offline Ready"),
    WATCH_LATER("Watch Later"),
    CONTINUE("Continue Watching"),
    PLAYLISTS("Playlists")
}

@Composable
fun MediaVaultScreen(
    app: CyberDeckApplication,
    onPlayMedia: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val items by app.mediaRepository.mediaFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by app.mediaRepository.playlistsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(MediaFilterTab.ALL) }
    var query by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    var linkingTargetId by remember { mutableStateOf<String?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var addToPlaylistItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    var activePlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }

    val multiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                app.mediaRepository.importLocalMediaFiles(uris)
                    .onSuccess { count -> onShowMessage("Imported/matched $count media file(s)") }
                    .onFailure { onShowMessage("Import error: ${it.message}") }
            }
        }
    }

    val folderScanner = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                app.mediaRepository.scanSafFolderForMedia(uri)
                    .onSuccess { count -> onShowMessage("Scanned folder: $count media item(s) added/matched") }
                    .onFailure { onShowMessage("Scan failed: ${it.message}") }
            }
        }
    }

    val singleLinkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val id = linkingTargetId
        linkingTargetId = null
        if (uri != null && id != null) {
            scope.launch {
                app.mediaRepository.linkLocalFileToMetadata(id, uri)
                    .onSuccess { onShowMessage("Local file linked to metadata entry") }
                    .onFailure { onShowMessage("Link failed: ${it.message}") }
            }
        }
    }

    val catalogExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                app.mediaRepository.exportMetadataCatalog(uri)
                    .onSuccess { count -> onShowMessage("Exported $count media catalog records") }
                    .onFailure { onShowMessage("Export failed: ${it.message}") }
            }
        }
    }

    val catalogImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                app.mediaRepository.importMetadataCatalog(uri)
                    .onSuccess { count -> onShowMessage("Imported $count media metadata records") }
                    .onFailure { onShowMessage("Import failed: ${it.message}") }
            }
        }
    }

    val filteredItems = remember(items, selectedTab, query) {
        items.filter { item ->
            val qOk = query.isBlank() ||
                item.title.contains(query, true) ||
                item.channel.contains(query, true) ||
                item.tags.contains(query, true) ||
                item.localFileName.orEmpty().contains(query, true)
            val tabOk = when (selectedTab) {
                MediaFilterTab.ALL -> true
                MediaFilterTab.AVAILABLE -> app.mediaRepository.computeAvailability(item) == MediaAvailability.AVAILABLE_OFFLINE
                MediaFilterTab.WATCH_LATER -> item.watchLater
                MediaFilterTab.CONTINUE -> item.watchedPositionMs > 5000L
                MediaFilterTab.PLAYLISTS -> true
            }
            qOk && tabOk
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Media Vault (${items.size})", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { folderScanner.launch(null) }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Scan")
                }
                Button(onClick = { multiFilePicker.launch(arrayOf("video/*", "audio/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Files")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search local videos, audio, channels, tags") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MediaFilterTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = {
                        selectedTab = tab
                        if (tab != MediaFilterTab.PLAYLISTS) activePlaylist = null
                    },
                    label = { Text(tab.label) }
                )
            }
            OutlinedButton(onClick = { catalogExporter.launch("cyberdeck_media_catalog.json") }) {
                Text("Export JSON")
            }
            OutlinedButton(onClick = { catalogImporter.launch(arrayOf("application/json")) }) {
                Text("Import JSON")
            }
        }
        Spacer(Modifier.height(10.dp))

        if (selectedTab == MediaFilterTab.PLAYLISTS) {
            PlaylistsPane(
                app = app,
                playlists = playlists,
                activePlaylist = activePlaylist,
                onSelectPlaylist = { activePlaylist = it },
                onCreatePlaylist = { showNewPlaylistDialog = true },
                onPlayMedia = onPlayMedia
            )
        } else if (filteredItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (items.isEmpty()) {
                        "No media in vault yet. Tap Scan to index a folder on your tablet or USB-C Deck Drive, or tap Files to pick local videos/audio."
                    } else {
                        "No media items match this filter."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredItems, key = { it.id }) { item ->
                    val availability = app.mediaRepository.computeAvailability(item)
                    MediaItemCard(
                        item = item,
                        availability = availability,
                        onPlay = {
                            if (availability == MediaAvailability.AVAILABLE_OFFLINE) {
                                onPlayMedia(item.id)
                            } else {
                                onShowMessage("No local playable file available (${availability.label}). Link a local file first.")
                            }
                        },
                        onEdit = { editingItem = item },
                        onLinkFile = {
                            linkingTargetId = item.id
                            singleLinkPicker.launch(arrayOf("video/*", "audio/*"))
                        },
                        onUnlink = {
                            scope.launch { app.mediaRepository.unlinkLocalFile(item) }
                        },
                        onConfirmMatch = {
                            scope.launch { app.mediaRepository.confirmMatch(item) }
                        },
                        onToggleWatchLater = {
                            scope.launch { app.mediaRepository.updateMediaItem(item.copy(watchLater = !item.watchLater)) }
                        },
                        onToggleFavorite = {
                            scope.launch { app.mediaRepository.updateMediaItem(item.copy(isFavorite = !item.isFavorite)) }
                        },
                        onAddToPlaylist = { addToPlaylistItem = item },
                        onDelete = {
                            scope.launch { app.mediaRepository.deleteMediaItem(item) }
                        }
                    )
                }
            }
        }
    }

    editingItem?.let { item ->
        var title by remember { mutableStateOf(item.title) }
        var channel by remember { mutableStateOf(item.channel) }
        var tags by remember { mutableStateOf(item.tags) }
        var desc by remember { mutableStateOf(item.description) }
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit Media Metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                    OutlinedTextField(value = channel, onValueChange = { channel = it }, label = { Text("Channel / Creator") })
                    OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags") })
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, maxLines = 3)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        app.mediaRepository.updateMediaItem(
                            item.copy(
                                title = title.ifBlank { item.title },
                                channel = channel,
                                tags = tags,
                                description = desc
                            )
                        )
                    }
                    editingItem = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) { Text("Cancel") }
            }
        )
    }

    if (showNewPlaylistDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist Name") })
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { app.mediaRepository.createPlaylist(name) }
                    showNewPlaylistDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistDialog = false }) { Text("Cancel") }
            }
        )
    }

    addToPlaylistItem?.let { item ->
        AlertDialog(
            onDismissRequest = { addToPlaylistItem = null },
            title = { Text("Add to Playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists yet. Create a playlist in the Playlists tab first.")
                } else {
                    Column {
                        playlists.forEach { pl ->
                            TextButton(onClick = {
                                scope.launch { app.mediaRepository.addMediaToPlaylist(pl.id, item.id) }
                                addToPlaylistItem = null
                                onShowMessage("Added to '${pl.name}'")
                            }) {
                                Text(pl.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addToPlaylistItem = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MediaItemCard(
    item: MediaItemEntity,
    availability: MediaAvailability,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onLinkFile: () -> Unit,
    onUnlink: () -> Unit,
    onConfirmMatch: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbBitmap = remember(item.thumbnailPath) {
        item.thumbnailPath?.let { path ->
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Column {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .width(128.dp)
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap.asImageBitmap(),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                    }
                    if (item.durationMs > 0L) {
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        ) {
                            Text(
                                Formatters.formatDurationMs(item.durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.channel.isNotBlank()) {
                        Text(item.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val statusColor = when (availability) {
                        MediaAvailability.AVAILABLE_OFFLINE -> DeckGreen
                        MediaAvailability.METADATA_ONLY -> DeckAmber
                        MediaAvailability.DRIVE_DISCONNECTED -> DeckRed
                    }
                    Text(
                        text = buildString {
                            append(availability.label)
                            if (item.sizeBytes > 0L) append(" • ${Formatters.formatBytes(item.sizeBytes)}")
                            if (!item.matchConfirmed && item.localFileUri != null) {
                                append(" • Match ${(item.matchConfidence * 100).toInt()}%")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (availability == MediaAvailability.AVAILABLE_OFFLINE) {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                }
                if (!item.matchConfirmed && item.localFileUri != null) {
                    IconButton(onClick = onConfirmMatch) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Confirm Match", tint = DeckGreen)
                    }
                }
                IconButton(onClick = onLinkFile) {
                    Icon(Icons.Filled.Link, contentDescription = "Link Local File")
                }
                if (item.localFileUri != null) {
                    IconButton(onClick = onUnlink) {
                        Icon(Icons.Filled.LinkOff, contentDescription = "Unlink File")
                    }
                }
                IconButton(onClick = onToggleWatchLater) {
                    Icon(
                        Icons.Filled.WatchLater,
                        contentDescription = "Watch Later",
                        tint = if (item.watchLater) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (item.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Favorite"
                    )
                }
                IconButton(onClick = onAddToPlaylist) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add to Playlist")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit Metadata")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
private fun PlaylistsPane(
    app: CyberDeckApplication,
    playlists: List<PlaylistEntity>,
    activePlaylist: PlaylistEntity?,
    onSelectPlaylist: (PlaylistEntity?) -> Unit,
    onCreatePlaylist: () -> Unit,
    onPlayMedia: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    if (activePlaylist == null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onCreatePlaylist) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New Playlist")
            }
            if (playlists.isEmpty()) {
                Text("No playlists created yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onSelectPlaylist(pl) }) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.PlaylistPlay, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(pl.name, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    scope.launch { app.mediaRepository.deletePlaylist(pl.id) }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Playlist")
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        val playlistItems by app.mediaRepository.observePlaylistItems(activePlaylist.id)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSelectPlaylist(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to playlists")
                }
                Text(activePlaylist.name, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlistItems, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onPlayMedia(item.id) }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(item.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                scope.launch {
                                    app.mediaRepository.removeMediaFromPlaylist(activePlaylist.id, item.id)
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove from playlist")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaPlayerScreen(
    app: CyberDeckApplication,
    mediaId: String,
    onBack: () -> Unit,
    onSelectOtherMedia: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allMedia by app.mediaRepository.mediaFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var item by remember { mutableStateOf<MediaItemEntity?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(mediaId) {
        val loaded = app.database.mediaDao().getById(mediaId)
        item = loaded
        val uriStr = loaded?.localFileUri
        if (!uriStr.isNullOrBlank() && app.driveManager.isUriOrFileAccessible(uriStr)) {
            exoPlayer.setMediaItem(MediaItem.fromUri(uriStr))
            exoPlayer.prepare()
            if (loaded.watchedPositionMs > 0L) {
                exoPlayer.seekTo(loaded.watchedPositionMs)
            }
            exoPlayer.playWhenReady = true
            app.mediaRepository.updatePlaybackProgress(loaded.id, loaded.watchedPositionMs, incrementPlayCount = true)
        }
    }

    LaunchedEffect(mediaId) {
        while (true) {
            delay(4000L)
            val current = item
            if (current != null && exoPlayer.isPlaying) {
                app.mediaRepository.updatePlaybackProgress(
                    id = current.id,
                    positionMs = exoPlayer.currentPosition,
                    incrementPlayCount = false
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val current = item
            if (current != null) {
                val pos = exoPlayer.currentPosition
                scope.launch {
                    app.mediaRepository.updatePlaybackProgress(current.id, pos, incrementPlayCount = false)
                }
            }
            exoPlayer.release()
        }
    }

    val currentItem = item
    val related = remember(allMedia, mediaId) {
        allMedia.filter {
            it.id != mediaId && app.mediaRepository.computeAvailability(it) == MediaAvailability.AVAILABLE_OFFLINE
        }.take(10)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(currentItem?.title ?: "Player", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (currentItem == null || currentItem.localFileUri.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text("No local file linked for playback.")
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            currentItem?.let { m ->
                item {
                    Text(m.title, style = MaterialTheme.typography.titleMedium)
                    if (m.channel.isNotBlank()) {
                        Text(m.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "Plays: ${m.playCount} • ${Formatters.formatBytes(m.sizeBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (m.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(m.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (related.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("More Available Offline", style = MaterialTheme.typography.titleSmall)
                }
                items(related, key = { it.id }) { rel ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onSelectOtherMedia(rel.id) }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(rel.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
