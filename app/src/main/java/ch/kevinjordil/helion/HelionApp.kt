package ch.kevinjordil.helion

import android.app.Application
import androidx.work.Configuration
import ch.kevinjordil.helion.source.SyncScheduler

/**
 * Application entry point for Helion.
 *
 * Implements [Configuration.Provider] so WorkManager initialises on demand -- on the
 * first call to `WorkManager.getInstance()`, e.g. from [ch.kevinjordil.helion.source.SyncScheduler] --
 * instead of eagerly during [onCreate] via its default androidx.startup ContentProvider.
 * Eager initialisation would register WorkManager's system broadcast receivers (battery,
 * network, ...) on every process start, including under Robolectric where they show up as
 * unrelated noise in tests that assert on registered receivers.
 */
class HelionApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        installCrashLog(this)
        container = AppContainer(this)

        // Scheduled unconditionally, not only once a location has been chosen: this is
        // the only place guaranteed to run on every process start regardless of which
        // entry point (if any beyond MainActivity ever exists) brought the app up, it is
        // idempotent (KEEP policy, see SyncScheduler), and SyncWorker already treats "no
        // location configured" as a harmless no-op (Result.success() with nothing done).
        // Gating this on a chosen location would just mean re-deriving that same
        // "has a location been chosen" check here *and* re-running schedule() the moment
        // one is, for no benefit -- periodic ingestion should simply start working the
        // instant Settings saves a location, without an extra wiring path to keep in sync.
        SyncScheduler.schedule(this)
    }
}
