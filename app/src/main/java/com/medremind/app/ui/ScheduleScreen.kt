package com.medremind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medremind.app.R
import com.medremind.app.data.DoseGroup
import com.medremind.app.data.DoseItem

@Composable
fun ScheduleScreen(vm: MedViewModel, modifier: Modifier = Modifier) {
    val groups by vm.groups.collectAsStateWithLifecycle()

    var timeFor by remember { mutableStateOf<DoseGroup?>(null) }
    var addMedicineTo by remember { mutableStateOf<Long?>(null) }
    var editMedicine by remember { mutableStateOf<DoseItem?>(null) }
    var deleteMedicine by remember { mutableStateOf<DoseItem?>(null) }
    var deleteSlot by remember { mutableStateOf<DoseGroup?>(null) }
    var addingSlot by remember { mutableStateOf(false) }
    var newSlotLabel by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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

                    entry.sortedItems.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.medicineName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = stringResource(foodRuleLabel(item.foodRule)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { editMedicine = item }) {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                            }
                            IconButton(onClick = { deleteMedicine = item }) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { addMedicineTo = group.id },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
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
            onConfirm = { name, rule ->
                vm.addMedicine(groupId, name, rule)
                addMedicineTo = null
            },
        )
    }

    editMedicine?.let { item ->
        MedicineDialog(
            title = item.medicineName,
            initialName = item.medicineName,
            initialRule = item.foodRule,
            onDismiss = { editMedicine = null },
            onConfirm = { name, rule ->
                vm.updateMedicine(item.copy(medicineName = name, foodRule = rule))
                editMedicine = null
            },
        )
    }

    deleteMedicine?.let { item ->
        ConfirmDialog(
            message = stringResource(R.string.delete_medicine_confirm, item.medicineName),
            onDismiss = { deleteMedicine = null },
            onConfirm = {
                vm.deleteMedicine(item)
                deleteMedicine = null
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
