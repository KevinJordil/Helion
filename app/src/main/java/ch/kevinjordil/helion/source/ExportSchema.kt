package ch.kevinjordil.helion.source

/**
 * Table and column names of the Gadgetbridge export database.
 * These come from a third-party project and can change between its releases,
 * so they are isolated here: a schema change is a one-file fix.
 * Verified against a real export before use.
 */
object ExportSchema {

    const val TABLE_MINUTE = "HUAMI_EXTENDED_ACTIVITY_SAMPLE"
    const val TABLE_STRESS = "HUAMI_STRESS_SAMPLE"
    const val TABLE_SPO2 = "HUAMI_SPO2_SAMPLE"
    const val TABLE_PAI = "HUAMI_PAI_SAMPLE"
    const val TABLE_HRV = "GENERIC_HRV_VALUE_SAMPLE"
    const val TABLE_TEMPERATURE = "GENERIC_TEMPERATURE_SAMPLE"
    const val TABLE_RESPIRATORY_RATE = "HUAMI_SLEEP_RESPIRATORY_RATE_SAMPLE"
    const val TABLE_SLEEP_SESSION = "HUAMI_SLEEP_SESSION_SAMPLE"

    const val COL_TIMESTAMP = "TIMESTAMP"
    const val COL_DATA = "DATA"
    const val COL_STEPS = "STEPS"
    const val COL_RAW_INTENSITY = "RAW_INTENSITY"
    const val COL_RAW_KIND = "RAW_KIND"
    const val COL_HEART_RATE = "HEART_RATE"
    const val COL_SLEEP = "SLEEP"

    const val COL_STRESS = "STRESS"
    const val COL_SPO2 = "SPO2"
    const val COL_PAI_TODAY = "PAI_TODAY"
    const val COL_HRV_VALUE = "VALUE"
    const val COL_TEMPERATURE = "TEMPERATURE"
    const val COL_RESPIRATORY_RATE = "RATE"

    val MINUTE_COLUMNS = listOf(
        COL_TIMESTAMP,
        COL_STEPS,
        COL_RAW_INTENSITY,
        COL_RAW_KIND,
        COL_HEART_RATE,
        COL_SLEEP,
    )
}
