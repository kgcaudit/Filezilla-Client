package org.filezilla.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.filezilla.android.files.Checksums
import java.io.File

/**
 * What the checksum dialog shows: the file's name, the algorithm chosen, the
 * hash once it is computed, and how far a running hash has got. A null hash
 * means still computing; an empty one means the file could not be read.
 */
data class ChecksumState(
    val name: String,
    val algorithm: Checksums.Algorithm,
    val hash: String?,
    val progress: Float,
)

/**
 * Summing a local file's fingerprint for the checksum dialog.
 *
 * Lifted out of [MainViewModel]. Self-contained but for the one thing only the
 * view model knows -- which file a pane row points at -- so the view model
 * resolves that and hands in the [File]. [scope] is the view model's own, so a
 * running sum is cancelled when the view model goes.
 */
class ChecksumController(private val scope: CoroutineScope) {

    var state by mutableStateOf<ChecksumState?>(null)
        private set

    private var file: File? = null
    private var job: Job? = null

    /** Shows the dialog for [file] and starts summing it. */
    fun start(file: File, algorithm: Checksums.Algorithm = Checksums.Algorithm.SHA256) {
        this.file = file
        sum(file, algorithm)
    }

    /** Re-sums the same file with a different algorithm. */
    fun setAlgorithm(algorithm: Checksums.Algorithm) {
        file?.let { sum(it, algorithm) }
    }

    /** Closes the dialog and stops any running sum. */
    fun close() {
        job?.cancel()
        job = null
        file = null
        state = null
    }

    private fun sum(file: File, algorithm: Checksums.Algorithm) {
        job?.cancel()
        state = ChecksumState(file.name, algorithm, hash = null, progress = 0f)
        job = scope.launch {
            val digest = withContext(Dispatchers.IO) {
                Checksums.of(file, algorithm, cancelled = { !isActive }) { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total) else 0f
                    state = state?.copy(progress = fraction)
                }
            }
            // Only the current run writes the answer: a run cancelled because
            // the algorithm changed or the dialog closed leaves the newer state
            // alone.
            if (isActive) state = state?.copy(hash = digest ?: "", progress = 1f)
        }
    }
}
