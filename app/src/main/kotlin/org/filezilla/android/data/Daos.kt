package org.filezilla.android.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    /**
     * Blocking on purpose: [org.filezilla.ftp.journal.TransferJournal] is a
     * plain synchronous interface, and it is called from the transfer thread
     * as bytes arrive. Room refuses these on the main thread, which is the
     * check that matters.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TransferEntity)

    @Query("SELECT * FROM transfers WHERE id = :id")
    fun byId(id: String): TransferEntity?

    @Query("SELECT * FROM transfers ORDER BY updated_at ASC")
    fun all(): List<TransferEntity>

    @Query("DELETE FROM transfers WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM transfers WHERE state = 'COMPLETED'")
    fun deleteCompleted()

    /** Newest first, which is the order the queue screen wants. */
    @Query("SELECT * FROM transfers ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    /**
     * Takes a transfer for one worker, or reports that someone else has it.
     *
     * The condition in the `WHERE` is the whole point. With two workers,
     * reading a record and then writing it back is a race whose losing side is
     * two workers downloading the same file over two connections, into two
     * partial files, publishing whichever finishes last. SQLite applies this
     * as one statement, so exactly one caller sees a row count of 1.
     *
     * `RUNNING` is not claimable. A record in that state is either held by a
     * live worker or left over from a process that died, and the queue turns
     * the leftovers back into `INTERRUPTED` before any worker starts.
     *
     * @return 1 when this caller took it, 0 when it was already gone.
     */
    @Query(
        """
        UPDATE transfers SET state = 'RUNNING', updated_at = :now
        WHERE id = :id AND state IN ('PENDING', 'INTERRUPTED')
        """,
    )
    fun claim(id: String, now: Long): Int

    /**
     * Turns transfers left `RUNNING` by a killed process back into work.
     *
     * Nothing is running when the queue starts, so a `RUNNING` row means the
     * process died holding it. They have to be moved out of `RUNNING` before
     * workers start, or [claim] could not tell a leftover from a record a
     * live worker is holding.
     */
    @Query("UPDATE transfers SET state = 'INTERRUPTED', updated_at = :now WHERE state = 'RUNNING'")
    fun releaseStaleClaims(now: Long): Int
}

@Dao
interface SiteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SiteEntity)

    @Delete
    suspend fun delete(entity: SiteEntity)

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun byId(id: String): SiteEntity?

    /**
     * The user's order, with name only as a tiebreak.
     *
     * The tiebreak matters: two rows can share a position if a write was
     * interrupted partway, and without it their order would flip about
     * between readings for no reason the user could see.
     */
    @Query("SELECT * FROM sites ORDER BY position ASC, name ASC")
    fun observeAll(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites ORDER BY position ASC, name ASC")
    suspend fun all(): List<SiteEntity>

    /**
     * Where a new site goes: after everything already there.
     *
     * Appended rather than sorted in. Once the order is the user's, dropping
     * a new server into the middle of it by name would be the app overruling
     * an arrangement they made.
     */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM sites")
    suspend fun nextPosition(): Int

    @Query("UPDATE sites SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    /**
     * Writes [ids] as the order, numbering from zero.
     *
     * Every row is renumbered rather than just the two that swapped. There
     * are a handful of servers, so it costs nothing, and it repairs a list
     * whose positions have drifted -- duplicates, gaps, rows left at zero by
     * an older version -- instead of preserving the damage.
     */
    @Transaction
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { index, id -> setPosition(id, index) }
    }

    /**
     * The site a transfer belongs to.
     *
     * A TransferRecord carries only host, port and user -- it has no room for
     * a password, and putting one there would spread credentials across two
     * tables. So the credentials are looked up by the endpoint the record
     * names, which is exactly how ServerCapabilities keys a server too.
     */
    @Query("SELECT * FROM sites WHERE host = :host AND port = :port AND user = :user LIMIT 1")
    fun byEndpoint(host: String, port: Int, user: String): SiteEntity?
}
