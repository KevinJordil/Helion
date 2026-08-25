package ch.kevinjordil.helion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import ch.kevinjordil.helion.ui.HelionNavHost
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
    }

    /**
     * Exchanges the redirect's `code` query parameter for tokens, off the main thread. Any
     * failure here (network, or Strava rejecting the code) simply leaves the owner
     * unauthorised -- the next publish attempt reports that plainly rather than this
     * silently retrying or crashing the activity.
     */
    private fun handleOAuthRedirect(intent: Intent, container: AppContainer) {
        val uri = intent.data ?: return
        if (uri.scheme != "helion" || uri.host != "oauth-callback") return
        val code = uri.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { container.stravaAuth.exchangeCode(code) } }
        }
    }
}
