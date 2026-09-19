package org.filezilla.android.ui

/** Which way a row is being moved. */
enum class Move {
    UP,
    DOWN,
}

/**
 * Moving a row up or down a list.
 *
 * Kept apart from the database and the screen because it is the part that can
 * be wrong quietly: an off-by-one swaps the wrong pair, and a move off either
 * end either throws or silently does nothing. The screen also has to know
 * whether a move is possible before it is asked for, so that the button on
 * the top row is visibly unavailable rather than dead.
 */
object SiteOrder {

    /**
     * [ids] with [id] one place [towards], or null when it cannot go.
     *
     * Null rather than the unchanged list, so a caller cannot write back an
     * order it did not change, and so the screen has one answer to ask for
     * instead of comparing lists.
     */
    fun moved(ids: List<String>, id: String, towards: Move): List<String>? {
        val from = ids.indexOf(id)
        if (from < 0) return null
        val to = if (towards == Move.UP) from - 1 else from + 1
        if (to !in ids.indices) return null
        val moved = ids.toMutableList()
        moved[from] = moved[to]
        moved[to] = id
        return moved
    }

    /** Whether the button for this move should do anything. */
    fun canMove(ids: List<String>, id: String, towards: Move): Boolean =
        moved(ids, id, towards) != null
}
