package org.filezilla.android.ui

/**
 * Where a download lands, or why it cannot yet.
 *
 * There used to be two answers to that question. Pasting put a file in the
 * folder the other pane was showing; the download button put it in a folder
 * chosen once through the system picker and never seen again -- and the
 * header carried a line about that folder whether or not anything was going
 * to use it. Two destinations, one of them invisible, and the app's own
 * question to the user was "어디로 갔지".
 *
 * There is one answer now: the other pane. Two panes passing things between
 * them is what this app is, so the pane says where things go. Changing the
 * destination is changing what that pane is showing, which is a tap on a
 * breadcrumb -- and, unlike a remembered setting, is on screen the whole
 * time.
 */
sealed interface Destination {

    /** The folder on the phone that the other pane is showing. */
    data class Folder(val path: String) : Destination

    /** The other pane is on a server, or on nothing: there is nowhere to put it. */
    data object OtherPaneNotLocal : Destination

    /** The other pane is the phone, but the app may not read the phone yet. */
    data object NoStorageAccess : Destination

    /** The other pane has not listed anything yet, so it has no folder. */
    data object NowhereYet : Destination
}

/**
 * The destination a download from one pane would use.
 *
 * [other] is the state of the pane that is not the one being downloaded from.
 * Refusing is deliberate and is the decision the user made: the alternative,
 * quietly falling back to Downloads, is exactly the invisible destination
 * this replaces.
 */
fun destinationFor(other: BrowseState, storageGranted: Boolean): Destination = when {
    !other.isLocal -> Destination.OtherPaneNotLocal
    !storageGranted -> Destination.NoStorageAccess
    other.path.isEmpty() -> Destination.NowhereYet
    else -> Destination.Folder(other.path)
}
