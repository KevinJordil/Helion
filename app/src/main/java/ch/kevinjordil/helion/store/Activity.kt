package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/** The sport an [Activity] or a [Slot] is for. */
enum class SportType {
    BADMINTON,
    RUNNING,
    CYCLING,
    WALKING,
    SWIMMING,
    OTHER,
}

/**
 * How an [Activity] came to exist. This is provenance, not permission: an activity created
 * from a [Slot] or by [DETECTED] automatic recognition still needs the owner to move its
 * [ActivityStatus] forward -- nothing in this table ever publishes itself.
 */
enum class ActivityOrigin {
    /** Generated from a [Slot] occurrence -- see [ch.kevinjordil.helion.activity.occurrencesBetween]. */
    SLOT,

    /** Proposed by a later step's detection algorithm, not built here. */
    DETECTED,

    /** Typed in by the owner directly. */
    MANUAL,
}

/**
 * Where an [Activity] sits in the owner's own decision process. This is the field that
 * makes the whole store safe: once a time range has been decided one way or the other
 * ([CONFIRMED], [PUBLISHED] or [DISMISSED]), [ActivityDao.overlapping] lets a later step
 * check that before creating a new candidate for the same window, so a rejected false
 * positive can never come back, and a confirmed session never gets a duplicate proposed
 * next to it.
 *
 * [CANDIDATE] is the only state a later step may create unattended (from a slot occurrence
 * or from detection). Moving past it -- to [CONFIRMED], to [PUBLISHED], or sideways to
 * [DISMISSED] -- is always something the owner does; nothing in this module ever performs
 * that transition on its own.
 */
enum class ActivityStatus {
    /** Proposed, not yet looked at. */
    CANDIDATE,

    /** The owner looked at it and said yes, this happened. */
    CONFIRMED,

    /** The owner chose to publish it (see [Publication] for the publish-side record). */
    PUBLISHED,

    /** The owner looked at it and said no -- must never be re-proposed for the same range. */
    DISMISSED,
}

/**
 * One recorded activity session. [startTimestamp] and [endTimestamp] are Unix seconds.
 *
 * [slotId] is set when [origin] is [ActivityOrigin.SLOT], linking back to the recurring
 * commitment that produced this occurrence; it is null for [ActivityOrigin.DETECTED] and
 * [ActivityOrigin.MANUAL]. `ON DELETE SET NULL` rather than cascading: removing a slot must
 * never take a past, already-decided activity down with it.
 */
@Entity(
    tableName = "activity",
    foreignKeys = [
        ForeignKey(
            entity = Slot::class,
            parentColumns = ["id"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("startTimestamp", "endTimestamp"),
        Index("status"),
        Index("slotId"),
    ],
)
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val sport: SportType,
    val title: String?,
    val notes: String?,
    val origin: ActivityOrigin,
    val status: ActivityStatus,
    val slotId: Long? = null,
)

@Dao
interface ActivityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: Activity): Long

    @Update
    suspend fun update(activity: Activity)

    @Query("SELECT * FROM activity WHERE id = :id")
    suspend fun get(id: Long): Activity?

    /**
     * Every activity, in any status, whose [Activity.startTimestamp]..[Activity.endTimestamp]
     * overlaps `[from, to]`. This is the question detection must ask before proposing a new
     * candidate for a time range: if it comes back non-empty, the range has already been
     * decided on -- confirmed, published, or dismissed -- and must be left alone regardless
     * of the verdict. Backed by the (startTimestamp, endTimestamp) index so it stays cheap
     * to call on every detection pass.
     */
    @Query("SELECT * FROM activity WHERE startTimestamp <= :to AND endTimestamp >= :from ORDER BY startTimestamp")
    suspend fun overlapping(from: Long, to: Long): List<Activity>

    @Query("SELECT * FROM activity WHERE status = :status ORDER BY startTimestamp")
    suspend fun withStatus(status: ActivityStatus): List<Activity>

    /** Every activity, most recent first -- what the Activités list screen shows. */
    @Query("SELECT * FROM activity ORDER BY startTimestamp DESC")
    suspend fun all(): List<Activity>

    @Query("DELETE FROM activity WHERE id = :id")
    suspend fun delete(id: Long)
}
