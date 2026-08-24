package ch.kevinjordil.helion

import android.content.Context
import androidx.room.Room
import ch.kevinjordil.helion.source.BroadcastCommandSender
import ch.kevinjordil.helion.source.BroadcastExportSignal
import ch.kevinjordil.helion.source.ExportLocation
import ch.kevinjordil.helion.source.ExportReader
import ch.kevinjordil.helion.source.GadgetbridgeCommands
import ch.kevinjordil.helion.source.Ingestor
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MIGRATION_1_2

/** Manual dependency wiring. The graph is small enough that a framework would cost more than it saves. */
class AppContainer(context: Context) {

    val database: HelionDatabase = Room
        .databaseBuilder(context, HelionDatabase::class.java, "helion.db")
        .addMigrations(MIGRATION_1_2)
        .build()

    val exportLocation = ExportLocation(context)

    val ingestor = Ingestor(
        reader = ExportReader(),
        commands = GadgetbridgeCommands(BroadcastCommandSender(context)),
        signal = BroadcastExportSignal(context),
        db = database,
        now = { System.currentTimeMillis() / 1000 },
    )
}
