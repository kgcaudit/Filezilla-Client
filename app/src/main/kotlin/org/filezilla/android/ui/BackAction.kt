package org.filezilla.android.ui

/**
 * Where the app is.
 *
 * [FILES] is the app. The other three are places you go and come back from,
 * and they were four tabs along the bottom of every screen -- 80dp of it,
 * permanently. One of the four was a tab to the screen you were already on;
 * another listed the servers the storage sheet already lists; a third was a
 * diagnostic log. None of them earned a permanent seat, so none of them has
 * one: they open over the files screen and close again.
 */
enum class Screen { FILES, SITES, QUEUE, LOG, RECENTS, TRASH }

/** Where the app opens, and where back finally lands. */
val HOME = Screen.FILES

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

    /** Close whatever opened over the files screen. */
    CLOSE_SCREEN,

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
    screen: Screen,
    selecting: Boolean,
    canGoUp: Boolean,
    exitArmed: Boolean,
): BackAction = when {
    // Both only mean anything on the files screen; a selection made there is
    // still held while the user reads the log, and back should not silently
    // discard it from a screen that never showed it.
    screen == HOME && selecting -> BackAction.CLEAR_SELECTION
    screen == HOME && canGoUp -> BackAction.GO_UP
    screen != HOME -> BackAction.CLOSE_SCREEN
    exitArmed -> BackAction.EXIT
    else -> BackAction.CONFIRM_EXIT
}

/** How long a [BackAction.CONFIRM_EXIT] stays armed. */
const val EXIT_CONFIRM_MILLIS = 2_500L
