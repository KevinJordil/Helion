package ch.kevinjordil.helion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ch.kevinjordil.helion.ui.HelionNavHost
import ch.kevinjordil.helion.ui.theme.HelionTheme

/** The app's only launchable entry point. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HelionApp).container
        setContent {
            HelionTheme {
                HelionNavHost(container)
            }
        }
    }
}
