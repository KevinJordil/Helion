package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import java.time.DayOfWeek

/**
 * A recurring declared commitment, e.g. "badminton, Tuesdays 20:00-22:00". [dayOfWeek],
 * [startSecondOfDay] and [endSecondOfDay] are wall-clock local values, not instants -- they
 * describe a recurring point on the owner's calendar, not a fixed Unix timestamp, and are
 * only ever turned into concrete instants by
 * [ch.kevinjordil.helion.activity.occurrencesBetween], which takes a [java.time.ZoneId]
 * explicitly (the same pattern [ch.kevinjordil.helion.ui.metric.MetricReader] uses) so that
 * resolution happens in the owner's local zone and stays correct across a daylight-saving
 * transition, rather than by adding a fixed offset in seconds.
 *
 * [startSecondOfDay] and [endSecondOfDay] are seconds since local midnight, each in
 * `[0, 86399]`. [endSecondOfDay] is allowed to be less than or equal to
 * [startSecondOfDay]: that is read as the slot crossing midnight, ending on the calendar
 * day after the one it starts on (a late match easily runs past midnight; see
 * [ch.kevinjordil.helion.activity.occurrencesBetween] for how that is resolved).
 *
 * [active] lets the owner suspend a slot -- stop generating new occurrences from it --
 * without deleting it, which would also sever every past [Activity] that references it via
 * [Activity.slotId].
 */
@Entity(tableName = "slot")
data class Slot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val dayOfWeek: DayOfWeek,
    val startSecondOfDay: Int,
    val endSecondOfDay: Int,
    val sport: SportType,
    val active: Boolean = true,
) {
    init {
        require(startSecondOfDay in 0..MAX_SECOND_OF_DAY) {
            "startSecondOfDay must be in [0, $MAX_SECOND_OF_DAY], was $startSecondOfDay"
        }
        require(endSecondOfDay in 0..MAX_SECOND_OF_DAY) {
            "endSecondOfDay must be in [0, $MAX_SECOND_OF_DAY], was $endSecondOfDay"
        }
    }

    private companion object {
        const val MAX_SECOND_OF_DAY = 86_399
    }
}

@Dao
interface SlotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: Slot): Long

    @Update
    suspend fun update(slot: Slot)

    @Query("SELECT * FROM slot WHERE id = :id")
    suspend fun get(id: Long): Slot?

    /**
     * Ordered by the real calendar week (Monday first, matching [java.time.DayOfWeek]'s own
     * order), not by [Slot.dayOfWeek] as TEXT. [dayOfWeek] is stored as the enum constant's
     * name (see [Converters]), and `ORDER BY dayOfWeek` alphabetises those names --
     * "dimanche, jeudi, lundi, mardi..." -- which reads as scrambled to anyone looking at a
     * real week. The `CASE` expression below maps each name to its Monday-first position
     * before sorting, exactly the order [R.array.weekday_short] already uses.
     */
    @Query(
        """
        SELECT * FROM slot ORDER BY
        CASE dayOfWeek
            WHEN 'MONDAY' THEN 1
            WHEN 'TUESDAY' THEN 2
            WHEN 'WEDNESDAY' THEN 3
            WHEN 'THURSDAY' THEN 4
            WHEN 'FRIDAY' THEN 5
            WHEN 'SATURDAY' THEN 6
            WHEN 'SUNDAY' THEN 7
        END, startSecondOfDay
        """,
    )
    suspend fun all(): List<Slot>

    /**
     * Only the slots still generating occurrences -- what detection should iterate. Same
     * Monday-first ordering as [all]; see its kdoc.
     */
    @Query(
        """
        SELECT * FROM slot WHERE active = 1 ORDER BY
        CASE dayOfWeek
            WHEN 'MONDAY' THEN 1
            WHEN 'TUESDAY' THEN 2
            WHEN 'WEDNESDAY' THEN 3
            WHEN 'THURSDAY' THEN 4
            WHEN 'FRIDAY' THEN 5
            WHEN 'SATURDAY' THEN 6
            WHEN 'SUNDAY' THEN 7
        END, startSecondOfDay
        """,
    )
    suspend fun active(): List<Slot>

    /** Removing a slot never touches a past [Activity] generated from it -- see [Activity.slotId]'s `ON DELETE SET NULL`. */
    @Query("DELETE FROM slot WHERE id = :id")
    suspend fun delete(id: Long)
}
