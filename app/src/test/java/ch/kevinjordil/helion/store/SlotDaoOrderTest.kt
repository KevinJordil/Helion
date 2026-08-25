package ch.kevinjordil.helion.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.DayOfWeek
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards against the ordering defect [SlotDao.all] and [SlotDao.active] used to have:
 * `ORDER BY dayOfWeek` sorted the stored enum name as TEXT, which reads as
 * "dimanche, jeudi, lundi, mardi..." -- alphabetical, not the real week. Both queries must
 * come back Monday-first, matching [DayOfWeek]'s own declaration order.
 */
@RunWith(RobolectricTestRunner::class)
class SlotDaoOrderTest {

    private lateinit var db: HelionDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun slotOn(day: DayOfWeek, label: String) = Slot(
        label = label,
        dayOfWeek = day,
        startSecondOfDay = 0,
        endSecondOfDay = 3600,
        sport = SportType.BADMINTON,
    )

    @Test
    fun `all returns slots in real calendar-week order, not alphabetical by stored name`() = runTest {
        val dao = db.slots()
        // Inserted in an order that would already be alphabetical-by-name if the bug were
        // still present (dimanche/SUNDAY, jeudi/THURSDAY, lundi/MONDAY, mardi/TUESDAY...),
        // so a passing test genuinely exercises the fix rather than an accidental match.
        listOf(DayOfWeek.SUNDAY, DayOfWeek.THURSDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)
            .forEach { day -> dao.upsert(slotOn(day, day.name)) }

        val ordered = dao.all().map { it.dayOfWeek }
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            ordered,
        )
    }

    @Test
    fun `active mirrors the same real calendar-week order`() = runTest {
        val dao = db.slots()
        listOf(DayOfWeek.FRIDAY, DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)
            .forEach { day -> dao.upsert(slotOn(day, day.name)) }

        val ordered = dao.active().map { it.dayOfWeek }
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), ordered)
    }

    @Test
    fun `within the same day, slots are ordered by start time`() = runTest {
        val dao = db.slots()
        dao.upsert(slotOn(DayOfWeek.TUESDAY, "late").copy(startSecondOfDay = 72_000, endSecondOfDay = 79_200))
        dao.upsert(slotOn(DayOfWeek.TUESDAY, "early").copy(startSecondOfDay = 3_600, endSecondOfDay = 7_200))

        val ordered = dao.all().map { it.label }
        assertEquals(listOf("early", "late"), ordered)
    }
}
