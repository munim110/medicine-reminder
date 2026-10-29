package com.medremind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medremind.app.R
import com.medremind.app.data.DrugDirectory
import com.medremind.app.data.DrugProduct
import com.medremind.app.data.FoodRule
import com.medremind.app.data.Frequency
import java.time.LocalDate
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}

/** What the medicine dialog edits: the medicine's name plus how it is taken in this slot. */
data class MedicineInput(
    val name: String = "",
    val quantity: Double = 1.0,
    val foodRule: FoodRule = FoodRule.AFTER_FOOD,
    val frequency: Frequency = Frequency.DAILY,
    val dayOfWeek: Int = LocalDate.now().dayOfWeek.value,
    val dayOfMonth: Int = LocalDate.now().dayOfMonth,
    val genericName: String? = null,
)

/** Add a medicine to a slot, or edit one. Renaming applies to every slot the medicine is in. */
@Composable
fun MedicineDialog(
    title: String,
    initial: MedicineInput = MedicineInput(),
    showRenameNote: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (MedicineInput) -> Unit,
) {
    var input by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input.copy(name = input.name.trim())) },
                enabled = input.name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MedicineNameField(
                    value = input.name,
                    onValueChange = { input = input.copy(name = it, genericName = null) },
                    onPick = { input = input.copy(name = it.displayName, genericName = it.generic) },
                )
                input.genericName?.let {
                    Text(
                        text = stringResource(R.string.generic_name, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showRenameNote) {
                    Text(
                        text = stringResource(R.string.rename_everywhere),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel(stringResource(R.string.quantity_per_dose))
                QuantityChips(input.quantity, onChange = { input = input.copy(quantity = it) })

                SectionLabel(stringResource(R.string.food_rule))
                FoodRuleChips(input.foodRule, onChange = { input = input.copy(foodRule = it) })

                SectionLabel(stringResource(R.string.how_often))
                FrequencyPicker(
                    frequency = input.frequency,
                    dayOfWeek = input.dayOfWeek,
                    dayOfMonth = input.dayOfMonth,
                    onChange = { f, dow, dom -> input = input.copy(frequency = f, dayOfWeek = dow, dayOfMonth = dom) },
                )
            }
        },
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Medicine name with suggestions from the bundled drug list as the user types. Picking one fills
 * in the brand and strength the way a prescription writes them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicineNameField(
    value: String,
    onValueChange: (String) -> Unit,
    onPick: (DrugProduct) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.medicine_name),
) {
    val context = LocalContext.current
    var suggestions by remember { mutableStateOf(emptyList<DrugProduct>()) }
    var picked by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(value) {
        if (value == picked) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = runCatching { DrugDirectory.get(context).complete(value, limit = 4) }
            .getOrDefault(emptyList())
            .filterNot { it.displayName.equals(value.trim(), ignoreCase = true) }
    }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { product ->
                    SuggestionChip(
                        onClick = {
                            picked = product.displayName
                            onPick(product)
                        },
                        label = { Text(product.displayName) },
                    )
                }
            }
        }
    }
}

private val QUANTITY_OPTIONS = listOf(0.5, 1.0, 1.5, 2.0, 3.0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuantityChips(value: Double, onChange: (Double) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (QUANTITY_OPTIONS + listOf(value).filter { it !in QUANTITY_OPTIONS && it > 0 }).forEach { option ->
            FilterChip(
                selected = value == option,
                onClick = { onChange(option) },
                label = { Text("${Bn.qty(option)}টি") },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodRuleChips(value: FoodRule, onChange: (FoodRule) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FoodRule.entries.forEach { option ->
            FilterChip(
                selected = value == option,
                onClick = { onChange(option) },
                label = { Text(stringResource(foodRuleLabel(option))) },
            )
        }
    }
}

/** Every day, one weekday, or one day of the month. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrequencyPicker(
    frequency: Frequency,
    dayOfWeek: Int,
    dayOfMonth: Int,
    onChange: (Frequency, Int, Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Frequency.DAILY to R.string.freq_daily,
            Frequency.WEEKLY to R.string.freq_weekly,
            Frequency.MONTHLY to R.string.freq_monthly,
        ).forEach { (option, label) ->
            FilterChip(
                selected = frequency == option,
                onClick = { onChange(option, dayOfWeek, dayOfMonth) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    DayPicker(frequency, dayOfWeek, dayOfMonth, onChange)
}

/** The weekday for a weekly dose or the date for a monthly one; nothing for daily. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayPicker(
    frequency: Frequency,
    dayOfWeek: Int,
    dayOfMonth: Int,
    onChange: (Frequency, Int, Int) -> Unit,
) {
    when (frequency) {
        Frequency.DAILY -> Unit
        Frequency.WEEKLY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Saturday first, as the Bangladeshi week starts.
            listOf(6, 7, 1, 2, 3, 4, 5).forEach { day ->
                FilterChip(
                    selected = dayOfWeek == day,
                    onClick = { onChange(frequency, day, dayOfMonth) },
                    label = { Text(Bn.weekday(day)) },
                )
            }
        }
        Frequency.MONTHLY -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.day_of_month))
            IconButton(onClick = { onChange(frequency, dayOfWeek, if (dayOfMonth <= 1) 31 else dayOfMonth - 1) }) {
                Icon(Icons.Filled.Remove, contentDescription = null)
            }
            Text(Bn.num(dayOfMonth), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onChange(frequency, dayOfWeek, if (dayOfMonth >= 31) 1 else dayOfMonth + 1) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    }
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
    )
}

@Composable
fun ConfirmDialog(
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A tappable warning card used for the permission and setup prompts. */
@Composable
fun ActionBanner(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                Text(actionLabel)
            }
        }
    }
}
