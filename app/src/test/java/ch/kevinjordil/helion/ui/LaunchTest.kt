package ch.kevinjordil.helion.ui

import androidx.test.core.app.ActivityScenario
import ch.kevinjordil.helion.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class LaunchTest {

    // Regression guard: a top-level val that touched RootDestination during this file
    // class's initialisation left every tab icon null, crashing the first frame. Nothing
    // below the composition level catches that, so the activity is launched for real.

    @Test
    fun `MainActivity launches`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
