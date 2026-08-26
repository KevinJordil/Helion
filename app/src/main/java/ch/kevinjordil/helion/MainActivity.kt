package ch.kevinjordil.helion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ch.kevinjordil.helion.notification.EXTRA_OPEN_ACTIVITIES_LIST
import ch.kevinjordil.helion.notification.EXTRA_OPEN_ACTIVITY_ID
import ch.kevinjordil.helion.ui.HelionNavHost
import ch.kevinjordil.helion.ui.NotificationNavigationTarget
import ch.kevinjordil.helion.ui.activityDetailRoute
import ch.kevinjordil.helion.ui.activitiesListRoute
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionTheme

/** The app's only launchable entry point. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Reads the extras [ch.kevinjordil.helion.notification.CandidateNotifier] puts on a
     * notification's own content intent and turns them into the one route
     * [ch.kevinjordil.helion.ui.HelionNavHost] should land on next -- a single candidate's
     * detail directly, or the Activités list for a batch. Neither extra present (any other
     * launch, including a plain relaunch from the launcher) leaves
     * [NotificationNavigationTarget] untouched, so the app opens wherever it otherwise would.
     */
    private fun handleNotificationIntent(intent: Intent) {
        val activityId = intent.getLongExtra(EXTRA_OPEN_ACTIVITY_ID, -1L)
        when {
            activityId >= 0 -> NotificationNavigationTarget.route = activityDetailRoute(activityId)
            intent.getBooleanExtra(EXTRA_OPEN_ACTIVITIES_LIST, false) -> NotificationNavigationTarget.route = activitiesListRoute
        }
    }
}
