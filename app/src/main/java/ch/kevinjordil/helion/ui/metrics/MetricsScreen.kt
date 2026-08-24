package ch.kevinjordil.helion.ui.metrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.Metric
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricScreen

/**
 * The Metrics tab: a list of every catalog entry, each opening its own [MetricScreen].
 * Local state toggles between the list and the detail rather than reaching into
 * [ch.kevinjordil.helion.ui.HelionNavHost] -- the nav shell's three destinations are
 * flat and this drill-down is internal to the Metrics tab.
 */
@Composable
fun MetricsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var selectedMetric by rememberSaveable { mutableStateOf<String?>(null) }

    val metric = selectedMetric?.let { MetricCatalog.byId(it) }
    if (metric != null) {
        MetricScreen(container, metric, onBack = { selectedMetric = null }, modifier = modifier)
    } else {
        MetricList(onSelect = { selectedMetric = it.id }, modifier = modifier)
    }
}

@Composable
private fun MetricList(onSelect: (Metric) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.tab_metrics), style = MaterialTheme.typography.headlineSmall)
        MetricCatalog.all.forEach { metric ->
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(metric) }
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(metric.labelRes))
            }
        }
    }
}
