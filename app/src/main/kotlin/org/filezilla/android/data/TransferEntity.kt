package org.filezilla.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState

/**
 * The Room row behind a [TransferRecord].
 *
 * A separate type rather than annotating [TransferRecord] itself, because that
 * one lives in `:core-ftp`, which has no Android APIs on purpose -- keeping it
 * that way is what lets the resume logic be tested on a plain JVM against a
 * real server.
 *
 * Enums are stored as their names rather than their ordinals. An ordinal
 * silently changes meaning when someone inserts a constant in the middle, and
 * the value it would corrupt here is the one that says whether a transfer is
 * still safe to resume.
 */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val host: String,
    val port: Int,
    val user: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    val destination: String?,
    val state: String,
    @ColumnInfo(name = "bytes_transferred") val bytesTransferred: Long,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long?,
    @ColumnInfo(name = "fingerprint_size") val fingerprintSize: Long?,
    @ColumnInfo(name = "fingerprint_modified") val fingerprintModified: Long?,
    val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
) {
    fun toRecord(): TransferRecord = TransferRecord(
        id = id,
        direction = enumValueOf<TransferDirection>(direction),
        host = host,
        port = port,
        user = user,
        remotePath = remotePath,
        localPath = localPath,
        destination = destination,
        state = enumValueOf<TransferState>(state),
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        // A fingerprint of two nulls carries nothing, and RemoteFingerprint
        // itself treats that as unusable; storing it as an absent fingerprint
        // keeps the round trip exact.
        fingerprint = if (fingerprintSize == null && fingerprintModified == null) {
            null
        } else {
            RemoteFingerprint(fingerprintSize, fingerprintModified)
        },
        attempts = attempts,
        lastError = lastError,
        updatedAtMillis = updatedAtMillis,
    )

    companion object {
        fun from(record: TransferRecord): TransferEntity = TransferEntity(
            id = record.id,
            direction = record.direction.name,
            host = record.host,
            port = record.port,
            user = record.user,
            remotePath = record.remotePath,
            localPath = record.localPath,
            destination = record.destination,
            state = record.state.name,
            bytesTransferred = record.bytesTransferred,
            totalBytes = record.totalBytes,
            fingerprintSize = record.fingerprint?.size,
            fingerprintModified = record.fingerprint?.modifiedMillis,
            attempts = record.attempts,
            lastError = record.lastError,
            updatedAtMillis = record.updatedAtMillis,
        )
    }
}
