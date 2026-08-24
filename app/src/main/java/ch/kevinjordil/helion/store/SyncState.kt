package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Single-row table tracking how far ingestion has got.
 * [lastIngestedTimestamp] only ever moves forward, and only after a complete pass.
 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 1,
    val lastIngestedTimestamp: Long,
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
