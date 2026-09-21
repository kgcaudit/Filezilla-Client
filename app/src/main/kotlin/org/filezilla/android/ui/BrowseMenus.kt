package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Unarchive
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
    onAssociations: () -> Unit,
    onRefresh: () -> Unit,
    /** Null on a pane showing the phone, which makes folders with its own button. */
    onNewDirectory: (() -> Unit)? = null,
    /** Null on a pane showing the phone: "up" from there is the other pane. */
    onUpload: (() -> Unit)? = null,
    /** Set only inside an archive: unpack the whole of it. */
    onExtractAll: (() -> Unit)? = null,
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
            onExtractAll?.let { action ->
                Item(R.string.archive_extract_all, Icons.Filled.Unarchive) {
                    open = false
                    action()
                }
                HorizontalDivider()
            }
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
            Item(R.string.menu_select, Icons.Filled.CheckCircle) {
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
            // Where a remembered "always open .srt this way" is taken back.
            // Here because this is the menu about files rather than about
            // transfers, and because a choice that cannot be unmade is a
            // trap: the wrong app picked once for a kind of file opened
            // daily would be wrong for ever with nothing admitting it.
            Item(R.string.menu_associations, Icons.Filled.AppShortcut) {
                open = false
                onAssociations()
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
 * View and sort, as two rows of pictures rather than a wrapping list.
 *
 * What it replaced: six chips that wrapped to four lines, all four sort
 * keys carrying the same sort glyph, and "ascending / descending" spending
 * a whole row of its own as though it were a fifth thing to sort by. So
 * the panel said less than it could in more space than it needed, and the
 * one picture repeated four times said nothing at all.
 *
 * Now: one row per question, one picture per answer, and the direction
 * tucked under whichever key is chosen -- because it belongs to that key.
 * Tapping the chosen key again turns it round.
 */
@Composable
fun ViewOptionsDialog(
    options: BrowseOptions,
    /** True when this folder is already arranged by settings of its own. */
    onlyHere: Boolean,
    /** Null when there is no folder to pin settings to, and the box is hidden. */
    onOnlyHere: ((Boolean) -> Unit)?,
    onDismiss: () -> Unit,
    onApply: (BrowseOptions) -> Unit,
) {
    OloInfoDialog(
        title = stringResource(R.string.menu_view_options),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel(R.string.view_mode)
                Row(modifier = Modifier.fillMaxWidth()) {
                    OptionTile(
                        label = stringResource(R.string.view_list),
                        icon = Icons.AutoMirrored.Filled.ViewList,
                        selected = options.viewMode == ViewMode.LIST,
                        onClick = { onApply(options.copy(viewMode = ViewMode.LIST)) },
                        modifier = Modifier.weight(1f),
                    )
                    OptionTile(
                        label = stringResource(R.string.view_grid),
                        icon = Icons.Filled.GridView,
                        selected = options.viewMode == ViewMode.GRID,
                        onClick = { onApply(options.copy(viewMode = ViewMode.GRID)) },
                        modifier = Modifier.weight(1f),
                    )
                    // Two answers, four columns' worth of row: spacers so
                    // the pictures sit under the ones above rather than
                    // spreading to fill a row they do not need.
                    Spacer(modifier = Modifier.weight(2f))
                }

                SectionLabel(R.string.sort_mode)
                Row(modifier = Modifier.fillMaxWidth()) {
                    // A different picture for each, which is the whole
                    // point: an A-to-Z, a calendar, a set of bars and a
                    // page say what four identical sort glyphs did not.
                    SortTile(R.string.sort_name, Icons.Filled.SortByAlpha, SortKey.NAME, options, onApply)
                    SortTile(R.string.sort_date, Icons.Filled.CalendarMonth, SortKey.DATE, options, onApply)
                    SortTile(R.string.sort_size, Icons.Filled.BarChart, SortKey.SIZE, options, onApply)
                    SortTile(R.string.sort_type, Icons.Filled.Description, SortKey.TYPE, options, onApply)
                }

                SectionLabel(R.string.menu_folder_options)
                Toggle(R.string.option_folders_first, options.foldersFirst) {
                    onApply(options.copy(foldersFirst = it))
                }
                Toggle(R.string.option_show_hidden, options.showHidden) {
                    onApply(options.copy(showHidden = it))
                }
                // Last, and only where there is a folder to pin to. One
                // setting for the whole app means changing it on the way
                // into a folder of photos and changing it back on the way
                // out; this lets a folder keep its own and leaves every
                // other folder on the shared one.
                onOnlyHere?.let { set ->
                    Toggle(
                        labelRes = R.string.option_only_here,
                        checked = onlyHere,
                        detailRes = R.string.option_only_here_detail,
                        onChange = set,
                    )
                }
            }
        },
    )
}

/**
 * One sort key, with its direction under it when it is the one in force.
 *
 * Pressing the key that is already chosen turns the sort round, which is
 * how every list on a phone behaves and is why the direction no longer
 * needs a row of its own.
 */
@Composable
private fun RowScope.SortTile(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    key: SortKey,
    options: BrowseOptions,
    onApply: (BrowseOptions) -> Unit,
) {
    val chosen = options.sortKey == key
    OptionTile(
        label = stringResource(labelRes),
        icon = icon,
        selected = chosen,
        onClick = {
            onApply(
                if (chosen) {
                    options.copy(ascending = !options.ascending)
                } else {
                    options.copy(sortKey = key)
                },
            )
        },
        footnote = stringResource(
            if (options.ascending) R.string.sort_ascending_short else R.string.sort_descending_short,
        ),
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun Toggle(
    labelRes: Int,
    checked: Boolean,
    /** A line under the name, for a setting whose effect is not obvious. */
    detailRes: Int? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) })
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(labelRes))
            if (detailRes != null) {
                Text(
                    stringResource(detailRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                // The row this was opened from, so the dialog says which
                // file before it says anything about it. The name was the
                // first of seven identical label-and-value pairs, which
                // asks the reader to find it.
                FileHeading(entry.name, entry.isDirectory)

                SectionLabel(R.string.props_section_what)
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
                // The listing carries this and the dialog was throwing it
                // away: a row said "link" and then would not say a link to
                // what, which is the only thing anybody opens this to find
                // out about one.
                entry.linkTarget?.takeIf { it.isNotBlank() }?.let {
                    Field(R.string.props_link_target, it)
                }
                Field(R.string.props_modified, formatEntryTime(entry).ifBlank { unknown })

                // Who may do what with it, which is a different question
                // from what it is -- and the one that is usually being
                // looked up when a transfer has just been refused.
                SectionLabel(R.string.props_section_access)
                Field(R.string.props_permissions, entry.permissions ?: unknown)
                Field(R.string.props_owner, entry.ownerGroup ?: unknown)

                SectionLabel(R.string.props_section_where)
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
