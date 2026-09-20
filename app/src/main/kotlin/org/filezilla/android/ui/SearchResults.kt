package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * The offer at the end of a filtered listing.
 *
 * The filter says what this folder holds; this says the rest of the tree has
 * not been looked at, and offers to look. Deliberately a thing the user taps
 * rather than something that happens: on a server the walk is a round trip
 * per folder, and doing that on every keystroke would hammer the server for
 * an answer nobody asked for.
 */
@Composable
fun SearchDeeperRow(shown: Int, onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSearch)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(R.string.search_deeper),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // What the filter did find, so the offer reads as "and also"
            // rather than as the only answer going.
            Text(
                stringResource(R.string.search_here_count, shown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a walk below this folder turned up.
 *
 * Shown instead of the listing, not merged into it, because these rows are
 * from other folders: a selection spanning several folders has no single
 * place to paste into, and "select all" would mean something different in
 * every row. So a result is a way *to* somewhere -- tapping one opens the
 * folder it lives in, with the filter still on, where every ordinary action
 * works as it always did.
 */
@Composable
fun SearchResults(
    search: SearchState,
    onOpen: (SearchHit) -> Unit,
    onStopWalking: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.search_results_title, search.needle),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Three different sentences, because "500개" after a
                        // search that gave up at five hundred means
                        // something else entirely from "500개" after one
                        // that read the whole tree.
                        when {
                            search.running -> stringResource(
                                R.string.search_running,
                                search.hits.size,
                                search.foldersRead,
                            )
                            search.truncated -> stringResource(R.string.search_truncated, search.hits.size)
                            search.cancelled -> stringResource(R.string.search_stopped, search.hits.size)
                            else -> stringResource(R.string.search_found, search.hits.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (search.running) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = onStopWalking) {
                        Text(stringResource(R.string.search_stop))
                    }
                } else {
                    androidx.compose.material3.IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_close),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        if (search.hits.isEmpty() && !search.running) {
            EmptyState(
                title = stringResource(R.string.search_none, search.needle),
                detail = stringResource(R.string.search_none_detail),
                icon = R.drawable.ic_tile_search,
            )
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(search.hits, key = { it.path }) { hit ->
                HitRow(hit, onOpen)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onOpen: (SearchHit) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(hit) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val kind = kindOf(hit.entry.name, hit.entry.isDirectory)
        FileTile(kind = kind, colour = colourFor(kind), contentDescription = null)
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                hit.entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The folder, which is the reason a result is worth showing at
            // all: the same name in two places is the usual case, and a list
            // of bare names could not tell them apart.
            Text(
                hit.folder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
