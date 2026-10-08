package com.medremind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medremind.app.R
import com.medremind.app.data.AlarmSettings

@Composable
fun SettingsScreen(vm: MedViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            DurationRow(
                title = stringResource(R.string.settings_ring_title),
                description = stringResource(R.string.settings_ring_desc),
                minutes = settings.ringSeconds / 60,
                onChange = { vm.updateSettings(settings.copy(ringSeconds = it * 60)) },
            )
        }
        item {
            DurationRow(
                title = stringResource(R.string.settings_retry_title),
                description = stringResource(R.string.settings_retry_desc),
                minutes = settings.retryIntervalSeconds / 60,
                onChange = { vm.updateSettings(settings.copy(retryIntervalSeconds = it * 60)) },
            )
        }
        item {
            DurationRow(
                title = stringResource(R.string.settings_giveup_title),
                description = stringResource(R.string.settings_giveup_desc),
                minutes = settings.giveUpSeconds / 60,
                step = 5,
                onChange = { vm.updateSettings(settings.copy(giveUpSeconds = it * 60)) },
            )
        }
        item {
            DurationRow(
                title = stringResource(R.string.settings_snooze_title),
                description = stringResource(R.string.settings_snooze_desc),
                minutes = settings.snoozeMinutes,
                step = 5,
                onChange = { vm.updateSettings(settings.copy(snoozeMinutes = it)) },
            )
        }
        item { CyclePreview(settings) }
    }
}

@Composable
private fun DurationRow(
    title: String,
    description: String,
    minutes: Int,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = { onChange((minutes - step).coerceAtLeast(1)) }) {
                Icon(Icons.Filled.Remove, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.minutes_value, Bn.num(minutes)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(96.dp)
                    .padding(horizontal = 4.dp),
            )
            FilledTonalIconButton(onClick = { onChange(minutes + step) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    }
}

/** Spells out the resulting cycle for a 3:00 dose, so the numbers are not abstract. */
@Composable
private fun CyclePreview(settings: AlarmSettings) {
    val baseHour = 15
    val ringStarts = buildList {
        var offsetSeconds = 0
        while (offsetSeconds < settings.giveUpSeconds && size < 12) {
            add(offsetSeconds)
            offsetSeconds += settings.retryIntervalSeconds.coerceAtLeast(60)
        }
    }
    val times = ringStarts.joinToString(", ") { offset ->
        val total = baseHour * 60 + offset / 60
        Bn.timeShort((total / 60) % 24, total % 60)
    }
    val missedTotal = baseHour * 60 + settings.giveUpSeconds / 60

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_preview_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.settings_preview_rings,
                    Bn.num(settings.ringSeconds / 60),
                    times,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(
                    R.string.settings_preview_missed,
                    Bn.timeShort((missedTotal / 60) % 24, missedTotal % 60),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
