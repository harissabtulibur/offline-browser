package com.offlinecyberdeck.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.offlinecyberdeck.core.database.dao.MediaDao
import com.offlinecyberdeck.core.database.entity.MediaItemEntity
import com.offlinecyberdeck.core.database.entity.PlaylistEntity
import com.offlinecyberdeck.core.database.entity.PlaylistMediaCrossRef
import com.offlinecyberdeck.core.model.MediaAvailability
import com.offlinecyberdeck.core.model.StorageTarget
import com.offlinecyberdeck.core.storage.DeckDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class MediaVaultRepository(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val driveManager: DeckDriveManager
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val mediaFlow: Flow<List<MediaItemEntity>> = mediaDao.observeAll()
    val playlistsFlow: Flow<List<PlaylistEntity>> = mediaDao.observePlaylists()

    fun observePlaylistItems(playlistId: String): Flow<List<MediaItemEntity>> =
        mediaDao.observePlaylistItems(playlistId)

    fun computeAvailability(item: MediaItemEntity): MediaAvailability {
        val uri = item.localFileUri
        if (uri.isNullOrBlank()) return MediaAvailability.METADATA_ONLY
        return if (driveManager.isUriOrFileAccessible(uri)) {
            MediaAvailability.AVAILABLE_OFFLINE
        } else {
            MediaAvailability.DRIVE_DISCONNECTED
        }
    }

    suspend fun importLocalMediaFiles(uris: List<Uri>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var imported = 0
            for (uri in uris) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                if (ingestMediaUri(uri)) imported++
            }
            imported
        }
    }

    suspend fun scanSafFolderForMedia(treeUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Cannot access selected folder")
            var count = 0
            val queue = ArrayDeque<DocumentFile>()
            queue.add(root)
            var visitedDirs = 0
            while (queue.isNotEmpty() && visitedDirs < 150) {
                val dir = queue.removeFirst()
                visitedDirs++
                for (child in dir.listFiles()) {
                    if (child.isDirectory) {
                        queue.add(child)
                    } else if (child.isFile && isSupportedMediaFile(child.name.orEmpty())) {
                        if (ingestMediaUri(child.uri)) count++
                    }
                }
            }
            count
        }
    }

    private suspend fun ingestMediaUri(uri: Uri): Boolean {
        val uriStr = uri.toString()
        val existingByUri = mediaDao.findByLocalUri(uriStr)
        if (existingByUri != null) return false

        val (fileName, sizeBytes) = driveManager.queryDisplayNameAndSize(uri)
        val cleanTitle = cleanTitleFromFileName(fileName)
        val extractedVideoId = extractVideoIdFromString(fileName)
        val (durationMs, thumbPath) = extractMediaMetadataAndThumb(uri, fileName)
        val storageTarget = if (uriStr.contains("external", ignoreCase = true) ||
            uri.authority?.contains("externalstorage") == true
        ) StorageTarget.DECK_DRIVE else StorageTarget.INTERNAL

        // Attempt match against existing metadata-only entries
        val allItems = mediaDao.getAllSnapshot().filter { it.localFileUri.isNullOrBlank() }
        var bestMatch: MediaItemEntity? = null
        var bestScore = 0f

        if (extractedVideoId != null) {
            bestMatch = allItems.firstOrNull { it.videoId == extractedVideoId }
            if (bestMatch != null) bestScore = 1.0f
        }
        if (bestMatch == null) {
            for (candidate in allItems) {
                val score = titleSimilarity(cleanTitle, candidate.title)
                if (score > bestScore && score >= 0.45f) {
                    bestScore = score
                    bestMatch = candidate
                }
            }
        }

        if (bestMatch != null) {
            mediaDao.upsert(
                bestMatch.copy(
                    localFileUri = uriStr,
                    localFileName = fileName,
                    durationMs = if (durationMs > 0L) durationMs else bestMatch.durationMs,
                    sizeBytes = sizeBytes.coerceAtLeast(0L),
                    thumbnailPath = thumbPath ?: bestMatch.thumbnailPath,
                    storageTarget = storageTarget,
                    matchConfidence = bestScore,
                    matchConfirmed = bestScore >= 0.99f
                )
            )
            return true
        }

        mediaDao.upsert(
            MediaItemEntity(
                id = UUID.randomUUID().toString(),
                title = cleanTitle,
                videoId = extractedVideoId,
                thumbnailPath = thumbPath,
                localFileUri = uriStr,
                localFileName = fileName,
                durationMs = durationMs,
                sizeBytes = sizeBytes.coerceAtLeast(0L),
                storageTarget = storageTarget,
                matchConfidence = 1.0f,
                matchConfirmed = true
            )
        )
        return true
    }

    suspend fun savePublicWebMetadata(
        title: String,
        channel: String,
        videoId: String?,
        sourceUrl: String,
        description: String
    ): MediaItemEntity {
        val existing = if (!videoId.isNullOrBlank()) mediaDao.findByVideoId(videoId) else null
        if (existing != null) {
            val updated = existing.copy(
                title = title.ifBlank { existing.title },
                channel = channel.ifBlank { existing.channel },
                sourceUrl = sourceUrl,
                description = description.ifBlank { existing.description }
            )
            mediaDao.upsert(updated)
            return updated
        }

        // Check if an unlinked local file matches this newly cataloged metadata
        val unlinkedLocal = mediaDao.getAllSnapshot().filter { !it.localFileUri.isNullOrBlank() && it.sourceUrl.isNullOrBlank() }
        var matchedLocal: MediaItemEntity? = null
        var score = 0f
        if (!videoId.isNullOrBlank()) {
            matchedLocal = unlinkedLocal.firstOrNull { it.videoId == videoId }
            if (matchedLocal != null) score = 1.0f
        }
        if (matchedLocal == null) {
            for (local in unlinkedLocal) {
                val s = titleSimilarity(title, local.title)
                if (s > score && s >= 0.55f) {
                    score = s
                    matchedLocal = local
                }
            }
        }

        val entry = if (matchedLocal != null) {
            matchedLocal.copy(
                title = title.ifBlank { matchedLocal.title },
                channel = channel,
                videoId = videoId ?: matchedLocal.videoId,
                sourceUrl = sourceUrl,
                description = description,
                matchConfidence = score,
                matchConfirmed = score >= 0.99f
            )
        } else {
            MediaItemEntity(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { sourceUrl },
                channel = channel,
                videoId = videoId,
                sourceUrl = sourceUrl,
                description = description
            )
        }
        mediaDao.upsert(entry)
        return entry
    }

    suspend fun linkLocalFileToMetadata(metadataId: String, localFileUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        localFileUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val item = mediaDao.getById(metadataId) ?: error("Metadata record not found")
                val (fileName, size) = driveManager.queryDisplayNameAndSize(localFileUri)
                val (durationMs, thumbPath) = extractMediaMetadataAndThumb(localFileUri, fileName)
                mediaDao.upsert(
                    item.copy(
                        localFileUri = localFileUri.toString(),
                        localFileName = fileName,
                        durationMs = if (durationMs > 0L) durationMs else item.durationMs,
                        sizeBytes = size.coerceAtLeast(0L),
                        thumbnailPath = thumbPath ?: item.thumbnailPath,
                        matchConfidence = 1.0f,
                        matchConfirmed = true
                    )
                )
            }
        }

    suspend fun unlinkLocalFile(item: MediaItemEntity) {
        mediaDao.upsert(
            item.copy(
                localFileUri = null,
                localFileName = null,
                matchConfidence = 0f,
                matchConfirmed = false
            )
        )
    }

    suspend fun confirmMatch(item: MediaItemEntity) {
        mediaDao.upsert(item.copy(matchConfirmed = true))
    }

    suspend fun updatePlaybackProgress(id: String, positionMs: Long, incrementPlayCount: Boolean) {
        val item = mediaDao.getById(id) ?: return
        mediaDao.upsert(
            item.copy(
                watchedPositionMs = positionMs.coerceAtLeast(0L),
                playCount = if (incrementPlayCount) item.playCount + 1 else item.playCount,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateMediaItem(item: MediaItemEntity) {
        mediaDao.upsert(item)
    }

    suspend fun deleteMediaItem(item: MediaItemEntity) {
        mediaDao.delete(item)
    }

    suspend fun createPlaylist(name: String, description: String = "") {
        if (name.isBlank()) return
        mediaDao.upsertPlaylist(
            PlaylistEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim()
            )
        )
    }

    suspend fun deletePlaylist(playlistId: String) {
        mediaDao.deletePlaylist(playlistId)
    }

    suspend fun addMediaToPlaylist(playlistId: String, mediaId: String) {
        mediaDao.addToPlaylist(PlaylistMediaCrossRef(playlistId, mediaId, 0))
    }

    suspend fun removeMediaFromPlaylist(playlistId: String, mediaId: String) {
        mediaDao.removeFromPlaylist(playlistId, mediaId)
    }

    suspend fun exportMetadataCatalog(targetUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val items = mediaDao.getAllSnapshot()
            val json = gson.toJson(items)
            context.contentResolver.openOutputStream(targetUri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: error("Cannot write catalog file")
            items.size
        }
    }

    suspend fun importMetadataCatalog(sourceUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(sourceUri)?.use {
                it.bufferedReader().readText()
            } ?: error("Cannot read catalog file")
            val type = object : TypeToken<List<MediaItemEntity>>() {}.type
            val list: List<MediaItemEntity> = gson.fromJson(json, type) ?: emptyList()
            for (item in list) {
                mediaDao.upsert(item)
            }
            list.size
        }
    }

    private fun extractMediaMetadataAndThumb(uri: Uri, fileName: String): Pair<Long, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull() ?: 0L
            var thumbPath: String? = null
            val isAudioOnly = fileName.substringAfterLast('.', "").lowercase() in
                setOf("mp3", "m4a", "ogg", "opus", "wav", "flac")
            if (!isAudioOnly) {
                val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, 320, 180, true)
                    if (scaled != frame) frame.recycle()
                    val thumbFile = File(driveManager.internalThumbnailsDir, "${UUID.randomUUID()}.jpg")
                    thumbFile.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    }
                    scaled.recycle()
                    thumbPath = thumbFile.absolutePath
                }
            }
            durationMs to thumbPath
        } catch (_: Throwable) {
            0L to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isSupportedMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "mp3", "m4a", "ogg", "opus", "wav", "flac")
    }

    private fun cleanTitleFromFileName(name: String): String {
        return name.substringBeforeLast('.')
            .replace(Regex("\\[[A-Za-z0-9_-]{11}]"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { name }
    }

    fun extractVideoIdFromString(input: String): String? {
        val bracketed = Regex("\\[([A-Za-z0-9_-]{11})]").find(input)?.groupValues?.getOrNull(1)
        if (bracketed != null) return bracketed
        val urlParam = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(input)?.groupValues?.getOrNull(1)
        if (urlParam != null) return urlParam
        val shortUrl = Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(input)?.groupValues?.getOrNull(1)
        return shortUrl
    }

    fun titleSimilarity(a: String, b: String): Float {
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
        val intersection = tokensA.intersect(tokensB).size.toFloat()
        val union = tokensA.union(tokensB).size.toFloat()
        return if (union == 0f) 0f else (intersection / union).coerceIn(0f, 0.95f)
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .toSet()
    }
}
