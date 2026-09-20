package org.filezilla.android.files

import java.io.File
import java.io.IOException

/**
 * Making, moving and removing files on the device.
 *
 * Separate from [LocalFileSource], which only reads. Writing is where the
 * damage is, so the checks that prevent it live together here rather than
 * being spread across the callers that happen to need them.
 */
object LocalOperations {

    /** How a move turned out, since the cheap way is not always available. */
    enum class MoveKind {
        /** The filesystem renamed it, which costs nothing whatever the size. */
        RENAMED,

        /** Copied then removed, because the two places are different volumes. */
        COPIED,
    }

    fun createDirectory(parent: String, name: String): String {
        val target = File(FilePath.child(parent, validName(name)))
        if (target.exists()) throw IOException("${target.name} already exists")
        if (!target.mkdirs()) throw IOException("could not create ${target.name}")
        return target.absolutePath
    }

    fun createFile(parent: String, name: String): String {
        val target = File(FilePath.child(parent, validName(name)))
        // createNewFile answers false for "already there", which is a
        // different thing from failing and deserves its own message.
        if (target.exists()) throw IOException("${target.name} already exists")
        if (!target.createNewFile()) throw IOException("could not create ${target.name}")
        return target.absolutePath
    }

    /**
     * Gives [path] a new name, falling back to copying when the filesystem
     * will not do it the cheap way.
     *
     * `renameTo` answers false rather than saying why, and on the emulated
     * volume it answers false for cases that are perfectly legal -- a folder
     * holding files the media store has indexed is the common one. Renaming a
     * folder therefore looked like it simply did nothing. Falling back to a
     * copy and a delete gets the user the result they asked for; it costs as
     * long as the folder is big, which is still better than not happening.
     */
    fun rename(path: String, newName: String): String {
        val source = File(FilePath.normalize(path))
        val target = File(source.parentFile, validName(newName))
        if (!source.exists()) throw IOException("${source.name} is not there any more")
        if (target.exists()) throw IOException("${target.name} already exists")
        if (source.renameTo(target)) return target.absolutePath

        copyInto(source, target)
        // Only once the copy is whole. Removing the original first and then
        // failing would lose it, and this path exists precisely because the
        // filesystem is already refusing things here.
        delete(source.absolutePath)
        return target.absolutePath
    }

    /**
     * Removes a file, or a folder and everything in it.
     *
     * Symbolic links are unlinked rather than followed. Deleting through one
     * would reach outside the folder the user is looking at, which is not
     * what they asked for and not something they could see coming.
     */
    fun delete(path: String) {
        val target = File(FilePath.normalize(path))
        if (!target.exists()) return
        if (target.isDirectory && !isLink(target)) {
            for (child in target.listFiles().orEmpty()) delete(child.absolutePath)
        }
        if (!target.delete()) throw IOException("could not delete ${target.name}")
    }

    /**
     * Moves [path] into [intoDirectory].
     *
     * Tries a rename first, which the filesystem does by moving a name rather
     * than bytes: instant, whatever the size. That only works within one
     * volume, so a move to an SD card falls back to copying and then removing
     * the original -- and says which it did, because one of them takes as
     * long as the file is big and the caller may want to show that.
     */
    fun move(path: String, intoDirectory: String, asName: String? = null): MoveKind {
        val source = File(FilePath.normalize(path))
        val target = File(FilePath.child(intoDirectory, asName ?: source.name))
        refuseUnsafe(source, target)
        if (target.exists()) throw IOException("${target.name} already exists")

        if (source.renameTo(target)) return MoveKind.RENAMED

        copy(source.absolutePath, intoDirectory, asName)
        delete(source.absolutePath)
        return MoveKind.COPIED
    }

    /** Copies [path] into [intoDirectory], folders and all. */
    fun copy(path: String, intoDirectory: String, asName: String? = null) {
        val source = File(FilePath.normalize(path))
        val target = File(FilePath.child(intoDirectory, asName ?: source.name))
        refuseUnsafe(source, target)
        if (target.exists()) throw IOException("${target.name} already exists")
        copyInto(source, target)
    }

    private fun copyInto(source: File, target: File) {
        if (source.isDirectory && !isLink(source)) {
            if (!target.mkdirs()) throw IOException("could not create ${target.name}")
            for (child in source.listFiles().orEmpty()) {
                copyInto(child, File(target, child.name))
            }
            return
        }
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Refuses the two ways a copy or move eats itself.
     *
     * A folder cannot go inside itself, and it cannot go inside one of its
     * own descendants: both walk forever, and the first thing they do on the
     * way is start writing. Checked before anything is created, because
     * finding out halfway leaves a half-copied tree behind.
     */
    private fun refuseUnsafe(source: File, target: File) {
        val from = source.absolutePath
        val to = target.absolutePath
        if (from == to) throw IOException("${source.name} is already there")
        if (source.isDirectory && FilePath.isWithin(to, from)) {
            throw IOException("${source.name} cannot be put inside itself")
        }
    }

    /**
     * A name that cannot escape the folder it is being created in.
     *
     * A separator or a "core-ftp" style ".." in a typed name would put the
     * file somewhere the user was not looking, and on a remote listing the
     * name is not even theirs -- it is the server's.
     */
    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw IOException("the name cannot be empty")
        if (trimmed == "." || trimmed == "..") throw IOException("$trimmed is not a name")
        if (FilePath.SEPARATOR in trimmed) throw IOException("a name cannot contain a slash")
        return trimmed
    }

    private fun isLink(file: File): Boolean =
        runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false)
}
