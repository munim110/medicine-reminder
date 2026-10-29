package com.medremind.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medremind.app.R
import com.medremind.app.data.DoseGroup
import com.medremind.app.data.MedicineWithItems
import com.medremind.app.data.SlotMedicine
import java.time.LocalDate

@Composable
fun ScheduleScreen(
    vm: MedViewModel,
    onScanCamera: () -> Unit,
    onScanGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val medicines by vm.medicines.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var timeFor by remember { mutableStateOf<DoseGroup?>(null) }
    var addMedicineTo by remember { mutableStateOf<Long?>(null) }
    var editMedicine by remember { mutableStateOf<SlotMedicine?>(null) }
    var removeMedicine by remember { mutableStateOf<SlotMedicine?>(null) }
    var deleteSlot by remember { mutableStateOf<DoseGroup?>(null) }
    var addingSlot by remember { mutableStateOf(false) }
    var newSlotLabel by remember { mutableStateOf<String?>(null) }
    var bought by remember { mutableStateOf<MedicineWithItems?>(null) }
    var stockFor by remember { mutableStateOf<MedicineWithItems?>(null) }
    var courseOver by remember { mutableStateOf<MedicineWithItems?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ScanEntryCard(onCamera = onScanCamera, onGallery = onScanGallery) }

        items(groups, key = { it.group.id }) { entry ->
            val group = entry.group
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(group.label, style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = { timeFor = group },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = if (group.isTimeSet) {
                                        Bn.time(group.hour!!, group.minute!!)
                                    } else {
                                        stringResource(R.string.time_not_set)
                                    },
                                )
                            }
                        }
                        Switch(
                            checked = group.enabled,
                            onCheckedChange = { vm.setEnabled(group.id, it) },
                        )
                        IconButton(onClick = { deleteSlot = group }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    entry.sortedItems.forEach { slotMedicine ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(slotMedicine.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = doseSummary(
                                        slotMedicine.item,
                                        stringResource(foodRuleLabel(slotMedicine.item.foodRule)),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { editMedicine = slotMedicine }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                            }
                            IconButton(onClick = { removeMedicine = slotMedicine }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { addMedicineTo = group.id },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(
                            text = stringResource(R.string.add_medicine),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { addingSlot = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.add_slot),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (medicines.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.medicines_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = stringResource(R.string.medicines_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (medicines.any { it.isLowOnStock }) {
                        IconButton(onClick = { shareShoppingList(context, medicines) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_shopping_list))
                        }
                    }
                }
            }
            items(medicines, key = { "medicine-${it.medicine.id}" }) { medicine ->
                MedicineStockCard(
                    medicine = medicine,
                    onBought = { bought = medicine },
                    onSetStock = { stockFor = medicine },
                    onCourseOver = { courseOver = medicine },
                )
            }
        }
    }

    timeFor?.let { group ->
        TimePickerDialog(
            initialHour = group.hour ?: 8,
            initialMinute = group.minute ?: 0,
            onDismiss = { timeFor = null },
            onConfirm = { hour, minute ->
                vm.setTime(group.id, hour, minute)
                timeFor = null
            },
        )
    }

    addMedicineTo?.let { groupId ->
        MedicineDialog(
            title = stringResource(R.string.add_medicine),
            onDismiss = { addMedicineTo = null },
            onConfirm = { input ->
                vm.addMedicine(groupId, input)
                addMedicineTo = null
            },
        )
    }

    editMedicine?.let { entry ->
        val today = LocalDate.now()
        MedicineDialog(
            title = entry.name,
            initial = MedicineInput(
                name = entry.name,
                quantity = entry.item.quantity,
                foodRule = entry.item.foodRule,
                frequency = entry.item.frequency,
                dayOfWeek = entry.item.dayOfWeek ?: today.dayOfWeek.value,
                dayOfMonth = entry.item.dayOfMonth ?: today.dayOfMonth,
                genericName = entry.medicine.genericName,
            ),
            showRenameNote = true,
            onDismiss = { editMedicine = null },
            onConfirm = { input ->
                vm.updateMedicine(entry, input)
                editMedicine = null
            },
        )
    }

    removeMedicine?.let { entry ->
        ConfirmDialog(
            message = stringResource(R.string.delete_medicine_confirm, entry.name),
            onDismiss = { removeMedicine = null },
            onConfirm = {
                vm.removeFromSlot(entry.item)
                removeMedicine = null
            },
        )
    }

    deleteSlot?.let { group ->
        ConfirmDialog(
            message = stringResource(R.string.delete_slot_confirm),
            onDismiss = { deleteSlot = null },
            onConfirm = {
                vm.deleteSlot(group)
                deleteSlot = null
            },
        )
    }

    courseOver?.let { medicine ->
        ConfirmDialog(
            message = stringResource(R.string.course_over_confirm, medicine.medicine.name),
            onDismiss = { courseOver = null },
            onConfirm = {
                vm.deleteMedicine(medicine.medicine.id)
                courseOver = null
            },
        )
    }

    bought?.let { medicine ->
        NumberPromptDialog(
            title = stringResource(R.string.bought_title, medicine.medicine.name),
            label = stringResource(R.string.bought_label),
            onDismiss = { bought = null },
            onConfirm = { amount ->
                if (amount != null && amount > 0) vm.addStock(medicine.medicine.id, amount)
                bought = null
            },
        )
    }

    stockFor?.let { medicine ->
        NumberPromptDialog(
            title = stringResource(R.string.stock_title, medicine.medicine.name),
            label = stringResource(R.string.stock_label),
            initial = medicine.medicine.stock,
            allowEmpty = true,
            onDismiss = { stockFor = null },
            onConfirm = { stock ->
                vm.setStock(medicine.medicine.id, stock)
                stockFor = null
            },
        )
    }

    if (addingSlot) {
        TextPromptDialog(
            title = stringResource(R.string.add_slot),
            label = stringResource(R.string.slot_label),
            onDismiss = { addingSlot = false },
            onConfirm = { label ->
                newSlotLabel = label
                addingSlot = false
            },
        )
    }

    newSlotLabel?.let { label ->
        TimePickerDialog(
            initialHour = 8,
            initialMinute = 0,
            onDismiss = { newSlotLabel = null },
            onConfirm = { hour, minute ->
                vm.addSlot(label, hour, minute)
                newSlotLabel = null
            },
        )
    }
}

@Composable
private fun ScanEntryCard(onCamera: () -> Unit, onGallery: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.scan_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.scan_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = onCamera) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Text(stringResource(R.string.scan_camera), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = onGallery) {
                    Icon(Icons.Filled.Photo, contentDescription = null)
                    Text(stringResource(R.string.scan_gallery), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun MedicineStockCard(
    medicine: MedicineWithItems,
    onBought: () -> Unit,
    onSetStock: () -> Unit,
    onCourseOver: () -> Unit,
) {
    val m = medicine.medicine
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (medicine.isLowOnStock) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium)
            m.genericName?.let {
                Text(
                    text = stringResource(R.string.generic_name, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (medicine.items.isEmpty()) {
                Text(stringResource(R.string.as_needed_medicine), style = MaterialTheme.typography.bodyMedium)
            }
            m.instructions?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                text = stockLine(medicine),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBought) { Text(stringResource(R.string.bought)) }
                TextButton(onClick = onSetStock) { Text(stringResource(R.string.set_stock)) }
                TextButton(onClick = onCourseOver) { Text(stringResource(R.string.course_over)) }
            }
        }
    }
}

@Composable
private fun stockLine(medicine: MedicineWithItems): String {
    val stock = medicine.medicine.stock ?: return stringResource(R.string.stock_not_tracked)
    val days = medicine.daysLeft
    return if (days == null) {
        stringResource(R.string.stock_units, Bn.qty(stock))
    } else {
        stringResource(R.string.stock_units_days, Bn.qty(stock), Bn.num(days))
    }
}

/** Sends a plain-text list of low medicines to WhatsApp, SMS or whatever the family uses. */
internal fun shareShoppingList(context: Context, medicines: List<MedicineWithItems>) {
    val lines = medicines.filter { it.isLowOnStock }.joinToString("\n") { m ->
        context.getString(R.string.shopping_line, m.medicine.name, Bn.num(m.daysLeft ?: 0))
    }
    val text = context.getString(R.string.shopping_header) + "\n" + lines
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Accepts Bengali or Latin digits and a decimal point; empty means "no number" when allowed. */
@Composable
fun NumberPromptDialog(
    title: String,
    label: String,
    initial: Double? = null,
    allowEmpty: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.let { Bn.qty(it) }.orEmpty()) }
    val value = parseNumber(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value != null || (allowEmpty && text.isBlank()),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (allowEmpty) {
                    Text(
                        text = stringResource(R.string.stock_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** `৩০`, `30`, `১½`, `2.5` → numbers; anything else → null. */
fun parseNumber(text: String): Double? {
    val latin = text.trim()
        .map { ch -> if (ch in '০'..'৯') '0' + (ch - '০') else ch }
        .joinToString("")
    if (latin.isEmpty()) return null
    val half = latin.endsWith("½")
    val base = latin.removeSuffix("½")
    val number = if (base.isEmpty()) 0.0 else base.toDoubleOrNull() ?: return null
    return (number + if (half) 0.5 else 0.0).takeIf { it >= 0 && it < 100_000 }
}
