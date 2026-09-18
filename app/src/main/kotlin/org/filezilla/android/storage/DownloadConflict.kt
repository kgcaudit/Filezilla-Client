package org.filezilla.android.storage

/** What the user chose to do about files that are already in the folder. */
enum class ConflictChoice {
    /** Replace what is there. */
    OVERWRITE,

    /** Leave what is there and do not fetch the remote copy at all. */
    SKIP,

    /** Fetch it anyway, beside the existing one under a numbered name. */
    KEEP_BOTH,
    ;

    companion object {
        /**
         * What happens when nothing was asked.
         *
         * Keeping both, because it is the only one of the three that cannot
         * lose data: overwriting destroys a file the user may want, skipping
         * silently does not fetch one they asked for.
         */
        val DEFAULT = KEEP_BOTH
    }
}

/** A file already in the destination folder, and the one about to land on it. */
data class DownloadConflict(
    val displayName: String,
    val remoteSize: Long?,
    val remoteModifiedMillis: Long?,
    val localSize: Long,
    val localModifiedMillis: Long,
) {
    /**
     * Whether the two look like the same file.
     *
     * Size only. A timestamp is not evidence here: FTP servers report times at
     * varying precision and in their own timezone, and the local copy carries
     * the moment it was written rather than the moment it was made, so two
     * identical files routinely differ by hours. Reporting "different" on that
     * basis would push the user toward re-downloading a file they already have.
     *
     * Same size is not proof either, which is why this is phrased as looks
     * rather than is, and why the user is the one who decides.
     */
    val sameSize: Boolean get() = remoteSize != null && remoteSize == localSize
}
