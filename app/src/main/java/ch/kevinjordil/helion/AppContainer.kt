package ch.kevinjordil.helion

import android.content.Context
import androidx.room.Room
import ch.kevinjordil.helion.activity.ActivityDetector
import ch.kevinjordil.helion.customserver.CustomServerPublisher
import ch.kevinjordil.helion.customserver.HttpCustomServerApi
import ch.kevinjordil.helion.healthconnect.HealthConnectExporter
import ch.kevinjordil.helion.healthconnect.enqueueHealthConnectExport
import ch.kevinjordil.helion.healthconnect.realHealthConnectWriterOrNull
import ch.kevinjordil.helion.source.BroadcastCommandSender
import ch.kevinjordil.helion.source.BroadcastExportSignal
import ch.kevinjordil.helion.source.BroadcastSyncSignal
import ch.kevinjordil.helion.source.ExportLocation
import ch.kevinjordil.helion.source.ExportReader
import ch.kevinjordil.helion.source.GadgetbridgeCommands
import ch.kevinjordil.helion.source.Ingestor
import ch.kevinjordil.helion.notification.CandidateNotifier
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MIGRATION_1_2
import ch.kevinjordil.helion.store.MIGRATION_2_3
import ch.kevinjordil.helion.store.MIGRATION_3_4
import ch.kevinjordil.helion.store.MIGRATION_4_5
import ch.kevinjordil.helion.store.MIGRATION_5_6
import ch.kevinjordil.helion.store.MIGRATION_6_7
import ch.kevinjordil.helion.store.MIGRATION_7_8
import ch.kevinjordil.helion.store.MIGRATION_8_9
import ch.kevinjordil.helion.store.MIGRATION_9_10
import ch.kevinjordil.helion.ui.home.OpenSyncGate
import ch.kevinjordil.helion.ui.settings.CustomServerConfig
import ch.kevinjordil.helion.ui.settings.HealthConnectConfig
import ch.kevinjordil.helion.ui.settings.NotificationPreference
import ch.kevinjordil.helion.ui.settings.Profile
import ch.kevinjordil.helion.ui.settings.StepsGoal
import java.time.ZoneId

/** Manual dependency wiring. The graph is small enough that a framework would cost more than it saves. */
class AppContainer(context: Context) {

    val database: HelionDatabase = Room
        .databaseBuilder(context, HelionDatabase::class.java, "helion.db")
        .addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
        )
        .build()

    val exportLocation = ExportLocation(context)

    /** The owner's own inputs to [ch.kevinjordil.helion.calorie.CalorieEstimator] -- see [Profile]'s own kdoc. */
    val profile = Profile(context)

    /** Where the owner's own server URL and shared token live -- see [CustomServerConfig]'s own kdoc. */
    val customServerConfig = CustomServerConfig(context)

    /**
     * The one send entry point the UI calls for the owner's own server, wired to the real
     * network implementation. See [CustomServerPublisher]'s own kdoc for why this is safe
     * to call again.
     */
    val customServerPublisher = CustomServerPublisher(
        activities = database.activities(),
        minuteSamples = database.minuteSamples(),
        publications = database.publications(),
        config = customServerConfig,
        api = HttpCustomServerApi(),
        profile = profile,
        zone = ZoneId.systemDefault(),
        now = { System.currentTimeMillis() / 1000 },
    )

    /** The Réglages on/off switch for the Health Connect export -- see [HealthConnectConfig]'s own kdoc. */
    val healthConnectConfig = HealthConnectConfig(context)

    /**
     * The one entry point [ch.kevinjordil.helion.source.Ingestor] (via
     * [ch.kevinjordil.helion.healthconnect.HealthConnectSyncWorker]) and Réglages' own
     * "Exporter maintenant" button both call. [realHealthConnectWriterOrNull] is re-checked
     * on every call rather than resolved once here: Health Connect being installed, or the
     * owner's permission grant, can both change between one call and the next.
     */
    val healthConnectExporter = HealthConnectExporter(
        db = database,
        config = healthConnectConfig,
        writerProvider = { realHealthConnectWriterOrNull(context) },
    )

    val stepsGoal = StepsGoal(context)

    /** The Réglages on/off switch for candidate-detection notifications -- see its own kdoc. */
    val notificationPreference = NotificationPreference(context)

    val commands = GadgetbridgeCommands(BroadcastCommandSender(context))

    /**
     * Used only by the home screen's pull-to-refresh, to wait for Gadgetbridge's Bluetooth
     * sync to actually finish before the export is triggered. [Ingestor] itself does not
     * hold a reference to this -- see [Ingestor]'s kdoc for why its own periodic pass does
     * not wait on this broadcast.
     */
    val syncSignal = BroadcastSyncSignal(context)

    val ingestor = Ingestor(
        reader = ExportReader(),
        commands = commands,
        signal = BroadcastExportSignal(context),
        db = database,
        now = { System.currentTimeMillis() / 1000 },
    )

    /** Debounces Accueil's open-sync across remounts of the screen; see [OpenSyncGate]'s kdoc. */
    val openSyncGate = OpenSyncGate()

    /**
     * Wired onto [ingestor] rather than passed to its constructor -- see [Ingestor.detector]'s
     * own kdoc for why. `noteFor` renders `activity_candidate_note`, the one place a
     * detection pass' evidence (the heart-rate range it saw, against the owner's own resting
     * rate) becomes the French sentence stored on
     * [ch.kevinjordil.helion.store.Activity.detectionContext].
     */
    init {
        ingestor.detector = ActivityDetector(
            db = database,
            zone = ZoneId.systemDefault(),
            now = { System.currentTimeMillis() / 1000 },
            noteFor = { min, max, resting -> context.getString(R.string.activity_candidate_note, min, max, resting) },
        )
        ingestor.notifier = CandidateNotifier(context, notificationPreference)
        ingestor.healthConnectExportTrigger = { enqueueHealthConnectExport(context) }
    }
}
