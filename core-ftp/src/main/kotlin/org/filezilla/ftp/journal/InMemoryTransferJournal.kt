package org.filezilla.ftp.journal

import java.util.concurrent.ConcurrentHashMap

/**
 * A [TransferJournal] that lives only in memory.
 *
 * Used by the tests, and as a fallback where no durable store is configured.
 * It gives up everything on process death, which is the one thing a journal
 * exists to prevent, so the app module must supply a real one.
 */
class InMemoryTransferJournal : TransferJournal {

    private val records = ConcurrentHashMap<String, TransferRecord>()

    override fun put(record: TransferRecord) {
        records[record.id] = record
    }

    override fun get(id: String): TransferRecord? = records[id]

    override fun all(): List<TransferRecord> = records.values.sortedBy { it.updatedAtMillis }

    override fun remove(id: String) {
        records.remove(id)
    }
}
