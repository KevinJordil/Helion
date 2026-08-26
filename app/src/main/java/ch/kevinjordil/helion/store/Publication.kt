package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Where an [Activity] could be published. */
enum class PublicationTarget {
    /**
     * The direct Strava API integration. No longer published to -- see
     * `docs/archive/strava-api-integration.md` for why it was removed -- but the constant
     * is kept, not deleted: existing installs have real `publication` rows with this
     * target on disk, and Room's enum column converter throws if it reads a stored value
     * with no matching constant.
     */
    STRAVA,

    /** The owner's own server, configured in Réglages -- see [ch.kevinjordil.helion.customserver.CustomServerPublisher]. */
    CUSTOM_SERVER,
}

/** How a publish attempt to a [PublicationTarget] currently stands. */
enum class PublicationState {
    /** The owner asked for this to be published; not yet attempted or not yet acknowledged. */
    PENDING,

    /**
     * The file has been submitted to the target and [Publication.uploadId] holds its
     * asynchronous job id, but the target has not yet resolved it to a final activity.
     * This is the state that makes an interrupted upload resumable: if the app is killed
     * or crashes after the submit call returns but before the poll loop finishes, the next
     * publish attempt finds this row, sees [Publication.uploadId] already set, and polls
     * that existing job instead of submitting the file a second time.
     */
    UPLOADING,

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
 * [lastAttempt] is Unix seconds, [remoteId], [uploadId] and [lastError] are all null until
 * an attempt has actually been made.
 *
 * [uploadId] is the target's asynchronous job id for an in-flight upload (see
 * [PublicationState.UPLOADING]'s kdoc) -- it is what makes an interrupted upload resumable
 * rather than resubmitted from scratch.
 *
 * [lastErrorDetail] is the target's own explanation text for [lastError] (a response
 * body's message, or the field-name-shaped detail an HTTP status alone cannot convey),
 * never a token or credential. Null for the reasons that need no further detail
 * ([lastError] values decided locally, before any request ever reached the target).
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
    val uploadId: String? = null,
    val state: PublicationState,
    val lastAttempt: Long?,
    val lastError: String?,
    val lastErrorDetail: String? = null,
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
