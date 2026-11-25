package com.offlinecyberdeck.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.offlinecyberdeck.core.database.dao.LibraryDao
import com.offlinecyberdeck.core.database.entity.LibraryItemEntity
import com.offlinecyberdeck.core.model.LibraryCategory
import com.offlinecyberdeck.core.model.LibraryFileType
import com.offlinecyberdeck.core.model.PackageDirectoryEntry
import com.offlinecyberdeck.core.model.StorageTarget
import com.offlinecyberdeck.core.storage.DeckDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class KnowledgeVaultRepository(
    private val context: Context,
    private val libraryDao: LibraryDao,
    private val driveManager: DeckDriveManager
) {
    val itemsFlow: Flow<List<LibraryItemEntity>> = libraryDao.observeAll()

    suspend fun importDocument(
        uri: Uri,
        category: LibraryCategory,
        language: String = "en",
        source: String = "Local Import",
        tags: String = "",
        copyIntoVault: Boolean = false
    ): Result<LibraryItemEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val (rawName, sizeBytes) = driveManager.queryDisplayNameAndSize(uri)
            val fileType = detectFileType(rawName)
            val title = rawName.substringBeforeLast('.').ifBlank { rawName }

            var persisted = false
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                persisted = true
            }

            val finalUriOrPath: String
            val finalSize: Long
            val target: StorageTarget

            if (copyIntoVault || !persisted) {
                val destFile = driveManager.createUniqueInternalFile(driveManager.internalVaultDir, rawName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { out ->
                        input.copyTo(out)
                    }
                } ?: error("Cannot read selected file")
                finalUriOrPath = destFile.absolutePath
                finalSize = destFile.length()
                target = StorageTarget.INTERNAL
            } else {
                finalUriOrPath = uri.toString()
                finalSize = sizeBytes.coerceAtLeast(0L)
                target = if (uri.toString().contains("external", ignoreCase = true) ||
                    uri.authority?.contains("externalstorage") == true
                ) StorageTarget.DECK_DRIVE else StorageTarget.INTERNAL
            }

            val item = LibraryItemEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                source = source,
                category = category,
                language = language,
                description = when (fileType) {
                    LibraryFileType.ZIM -> "Kiwix ZIM archive cataloged. In-app ZIM reader is not bundled; open with Kiwix or external ZIM viewer."
                    LibraryFileType.MAP -> "Offline map package cataloged. Open with an installed offline maps app."
                    else -> ""
                },
                fileUri = finalUriOrPath,
                fileName = rawName,
                fileType = fileType,
                sizeBytes = finalSize,
                storageTarget = target,
                tags = tags
            )
            libraryDao.upsert(item)
            item
        }
    }

    suspend fun updateItem(item: LibraryItemEntity) {
        libraryDao.upsert(item)
    }

    suspend fun deleteItem(item: LibraryItemEntity, deleteLocalCopy: Boolean) = withContext(Dispatchers.IO) {
        if (deleteLocalCopy && !item.fileUri.startsWith("content://")) {
            runCatching { File(item.fileUri).delete() }
        }
        libraryDao.delete(item)
    }

    fun isItemAvailable(item: LibraryItemEntity): Boolean {
        return driveManager.isUriOrFileAccessible(item.fileUri)
    }

    suspend fun loadTextOrEpubContent(item: LibraryItemEntity): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = driveManager.openInputStream(item.fileUri)
                ?: error("File unavailable. If stored on Deck Drive, verify the USB-C SSD is connected.")
            stream.use { input ->
                when (item.fileType) {
                    LibraryFileType.TEXT -> input.bufferedReader().readText()
                    LibraryFileType.HTML -> {
                        val raw = input.bufferedReader().readText()
                        Jsoup.parse(raw).text()
                    }
                    LibraryFileType.EPUB -> extractEpubReadableText(input)
                    else -> error("Built-in text reader supports TXT, MD, HTML, and EPUB.")
                }
            }
        }
    }

    private fun extractEpubReadableText(input: java.io.InputStream): String {
        val builder = StringBuilder()
        var chapters = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && chapters < 120) {
                val lower = entry.name.lowercase()
                if (!entry.isDirectory && (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
                    val html = zip.bufferedReader().readText()
                    val doc = Jsoup.parse(html)
                    val title = doc.title().trim()
                    val bodyText = doc.body()?.text()?.trim().orEmpty()
                    if (bodyText.isNotBlank()) {
                        if (title.isNotBlank()) builder.appendLine("=== $title ===").appendLine()
                        builder.appendLine(bodyText).appendLine().appendLine()
                        chapters++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return builder.toString().ifBlank { "No readable XHTML chapters found inside EPUB archive." }
    }

    fun buildExternalViewIntent(item: LibraryItemEntity): Intent? {
        val uri = if (item.fileUri.startsWith("content://")) {
            Uri.parse(item.fileUri)
        } else {
            val f = File(item.fileUri)
            if (!f.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
        val ext = item.fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun detectFileType(fileName: String): LibraryFileType {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> LibraryFileType.PDF
            "epub" -> LibraryFileType.EPUB
            "html", "htm" -> LibraryFileType.HTML
            "txt", "md", "csv", "json", "log" -> LibraryFileType.TEXT
            "png", "jpg", "jpeg", "webp", "gif" -> LibraryFileType.IMAGE
            "mp3", "m4a", "ogg", "opus", "wav", "flac" -> LibraryFileType.AUDIO
            "mp4", "mkv", "webm", "mov", "avi" -> LibraryFileType.VIDEO
            "zip" -> LibraryFileType.ZIP
            "zim" -> LibraryFileType.ZIM
            "mbtiles", "obf", "map", "gpx" -> LibraryFileType.MAP
            else -> LibraryFileType.OTHER
        }
    }

    fun getCuratedPackageDirectory(): List<PackageDirectoryEntry> = listOf(
        PackageDirectoryEntry(
            id = "kiwix_library",
            name = "Kiwix ZIM Library (Wikipedia, Wiktionary, WikiHow, iFixit)",
            category = LibraryCategory.ENCYCLOPEDIA,
            language = "Multi",
            estimatedSize = "50 MB – 100 GB+",
            description = "Official Kiwix repository of compressed offline ZIM packages. Download via browser to your Deck Drive, then catalog here or open in Kiwix.",
            sourceUrl = "https://library.kiwix.org/",
            formatNote = "Format: .zim (Cataloged in Vault; requires Kiwix app for full ZIM rendering)"
        ),
        PackageDirectoryEntry(
            id = "gutenberg",
            name = "Project Gutenberg Free Ebooks",
            category = LibraryCategory.BOOKS,
            language = "Multi",
            estimatedSize = "500 KB – 5 MB per book",
            description = "Public-domain EPUB, HTML, and plain text books readable directly inside Cyber Deck's built-in reader.",
            sourceUrl = "https://www.gutenberg.org/",
            formatNote = "Format: .epub / .html / .txt (Readable in-app)"
        ),
        PackageDirectoryEntry(
            id = "rfc_editor",
            name = "IETF RFC Technical Specifications",
            category = LibraryCategory.NETWORKING,
            language = "en",
            estimatedSize = "~100 KB per RFC",
            description = "Core networking protocols (TCP/IP, HTTP, DNS, TLS) in plain text and HTML formats.",
            sourceUrl = "https://www.rfc-editor.org/",
            formatNote = "Format: .txt / .html / .pdf (Readable in-app)"
        ),
        PackageDirectoryEntry(
            id = "organic_maps",
            name = "Organic Maps / OpenStreetMap Offline Extracts",
            category = LibraryCategory.MAPS,
            language = "Global",
            estimatedSize = "20 MB – 4 GB per region",
            description = "Offline vector maps and MBTiles packages. Catalog map packages in Vault and launch compatible offline map apps.",
            sourceUrl = "https://organicmaps.app/",
            formatNote = "Format: .mbtiles / .obf / .gpx (Catalog & external app handoff)"
        ),
        PackageDirectoryEntry(
            id = "usda_fdc",
            name = "USDA FoodData Central & Survival Guides",
            category = LibraryCategory.FOOD,
            language = "en",
            estimatedSize = "10 MB – 300 MB",
            description = "Public nutrition reference datasets and emergency preparedness PDF manuals.",
            sourceUrl = "https://fdc.nal.usda.gov/",
            formatNote = "Format: .pdf / .csv / .json (Readable in-app)"
        )
    )
}
