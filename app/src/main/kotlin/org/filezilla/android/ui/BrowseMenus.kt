package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.ftp.listing.DirectoryEntry

/**
 * Everything the Files screen can do, behind one ⋮.
 *
 * The app bar had four icons competing for a phone's width and no room for
 * what a file manager is actually expected to offer. The two actions that are
 * used constantly -- new directory and upload -- stay as icons; the rest moves
 * in here, in the order a person reaches for them: work with what is listed,
 * change how it is listed, then the occasional settings.
 */
@Composable
fun BrowseOverflow(
    filterOpen: Boolean,
    onSelectMode: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleFilter: () -> Unit,
    onViewOptions: () -> Unit,
    onRefresh: () -> Unit,
    /** Null on a pane showing the phone, which makes folders with its own button. */
    onNewDirectory: (() -> Unit)? = null,
    /** Null on a pane showing the phone: "up" from there is the other pane. */
    onUpload: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }

    // The Box is what makes the menu come out of the button. A DropdownMenu
    // anchors to its own parent layout node, and these two were siblings in
    // the header row -- so the anchor was the row, which spans the screen,
    // and a menu opened from a button on the right came out at the far left
    // of the phone, under nothing.
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onNewDirectory?.let { action ->
                Item(R.string.new_folder_title, Icons.Filled.CreateNewFolder) {
                    open = false
                    action()
                }
            }
            onUpload?.let { action ->
                Item(R.string.browse_upload, Icons.Filled.Upload) {
                    open = false
                    action()
                }
            }
            if (onNewDirectory != null || onUpload != null) HorizontalDivider()
            // One picture per entry. These two were the same checklist icon,
            // and the folder options below them were the same eye twice --
            // so the column of icons told the reader nothing that the words
            // beside it had not already said, and read as decoration.
            Item(R.string.menu_select, Icons.Filled.CheckCircleOutline) {
                open = false
                onSelectMode()
            }
            Item(R.string.menu_select_all, Icons.Filled.DoneAll) {
                open = false
                onSelectAll()
            }
            HorizontalDivider()
            Item(
                if (filterOpen) R.string.filter_clear else R.string.menu_filter,
                Icons.Filled.FilterList,
            ) {
                open = false
                onToggleFilter()
            }
            // "Folders first" and "show hidden" used to sit here as two
            // checkboxes as well as in view options. A checkbox inside a
            // menu is neither a menu entry nor a setting, and having the
            // same two settings in two places meant they could be read in
            // two places and believed in neither. They live in view options,
            // which is the line directly above.
            Item(R.string.menu_view_options, Icons.Filled.Tune) {
                open = false
                onViewOptions()
            }
            HorizontalDivider()
            Item(R.string.browse_refresh, Icons.Filled.Refresh) {
                open = false
                onRefresh()
            }
        }
    }
}

@Composable
private fun Item(labelRes: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                // Material's default is onSurfaceVariant already, but the
                // size is not: at 24dp against 14sp labels the icons were
                // the heaviest thing in the menu and the words the lightest.
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}

/**
 * View and sort, laid out as a panel rather than a list of menu entries.
 *
 * Sorting has two parts -- what to sort by and which way round -- and a flat
 * menu hides that: the user picks "size" and cannot see that it is still
 * descending from last time. Shown together, the current state is one glance.
 */
@Composable
fun ViewOptionsDialog(
    options: BrowseOptions,
    onDismiss: () -> Unit,
    onApply: (BrowseOptions) -> Unit,
) {
    OloInfoDialog(
        title = stringResource(R.string.menu_view_options),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(R.string.view_mode)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(R.string.view_list, Icons.AutoMirrored.Filled.ViewList, options.viewMode == ViewMode.LIST) {
                        onApply(options.copy(viewMode = ViewMode.LIST))
                    }
                    Choice(R.string.view_grid, Icons.Filled.GridView, options.viewMode == ViewMode.GRID) {
                        onApply(options.copy(viewMode = ViewMode.GRID))
                    }
                }

                Label(R.string.sort_mode)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortChoice(R.string.sort_name, SortKey.NAME, options, onApply)
                    SortChoice(R.string.sort_date, SortKey.DATE, options, onApply)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortChoice(R.string.sort_size, SortKey.SIZE, options, onApply)
                    SortChoice(R.string.sort_type, SortKey.TYPE, options, onApply)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(R.string.sort_ascending, Icons.Filled.ArrowUpward, options.ascending) {
                        onApply(options.copy(ascending = true))
                    }
                    Choice(R.string.sort_descending, Icons.Filled.ArrowDownward, !options.ascending) {
                        onApply(options.copy(ascending = false))
                    }
                }

                Label(R.string.menu_folder_options)
                Toggle(R.string.option_folders_first, options.foldersFirst) {
                    onApply(options.copy(foldersFirst = it))
                }
                Toggle(R.string.option_show_hidden, options.showHidden) {
                    onApply(options.copy(showHidden = it))
                }
            }
        },
    )
}

@Composable
private fun Label(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Choice(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ChoiceChip(
        label = stringResource(labelRes),
        selected = selected,
        onClick = onClick,
        icon = icon,
    )
}

@Composable
private fun SortChoice(
    labelRes: Int,
    key: SortKey,
    options: BrowseOptions,
    onApply: (BrowseOptions) -> Unit,
) {
    ChoiceChip(
        label = stringResource(labelRes),
        selected = options.sortKey == key,
        onClick = { onApply(options.copy(sortKey = key)) },
        icon = Icons.AutoMirrored.Filled.Sort,
    )
}

@Composable
private fun Toggle(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) })
        Text(stringResource(labelRes), modifier = Modifier.padding(start = 4.dp))
    }
}

/** Everything the listing knows about one entry, which is more than a row shows. */
@Composable
fun PropertiesDialog(entry: DirectoryEntry, path: String, onDismiss: () -> Unit) {
    val unknown = stringResource(R.string.props_unknown)
    OloInfoDialog(
        title = stringResource(R.string.props_title),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(R.string.props_name, entry.name)
                Field(
                    R.string.props_kind,
                    stringResource(
                        when {
                            entry.isLink -> R.string.props_kind_link
                            entry.isDirectory -> R.string.props_kind_folder
                            else -> R.string.props_kind_file
                        },
                    ),
                )
                if (!entry.isDirectory) {
                    Field(R.string.props_size, formatSize(entry.size).ifBlank { unknown })
                }
                Field(R.string.props_modified, formatEntryTime(entry).ifBlank { unknown })
                Field(R.string.props_permissions, entry.permissions ?: unknown)
                Field(R.string.props_owner, entry.ownerGroup ?: unknown)
                Field(R.string.props_path, remotePathOf(path, entry.name))
            }
        },
    )
}

@Composable
private fun Field(labelRes: Int, value: String) {
    Column {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
