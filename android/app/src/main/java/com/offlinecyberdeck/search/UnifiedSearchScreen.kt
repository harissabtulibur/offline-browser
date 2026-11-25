package com.offlinecyberdeck.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinecyberdeck.CyberDeckApplication
import com.offlinecyberdeck.core.model.SearchResultType
import com.offlinecyberdeck.core.model.UnifiedSearchResult
import com.offlinecyberdeck.ui.theme.DeckAmber
import com.offlinecyberdeck.ui.theme.DeckGreen
import kotlinx.coroutines.launch

@Composable
fun UnifiedSearchScreen(
    app: CyberDeckApplication,
    onOpenArchive: (String) -> Unit,
    onOpenLibraryItem: (String) -> Unit,
    onOpenMediaItem: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val recentSearches by app.searchRepository.recentSearchesFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var selectedTypes by remember { mutableStateOf(SearchResultType.entries.toSet()) }
    var results by remember { mutableStateOf<List<UnifiedSearchResult>>(emptyList()) }

    LaunchedEffect(query, selectedTypes) {
        results = if (query.isBlank()) {
            emptyList()
        } else {
            app.searchRepository.searchOffline(query, selectedTypes)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Unified Offline Search", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            placeholder = { Text("Search archives, books, media, bookmarks, history...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                scope.launch { app.searchRepository.recordSearch(query) }
            }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchResultType.entries.forEach { type ->
                val enabled = type in selectedTypes
                FilterChip(
                    selected = enabled,
                    onClick = {
                        selectedTypes = if (enabled && selectedTypes.size > 1) {
                            selectedTypes - type
                        } else {
                            selectedTypes + type
                        }
                    },
                    label = { Text(type.label) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (query.isBlank()) {
            if (recentSearches.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent Searches", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { scope.launch { app.searchRepository.clearRecentSearches() } }) {
                        Text("Clear")
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recentSearches, key = { it.query }) { rec ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { query = rec.query }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null)
                            Text(rec.query, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Search works 100% offline across all local archives, Knowledge Vault documents, media metadata, bookmarks, and history.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No offline matches found for '$query'.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val grouped = remember(results) { results.groupBy { it.type } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEach { (type, groupItems) ->
                    item(key = "header_${type.name}") {
                        Text(
                            "${type.label} (${groupItems.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(groupItems, key = { "${it.type.name}_${it.id}" }) { res ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.searchRepository.recordSearch(query) }
                                    when (res.type) {
                                        SearchResultType.ARCHIVE -> onOpenArchive(res.targetRef)
                                        SearchResultType.LIBRARY -> onOpenLibraryItem(res.targetRef)
                                        SearchResultType.MEDIA -> onOpenMediaItem(res.targetRef)
                                        SearchResultType.BOOKMARK,
                                        SearchResultType.HISTORY -> onOpenUrl(res.targetRef)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val icon = when (res.type) {
                                    SearchResultType.ARCHIVE -> Icons.Filled.Archive
                                    SearchResultType.LIBRARY -> Icons.Filled.MenuBook
                                    SearchResultType.MEDIA -> Icons.Filled.VideoLibrary
                                    SearchResultType.BOOKMARK -> Icons.Filled.Bookmark
                                    SearchResultType.HISTORY -> Icons.Filled.History
                                }
                                Icon(icon, contentDescription = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(res.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        res.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (res.isAvailableOffline) DeckGreen else DeckAmber,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
