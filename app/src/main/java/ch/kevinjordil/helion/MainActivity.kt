package ch.kevinjordil.helion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ch.kevinjordil.helion.notification.EXTRA_OPEN_ACTIVITIES_LIST
import ch.kevinjordil.helion.notification.EXTRA_OPEN_ACTIVITY_ID
import ch.kevinjordil.helion.notification.EXTRA_OPEN_SLEEP
import ch.kevinjordil.helion.ui.HelionNavHost
import ch.kevinjordil.helion.ui.NotificationNavigationTarget
import ch.kevinjordil.helion.ui.RootDestination
import ch.kevinjordil.helion.ui.activityDetailRoute
import ch.kevinjordil.helion.ui.activitiesListRoute
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionTheme

/** The app's only launchable entry point. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Drawn edge to edge behind transparent system bars with light icons: the app is dark
        // only, and the navigation shell pads its content and bar by the system insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        setContent {
            HelionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = HelionThemeTokens.colors.ground) {
                    HelionNavHost((application as HelionApp).container)
                }
            }
        }
    }

    /**
     * Every return to the foreground -- launcher, recents, a notification tap -- asks for the
     * opening sync, whichever screen the app lands on. [ch.kevinjordil.helion.ui.home.ForegroundSync]
     * decides whether one is actually due (debounced, and only once an export file is chosen).
     */
    override fun onResume() {
        super.onResume()
        (application as HelionApp).container.foregroundSync.onAppOpened()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Reads the extras [ch.kevinjordil.helion.notification.CandidateNotifier] puts on a
     * notification's own content intent and turns them into the one route
     * [ch.kevinjordil.helion.ui.HelionNavHost] should land on next -- a single candidate's
     * detail directly, the Activités list for a batch, or Sommeil for the sleep-summary
     * notification (see [ch.kevinjordil.helion.notification.SleepNightNotifier]). No extra
     * present (any other launch, including a plain relaunch from the launcher) leaves
     * [NotificationNavigationTarget] untouched, so the app opens wherever it otherwise would.
     */
    private fun handleNotificationIntent(intent: Intent) {
        val activityId = intent.getLongExtra(EXTRA_OPEN_ACTIVITY_ID, -1L)
        when {
            activityId >= 0 -> NotificationNavigationTarget.route = activityDetailRoute(activityId)
            intent.getBooleanExtra(EXTRA_OPEN_ACTIVITIES_LIST, false) -> NotificationNavigationTarget.route = activitiesListRoute
            intent.getBooleanExtra(EXTRA_OPEN_SLEEP, false) -> NotificationNavigationTarget.route = RootDestination.SLEEP.route
        }
    }
}
