package ch.kevinjordil.helion.store

/**
 * The eight groups [ch.kevinjordil.helion.ui.activity.SportPicker] shows [SportType] under,
 * chosen for a French speaker scanning fifty-seven names rather than for any taxonomy
 * Strava itself publishes -- Strava's own list is flat. Order here is also the picker's own
 * display order within a category-less (empty query) view.
 */
enum class SportCategory {
    CYCLING,
    RUNNING_WALKING,
    WATER,
    SNOW_ICE,
    RACKET,
    INDOOR_FITNESS,
    TEAM,
    OTHER,
}

/**
 * The sport an [Activity] or a [Slot] is for. Every constant except [MOTORCYCLING] is one of
 * Strava's own activity types (https://developers.strava.com/docs/reference/#api-models-ActivityType,
 * the exact vocabulary the owner asked this list to match), spelled here as
 * `SCREAMING_SNAKE_CASE` of Strava's own `PascalCase` name -- see [slug] for the stable
 * string form derived from that name. [MOTORCYCLING] has no Strava equivalent at all and is
 * a Helion-only addition, kept because the owner rides and wants it tracked; every export
 * path that has to fall back for it (see [ch.kevinjordil.helion.healthconnect.healthConnectExerciseType])
 * does so for exactly the same reason a real Strava type sometimes must: the target
 * vocabulary simply has nothing closer.
 *
 * [category] groups these fifty-seven for [ch.kevinjordil.helion.ui.activity.SportPicker];
 * it carries no meaning beyond that UI grouping (nothing here reads it for TCX or Health
 * Connect mapping, both of which switch on the sport itself).
 */
enum class SportType(val category: SportCategory) {
    // -- cycling --
    RIDE(SportCategory.CYCLING),
    E_BIKE_RIDE(SportCategory.CYCLING),
    E_MOUNTAIN_BIKE_RIDE(SportCategory.CYCLING),
    GRAVEL_RIDE(SportCategory.CYCLING),
    HANDCYCLE(SportCategory.CYCLING),
    MOUNTAIN_BIKE_RIDE(SportCategory.CYCLING),
    VELOMOBILE(SportCategory.CYCLING),

    // -- running and walking --
    HIKE(SportCategory.RUNNING_WALKING),
    RUN(SportCategory.RUNNING_WALKING),
    TRAIL_RUN(SportCategory.RUNNING_WALKING),
    WALK(SportCategory.RUNNING_WALKING),

    // -- water --
    CANOEING(SportCategory.WATER),
    KAYAKING(SportCategory.WATER),
    KITESURF(SportCategory.WATER),
    ROWING(SportCategory.WATER),
    SAIL(SportCategory.WATER),
    STAND_UP_PADDLING(SportCategory.WATER),
    SURFING(SportCategory.WATER),
    SWIM(SportCategory.WATER),
    WINDSURF(SportCategory.WATER),

    // -- snow and ice --
    ALPINE_SKI(SportCategory.SNOW_ICE),
    BACKCOUNTRY_SKI(SportCategory.SNOW_ICE),
    ICE_SKATE(SportCategory.SNOW_ICE),
    NORDIC_SKI(SportCategory.SNOW_ICE),
    ROLLER_SKI(SportCategory.SNOW_ICE),
    SNOWBOARD(SportCategory.SNOW_ICE),
    SNOWSHOE(SportCategory.SNOW_ICE),

    // -- racket sports --
    BADMINTON(SportCategory.RACKET),
    PADEL(SportCategory.RACKET),
    PICKLEBALL(SportCategory.RACKET),
    RACQUETBALL(SportCategory.RACKET),
    SQUASH(SportCategory.RACKET),
    TABLE_TENNIS(SportCategory.RACKET),
    TENNIS(SportCategory.RACKET),

    // -- indoor and fitness --
    CROSSFIT(SportCategory.INDOOR_FITNESS),
    DANCE(SportCategory.INDOOR_FITNESS),
    ELLIPTICAL(SportCategory.INDOOR_FITNESS),
    HIGH_INTENSITY_INTERVAL_TRAINING(SportCategory.INDOOR_FITNESS),
    PILATES(SportCategory.INDOOR_FITNESS),
    PHYSICAL_THERAPY(SportCategory.INDOOR_FITNESS),
    STAIR_STEPPER(SportCategory.INDOOR_FITNESS),
    VIRTUAL_RIDE(SportCategory.INDOOR_FITNESS),
    VIRTUAL_ROW(SportCategory.INDOOR_FITNESS),
    VIRTUAL_RUN(SportCategory.INDOOR_FITNESS),
    WEIGHT_TRAINING(SportCategory.INDOOR_FITNESS),
    WORKOUT(SportCategory.INDOOR_FITNESS),
    YOGA(SportCategory.INDOOR_FITNESS),

    // -- team sports --
    BASKETBALL(SportCategory.TEAM),
    CRICKET(SportCategory.TEAM),
    SOCCER(SportCategory.TEAM),
    VOLLEYBALL(SportCategory.TEAM),

    // -- other --
    GOLF(SportCategory.OTHER),
    INLINE_SKATE(SportCategory.OTHER),
    ROCK_CLIMBING(SportCategory.OTHER),
    SKATEBOARD(SportCategory.OTHER),
    WHEELCHAIR(SportCategory.OTHER),
    MOTORCYCLING(SportCategory.OTHER),
}

/**
 * The stable, lower-case, hyphenated identifier for [sport] -- derived mechanically from the
 * enum constant's own name, never from [ch.kevinjordil.helion.ui.activity.sportLabelRes]'s
 * French text. Used both for the Downloads file name
 * ([ch.kevinjordil.helion.export.tcxDownloadFileName]) and the custom-server `sport` field
 * ([ch.kevinjordil.helion.customserver.customServerSportSlug]): one identifier, reused in
 * both places, so a repeat export of the same activity is recognisable by the same slug in
 * either destination, and it never changes just because the app's own display language does.
 * Hyphens, not underscores, so a slug dropped straight into a file name still matches the
 * "lower-case letters, digits, hyphens, one dot" rule the Downloads export already held
 * itself to before this catalogue existed.
 */
fun sportSlug(sport: SportType): String = sport.name.lowercase().replace('_', '-')
