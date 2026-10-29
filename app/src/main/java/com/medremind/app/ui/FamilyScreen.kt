package com.medremind.app.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medremind.app.R
import com.medremind.app.cloud.Guardian
import com.medremind.app.cloud.LinkedPatient
import com.medremind.app.cloud.PairingCode
import com.medremind.app.cloud.PatientView
import com.medremind.app.cloud.RemoteLog
import com.medremind.app.cloud.RemoteMedicine
import com.medremind.app.cloud.RemoteSlot
import com.medremind.app.data.DoseItem
import com.medremind.app.data.DoseStatus
import com.medremind.app.data.Quantities
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * পরিবার: the patient shares with family here, and a guardian follows the people they look after.
 * One app for both, since the same phone is often both.
 */
@Composable
fun FamilyScreen(vm: FamilyViewModel, modifier: Modifier = Modifier) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshPairing() }

    val patient = selected
    if (patient != null) {
        BackHandler { vm.select(null) }
        PatientDashboard(vm, patient, modifier)
        return
    }

    val busy by vm.busy.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { SharingCard(vm) }
        item { GuardianCard(vm) }
    }
}

// ---------------------------------------------------------------- patient side

@Composable
private fun SharingCard(vm: FamilyViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val guardians by vm.guardians.collectAsStateWithLifecycle()
    var askName by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<com.medremind.app.cloud.GuardianInfo?>(null) }
    var stopping by remember { mutableStateOf(false) }

    FamilyCard(title = stringResource(R.string.family_my_guardians)) {
        if (!sharing) {
            Text(stringResource(R.string.family_share_desc), style = MaterialTheme.typography.bodyMedium)
        }

        pairing?.let { p ->
            Text(
                text = stringResource(R.string.family_code_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = PairingCode.format(p.code),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            val until = Instant.ofEpochMilli(p.expiresAt).atZone(ZoneId.systemDefault())
            Text(
                text = stringResource(R.string.family_code_valid_until, Bn.time(until.hour, until.minute)),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                val text = resources.getString(R.string.family_code_share, PairingCode.format(p.code))
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(R.string.family_code_send), modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (guardians.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            guardians.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name.ifBlank { g.email.orEmpty() }, style = MaterialTheme.typography.bodyLarge)
                        if (g.name.isNotBlank() && g.email != null) {
                            Text(g.email, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { removing = g }) { Text(stringResource(R.string.remove)) }
                }
            }
            Text(
                text = stringResource(R.string.family_guardians_see),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { askName = true }) {
                Text(stringResource(if (guardians.isEmpty()) R.string.family_add_guardian else R.string.family_add_another))
            }
            if (sharing) {
                OutlinedButton(onClick = { stopping = true }) { Text(stringResource(R.string.family_stop)) }
            }
        }
    }

    if (askName) {
        NamePromptDialog(
            title = stringResource(R.string.family_name_title),
            label = stringResource(R.string.family_name_label),
            initial = vm.patientName.orEmpty(),
            onDismiss = { askName = false },
            onConfirm = {
                askName = false
                vm.createCode(it)
            },
        )
    }
    removing?.let { g ->
        ConfirmDialog(
            message = stringResource(R.string.family_remove_confirm, g.name.ifBlank { g.email.orEmpty() }),
            onDismiss = { removing = null },
            onConfirm = {
                vm.removeGuardian(g.uid)
                removing = null
            },
        )
    }
    if (stopping) {
        ConfirmDialog(
            message = stringResource(R.string.family_stop_confirm),
            onDismiss = { stopping = false },
            onConfirm = {
                vm.stopSharing()
                stopping = false
            },
        )
    }
}

// ---------------------------------------------------------------- guardian side

@Composable
private fun GuardianCard(vm: FamilyViewModel) {
    val context = LocalContext.current
    val isGoogle by vm.isGoogle.collectAsStateWithLifecycle()
    val linked by vm.linked.collectAsStateWithLifecycle()
    var enteringCode by remember { mutableStateOf(false) }

    FamilyCard(title = stringResource(R.string.family_i_look_after)) {
        if (!isGoogle) {
            Text(stringResource(R.string.family_guardian_desc), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { (context as? Activity)?.let { vm.signIn(it) } },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.google_sign_in)) }
            return@FamilyCard
        }

        vm.accountName?.let {
            Text(
                text = stringResource(R.string.family_signed_in_as, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        linked.forEach { p ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { vm.select(p) },
            ) {
                Text(
                    text = p.name.ifBlank { stringResource(R.string.family_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (linked.isEmpty()) {
            Text(stringResource(R.string.family_none_linked), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { enteringCode = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.family_enter_code))
        }
    }

    if (enteringCode) {
        NamePromptDialog(
            title = stringResource(R.string.family_enter_code),
            label = stringResource(R.string.family_code_label),
            onDismiss = { enteringCode = false },
            onConfirm = { code -> vm.link(code) { enteringCode = false } },
        )
    }
}

// ---------------------------------------------------------------- one patient

@Composable
private fun PatientDashboard(vm: FamilyViewModel, linked: LinkedPatient, modifier: Modifier) {
    val view by vm.patient.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val pending by vm.pendingEdits.collectAsStateWithLifecycle()
    var timeFor by remember { mutableStateOf<RemoteSlot?>(null) }
    var addTo by remember { mutableStateOf<RemoteSlot?>(null) }
    var boughtFor by remember { mutableStateOf<RemoteMedicine?>(null) }
    var dropping by remember { mutableStateOf<RemoteMedicine?>(null) }
    var unlinking by remember { mutableStateOf(false) }

    val name = view?.name?.ifBlank { null } ?: linked.name

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = { vm.select(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(name, style = MaterialTheme.typography.titleLarge)
            }
        }

        val v = view
        if (v == null) {
            item {
                Text(
                    text = stringResource(R.string.family_no_data),
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item { StatusCard(v, pending) }
            item { TodayCard(v, history) }
            item { AdherenceCard(history) }
            item {
                FamilyCard(title = stringResource(R.string.family_schedule)) {
                    v.slots.forEach { slot ->
                        SlotRow(slot, onTime = { timeFor = slot }, onAdd = { addTo = slot })
                    }
                }
            }
            if (v.medicines.isNotEmpty()) {
                item {
                    FamilyCard(title = stringResource(R.string.medicines_title)) {
                        v.medicines.forEach { m ->
                            RemoteMedicineRow(m, onBought = { boughtFor = m }, onDrop = { dropping = m })
                        }
                    }
                }
            }
        }
        item { HistoryCard(history) }
        item {
            TextButton(onClick = { unlinking = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.family_unlink), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    timeFor?.let { slot ->
        TimePickerDialog(
            initialHour = slot.hour ?: 8,
            initialMinute = slot.minute ?: 0,
            onDismiss = { timeFor = null },
            onConfirm = { hour, minute ->
                vm.sendEdit(mapOf("type" to "set_time", "slotId" to slot.id, "hour" to hour, "minute" to minute))
                timeFor = null
            },
        )
    }
    addTo?.let { slot ->
        MedicineDialog(
            title = stringResource(R.string.family_add_to, slot.label),
            onDismiss = { addTo = null },
            onConfirm = { input ->
                vm.sendEdit(
                    mapOf(
                        "type" to "add_medicine",
                        "slotId" to slot.id,
                        "name" to input.name,
                        "genericName" to input.genericName,
                        "quantity" to input.quantity,
                        "foodRule" to input.foodRule.name,
                        "frequency" to input.frequency.name,
                        "dayOfWeek" to input.dayOfWeek,
                        "dayOfMonth" to input.dayOfMonth,
                    ),
                )
                addTo = null
            },
        )
    }
    boughtFor?.let { m ->
        NumberPromptDialog(
            title = stringResource(R.string.bought_title, m.name),
            label = stringResource(R.string.bought_label),
            onDismiss = { boughtFor = null },
            onConfirm = { amount ->
                if (amount != null && amount > 0) {
                    vm.sendEdit(mapOf("type" to "add_stock", "medicineId" to m.id, "amount" to amount))
                }
                boughtFor = null
            },
        )
    }
    dropping?.let { m ->
        ConfirmDialog(
            message = stringResource(R.string.course_over_confirm, m.name),
            onDismiss = { dropping = null },
            onConfirm = {
                vm.sendEdit(mapOf("type" to "delete_medicine", "medicineId" to m.id))
                dropping = null
            },
        )
    }
    if (unlinking) {
        ConfirmDialog(
            message = stringResource(R.string.family_unlink_confirm, name),
            onDismiss = { unlinking = false },
            onConfirm = {
                vm.unlink(linked)
                unlinking = false
            },
        )
    }
}

@Composable
private fun StatusCard(view: PatientView, pending: Int) {
    val lastSeen = view.lastSeen?.toDate()?.time
    val quiet = lastSeen == null || System.currentTimeMillis() - lastSeen > Guardian.QUIET_MILLIS
    val problems = buildList {
        if (!view.health.notifications) add(R.string.health_notifications)
        if (!view.health.exactAlarms) add(R.string.health_exact)
        if (!view.health.fullScreen) add(R.string.health_fullscreen)
        if (!view.health.batteryUnrestricted) add(R.string.health_battery)
        if (view.slots.any { it.hour == null && it.items.isNotEmpty() }) add(R.string.health_time_unset)
    }
    FamilyCard(
        title = stringResource(R.string.family_status),
        warning = quiet || problems.isNotEmpty(),
    ) {
        Text(
            text = if (lastSeen == null) {
                stringResource(R.string.family_never_seen)
            } else {
                stringResource(R.string.family_last_seen, Bn.ago(lastSeen))
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (quiet && lastSeen != null) {
            Text(stringResource(R.string.family_quiet_hint), style = MaterialTheme.typography.bodyMedium)
        }
        problems.forEach { Text("⚠ " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        if (problems.isNotEmpty()) {
            Text(stringResource(R.string.health_fix_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (pending > 0) {
            Text(
                text = stringResource(R.string.family_pending, Bn.num(pending)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TodayCard(view: PatientView, history: List<RemoteLog>) {
    val today = LocalDate.now()
    val byGroup = history.filter { it.date == today.toString() }.associateBy { it.groupId }
    val due = view.slots.filter { slot -> slot.enabled && slot.items.any { it.toItem().isDueOn(today) } }
    FamilyCard(title = stringResource(R.string.tab_today)) {
        if (due.isEmpty()) Text(stringResource(R.string.today_empty))
        due.forEach { slot ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = slot.label + (if (slot.hour != null && slot.minute != null) " · " + Bn.time(slot.hour, slot.minute) else ""),
                    modifier = Modifier.weight(1f),
                )
                val status = byGroup[slot.id]?.status
                Text(stringResource(statusLabel(status)), color = statusColor(status), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AdherenceCard(history: List<RemoteLog>) {
    val today = LocalDate.now()
    fun rate(days: Long): Pair<Int, Int> {
        val from = today.minusDays(days - 1).toString()
        val window = history.filter { it.date >= from }
        return window.count { it.status == DoseStatus.TAKEN } to window.size
    }
    FamilyCard(title = stringResource(R.string.family_adherence)) {
        listOf(7L, 30L).forEach { days ->
            val (taken, total) = rate(days)
            Text(
                text = if (total == 0) {
                    stringResource(R.string.family_adherence_none, Bn.num(days.toInt()))
                } else {
                    stringResource(
                        R.string.family_adherence_line,
                        Bn.num(days.toInt()),
                        Bn.num(taken * 100 / total),
                        Bn.num(taken),
                        Bn.num(total),
                    )
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val missed = history.count { it.status == DoseStatus.MISSED && it.date >= today.minusDays(6).toString() }
        if (missed > 0) {
            Text(
                text = stringResource(R.string.family_missed_week, Bn.num(missed)),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SlotRow(slot: RemoteSlot, onTime: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (slot.hour != null && slot.minute != null) Bn.time(slot.hour, slot.minute) else stringResource(R.string.time_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onTime) { Text(stringResource(R.string.family_change_time)) }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.add)) }
        }
        slot.items.forEach { item ->
            Text(
                text = "• " + Quantities.describe(item.name, item.quantity) + " — " +
                    stringResource(foodRuleLabel(item.foodRule)) +
                    (frequencyLabel(item.toItem())?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RemoteMedicineRow(m: RemoteMedicine, onBought: () -> Unit, onDrop: () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(m.name, style = MaterialTheme.typography.titleSmall, color = if (m.low) MaterialTheme.colorScheme.error else Color.Unspecified)
        m.genericName?.let { Text(stringResource(R.string.generic_name, it), style = MaterialTheme.typography.bodySmall) }
        Text(
            text = when {
                m.stock == null -> stringResource(R.string.stock_not_tracked)
                m.daysLeft == null -> stringResource(R.string.stock_units, Bn.qty(m.stock))
                else -> stringResource(R.string.stock_units_days, Bn.qty(m.stock), Bn.num(m.daysLeft))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row {
            TextButton(onClick = onBought) { Text(stringResource(R.string.bought)) }
            TextButton(onClick = onDrop) { Text(stringResource(R.string.course_over)) }
        }
    }
}

@Composable
private fun HistoryCard(history: List<RemoteLog>) {
    FamilyCard(title = stringResource(R.string.tab_history)) {
        if (history.isEmpty()) Text(stringResource(R.string.history_empty))
        history.groupBy { it.date }.forEach { (date, logs) ->
            Text(
                text = Bn.relativeDate(runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            logs.sortedBy { it.scheduledAt }.forEach { log ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(log.label, style = MaterialTheme.typography.bodyMedium)
                        if (log.medicines.isNotBlank()) {
                            Text(log.medicines, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(stringResource(statusLabel(log.status)), color = statusColor(log.status))
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: DoseStatus?): Color = when (status) {
    DoseStatus.TAKEN -> Color(0xFF1B5E20)
    DoseStatus.MISSED -> MaterialTheme.colorScheme.error
    DoseStatus.SKIPPED -> MaterialTheme.colorScheme.outline
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun FamilyCard(title: String, warning: Boolean = false, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/** A text prompt that can start pre-filled, unlike [TextPromptDialog]. */
@Composable
private fun NamePromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
    )
}

private fun com.medremind.app.cloud.RemoteItem.toItem() = DoseItem(
    groupId = 0,
    medicineId = medicineId,
    quantity = quantity,
    foodRule = foodRule,
    frequency = frequency,
    dayOfWeek = dayOfWeek,
    dayOfMonth = dayOfMonth,
)
