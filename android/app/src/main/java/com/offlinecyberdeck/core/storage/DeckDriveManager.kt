package com.offlinecyberdeck.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.offlinecyberdeck.core.model.DeckDriveStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class DeckDriveManager(private val context: Context) {

    private val _status = MutableStateFlow(DeckDriveStatus())
    val status: StateFlow<DeckDriveStatus> = _status.asStateFlow()

    val internalArchivesDir: File
        get() = File(context.filesDir, "archives").apply { mkdirs() }

    val internalDownloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    val internalVaultDir: File
        get() = File(context.filesDir, "vault").apply { mkdirs() }

    val internalThumbnailsDir: File
        get() = File(context.cacheDir, "thumbs").apply { mkdirs() }

    suspend fun attachDeckDrive(treeUri: Uri): DeckDriveStatus = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        refreshStatus(treeUri.toString())
    }

    suspend fun detachDeckDrive(configuredUri: String?): DeckDriveStatus = withContext(Dispatchers.IO) {
        if (!configuredUri.isNullOrBlank()) {
            val uri = Uri.parse(configuredUri)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
        val empty = DeckDriveStatus(statusDetail = "Deck Drive disconnected by user")
        _status.value = empty
        empty
    }

    suspend fun refreshStatus(configuredUri: String?): DeckDriveStatus = withContext(Dispatchers.IO) {
        if (configuredUri.isNullOrBlank()) {
            val empty = DeckDriveStatus()
            _status.value = empty
            return@withContext empty
        }
        val uri = Uri.parse(configuredUri)
        val hasPersisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        val exists = runCatching { doc?.exists() == true }.getOrDefault(false)
        val readable = runCatching { doc?.canRead() == true }.getOrDefault(false)
        val writable = runCatching { doc?.canWrite() == true }.getOrDefault(false)
        val name = runCatching { doc?.name }.getOrNull() ?: uri.lastPathSegment ?: "External Drive"

        val (totalBytes, availableBytes) = if (exists && readable) {
            probeSafCapacity(uri)
        } else {
            null to null
        }

        val detail = when {
            !hasPersisted -> "SAF permission missing or revoked; re-select Deck Drive"
            !exists || !readable -> "Drive disconnected or unavailable"
            !writable -> "Connected (Read-Only) — cannot write new archives here"
            else -> "Connected & Writable"
        }

        val next = DeckDriveStatus(
            configuredUri = configuredUri,
            displayName = name,
            isConnected = hasPersisted && exists && readable,
            isWritable = hasPersisted && exists && writable,
            hasPersistedPermission = hasPersisted,
            totalBytes = totalBytes,
            availableBytes = availableBytes,
            statusDetail = detail
        )
        _status.value = next
        next
    }

    private fun probeSafCapacity(treeUri: Uri): Pair<Long?, Long?> {
        return runCatching {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null to null
            context.contentResolver.openFileDescriptor(rootDoc.uri, "r")?.use { pfd ->
                val stat = Os.fstatvfs(pfd.fileDescriptor)
                val blockSize = stat.f_frsize.takeIf { it > 0 } ?: stat.f_bsize
                val total = stat.f_blocks * blockSize
                val avail = stat.f_bavail * blockSize
                if (total > 0) total to avail else null to null
            } ?: (null to null)
        }.getOrDefault(null to null)
    }

    fun getInternalStorageCapacity(): Pair<Long?, Long?> {
        return runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.totalBytes to stat.availableBytes
        }.getOrDefault(null to null)
    }

    fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    suspend fun ensureSubdirectory(treeUriString: String, vararg pathSegments: String): DocumentFile? =
        withContext(Dispatchers.IO) {
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) }.getOrNull()
                ?: return@withContext null
            if (!root.exists() || !root.canWrite()) return@withContext null
            var current: DocumentFile = root
            for (segment in pathSegments) {
                val safe = sanitizeSegment(segment)
                val existing = current.findFile(safe)
                current = if (existing != null && existing.isDirectory) {
                    existing
                } else {
                    current.createDirectory(safe) ?: return@withContext null
                }
            }
            current
        }

    suspend fun createUniqueSafFile(
        parentDir: DocumentFile,
        desiredName: String,
        mimeType: String
    ): DocumentFile? = withContext(Dispatchers.IO) {
        val cleanName = sanitizeFileName(desiredName)
        val dotIdx = cleanName.lastIndexOf('.')
        val base = if (dotIdx > 0) cleanName.substring(0, dotIdx) else cleanName
        val ext = if (dotIdx > 0) cleanName.substring(dotIdx) else ""
        var candidate = cleanName
        var attempt = 1
        while (parentDir.findFile(candidate) != null && attempt <= 200) {
            candidate = "${base}_($attempt)$ext"
            attempt++
        }
        parentDir.createFile(mimeType, candidate)
    }

    fun createUniqueInternalFile(parentDir: File, desiredName: String): File {
        parentDir.mkdirs()
        val cleanName = sanitizeFileName(desiredName)
        val dotIdx = cleanName.lastIndexOf('.')
        val base = if (dotIdx > 0) cleanName.substring(0, dotIdx) else cleanName
        val ext = if (dotIdx > 0) cleanName.substring(dotIdx) else ""
        var candidate = File(parentDir, cleanName)
        var attempt = 1
        while (candidate.exists() && attempt <= 200) {
            candidate = File(parentDir, "${base}_($attempt)$ext")
            attempt++
        }
        return candidate
    }

    fun openInputStream(uriOrPath: String): InputStream? {
        if (uriOrPath.isBlank()) return null
        return if (uriOrPath.startsWith("content://")) {
            runCatching { context.contentResolver.openInputStream(Uri.parse(uriOrPath)) }.getOrNull()
        } else {
            val file = File(uriOrPath)
            if (file.exists() && file.canRead()) file.inputStream() else null
        }
    }

    fun openOutputStream(uriOrPath: String): OutputStream? {
        if (uriOrPath.isBlank()) return null
        return if (uriOrPath.startsWith("content://")) {
            runCatching { context.contentResolver.openOutputStream(Uri.parse(uriOrPath), "w") }.getOrNull()
        } else {
            val file = File(uriOrPath)
            file.parentFile?.mkdirs()
            runCatching { file.outputStream() }.getOrNull()
        }
    }

    fun isUriOrFileAccessible(uriOrPath: String?): Boolean {
        if (uriOrPath.isNullOrBlank()) return false
        return if (uriOrPath.startsWith("content://")) {
            runCatching {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(uriOrPath))
                doc?.exists() == true && doc.canRead()
            }.getOrDefault(false)
        } else {
            val f = File(uriOrPath)
            f.exists() && f.canRead()
        }
    }

    suspend fun runWriteReadSelfTest(treeUriString: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val testDir = ensureSubdirectory(treeUriString, "CyberDeck", "_SelfTest")
                ?: error("Cannot create CyberDeck/_SelfTest folder on selected Deck Drive")
            val testFile = createUniqueSafFile(testDir, "deck_probe.txt", "text/plain")
                ?: error("Cannot create test file on selected Deck Drive")
            val payload = "Offline Cyber Deck SAF probe ${System.currentTimeMillis()}"
            context.contentResolver.openOutputStream(testFile.uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Failed opening output stream on Deck Drive")

            val readBack = context.contentResolver.openInputStream(testFile.uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: error("Failed reading test file back from Deck Drive")

            val deleted = testFile.delete()
            if (readBack != payload) {
                error("Read-back verification mismatch")
            }
            "Write + read-back verified (${if (deleted) "temp file cleaned up" else "cleanup skipped"})"
        }
    }

    fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            return (file.name.ifBlank { "file" }) to file.length()
        }
        var name = uri.lastPathSegment ?: "document"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .ifBlank { "cyberdeck_file" }
            .take(120)
    }

    private fun sanitizeSegment(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifBlank { "CyberDeck" }
    }
}
