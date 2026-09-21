package org.filezilla.android.transfer

import org.filezilla.android.data.SiteEntity
import org.filezilla.android.files.FilePath
import org.filezilla.ftp.listing.DirectoryEntry

/**
 * The last thing each server said about a folder, for a little while.
 *
 * Walking into a folder and back out re-asked the server for a listing it
 * had just given us. With the connection pooled that is three round trips
 * and a directory transfer; with a server across the internet it is the
 * difference between a pane that appears and a pane that arrives.
 *
 * So the listing is kept. Two things decide whether keeping it is honest
 * rather than merely fast:
 *
 *  - **Our own writes throw it away.** Making a folder, renaming, deleting
 *    or uploading makes every listing this app holds for that server a
 *    guess. That is not remembered at each call site -- it is counted on
 *    [FtpSession] itself and noticed by [TransferManager.browse], so a
 *    write added later cannot forget to say so.
 *  - **Someone else's writes are bounded by time.** Nothing here can know
 *    that a different machine uploaded a file, so an entry is only trusted
 *    for [LIFETIME_MILLIS] and the refresh gesture ignores it entirely.
 *
 * What this does not do is re-ask in the background after answering from
 * memory. That would spend the round trips anyway -- half the point was
 * not spending them -- and would shuffle rows under a finger already
 * reaching for one.
 */
class RemoteListings(private val now: () -> Long = System::currentTimeMillis) {

    /** A listing, and the path the server said it was of. */
    data class Listing(val path: String, val entries: List<DirectoryEntry>)

    private class Held(val listing: Listing, val takenAtMillis: Long)

    private val lock = Any()

    /**
     * Least-recently-used first, which is what the ordering is for. A plain
     * map has no order to evict by, and evicting an arbitrary entry would
     * make the cache's behaviour depend on hash codes.
     */
    private val held = LinkedHashMap<String, Held>(16, 0.75f, true)

    /** Rows across every entry, kept in step so eviction need not recount. */
    private var rows = 0

    /**
     * Bumped every time a server is forgotten.
     *
     * A listing is fetched, and only then remembered -- and in between,
     * something else may have written to that server and emptied this. The
     * caller reads this before it asks and hands it back with the answer,
     * so an answer older than the write is dropped instead of putting the
     * pre-write listing back.
     *
     * One counter for everything rather than one per server: forgetting is
     * rare, the cost of a false miss is one re-listing, and a counter per
     * server is a second map to keep in step with the first.
     */
    private var forgettings = 0L

    /**
     * What the server said [asked] held, or null if that is not worth
     * believing any more.
     */
    fun recall(site: SiteEntity, asked: String): Listing? = synchronized(lock) {
        val key = keyFor(site, asked)
        val entry = held[key] ?: return null
        if (now() - entry.takenAtMillis >= LIFETIME_MILLIS) {
            held.remove(key)
            rows -= entry.listing.entries.size
            return null
        }
        entry.listing
    }

    /**
     * What has been forgotten so far, to be handed back to [remember].
     *
     * Read before a listing is asked for. See [forgettings].
     */
    fun asOf(): Long = synchronized(lock) { forgettings }

    /**
     * Keeps what the server said, under the path that was asked for.
     *
     * [asked] and [resolved] are both carried because they need not match:
     * a relative path or a symbolic link lands where the server says, and a
     * later visit will ask by the same name it asked by this time.
     */
    fun remember(
        site: SiteEntity,
        asked: String,
        resolved: String,
        entries: List<DirectoryEntry>,
        /**
         * What [asOf] said before this listing was asked for. A listing
         * that set out before a write is not an answer about what is
         * there now.
         */
        asOf: Long = -1,
    ) = synchronized(lock) {
        if (asOf >= 0 && asOf != forgettings) return@synchronized
        val key = keyFor(site, asked)
        held.remove(key)?.let { rows -= it.listing.entries.size }
        held[key] = Held(Listing(resolved, entries), now())
        rows += entries.size
        evictDown()
    }

    /** Forgets one server, for anything that changed it. */
    fun forget(site: SiteEntity) = forgetServer(site.host, site.port, site.user)

    /**
     * Forgets every site pointed at one login on one server.
     *
     * By host, port and user rather than by site id because that is all a
     * finished upload knows about where its bytes went -- and because two
     * saved sites onto the same login are two views of one filesystem, so a
     * write through either invalidates both.
     */
    fun forgetServer(host: String, port: Int, user: String) = synchronized(lock) {
        val prefix = serverKey(host, port, user) + SEPARATOR
        forgettings++
        val going = held.keys.filter { it.startsWith(prefix) }
        for (key in going) held.remove(key)?.let { rows -= it.listing.entries.size }
    }

    fun forgetEverything() = synchronized(lock) {
        forgettings++
        held.clear()
        rows = 0
    }

    /** How many folders are being held. For the tests, and for nothing else. */
    fun size(): Int = synchronized(lock) { held.size }

    private fun evictDown() {
        while (held.size > MAX_FOLDERS || rows > MAX_ROWS) {
            val oldest = held.entries.iterator()
            if (!oldest.hasNext()) break
            val going = oldest.next()
            rows -= going.value.listing.entries.size
            oldest.remove()
        }
    }

    /**
     * Which listing this is, including everything that changes what a
     * listing of the same path would say.
     *
     * The encoding is in here because it decides what the bytes of a name
     * turn into: the same folder read as EUC-KR and as UTF-8 is two
     * different sets of names, and handing one back for the other would put
     * mojibake on screen.
     */
    private fun keyFor(site: SiteEntity, path: String): String = listOf(
        serverKey(site.host, site.port, site.user),
        site.encoding.orEmpty(),
        FilePath.normalize(path),
    ).joinToString(SEPARATOR)

    private fun serverKey(host: String, port: Int, user: String): String =
        listOf(host, port.toString(), user).joinToString("\u0001")

    companion object {
        /**
         * How long a listing is believed.
         *
         * Long enough to cover a burst of walking around a tree, which is
         * where every hit comes from, and short enough that a file put
         * there from a desk is on screen by the time anyone goes looking
         * for it. The refresh gesture does not wait for it to run out.
         */
        const val LIFETIME_MILLIS = 60_000L

        /** Bounds, so that browsing a big tree cannot grow without end. */
        const val MAX_FOLDERS = 32
        const val MAX_ROWS = 20_000

        private const val SEPARATOR = "\u0000"
    }
}
