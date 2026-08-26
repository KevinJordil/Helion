package ch.kevinjordil.helion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import ch.kevinjordil.helion.notification.EXTRA_OPEN_ACTIVITIES_LIST
import ch.kevinjordil.helion.notification.EXTRA_OPEN_ACTIVITY_ID
import ch.kevinjordil.helion.strava.OAuthRedirect
import ch.kevinjordil.helion.strava.parseOAuthRedirect
import ch.kevinjordil.helion.ui.HelionNavHost
import ch.kevinjordil.helion.ui.NotificationNavigationTarget
import ch.kevinjordil.helion.ui.activityDetailRoute
import ch.kevinjordil.helion.ui.activitiesListRoute
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's only launchable entry point. Also where Strava's OAuth redirect lands: the
 * manifest's `helion://oauth-callback` intent-filter on this activity (see
 * [ch.kevinjordil.helion.strava.StravaConfig.REDIRECT_URI]) brings the browser's result
 * back here as a `VIEW` intent carrying the authorization code as a query parameter.
 * `singleTask` launch mode means that always lands in [onNewIntent] on the already-running
 * instance rather than starting a second one.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HelionApp).container
        handleOAuthRedirect(intent, container)
        handleNotificationIntent(intent)
        setContent {
            HelionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = HelionThemeTokens.colors.ground) {
                    HelionNavHost(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthRedirect(intent, (application as HelionApp).container)
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

    /**
     * Parses the redirect (a `code`, an `error`, or neither -- see [parseOAuthRedirect]) and
     * hands it to [ch.kevinjordil.helion.strava.StravaAuth.handleRedirect], off the main
     * thread since exchanging a code makes a network request. Every outcome -- a rejected
     * secret, a code already used, the owner declining, a dead network -- is captured there
     * and published on [ch.kevinjordil.helion.strava.StravaAuth.status], never discarded:
     * this used to `runCatching { ... }` and drop the result, which is why a failed
     * exchange looked identical to nothing having happened at all.
     */
    private fun handleOAuthRedirect(intent: Intent, container: AppContainer) {
        val uri = intent.data ?: return
        val redirect = parseOAuthRedirect(uri)
        if (redirect == OAuthRedirect.NotAnOAuthRedirect) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.stravaAuth.handleRedirect(redirect) }
        }
    }
}
