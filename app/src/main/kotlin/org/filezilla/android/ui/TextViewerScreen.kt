package org.filezilla.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.viewer.Syntax
import org.filezilla.android.viewer.TextFiles

/**
 * The app's own text reader: shows a file, highlights code, finds text in it,
 * and -- on the phone's own files -- lets it be edited and saved.
 *
 * One text box does all of it. Read-only until editing is turned on, coloured
 * by a rough syntax pass, and searched by moving the selection from match to
 * match, which is also how the box scrolls itself to each one. A file too big
 * for an editor to hold, or one that would not read, says so and offers the
 * door back rather than freezing on the way in.
 */
@Composable
fun TextViewerScreen(viewer: MainViewModel.TextViewer, model: MainViewModel) {
    var loaded by remember(viewer.file) { mutableStateOf<TextFiles.Loaded?>(null) }
    var failed by remember(viewer.file) { mutableStateOf(false) }
    var value by remember(viewer.file) { mutableStateOf(TextFieldValue("")) }
    var editing by remember(viewer.file) { mutableStateOf(false) }
    var searching by remember(viewer.file) { mutableStateOf(false) }
    var query by remember(viewer.file) { mutableStateOf("") }
    var saved by remember(viewer.file) { mutableStateOf(true) }

    LaunchedEffect(viewer.file) {
        val result = model.loadText(viewer.file)
        if (result == null) failed = true else {
            loaded = result
            value = TextFieldValue(result.text)
        }
    }

    val lang = remember(viewer.name) { Syntax.langFor(viewer.name) }
    val matches = remember(value.text, query) { matchesOf(value.text, query) }

    fun jumpTo(which: Int) {
        if (matches.isEmpty()) return
        val i = ((which % matches.size) + matches.size) % matches.size
        val at = matches[i]
        // Moving the selection onto the match is what scrolls the box to it.
        value = value.copy(selection = TextRange(at, at + query.length))
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding().imePadding()) {
            ViewerBar(
                name = viewer.name,
                onClose = model::closeTextViewer,
            ) {
                if (matches.isNotEmpty() || searching) {
                    Text(
                        if (query.isEmpty()) "" else "${matches.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                IconButton(onClick = { searching = !searching }) {
                    Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                }
                if (viewer.editable && loaded != null) {
                    if (editing && !saved) {
                        IconButton(onClick = {
                            loaded?.let { l -> model.saveText(viewer.file, l, value.text) { ok -> saved = ok } }
                        }) { Icon(Icons.Filled.Save, stringResource(R.string.viewer_save)) }
                    }
                    IconButton(onClick = { editing = !editing }) {
                        Icon(
                            if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                            stringResource(if (editing) R.string.viewer_done_editing else R.string.viewer_edit),
                        )
                    }
                }
            }

            if (searching) {
                SearchRow(
                    query = query,
                    onQuery = { query = it },
                    matchCount = matches.size,
                    onPrev = { jumpTo(currentMatch(matches, value.selection.start) - 1) },
                    onNext = { jumpTo(currentMatch(matches, value.selection.start) + 1) },
                )
            }

            when {
                failed -> ViewerMessage(stringResource(R.string.viewer_text_too_big))
                loaded == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val colors = MaterialTheme.colorScheme
                    val transformation = remember(lang, query, colors) {
                        highlightTransformation(
                            lang = lang,
                            query = query,
                            keyword = colors.primary,
                            string = colors.tertiary,
                            comment = colors.onSurfaceVariant,
                            number = colors.secondary,
                            matchBackground = colors.primary.copy(alpha = 0.28f),
                        )
                    }
                    val monospace = lang != Syntax.Lang.PLAIN
                    val scroll = rememberScrollState()
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it; saved = false },
                        readOnly = !editing,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        ),
                        visualTransformation = transformation,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The top bar shared by both viewers: a way back, the name, and actions. */
@Composable
private fun ViewerBar(name: String, onClose: () -> Unit, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close))
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        actions()
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OloTextField(
            value = query,
            onValueChange = onQuery,
            label = stringResource(R.string.action_search),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.viewer_prev_match))
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.viewer_next_match))
        }
    }
}

@Composable
private fun ViewerMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The offsets in [text] where [query] appears, case-insensitively. */
private fun matchesOf(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = text.indexOf(query, 0, ignoreCase = true)
    while (from >= 0) {
        out += from
        from = text.indexOf(query, from + query.length, ignoreCase = true)
    }
    return out
}

/** The match at or after the cursor, so next/prev step from where you are. */
private fun currentMatch(matches: List<Int>, cursor: Int): Int {
    val at = matches.indexOfFirst { it >= cursor }
    return if (at < 0) matches.size - 1 else at
}

/**
 * Colours the text as it goes to the screen: syntax first, then every search
 * match on top, without changing a character or an offset. Plain rather than
 * composable so it can be built once inside a remember.
 */
private fun highlightTransformation(
    lang: Syntax.Lang,
    query: String,
    keyword: androidx.compose.ui.graphics.Color,
    string: androidx.compose.ui.graphics.Color,
    comment: androidx.compose.ui.graphics.Color,
    number: androidx.compose.ui.graphics.Color,
    matchBackground: androidx.compose.ui.graphics.Color,
): VisualTransformation = VisualTransformation { original ->
    val text = original.text
    val builder = AnnotatedString.Builder(text)
    for (span in Syntax.spans(text, lang)) {
        val colour = when (span.token) {
            Syntax.Token.KEYWORD -> keyword
            Syntax.Token.STRING -> string
            Syntax.Token.COMMENT -> comment
            Syntax.Token.NUMBER -> number
        }
        builder.addStyle(SpanStyle(color = colour), span.start, span.end)
    }
    if (query.isNotEmpty()) {
        for (at in matchesOf(text, query)) {
            builder.addStyle(SpanStyle(background = matchBackground), at, at + query.length)
        }
    }
    TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
}
