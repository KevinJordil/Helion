package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Where an [Activity] could be published. A single value for now; the shape allows more. */
enum class PublicationTarget {
    STRAVA,
}

/** How a publish attempt to a [PublicationTarget] currently stands. */
enum class PublicationState {
    /** The owner asked for this to be published; not yet attempted or not yet acknowledged. */
    PENDING,

    /** The target accepted it; [Publication.remoteId] is its id there. */
    PUBLISHED,

    /** The last attempt failed; see [Publication.lastError]. */
    FAILED,
}

/**
 * Records the publish-side state of one [Activity] on one [PublicationTarget]. No network
 * code lives here or is implied by it -- a later step performs the actual publish call and
 * fills these fields in; this table only decides the shape so that step can be made
 * idempotent: `(activityId, target)` is the primary key, so re-attempting a publish (after
 * a crash, a retry, a re-run of a worker) upserts the same row instead of creating a
 * second one, and [remoteId] gives that later step a place to remember "this activity is
 * already this remote object" before it ever calls the network.
 *
 * [lastAttempt] is Unix seconds, [remoteId] and [lastError] are both null until an attempt
 * has actually been made.
 */
@Entity(
    tableName = "publication",
    primaryKeys = ["activityId", "target"],
    foreignKeys = [
        ForeignKey(
            entity = Activity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Publication(
    val activityId: Long,
    val target: PublicationTarget,
    val remoteId: String?,
    val state: PublicationState,
    val lastAttempt: Long?,
    val lastError: String?,
)

@Dao
interface PublicationDao {

    /** Idempotent by (activity, target): re-recording an attempt replaces, never duplicates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(publication: Publication)

    @Query("SELECT * FROM publication WHERE activityId = :activityId AND target = :target")
    suspend fun get(activityId: Long, target: PublicationTarget): Publication?

    @Query("SELECT * FROM publication WHERE activityId = :activityId")
    suspend fun forActivity(activityId: Long): List<Publication>
}
