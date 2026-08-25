package ch.kevinjordil.helion.store

import androidx.room.TypeConverter
import java.time.DayOfWeek

/**
 * Enum <-> TEXT converters for [Activity], [Slot] and [Publication]'s enum columns. Stored
 * as the enum constant's own name rather than its ordinal, so reordering the constants in
 * source (or inserting a new one in the middle) can never silently change what an existing
 * row means -- only renaming or removing a constant breaks the round-trip, and either would
 * already need its own migration.
 */
class Converters {

    @TypeConverter
    fun sportTypeToString(value: SportType): String = value.name

    @TypeConverter
    fun stringToSportType(value: String): SportType = SportType.valueOf(value)

    @TypeConverter
    fun activityOriginToString(value: ActivityOrigin): String = value.name

    @TypeConverter
    fun stringToActivityOrigin(value: String): ActivityOrigin = ActivityOrigin.valueOf(value)

    @TypeConverter
    fun activityStatusToString(value: ActivityStatus): String = value.name

    @TypeConverter
    fun stringToActivityStatus(value: String): ActivityStatus = ActivityStatus.valueOf(value)

    @TypeConverter
    fun dayOfWeekToString(value: DayOfWeek): String = value.name

    @TypeConverter
    fun stringToDayOfWeek(value: String): DayOfWeek = DayOfWeek.valueOf(value)

    @TypeConverter
    fun publicationTargetToString(value: PublicationTarget): String = value.name

    @TypeConverter
    fun stringToPublicationTarget(value: String): PublicationTarget = PublicationTarget.valueOf(value)

    @TypeConverter
    fun publicationStateToString(value: PublicationState): String = value.name

    @TypeConverter
    fun stringToPublicationState(value: String): PublicationState = PublicationState.valueOf(value)
}
