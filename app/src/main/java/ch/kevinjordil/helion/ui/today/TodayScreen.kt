package ch.kevinjordil.helion.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import ch.kevinjordil.helion.ui.minutesSinceLastSync

/**
 * Placeholder for now (task 8a): a title and the freshness indicator. Per-metric summaries
 * land on this screen in a later task.
 */
@Composable
fun TodayScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var lastSyncAttempt by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lastSyncAttempt = container.database.syncState().get()?.lastSyncAttempt
        loaded = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.tab_today), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (loaded) {
            val minutes = minutesSinceLastSync(lastSyncAttempt, System.currentTimeMillis() / 1000)
            Text(
                if (minutes == null) {
                    stringResource(R.string.never_synced)
                } else {
                    stringResource(R.string.synced_minutes_ago, minutes)
                },
            )
        }
    }
}
