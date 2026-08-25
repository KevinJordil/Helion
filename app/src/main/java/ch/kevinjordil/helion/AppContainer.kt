package ch.kevinjordil.helion

import android.content.Context
import androidx.room.Room
import ch.kevinjordil.helion.source.BroadcastCommandSender
import ch.kevinjordil.helion.source.BroadcastExportSignal
import ch.kevinjordil.helion.source.BroadcastSyncSignal
import ch.kevinjordil.helion.source.ExportLocation
import ch.kevinjordil.helion.source.ExportReader
import ch.kevinjordil.helion.source.GadgetbridgeCommands
import ch.kevinjordil.helion.source.Ingestor
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MIGRATION_1_2
import ch.kevinjordil.helion.store.MIGRATION_2_3
import ch.kevinjordil.helion.store.MIGRATION_3_4
import ch.kevinjordil.helion.ui.home.OpenSyncGate
import ch.kevinjordil.helion.ui.settings.StepsGoal

/** Manual dependency wiring. The graph is small enough that a framework would cost more than it saves. */
class AppContainer(context: Context) {

    val database: HelionDatabase = Room
        .databaseBuilder(context, HelionDatabase::class.java, "helion.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()

    val exportLocation = ExportLocation(context)

    val stepsGoal = StepsGoal(context)

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
}
