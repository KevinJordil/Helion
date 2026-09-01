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
    MOTORCYCLING,
    CLIMBING,
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
    /**
     * Why a detection pass proposed this candidate -- the heart-rate range it saw, against
     * the owner's own resting rate (see `activity_candidate_note` in `strings.xml`, still
     * the format used to render it). Set only by [ch.kevinjordil.helion.activity.ActivityDetector]
     * for [ActivityOrigin.SLOT] and [ActivityOrigin.DETECTED] candidates, always null for
     * [ActivityOrigin.MANUAL]. This is diagnostic text for the owner to read *while
     * reviewing* a candidate, never his own words -- kept apart from [notes] precisely so
     * an export can send [notes] (what he actually wrote) without ever forwarding this.
     */
    val detectionContext: String? = null,
    /**
     * Whether a candidate-detection notification has already been posted for this row --
     * see `ch.kevinjordil.helion.notification.CandidateNotifier`. Set to true only once
     * [ch.kevinjordil.helion.source.CandidateNotificationSink.notifyNewCandidates] actually
     * returns true for it (a real post, not merely attempted): the owner's own rule is one
     * notification per candidate *ever*, so this flag -- not a WorkManager input or a
     * separate table -- is the single source of truth a re-run of detection over the same
     * window, a reinstalled periodic worker, or a second ingest pass minutes later all read
     * before ever notifying again. Kept on the row itself rather than a side table so it can
     * never drift out of sync with the candidate it describes, and because it means nothing
     * beyond [ActivityStatus.CANDIDATE] ever needs to care about it again. Always false for
     * [ActivityOrigin.MANUAL] -- nothing ever notifies about a row the owner drew himself.
     */
    val notified: Boolean = false,
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

    /**
     * Every activity the owner has actually approved -- [ActivityStatus.CONFIRMED] or
     * [ActivityStatus.PUBLISHED] -- and only these two: what
     * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter] scans on every pass to
     * decide which exercise sessions belong in Health Connect at all. Never
     * [ActivityStatus.CANDIDATE] (not yet looked at) or [ActivityStatus.DISMISSED] (the
     * owner said no) -- see that exporter's own kdoc for why this is the one rule the whole
     * feature exists to enforce.
     */
    @Query("SELECT * FROM activity WHERE status IN ('CONFIRMED', 'PUBLISHED') ORDER BY startTimestamp")
    suspend fun confirmedOrPublished(): List<Activity>

    /**
     * Every [ActivityStatus.DISMISSED] activity -- what
     * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter] checks against its own
     * [ch.kevinjordil.helion.store.PublicationDao] records to find one that was written to
     * Health Connect before being dismissed, and remove it there too. See that exporter's
     * kdoc for why this path is not currently reachable through the app's own UI.
     */
    @Query("SELECT * FROM activity WHERE status = 'DISMISSED'")
    suspend fun dismissed(): List<Activity>

    /**
     * Every [ActivityStatus.CANDIDATE] row no notification has been posted for yet -- what
     * [ch.kevinjordil.helion.source.Ingestor] reads after every pass to decide whether to
     * notify at all, and whether that is a single-candidate or a batch notification. Scoped
     * to status rather than a time window: a candidate left unnotified because the owner
     * had notifications off, or because Android's permission was refused at the time, keeps
     * its one unnotified slot until this query picks it up on a later pass -- exactly the
     * batching this app wants for "several days without opening the app," rather than a
     * silently lost notification.
     */
    @Query("SELECT * FROM activity WHERE status = 'CANDIDATE' AND notified = 0 ORDER BY startTimestamp")
    suspend fun unnotifiedCandidates(): List<Activity>

    /** Marks [ids] as notified -- called only once a notification for them actually posted. */
    @Query("UPDATE activity SET notified = 1 WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<Long>)

    /** Every activity, most recent first -- what the Activités list screen shows. */
    @Query("SELECT * FROM activity ORDER BY startTimestamp DESC")
    suspend fun all(): List<Activity>

    @Query("DELETE FROM activity WHERE id = :id")
    suspend fun delete(id: Long)
}
