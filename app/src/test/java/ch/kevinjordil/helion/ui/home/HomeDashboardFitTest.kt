package ch.kevinjordil.helion.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import ch.kevinjordil.helion.ui.HelionNavHost
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.theme.HelionTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify the actual composed dashboard, including its bottom navigation, rather than a height formula. */
@RunWith(RobolectricTestRunner::class)
// Qualifiers in Android's order: night mode comes before density, or Robolectric refuses them.
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class HomeDashboardFitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun display(width: Int = 360, height: Int = 700, fontScale: Float = 1f) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context)
        container.exportLocation.uri = "content://test/export"
        val now = System.currentTimeMillis() / 1000
        container.openSyncGate.recordAttempt(now)
        runBlocking {
            container.database.minuteSamples().upsertAll(listOf(
                MinuteSample(timestamp = now - 60, steps = 12500, intensity = 10,
                    rawKind = 1, heartRate = 124, sleepStage = null),
            ))
            container.database.pointSamples().upsertAll(
                mapOf("stress" to 28.0, "spo2" to 98.0, "pai" to 219.3,
                    "hrv" to 46.5, "temperature" to 33.4, "respiratory_rate" to 14.0)
                    .map { (series, value) -> PointSample(series, now - 60, value) },
            )
        }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HelionTheme {
                    // 700dp is conservative: it already excludes room for the phone's system bars.
                    Box(Modifier.requiredSize(width.dp, height.dp)) { HelionNavHost(container) }
                }
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag("home-dashboard")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun assertAllMetricsFit() {
        val scroll = rule.onNodeWithTag("home-dashboard").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("The complete home screen must fit above the navigation bar", 0f, scroll.maxValue(), 0.5f)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MetricCatalog.all.forEach { metric ->
            rule.onNodeWithText(context.getString(metric.labelRes)).assertIsDisplayed()
        }
        rule.onNodeWithText("Réglages").assertIsDisplayed()
    }

    @Test fun `all eight metrics fit the S23 portrait viewport`() {
        display()
        assertAllMetricsFit()
    }

    @Test fun `moderately enlarged text still fits without scrolling`() {
        display(fontScale = 1.15f)
        assertAllMetricsFit()
    }

    @Test fun `smaller displays with large text can still reach every metric`() {
        display(width = 320, height = 600, fontScale = 1.3f)
        val dashboard = rule.onNodeWithTag("home-dashboard")
        val scroll = dashboard.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.maxValue() > 0f)
        dashboard.performScrollToNode(hasText("Respiration"))
        rule.onNodeWithText("Respiration").assertIsDisplayed()
    }
}
