package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Single-row table recording the outcome of the last ingestion pass.
 *
 * Deliberately *not* a correctness mechanism. It used to carry a global
 * `lastIngestedTimestamp` watermark, which could only ever track the freshest series and
 * silently excluded every slower one (see [ch.kevinjordil.helion.source.Watermarks]);
 * watermarks are now derived per series from the archive itself, so the column was dropped
 * rather than kept as a tempting second source of truth. What is left is a report: when the
 * last pass ran, and why it failed if it did. [lastError] is null after a successful pass.
 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 1,
    val lastSyncAttempt: Long,
    val lastError: String?,
)

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncState)
}
