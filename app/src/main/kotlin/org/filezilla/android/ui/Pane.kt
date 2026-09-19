package org.filezilla.android.ui

import org.filezilla.android.data.SiteEntity

/** Which of the two panes. Left and right, as they sit on the screen. */
enum class PaneId { LEFT, RIGHT }

/**
 * What a pane is looking at.
 *
 * A sealed type rather than a nullable site, because "no server chosen yet"
 * and "this pane shows the phone" are different states that used to be the
 * same null -- and the screen has a different thing to say about each.
 */
sealed interface PaneSource {

    /** The device's own storage. */
    data object Local : PaneSource

    /** A saved server. */
    data class Remote(val site: SiteEntity) : PaneSource

    /** Nothing picked yet; the pane offers a choice instead of a listing. */
    data object Empty : PaneSource
}

/**
 * Where each pane starts on a first run.
 *
 * Left on the phone and right on the server, which is the arrangement the
 * whole screen is for: what you have on one side, what you are sending it to
 * on the other.
 */
fun defaultSourceFor(pane: PaneId): PaneSource =
    if (pane == PaneId.LEFT) PaneSource.Local else PaneSource.Empty

/**
 * The pane the user is acting on.
 *
 * The toolbar and the file operations belong to whichever pane is in front,
 * so this is read from the pager rather than tracked separately -- two ideas
 * of which pane is current is one more than can be kept in step.
 */
fun paneAt(page: Int): PaneId = if (page == 0) PaneId.LEFT else PaneId.RIGHT

fun pageOf(pane: PaneId): Int = if (pane == PaneId.LEFT) 0 else 1

/**
 * The width at which both panes fit side by side.
 *
 * Two listings need room for two file names, and a file manager whose names
 * are all truncated is one nobody can use -- so the panes only sit together
 * once there is space for both to stay readable. 720dp is a folded phone
 * opened out, or a tablet; a phone on its own is nowhere near it.
 */
const val SIDE_BY_SIDE_WIDTH_DP = 720

/**
 * Whether to show both panes at once.
 *
 * Decided from the width alone rather than from a device class: a phone in
 * landscape, a folded phone opened out and a small tablet are three different
 * classes and one question, which is whether two file names fit.
 */
fun showsBothPanes(widthDp: Int): Boolean = widthDp >= SIDE_BY_SIDE_WIDTH_DP
