package org.filezilla.android.ui

import org.filezilla.android.files.LocalOperations
import java.io.File

/**
 * The file work behind the trash, gathered in one place.
 *
 * Deleting a phone file moves it here instead of erasing it, and restoring
 * moves it back -- both plain moves within the phone's own storage, which the
 * filesystem does by renaming rather than copying whenever the two ends share
 * a volume. Kept out of the view model and off in its own file for the same
 * reason [pasteLocally] is: the moves that write files behind a delete belong
 * in one named place, so a second one cannot appear unnoticed.
 */
object Trash {

    /**
     * Moves [src] into [dir] under a name free within it, and returns that
     * name -- the one the file now wears inside the trash, which the caller
     * records so it can be found again. A name clash is stepped around rather
     * than allowed to overwrite, so two deleted files of one name both survive.
     */
    fun stash(dir: File, src: File): String {
        val name = freeNameIn(dir.path, src.name)
        LocalOperations.move(src.absolutePath, dir.path, name)
        return name
    }

    /**
     * Moves [stored] out of the trash into [into] under [originalName], or a
     * free variant of it when something already sits there -- so a restore
     * never writes over a file that took the old name in the meantime.
     */
    fun restore(stored: File, into: String, originalName: String) {
        val name = freeNameIn(into, originalName)
        LocalOperations.move(stored.absolutePath, into, name)
    }
}
