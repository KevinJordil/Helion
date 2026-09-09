package ch.kevinjordil.helion.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import ch.kevinjordil.helion.store.SleepStageSegment
import ch.kevinjordil.helion.store.Slot
import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.activity.ActivityDetailScreen
import ch.kevinjordil.helion.ui.activity.ActivityListScreen
import ch.kevinjordil.helion.ui.activity.DayTimelineScreen
import ch.kevinjordil.helion.ui.activity.SlotEditScreen
import ch.kevinjordil.helion.ui.activity.SlotListScreen
import ch.kevinjordil.helion.ui.home.HomeScreen
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricScreen
import ch.kevinjordil.helion.ui.settings.SettingsScreen
import ch.kevinjordil.helion.ui.settings.SettingsSectionScreen
import ch.kevinjordil.helion.ui.sleep.SleepScreen
import ch.kevinjordil.helion.ui.theme.HelionTheme
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Renders every screen off-screen and writes a PNG per screen, so the layout can be looked
 * at rather than reasoned about: this machine has no /dev/kvm and no system image, so an
 * emulator is not available and this is the only way to see the app.
 *
 * The archive it renders against is invented -- plausible-looking numbers written here in
 * the test, never a copy of the owner's own health data, which must not enter the source
 * tree.
 *
 * Not a regression test: it asserts nothing and is excluded from the normal suite (see the
 * `helion.shots` system property gate). It runs on demand, to produce pictures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenGallery {

    private companion object {
        const val SHOTS_PROPERTY = "helion.shots"
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File
        get() = File(System.getProperty(SHOTS_PROPERTY)!!)

    /**
     * Skips every case unless the output directory was asked for explicitly. Rendering a
     * dozen screens costs about a minute and writes files, neither of which belongs in the
     * suite that runs on every change -- this is a tool, not a regression test.
     */
    @Before
    fun onlyOnDemand() {
        Assume.assumeNotNull(System.getProperty(SHOTS_PROPERTY))
    }

    private lateinit var container: AppContainer

    private fun seed(dark: Boolean = true) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        container = AppContainer(ApplicationProvider.getApplicationContext())
        // Accueil renders nothing at all until a source is configured; the value is never
        // opened here, only checked for being non-null.
        container.exportLocation.uri = "content://render-bench/Gadgetbridge.db"
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000
        val today = LocalDate.now(zone)

        runBlocking {
            // A day of minute samples: a resting-ish heart rate with a busier afternoon.
            val minutes = (0 until 1440 step 5).map { offset ->
                val ts = now - (1440 - offset) * 60L
                val busy = offset in 900..1000
                MinuteSample(
                    timestamp = ts,
                    steps = if (busy) 40 else 3,
                    intensity = if (busy) 60 else 5,
                    rawKind = 1,
                    heartRate = if (busy) 138 + (offset % 11) else 58 + (offset % 7),
                    sleepStage = null,
                )
            }
            // Per-minute samples across the night, marked asleep: the night detector works
            // on a 60-second cadence, so a coarser series is read as no night at all.
            val night = today.minusDays(1)
            val sleepStart = night.atTime(LocalTime.of(23, 10)).atZone(zone).toEpochSecond()
            val wake = today.atTime(LocalTime.of(6, 7)).atZone(zone).toEpochSecond()
            val nightMinutes = (sleepStart..wake step 60L).map { ts ->
                val awakeBlip = ((ts - sleepStart) / 60) % 137 == 0L
                MinuteSample(
                    timestamp = ts,
                    steps = 0,
                    intensity = 0,
                    rawKind = 1,
                    heartRate = 52 + (((ts - sleepStart) / 60) % 9).toInt(),
                    sleepStage = if (awakeBlip) SleepStage.AWAKE else SleepStage.ASLEEP,
                )
            }
            container.database.minuteSamples().upsertAll(minutes + nightMinutes)

            val series = mapOf(
                "stress" to (28.0 to 4.0),
                "spo2" to (97.0 to 1.0),
                "pai" to (219.3 to 2.0),
                "hrv" to (46.5 to 3.0),
                "temperature" to (33.4 to 0.3),
                "respiratory_rate" to (14.0 to 1.0),
            )
            series.forEach { (name, spec) ->
                val (base, spread) = spec
                container.database.pointSamples().upsertAll(
                    (0 until 48).map { i ->
                        PointSample(
                            series = name,
                            timestamp = now - (48 - i) * 1800L,
                            value = base + ((i % 5) - 2) * spread,
                        )
                    },
                )
            }

            // The device's own stage segments for that same night.
            val stages = listOf(4, 5, 4, 8, 4, 5, 7, 4, 8, 4)
            var cursor = sleepStart
            val step = (wake - sleepStart) / stages.size
            val segments = stages.map { stage ->
                val segment = SleepStageSegment(
                    sessionEnd = wake,
                    startTimestamp = cursor,
                    endTimestamp = cursor + step,
                    stage = stage,
                )
                cursor += step
                segment
            }
            container.database.sleepStageSegments().upsertAll(segments)

            container.database.slots().upsert(
                Slot(
                    label = "Entraînement badminton",
                    dayOfWeek = DayOfWeek.MONDAY,
                    startSecondOfDay = 19 * 3600 + 30 * 60,
                    endSecondOfDay = 21 * 3600 + 15 * 60,
                    sport = SportType.BADMINTON,
                ),
            )
            // A spread of categories, so the list shows what a real week looks like.
            listOf(
                Triple(SportType.BADMINTON, "Entraînement badminton", ActivityStatus.CONFIRMED),
                Triple(SportType.RIDE, "Sortie vélo", ActivityStatus.CONFIRMED),
                Triple(SportType.RUN, "Footing du matin", ActivityStatus.PUBLISHED),
                Triple(SportType.SWIM, "Piscine", ActivityStatus.CONFIRMED),
                Triple(SportType.WEIGHT_TRAINING, "Renforcement", ActivityStatus.CONFIRMED),
            ).forEachIndexed { index, (sport, title, status) ->
                container.database.activities().upsert(
                    Activity(
                        startTimestamp = now - 9000L - index * 7200L,
                        endTimestamp = now - 3000L - index * 7200L,
                        sport = sport,
                        title = title,
                        notes = null,
                        origin = ActivityOrigin.SLOT,
                        status = status,
                    ),
                )
            }
        }
    }

    /**
     * Screens load their data off the main thread, so a single waitForIdle() captures an
     * empty frame. Pumps the main looper until the composition stops changing.
     */
    private fun settle() {
        repeat(40) {
            rule.waitForIdle()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        rule.waitForIdle()
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            HelionTheme {
                Box(modifier = Modifier.fillMaxSize().background(HelionThemeTokens.colors.ground)) { content() }
            }
        }
        settle()
        // Compose's own captureToImage() waits on a redraw callback Robolectric never
        // fires, so the view tree is measured, laid out and drawn straight into a bitmap.
        val view: View = rule.activity.window.decorView
        val widthPx = 1080
        val heightPx = 2340
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        val file = File(outDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name -> ${file.absolutePath}")
    }

    @Test fun accueil() { seed(); shoot("accueil") { HomeScreen(container, onOpenMetric = {}, onOpenSettings = {}) } }

    @Test fun accueilClair() {
        seed(dark = false); shoot("accueil-clair") { HomeScreen(container, onOpenMetric = {}, onOpenSettings = {}) }
    }

    @Test fun sommeil() { seed(); shoot("sommeil") { SleepScreen(container) } }

    @Test fun metrique() {
        seed()
        val metric = MetricCatalog.byId("respiratory_rate")!!
        shoot("metrique") { MetricScreen(container, metric, onBack = {}) }
    }

    @Test fun metriqueCardiaque() {
        seed()
        val metric = MetricCatalog.byId("heart_rate")!!
        shoot("metrique-cardiaque") { MetricScreen(container, metric, onBack = {}) }
    }

    @Test fun activites() { seed(); shoot("activites") { ActivityListScreen(container, onOpenActivity = {}, onNewActivity = {}, onManageSlots = {}) } }

    @Test fun activiteDetail() { seed(); shoot("activite-detail") { ActivityDetailScreen(container, activityId = 1L, onBack = {}, onDeleted = {}) } }

    @Test fun creneaux() { seed(); shoot("creneaux") { SlotListScreen(container, onBack = {}, onOpenSlot = {}, onNewSlot = {}) } }

    @Test fun creneauEdition() { seed(); shoot("creneau-edition") { SlotEditScreen(container, slotId = 1L, onBack = {}, onSaved = {}, onDeleted = {}) } }

    @Test fun timeline() { seed(); shoot("timeline") { DayTimelineScreen(container, onBack = {}, onActivityCreated = {}) } }

    @Test fun reglages() { seed(); shoot("reglages") { SettingsScreen(container, onOpenSection = {}) } }

    @Test fun reglagesProfil() { seed(); shoot("reglages-profil") { SettingsSectionScreen(container, sectionId = "profile", onBack = {}) } }
}
