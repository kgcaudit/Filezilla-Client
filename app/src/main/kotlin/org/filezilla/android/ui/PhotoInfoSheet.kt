package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.files.ExifFacts
import org.filezilla.android.ui.theme.tiles
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * What a photo's EXIF says, and -- for the user's own JPEG, PNG or WebP -- the
 * three lossless edits over it: a quarter-turn, and two strips that clean the
 * location or everything identifying off before it is shared.
 *
 * Every row is dropped when its fact is missing, and a whole section with it,
 * so the sheet is only as long as the photo has something to say. The edit
 * block is hidden outright for a format that cannot be written or a copy of a
 * server file, with one calm line in its place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoInfoSheet(
    info: MainViewModel.PhotoInfo,
    onRotate: (clockwise: Boolean) -> Unit,
    onClearLocation: () -> Unit,
    onClearAll: () -> Unit,
    onOpenMap: (lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        SheetContent {
            PhotoInfoBody(info, onRotate, onClearLocation, onClearAll, onOpenMap)
        }
    }
}

/**
 * The sheet's contents, apart from the sheet -- so a screenshot test can render
 * it, since a modal sheet draws in a window of its own that such a test never
 * sees inside (the same reason [QueueSettings] is split from its sheet).
 */
@Composable
fun PhotoInfoBody(
    info: MainViewModel.PhotoInfo,
    onRotate: (clockwise: Boolean) -> Unit,
    onClearLocation: () -> Unit,
    onClearAll: () -> Unit,
    onOpenMap: (lat: Double, lon: Double) -> Unit,
) {
    var confirmLocation by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val facts = info.facts

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.photo_info_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp),
                )

                Header(info)

                // 촬영
                val date = facts.dateTaken?.let { formatDate(it) }
                Section(stringResource(R.string.photo_section_shot), has = date != null) {
                    date?.let { Field(stringResource(R.string.photo_field_date), it) }
                }

                // 카메라
                val device = listOfNotNull(facts.cameraMake, facts.cameraModel).joinToString(" · ").ifBlank { null }
                Section(stringResource(R.string.photo_section_camera), has = device != null || facts.lensModel != null) {
                    device?.let { Field(stringResource(R.string.photo_field_device), it) }
                    facts.lensModel?.let { Field(stringResource(R.string.photo_field_lens), it) }
                }

                // 노출
                val exposure = exposureLine(facts)
                val focal = focalLine(facts)
                Section(stringResource(R.string.photo_section_exposure), has = exposure != null || focal != null) {
                    exposure?.let { Field(stringResource(R.string.photo_field_exposure), it) }
                    focal?.let { Field(stringResource(R.string.photo_field_focal), it) }
                }

                // 위치 -- with the map link only when there is a place to open.
                if (facts.hasLocation) {
                    SectionHeader(stringResource(R.string.photo_section_location))
                    Field(
                        label = stringResource(R.string.photo_field_coords),
                        value = coordinates(facts.latitude!!, facts.longitude!!),
                        trailing = {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenMap(facts.latitude, facts.longitude) }
                                    .defaultMinSize(minHeight = 48.dp)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_action_map),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    stringResource(R.string.photo_open_map),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        },
                    )
                }

                if (!facts.hasAny) {
                    Text(
                        stringResource(R.string.photo_exif_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))

                if (info.editable) {
                    SectionHeader(
                        stringResource(R.string.photo_section_edit) + "  ·  " +
                            stringResource(R.string.photo_edit_scope_local),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ActionChip(R.drawable.ic_action_rotate_left, stringResource(R.string.photo_rotate_left), danger = false) { onRotate(false) }
                        ActionChip(R.drawable.ic_action_rotate_right, stringResource(R.string.photo_rotate_right), danger = false) { onRotate(true) }
                        if (facts.hasLocation) {
                            ActionChip(R.drawable.ic_action_location_off, stringResource(R.string.photo_remove_location), danger = true) { confirmLocation = true }
                        }
                        ActionChip(R.drawable.ic_action_delete, stringResource(R.string.photo_remove_all), danger = true) { confirmAll = true }
                    }
                } else {
                    Text(
                        stringResource(R.string.photo_not_editable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

    if (confirmLocation) {
        OloConfirmDialog(
            title = stringResource(R.string.photo_remove_location_confirm),
            detail = stringResource(R.string.photo_remove_location_detail),
            confirmLabel = stringResource(R.string.photo_remove_location),
            onDismiss = { confirmLocation = false },
            onConfirm = { confirmLocation = false; onClearLocation() },
        )
    }
    if (confirmAll) {
        OloConfirmDialog(
            title = stringResource(R.string.photo_remove_all_confirm),
            detail = stringResource(R.string.photo_remove_all_detail),
            confirmLabel = stringResource(R.string.photo_remove_all),
            onDismiss = { confirmAll = false },
            onConfirm = { confirmAll = false; onClearAll() },
        )
    }
}

@Composable
private fun Header(info: MainViewModel.PhotoInfo) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The photo's own thumbnail, falling back to the image-hue tile when it
        // cannot be decoded (an unsupported format, a server copy not yet here).
        if (Thumbnails.handles(FileKind.IMAGE)) {
            EntryThumb(file = info.file, kind = FileKind.IMAGE, contentDescription = null, size = 52.dp)
        } else {
            FileTile(kind = FileKind.IMAGE, colour = MaterialTheme.tiles.image, contentDescription = null, size = 52.dp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                info.file.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOfNotNull(
                formatSize(info.file.length()).takeIf { info.file.length() > 0 },
                info.facts.width?.let { w -> info.facts.height?.let { h -> "$w × $h" } },
                info.file.name.substringAfterLast('.', "").uppercase().ifBlank { null },
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A section that draws its header and body only when it has something to show. */
@Composable
private inline fun Section(title: String, has: Boolean, body: @Composable () -> Unit) {
    if (!has) return
    SectionHeader(title)
    body()
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun Field(label: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp).padding(top = 2.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun ActionChip(
    @androidx.annotation.DrawableRes glyph: Int,
    label: String,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val fg = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(78.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(glyph), contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 2,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

// --------------------------------------------------------------- formatting

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun coordinates(lat: Double, lon: Double): String = "%.4f, %.4f".format(lat, lon)

/** "1/120초 · f/1.7 · ISO 50", with only the parts the photo carries. */
@Composable
private fun exposureLine(facts: ExifFacts): String? {
    val parts = buildList {
        facts.exposureSeconds?.let { add(stringResource(R.string.photo_shutter, shutter(it))) }
        facts.fNumber?.let { add("f/%.1f".format(it)) }
        facts.iso?.let { add(stringResource(R.string.photo_iso, it)) }
    }
    return parts.ifEmpty { null }?.joinToString(" · ")
}

/** "6.3mm" and, when the camera gives it, the 35mm-equivalent beside it. */
@Composable
private fun focalLine(facts: ExifFacts): String? {
    val f = facts.focalLength ?: return null
    val base = "%.1fmm".format(f)
    return facts.focalLength35?.let { "$base (${stringResource(R.string.photo_focal_35, it)})" } ?: base
}

/** The bare shutter number -- "1/120" or "2" -- the unit is a format string. */
private fun shutter(seconds: Double): String =
    if (seconds >= 1.0) "%.0f".format(seconds) else "1/${(1.0 / seconds).roundToInt()}"
