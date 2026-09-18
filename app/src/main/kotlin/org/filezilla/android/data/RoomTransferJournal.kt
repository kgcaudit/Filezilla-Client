package org.filezilla.android.data

import org.filezilla.ftp.journal.TransferJournal
import org.filezilla.ftp.journal.TransferRecord

/**
 * The durable [TransferJournal] the engine asks for, backed by Room.
 *
 * This is deliberately the whole of it: every decision about whether a
 * journalled offset can still be trusted lives in `:core-ftp`
 * ([org.filezilla.ftp.journal.ResumeSafety]), where it is tested against a
 * real server. The app's job is to make the record survive the process being
 * killed, and nothing else.
 *
 * Writes are synchronous. [org.filezilla.ftp.journal.JournalledTransfer]
 * writes progress as bytes arrive, and a write that is merely queued is not a
 * write: if the app is killed a moment later, the offset it recorded is the
 * one that has actually reached the database.
 */
class RoomTransferJournal(private val dao: TransferDao) : TransferJournal {

    override fun put(record: TransferRecord) = dao.upsert(TransferEntity.from(record))

    override fun get(id: String): TransferRecord? = dao.byId(id)?.toRecord()

    override fun all(): List<TransferRecord> = dao.all().map { it.toRecord() }

    override fun remove(id: String) = dao.deleteById(id)
}
