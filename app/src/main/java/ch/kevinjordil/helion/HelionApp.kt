package ch.kevinjordil.helion

import android.app.Application
import androidx.work.Configuration

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
        container = AppContainer(this)
    }
}
