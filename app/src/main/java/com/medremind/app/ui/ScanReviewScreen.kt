package com.medremind.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medremind.app.R
import com.medremind.app.cloud.ScanError
import com.medremind.app.data.Frequency
import com.medremind.app.data.SlotKind

/**
 * Everything the AI read, editable, before anything touches the schedule. Unsure readings must be
 * ticked as checked against the paper, and every medicine needs at least one time of day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanReviewScreen(state: ScanUiState, vm: ScanViewModel, onDone: () -> Unit) {
    BackHandler { vm.close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = {
                    IconButton(onClick = { vm.close() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
        bottomBar = {
            if (state is ScanUiState.Review) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (!state.canImport && state.included.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.review_incomplete),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = { vm.import() },
                            enabled = state.canImport,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.review_import, Bn.num(state.included.size)))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                ScanUiState.Idle -> Unit
                ScanUiState.Working -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.scan_working), modifier = Modifier.padding(top = 16.dp))
                }
                is ScanUiState.Failed -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(scanErrorLabel(state.error)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { vm.close() }) { Text(stringResource(R.string.close)) }
                }
                is ScanUiState.Done -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.review_done, Bn.num(state.added)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.newSlots > 0) {
                        Text(
                            text = stringResource(R.string.review_done_new_slots),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.close))
                    }
                }
                is ScanUiState.Review -> ReviewList(state, vm)
            }
        }
    }
}

@Composable
private fun ReviewList(state: ScanUiState.Review, vm: ScanViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.review_intro), style = MaterialTheme.typography.bodyMedium)
                if (state.notPrescription) {
                    Text(
                        text = stringResource(R.string.scan_not_prescription),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.drafts.isEmpty()) {
                    Text(stringResource(R.string.review_nothing_found), modifier = Modifier.padding(top = 4.dp))
                }
                state.notes?.let {
                    Text(
                        text = stringResource(R.string.review_notes, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        items(state.drafts, key = { it.key }) { draft -> DraftCard(draft, vm) }
        item {
            OutlinedButton(
                onClick = { vm.addBlank() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.review_add_missing), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                text = stringResource(R.string.scan_left_today, Bn.num(state.scansLeft)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(draft: Draft, vm: ScanViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (draft.include) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = draft.include,
                        role = Role.Checkbox,
                        onValueChange = { on -> vm.edit(draft.key) { it.copy(include = on) } },
                    ),
            ) {
                // The whole row toggles; this padding restores the spacing a clickable Checkbox would have.
                Checkbox(checked = draft.include, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(
                    text = stringResource(if (draft.include) R.string.review_include else R.string.review_excluded),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (!draft.include) {
                Text(draft.name, style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { vm.rename(draft.key, it) },
                label = { Text(stringResource(R.string.medicine_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.readAs.isNotBlank() && draft.readAs != draft.name) {
                Text(
                    text = stringResource(R.string.review_read_as, draft.readAs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            draft.genericName?.let {
                Text(
                    text = stringResource(R.string.generic_name, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (draft.suggestions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.review_did_you_mean),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                draft.suggestions.forEach { match ->
                    TextButton(
                        onClick = { vm.pick(draft.key, match) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = "${match.product.displayName} — ${match.product.generic}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            SectionLabel(
                stringResource(R.string.review_when) +
                    (draft.doseText?.let { " " + stringResource(R.string.review_written, it) } ?: ""),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    DraftFrequency.DAILY to R.string.freq_daily,
                    DraftFrequency.WEEKLY to R.string.freq_weekly,
                    DraftFrequency.MONTHLY to R.string.freq_monthly,
                    DraftFrequency.AS_NEEDED to R.string.scan_as_needed,
                ).forEach { (option, label) ->
                    FilterChip(
                        selected = draft.frequency == option,
                        onClick = { vm.edit(draft.key) { it.copy(frequency = option) } },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            if (draft.frequency != DraftFrequency.AS_NEEDED) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SlotKind.entries.forEach { kind ->
                        SlotDoseButton(
                            kind = kind,
                            quantity = draft.doses[kind] ?: 0.0,
                            onChange = { q -> vm.edit(draft.key) { it.copy(doses = it.doses + (kind to q)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (!draft.hasTimes) {
                    Text(
                        text = stringResource(R.string.review_pick_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (draft.frequency == DraftFrequency.WEEKLY || draft.frequency == DraftFrequency.MONTHLY) {
                DayPicker(
                    frequency = if (draft.frequency == DraftFrequency.WEEKLY) {
                        Frequency.WEEKLY
                    } else {
                        Frequency.MONTHLY
                    },
                    dayOfWeek = draft.dayOfWeek,
                    dayOfMonth = draft.dayOfMonth,
                    onChange = { _, dow, dom -> vm.edit(draft.key) { it.copy(dayOfWeek = dow, dayOfMonth = dom) } },
                )
            }

            SectionLabel(stringResource(R.string.food_rule))
            FoodRuleChips(draft.foodRule, onChange = { rule -> vm.edit(draft.key) { it.copy(foodRule = rule) } })

            draft.durationText?.let {
                Text(
                    text = stringResource(R.string.review_duration, it),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            draft.instructions?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            if (draft.needsCheck) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .toggleable(
                            value = draft.checked,
                            role = Role.Checkbox,
                            onValueChange = { on -> vm.edit(draft.key) { it.copy(checked = on) } },
                        ),
                ) {
                    Checkbox(checked = draft.checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(
                        text = stringResource(R.string.review_checked),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (draft.checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private val SLOT_QUANTITIES = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0)

/** `সকাল ১` — tap to pick how many at that time of day, or none. */
@Composable
private fun SlotDoseButton(kind: SlotKind, quantity: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = quantity > 0,
            onClick = { open = true },
            label = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(kind.label, style = MaterialTheme.typography.labelMedium)
                    Text(if (quantity > 0) Bn.qty(quantity) else "০", style = MaterialTheme.typography.titleMedium)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SLOT_QUANTITIES.forEach { q ->
                DropdownMenuItem(
                    text = { Text(if (q == 0.0) stringResource(R.string.none) else "${Bn.qty(q)}টি") },
                    onClick = {
                        onChange(q)
                        open = false
                    },
                )
            }
        }
    }
}

fun scanErrorLabel(error: ScanError): Int = when (error) {
    ScanError.OFFLINE -> R.string.scan_error_offline
    ScanError.DAILY_LIMIT -> R.string.scan_error_limit
    ScanError.BUSY -> R.string.scan_error_busy
    ScanError.UNREADABLE_IMAGE -> R.string.scan_error_image
    ScanError.FAILED -> R.string.scan_error_failed
}
