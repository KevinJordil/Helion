package ch.kevinjordil.helion.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricReader
import ch.kevinjordil.helion.ui.metric.Range
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.formatValue
import ch.kevinjordil.helion.ui.minutesSinceLastSample

/**
 * The Today tab: the freshness indicator plus every catalog metric's latest reading. Each
 * metric looks up its own latest value over a wide (year-long) window, rather than "right
 * now" -- nothing here is live, see [minutesSinceLastSample]'s kdoc.
 *
 * The indicator reports the age of the newest sample actually stored, not the time of the
 * last sync attempt, and a sync that failed is stated outright rather than left to be
 * inferred: a broken sync must never be able to look like a fresh one.
 */
@Composable
fun TodayScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var lastError by remember { mutableStateOf<String?>(null) }
    var latestByMetricId by remember { mutableStateOf<Map<String, Reading?>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val reader = MetricReader(container.database)
        val now = System.currentTimeMillis() / 1000
        lastError = container.database.syncState().get()?.lastError
        latestByMetricId = MetricCatalog.all.associate { metric ->
            metric.id to reader.load(metric, Range.YEAR, now).latest
        }
        loaded = true
    }

    // The newest sample anywhere in the archive: every metric's latest reading has already
    // been loaded above, so this costs no extra query.
    val newestSample = latestByMetricId.values.filterNotNull().maxOfOrNull { it.timestamp }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.tab_today), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (loaded) {
            val minutes = minutesSinceLastSample(newestSample, System.currentTimeMillis() / 1000)
            Text(
                if (minutes == null) {
                    stringResource(R.string.never_synced)
                } else {
                    stringResource(R.string.last_value_minutes_ago, minutes)
                },
            )
            lastError?.let { error ->
                Text(
                    stringResource(R.string.last_sync_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            MetricCatalog.all.forEach { metric ->
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(metric.labelRes))
                    val reading = latestByMetricId[metric.id]
                    Text(
                        if (reading == null) {
                            stringResource(R.string.no_data)
                        } else {
                            "${metric.formatValue(reading.value)} ${stringResource(metric.unitRes)}".trim()
                        },
                    )
                }
            }
        }
    }
}
