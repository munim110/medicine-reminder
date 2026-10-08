package com.medremind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medremind.app.R
import com.medremind.app.data.DoseStatus
import java.time.LocalDate

@Composable
fun HistoryScreen(vm: MedViewModel, modifier: Modifier = Modifier) {
    val logs by vm.history.collectAsStateWithLifecycle()

    if (logs.isEmpty()) {
        Text(
            text = stringResource(R.string.history_empty),
            modifier = modifier.padding(16.dp),
        )
        return
    }

    val byDate = logs.groupBy { it.date }.toSortedMap(compareByDescending { it })

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            val taken = logs.count { it.status == DoseStatus.TAKEN }
            Text(
                text = stringResource(
                    R.string.history_summary,
                    Bn.num(byDate.size),
                    Bn.num(taken),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        byDate.forEach { (date, entries) ->
            item(key = "header-$date") {
                Text(
                    text = Bn.relativeDate(runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(entries.sortedBy { it.scheduledAt }, key = { it.id }) { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(log.label, style = MaterialTheme.typography.bodyLarge)
                            if (log.medicines.isNotBlank()) {
                                Text(
                                    text = log.medicines,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = stringResource(statusLabel(log.status)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (log.status) {
                                DoseStatus.TAKEN -> Color(0xFF1B5E20)
                                DoseStatus.MISSED -> MaterialTheme.colorScheme.error
                                DoseStatus.SKIPPED -> MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                }
            }
        }
    }
}
