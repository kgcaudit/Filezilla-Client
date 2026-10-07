package org.filezilla.android.transfer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.filezilla.android.R
import org.filezilla.android.files.FilePath
import java.io.File

/**
 * Finds a queued upload's file again when it has moved, before giving up on it.
 *
 * An upload keeps a plain path to its file. When that path no longer opens --
 * the file was moved to another folder, renamed, or the folder reorganised --
 * this looks for the same file elsewhere on the same volume rather than failing
 * outright. Two ways, cheapest first: the media index, which already knows
 * where a photo or video moved to and answers without a walk; then a bounded
 * walk of the volume for everything the index does not cover (a subtitle, a
 * document). What it will not do is guess: only a single file of the same name
 * and size is accepted, so a reorganisation is recovered while a different file
 * that merely shares a name is left alone. (See [SourceRematch].)
 *
 * [volumeRoots] is asked afresh each time so a card inserted or removed since is
 * reflected; the walk is held to the volume the file came from, since that is
 * the only place a move keeps the same bytes.
 */
class AndroidUploadSources(
    private val context: Context,
    private val volumeRoots: () -> List<String>,
) : UploadSources {

    override fun resolve(stored: Uri, size: Long?): Uri {
        // Only a plain file path can go missing this way; a document URI is the
        // provider's to resolve and is left untouched.
        if (stored.scheme != "file") return stored
        val path = stored.path ?: return stored
        val file = File(path)
        if (file.exists()) return stored

        val name = file.name
        val found = mediaStoreMatch(name, size) ?: walkMatch(path, name, size)
        if (found != null) return Uri.fromFile(File(found.path))

        throw SourceMissingException(context.getString(R.string.upload_source_missing, name))
    }

    /**
     * Where the media index thinks a file of this name now is, if it is one the
     * index keeps and the file is really there. Survives a move or rename within
     * a volume because the index is keyed on the row, not the path.
     */
    private fun mediaStoreMatch(name: String, size: Long?): SourceCandidate? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val candidates = mutableListOf<SourceCandidate>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                val dataAt = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameAt = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (dataAt < 0 || nameAt < 0) return@use
                while (cursor.moveToNext() && candidates.size < CANDIDATE_CAP) {
                    val data = cursor.getString(dataAt) ?: continue
                    val onDisk = File(data)
                    // The index can lag a move by a moment; trust the disk.
                    if (!onDisk.isFile) continue
                    candidates += SourceCandidate(
                        path = data,
                        name = cursor.getString(nameAt) ?: onDisk.name,
                        size = if (sizeAt >= 0) cursor.getLong(sizeAt) else onDisk.length(),
                    )
                }
            }
        }
        return SourceRematch.bestMatch(name, size, candidates)
    }

    /**
     * A bounded walk of the volume [originalPath] sat on, for files the media
     * index does not keep. Bounded because a volume can hold far more than a
     * one-off recovery should ever read; only names and sizes are looked at, so
     * no file is opened.
     */
    private fun walkMatch(originalPath: String, name: String, size: Long?): SourceCandidate? {
        val root = volumeRoots().firstOrNull { FilePath.isWithin(originalPath, it) }
            ?: File(originalPath).parent
            ?: return null

        val matches = mutableListOf<SourceCandidate>()
        var visited = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(File(root) to 0)
        while (stack.isNotEmpty()) {
            if (visited >= MAX_ENTRIES || matches.size >= 2) break
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (visited >= MAX_ENTRIES || matches.size >= 2) break
                visited++
                if (child.isDirectory) {
                    stack.addLast(child to depth + 1)
                } else if (child.name == name && (size == null || size < 0 || child.length() == size)) {
                    // Already filtered to same name and size, so two is a tie
                    // the matcher will refuse rather than a list to rank.
                    matches += SourceCandidate(child.absolutePath, child.name, child.length())
                }
            }
        }
        return SourceRematch.bestMatch(name, size, matches)
    }

    private companion object {
        /** Entries a recovery walk will look at before giving up. Names only, so cheap. */
        const val MAX_ENTRIES = 100_000

        /** How deep the walk goes, the same floor the download walk uses. */
        const val MAX_DEPTH = 24

        /** Media rows of one name to weigh before deciding; a tie is refused anyway. */
        const val CANDIDATE_CAP = 8
    }
}
