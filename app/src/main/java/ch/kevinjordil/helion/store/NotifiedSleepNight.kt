package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per night the morning sleep-summary notification has already been sent for --
 * the same "notified, ever" discipline [Activity.notified] gives candidate activities,
 * kept here as its own table rather than a column because a night is not a row anywhere
 * else: it is derived on the fly from minute samples and stage segments (see
 * [ch.kevinjordil.helion.ui.sleep.SleepReader]), so there is nothing to add a column to.
 *
 * [wokeAt] -- a [ch.kevinjordil.helion.ui.sleep.SleepEpisode]'s own wake time -- is the
 * night's one stable identity: two different ingestion passes computing the same night
 * from slightly different minute windows still agree on when it ended, which is exactly
 * why [ch.kevinjordil.helion.ui.sleep.SleepReader] groups device stage sessions by their
 * own end time the same way. A re-ingest, a re-analysis, or a reinstall reading the same
 * on-disk database all see the same row here and skip re-notifying.
 */
@Entity(tableName = "notified_sleep_night")
data class NotifiedSleepNight(@PrimaryKey val wokeAt: Long)

@Dao
interface NotifiedSleepNightDao {

    @Query("SELECT EXISTS(SELECT 1 FROM notified_sleep_night WHERE wokeAt = :wokeAt)")
    suspend fun isNotified(wokeAt: Long): Boolean

    /** Idempotent: marking the same night notified twice is harmless. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markNotified(night: NotifiedSleepNight)
}
