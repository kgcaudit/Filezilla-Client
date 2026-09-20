package org.filezilla.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * One option out of several, showing plainly whether it is the chosen one.
 *
 * Material's own filter chip fills the chosen one with `secondaryContainer`
 * and puts an outline round the rest. On this theme that container is
 * #EFE6DE against a #F7F4EF dialog -- a difference of about six percent,
 * which nobody can see. So the *outline* was the strongest mark on the row,
 * and the chips that were not chosen looked like the ones that were. The
 * view options dialog showed four sort keys and the user could not tell
 * which one was in force.
 *
 * Three signals now, deliberately more than one:
 *
 *  - the chosen one is **filled with the brand colour**, so it is the only
 *    saturated thing in the group and reads from across the room;
 *  - it carries a **check mark**, so it survives being photographed in
 *    greyscale, printed, or looked at by somebody who does not see the
 *    difference between clay and cream;
 *  - its **label is bold**, so the weight differs even where the colour is
 *    washed out by a bright screen outdoors.
 *
 * The ones not chosen are an outline and nothing else: no fill, muted ink.
 * The contrast between the two states is asserted in ChoiceContrastTest, so
 * a later palette change cannot quietly undo this.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the option means, when an icon says it faster than the word. */
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.primary else colors.surface,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
            )
            if (selected) {
                // Last, so the word does not move when the choice changes:
                // the chip grows on the right rather than shuffling its
                // label along, and a row of chips stays where it was.
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.chosen),
                    modifier = Modifier.padding(start = 6.dp).size(16.dp),
                )
            }
        }
    }
}


/**
 * One option in a row of them, drawn as a picture over its name.
 *
 * Chips in a wrapping row were what this replaced, and they had two
 * problems the reference design does not. Four sort keys wrapped to two
 * lines of two, so the group lost the shape that said "these four are one
 * question". And all four carried the same sort glyph -- the same picture
 * four times, which tells the reader nothing the word beside it had not
 * already said, and is exactly what was wrong with the overflow menu.
 *
 * One picture each, in one row, with the name under it. The chosen one
 * keeps the treatment the rest of the app uses -- filled with the brand
 * colour, bold, and a mark -- because the reference's thin underline is
 * subtle enough that the user had to look twice, and that is the complaint
 * this whole design came out of.
 */
@Composable
fun OptionTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown under the name when chosen: which way this sort runs. */
    footnote: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (selected) colors.primary else colors.surface,
            contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Only under the chosen one, because it is a property of that
        // choice rather than a choice of its own. It had a row to itself,
        // which made "ascending" look like a fifth thing to sort by.
        if (selected && footnote != null) {
            Text(
                footnote,
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                maxLines = 1,
            )
        }
    }
}
