package com.offlinecyberdeck.core.model

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

enum class SearchEngineOption(
    val label: String,
    val templateUrl: String
) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s"),
    WIKIPEDIA("Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s"),
    CUSTOM("Custom", "https://duckduckgo.com/?q=%s")
}

enum class ArchiveStatus(val label: String) {
    QUEUED("Queued"),
    CAPTURING("Capturing"),
    READER_SNAPSHOT("Reader Snapshot"),
    PARTIAL_ARCHIVE("Partial Archive"),
    FULL_ARCHIVE("Full Archive"),
    FAILED("Failed")
}

enum class StorageTarget(val label: String) {
    INTERNAL("Internal Storage"),
    DECK_DRIVE("Deck Drive (USB-C SSD)")
}

enum class DownloadStatus(val label: String) {
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

enum class DuplicatePolicy(val label: String) {
    RENAME("Auto-rename"),
    ASK("Ask / Keep both")
}

enum class LibraryCategory(val label: String) {
    ENCYCLOPEDIA("Encyclopedia & Knowledge"),
    BOOKS("Books & Literature"),
    EDUCATION("Education & Courses"),
    PROGRAMMING("Programming & IT"),
    NETWORKING("Networking & Cybersecurity"),
    SCIENCE("Science & Nature"),
    HISTORY("History & Culture"),
    DIY("DIY & Repair"),
    LANGUAGES("Languages & Dictionaries"),
    MAPS("Maps & Travel"),
    DATABASES("Databases & Public Data"),
    MEDIA("Media"),
    FOOD("Food & Recipes")
}

enum class LibraryFileType(val label: String) {
    PDF("PDF"),
    EPUB("EPUB"),
    HTML("HTML"),
    TEXT("Text / Markdown"),
    IMAGE("Image"),
    AUDIO("Audio"),
    VIDEO("Video"),
    ZIP("Archive (ZIP)"),
    ZIM("Kiwix (ZIM)"),
    MAP("Map Package"),
    OTHER("Document")
}

enum class MediaAvailability(val label: String) {
    AVAILABLE_OFFLINE("Available Offline"),
    METADATA_ONLY("No Local File"),
    DRIVE_DISCONNECTED("Drive Disconnected")
}

enum class MainDestination(val route: String, val label: String) {
    BROWSER("browser", "Browser"),
    LIBRARY("library", "Vault"),
    MEDIA("media", "Media"),
    DOWNLOADS("downloads", "Downloads"),
    BOOKMARKS("bookmarks", "Bookmarks"),
    HISTORY("history", "History"),
    SEARCH("search", "Search"),
    SETTINGS("settings", "Settings")
}

data class DeckDriveStatus(
    val configuredUri: String? = null,
    val displayName: String = "Not connected",
    val isConnected: Boolean = false,
    val isWritable: Boolean = false,
    val hasPersistedPermission: Boolean = false,
    val totalBytes: Long? = null,
    val availableBytes: Long? = null,
    val statusDetail: String = "No external Deck Drive selected"
)

data class StorageDashboardStats(
    val internalTotalBytes: Long? = null,
    val internalAvailableBytes: Long? = null,
    val internalAppArchivesBytes: Long = 0L,
    val internalAppDownloadsBytes: Long = 0L,
    val archiveCount: Int = 0,
    val libraryCount: Int = 0,
    val mediaLocalCount: Int = 0,
    val mediaMetadataOnlyCount: Int = 0,
    val downloadCount: Int = 0,
    val deckDrive: DeckDriveStatus = DeckDriveStatus()
)

enum class SearchResultType(val label: String) {
    ARCHIVE("Archived Pages"),
    LIBRARY("Knowledge Vault"),
    MEDIA("Media Vault"),
    BOOKMARK("Bookmarks"),
    HISTORY("History")
}

data class UnifiedSearchResult(
    val id: String,
    val type: SearchResultType,
    val title: String,
    val subtitle: String,
    val tags: String = "",
    val score: Int = 0,
    val isAvailableOffline: Boolean = true,
    val targetRef: String = ""
)

data class PackageDirectoryEntry(
    val id: String,
    val name: String,
    val category: LibraryCategory,
    val language: String,
    val estimatedSize: String,
    val description: String,
    val sourceUrl: String,
    val formatNote: String
)
