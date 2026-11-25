package com.offlinecyberdeck.search

import com.offlinecyberdeck.core.database.CyberDeckDatabase
import com.offlinecyberdeck.core.database.entity.RecentSearchEntity
import com.offlinecyberdeck.core.model.SearchResultType
import com.offlinecyberdeck.core.model.UnifiedSearchResult
import com.offlinecyberdeck.core.storage.DeckDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

class UnifiedSearchRepository(
    private val db: CyberDeckDatabase,
    private val driveManager: DeckDriveManager
) {
    val recentSearchesFlow: Flow<List<RecentSearchEntity>> = db.recentSearchDao().observeRecent()

    suspend fun searchOffline(
        query: String,
        enabledTypes: Set<SearchResultType> = SearchResultType.entries.toSet()
    ): List<UnifiedSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val lower = trimmed.lowercase(Locale.US)
        val results = mutableListOf<UnifiedSearchResult>()

        if (SearchResultType.ARCHIVE in enabledTypes) {
            db.archiveDao().search(trimmed).forEach { page ->
                val score = scoreMatch(lower, page.title, page.url, page.tags, page.readerText)
                results += UnifiedSearchResult(
                    id = page.id,
                    type = SearchResultType.ARCHIVE,
                    title = page.title,
                    subtitle = "${page.status.label} • ${page.url}",
                    tags = page.tags,
                    score = score + 10,
                    isAvailableOffline = driveManager.isUriOrFileAccessible(page.directoryPathOrUri),
                    targetRef = page.id
                )
            }
        }

        if (SearchResultType.LIBRARY in enabledTypes) {
            db.libraryDao().search(trimmed).forEach { item ->
                val score = scoreMatch(lower, item.title, item.fileName, item.tags, item.description)
                results += UnifiedSearchResult(
                    id = item.id,
                    type = SearchResultType.LIBRARY,
                    title = item.title,
                    subtitle = "${item.category.label} • ${item.fileType.label}",
                    tags = item.tags,
                    score = score + 8,
                    isAvailableOffline = driveManager.isUriOrFileAccessible(item.fileUri),
                    targetRef = item.id
                )
            }
        }

        if (SearchResultType.MEDIA in enabledTypes) {
            db.mediaDao().search(trimmed).forEach { media ->
                val score = scoreMatch(
                    lower,
                    media.title,
                    media.channel,
                    media.tags,
                    media.localFileName.orEmpty()
                )
                val hasLocal = !media.localFileUri.isNullOrBlank() &&
                    driveManager.isUriOrFileAccessible(media.localFileUri)
                results += UnifiedSearchResult(
                    id = media.id,
                    type = SearchResultType.MEDIA,
                    title = media.title,
                    subtitle = buildString {
                        append(if (hasLocal) "Available Offline" else "Metadata Only (No Local File)")
                        if (media.channel.isNotBlank()) append(" • ${media.channel}")
                    },
                    tags = media.tags,
                    score = score + if (hasLocal) 8 else 3,
                    isAvailableOffline = hasLocal,
                    targetRef = media.id
                )
            }
        }

        if (SearchResultType.BOOKMARK in enabledTypes) {
            db.bookmarkDao().search(trimmed).forEach { bm ->
                val score = scoreMatch(lower, bm.title, bm.url, bm.tags, bm.folder)
                results += UnifiedSearchResult(
                    id = bm.id,
                    type = SearchResultType.BOOKMARK,
                    title = bm.title,
                    subtitle = bm.url,
                    tags = bm.tags,
                    score = score + 6,
                    isAvailableOffline = false,
                    targetRef = bm.url
                )
            }
        }

        if (SearchResultType.HISTORY in enabledTypes) {
            db.historyDao().search(trimmed).forEach { hist ->
                val score = scoreMatch(lower, hist.title, hist.url, "", "")
                results += UnifiedSearchResult(
                    id = hist.id,
                    type = SearchResultType.HISTORY,
                    title = hist.title.ifBlank { hist.url },
                    subtitle = hist.url,
                    tags = "",
                    score = score + 2,
                    isAvailableOffline = false,
                    targetRef = hist.url
                )
            }
        }

        results.sortedByDescending { it.score }
    }

    suspend fun recordSearch(query: String) {
        val clean = query.trim()
        if (clean.isNotEmpty()) {
            db.recentSearchDao().upsert(RecentSearchEntity(query = clean))
        }
    }

    suspend fun clearRecentSearches() {
        db.recentSearchDao().clearAll()
    }

    private fun scoreMatch(
        queryLower: String,
        title: String,
        secondary: String,
        tags: String,
        body: String
    ): Int {
        val t = title.lowercase(Locale.US)
        val s = secondary.lowercase(Locale.US)
        val tg = tags.lowercase(Locale.US)
        val b = body.lowercase(Locale.US)
        var score = 0
        if (t == queryLower) score += 100
        else if (t.startsWith(queryLower)) score += 65
        else if (t.contains(queryLower)) score += 45
        if (tg.contains(queryLower)) score += 30
        if (s.contains(queryLower)) score += 20
        if (b.contains(queryLower)) score += 10
        return score
    }
}
