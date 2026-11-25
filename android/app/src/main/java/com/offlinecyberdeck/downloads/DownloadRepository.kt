package com.offlinecyberdeck.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.offlinecyberdeck.core.database.dao.DownloadDao
import com.offlinecyberdeck.core.database.entity.DownloadEntity
import com.offlinecyberdeck.core.model.DownloadStatus
import com.offlinecyberdeck.core.model.StorageTarget
import com.offlinecyberdeck.core.settings.SettingsRepository
import com.offlinecyberdeck.core.storage.DeckDriveManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadRepository(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val settingsRepo: SettingsRepository,
    private val driveManager: DeckDriveManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    val downloadsFlow: Flow<List<DownloadEntity>> = downloadDao.observeAll()

    suspend fun enqueueDownload(
        url: String,
        suggestedFileName: String? = null,
        mimeType: String? = null,
        customFolderUri: String? = null
    ): DownloadEntity {
        val settings = settingsRepo.getSettings()
        val guessedName = suggestedFileName?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(url, null, mimeType)
        val cleanName = driveManager.sanitizeFileName(guessedName)
        val targetFolder = customFolderUri ?: settings.downloadFolderUri
        val targetMode = when {
            !targetFolder.isNullOrBlank() -> StorageTarget.DECK_DRIVE
            settings.downloadStorageTarget == StorageTarget.DECK_DRIVE &&
                driveManager.status.value.isWritable -> StorageTarget.DECK_DRIVE
            else -> StorageTarget.INTERNAL
        }

        val entity = DownloadEntity(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = cleanName,
            mimeType = mimeType?.takeIf { it.isNotBlank() } ?: guessMime(cleanName),
            destinationUri = targetFolder.orEmpty(),
            storageTarget = targetMode,
            status = DownloadStatus.QUEUED
        )
        downloadDao.upsert(entity)
        startJob(entity.id)
        return entity
    }

    fun pauseDownload(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        scope.launch {
            val current = downloadDao.getById(id) ?: return@launch
            if (current.status == DownloadStatus.DOWNLOADING || current.status == DownloadStatus.QUEUED) {
                downloadDao.upsert(current.copy(status = DownloadStatus.PAUSED))
            }
        }
    }

    fun resumeOrRetryDownload(id: String) {
        if (activeJobs[id]?.isActive == true) return
        startJob(id)
    }

    fun cancelDownload(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        scope.launch {
            val current = downloadDao.getById(id) ?: return@launch
            downloadDao.upsert(current.copy(status = DownloadStatus.CANCELLED))
        }
    }

    fun removeRecord(id: String, deleteFile: Boolean) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        scope.launch {
            val current = downloadDao.getById(id)
            if (deleteFile && current != null && current.destinationUri.isNotBlank()) {
                deleteTarget(current.destinationUri)
            }
            downloadDao.deleteById(id)
        }
    }

    private fun startJob(id: String) {
        val job = scope.launch {
            executeDownload(id)
        }
        activeJobs[id] = job
    }

    private suspend fun executeDownload(id: String) = withContext(Dispatchers.IO) {
        val initial = downloadDao.getById(id) ?: return@withContext
        val settings = settingsRepo.getSettings()
        var conn: HttpURLConnection? = null
        try {
            val targetUriOrPath = resolveDestination(initial, settings.deckDriveTreeUri)
            val isInternalFile = !targetUriOrPath.startsWith("content://")
            val existingBytes = if (isInternalFile) {
                File(targetUriOrPath).takeIf { it.exists() }?.length() ?: 0L
            } else {
                0L
            }

            downloadDao.upsert(
                initial.copy(
                    destinationUri = targetUriOrPath,
                    status = DownloadStatus.DOWNLOADING,
                    errorMessage = null
                )
            )

            val parsedUrl = URL(initial.url)
            if (parsedUrl.protocol != "http" && parsedUrl.protocol != "https") {
                error("Unsupported download scheme: ${parsedUrl.protocol}")
            }

            conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OfflineCyberDeck/1.0 Android")
                if (existingBytes > 0L && initial.supportsResume) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
            conn.connect()

            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code ${conn.responseMessage ?: ""}".trim())
            }

            val acceptRanges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            val isPartial = code == HttpURLConnection.HTTP_PARTIAL
            val contentLength = conn.contentLengthLong
            val totalBytes = when {
                isPartial && contentLength > 0 -> existingBytes + contentLength
                contentLength > 0 -> contentLength
                else -> -1L
            }

            val buffer = ByteArray(16 * 1024)
            var downloaded = if (isPartial) existingBytes else 0L
            var lastEmit = System.currentTimeMillis()

            conn.inputStream.use { input ->
                if (isInternalFile) {
                    val outFile = File(targetUriOrPath)
                    outFile.parentFile?.mkdirs()
                    RandomAccessFile(outFile, "rw").use { raf ->
                        if (isPartial) {
                            raf.seek(existingBytes)
                        } else {
                            raf.setLength(0L)
                        }
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 350L) {
                                lastEmit = now
                                downloadDao.upsert(
                                    initial.copy(
                                        destinationUri = targetUriOrPath,
                                        status = DownloadStatus.DOWNLOADING,
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalBytes,
                                        supportsResume = acceptRanges
                                    )
                                )
                            }
                        }
                    }
                } else {
                    val outStream = context.contentResolver.openOutputStream(Uri.parse(targetUriOrPath), "w")
                        ?: error("Cannot write to selected destination URI")
                    outStream.use { out ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 350L) {
                                lastEmit = now
                                downloadDao.upsert(
                                    initial.copy(
                                        destinationUri = targetUriOrPath,
                                        status = DownloadStatus.DOWNLOADING,
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalBytes,
                                        supportsResume = false
                                    )
                                )
                            }
                        }
                        out.flush()
                    }
                }
            }

            downloadDao.upsert(
                initial.copy(
                    destinationUri = targetUriOrPath,
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = downloaded,
                    totalBytes = if (totalBytes > 0) totalBytes else downloaded,
                    supportsResume = acceptRanges,
                    completedAt = System.currentTimeMillis()
                )
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val latest = downloadDao.getById(id) ?: initial
            downloadDao.upsert(
                latest.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = t.message ?: "Download failed"
                )
            )
        } finally {
            conn?.disconnect()
            activeJobs.remove(id)
        }
    }

    private suspend fun resolveDestination(entity: DownloadEntity, deckTreeUri: String?): String {
        // If resuming an internal partial file that still exists, keep its path
        if (entity.destinationUri.isNotBlank() && !entity.destinationUri.startsWith("content://")) {
            val existing = File(entity.destinationUri)
            if (existing.exists()) return existing.absolutePath
        }
        // Check SAF custom folder or Deck Drive
        val safFolderUri = when {
            entity.destinationUri.startsWith("content://") && entity.status == DownloadStatus.QUEUED ->
                entity.destinationUri
            entity.storageTarget == StorageTarget.DECK_DRIVE && !deckTreeUri.isNullOrBlank() -> null
            else -> null
        }

        if (!safFolderUri.isNullOrBlank()) {
            val treeDoc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(safFolderUri)) }.getOrNull()
            if (treeDoc != null && treeDoc.canWrite()) {
                val created = driveManager.createUniqueSafFile(treeDoc, entity.fileName, entity.mimeType)
                if (created != null) return created.uri.toString()
            }
        }

        if (entity.storageTarget == StorageTarget.DECK_DRIVE && !deckTreeUri.isNullOrBlank()) {
            val dir = driveManager.ensureSubdirectory(deckTreeUri, "CyberDeck", "Downloads")
            if (dir != null) {
                val created = driveManager.createUniqueSafFile(dir, entity.fileName, entity.mimeType)
                if (created != null) return created.uri.toString()
            }
        }

        val internalFile = driveManager.createUniqueInternalFile(
            driveManager.internalDownloadsDir,
            entity.fileName
        )
        return internalFile.absolutePath
    }

    private fun deleteTarget(uriOrPath: String) {
        if (uriOrPath.startsWith("content://")) {
            runCatching { DocumentFile.fromSingleUri(context, Uri.parse(uriOrPath))?.delete() }
        } else {
            runCatching { File(uriOrPath).delete() }
        }
    }

    fun buildOpenIntent(item: DownloadEntity): Intent? {
        if (item.destinationUri.isBlank()) return null
        val uri = if (item.destinationUri.startsWith("content://")) {
            Uri.parse(item.destinationUri)
        } else {
            val f = File(item.destinationUri)
            if (!f.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildShareIntent(item: DownloadEntity): Intent? {
        if (item.destinationUri.isBlank()) return null
        val uri = if (item.destinationUri.startsWith("content://")) {
            Uri.parse(item.destinationUri)
        } else {
            val f = File(item.destinationUri)
            if (!f.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
