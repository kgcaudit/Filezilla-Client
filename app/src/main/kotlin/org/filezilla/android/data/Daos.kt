package org.filezilla.android.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}

@Dao
interface SiteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SiteEntity)

    @Delete
    suspend fun delete(entity: SiteEntity)

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun byId(id: String): SiteEntity?

    @Query("SELECT * FROM sites ORDER BY name ASC")
    fun observeAll(): Flow<List<SiteEntity>>

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
