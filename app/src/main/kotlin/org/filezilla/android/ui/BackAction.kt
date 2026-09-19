package org.filezilla.android.ui

import org.filezilla.android.R

/**
 * The four places the app has, in the order the bar shows them.
 *
 * Here rather than beside the screen that draws them because the back button
 * has to reason about them too, and that reasoning is worth testing without a
 * device attached.
 */
enum class Tab(val label: Int) {
    SITES(R.string.tab_sites),
    BROWSE(R.string.tab_browse),
    QUEUE(R.string.tab_queue),
    LOG(R.string.tab_log),
}

/**
 * What the system back button does next.
 *
 * A file manager gives back several jobs, and getting the order wrong is what
 * made the app close out of nowhere: back went straight to the launcher from
 * anywhere, so walking six folders deep and then reaching for back lost the
 * whole session instead of climbing one step.
 *
 * Kept apart from the screen so the order can be read, and tested, without a
 * device in the loop.
 */
enum class BackAction {
    /** Drop a selection rather than navigate, so a mis-tap is one press away from undone. */
    CLEAR_SELECTION,

    /** Up one folder. The common case, and the reason the rest of this exists. */
    GO_UP,

    /** Back to the first tab from any other. */
    SHOW_FIRST_TAB,

    /** Say that another press leaves, and wait for it. */
    CONFIRM_EXIT,

    /** The second press. */
    EXIT,
}

/**
 * The first of [BackAction] that applies.
 *
 * [exitArmed] is the only piece of state this needs from outside: it is true
 * for the moment after a [CONFIRM_EXIT], and it is what turns the next press
 * into [EXIT] rather than another warning.
 */
fun backActionFor(
    tab: Tab,
    selecting: Boolean,
    canGoUp: Boolean,
    exitArmed: Boolean,
): BackAction = when {
    // Both only mean anything on the files tab; a selection made there is
    // still held while the user reads the log, and back should not silently
    // discard it from a screen that never showed it.
    tab == Tab.BROWSE && selecting -> BackAction.CLEAR_SELECTION
    tab == Tab.BROWSE && canGoUp -> BackAction.GO_UP
    tab != FIRST_TAB -> BackAction.SHOW_FIRST_TAB
    exitArmed -> BackAction.EXIT
    else -> BackAction.CONFIRM_EXIT
}

/** Where back finally lands, and where the app opens. */
val FIRST_TAB = Tab.SITES

/** How long a [BackAction.CONFIRM_EXIT] stays armed. */
const val EXIT_CONFIRM_MILLIS = 2_500L
