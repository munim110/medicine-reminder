package com.aigeolab.medremind.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aigeolab.medremind.R
import com.aigeolab.medremind.alarm.AlarmScheduler
import com.aigeolab.medremind.alarm.Notifications
import com.aigeolab.medremind.data.DoseStatus

@Composable
fun TodayScreen(vm: MedViewModel, modifier: Modifier = Modifier) {
    val rows by vm.todayRows.collectAsStateWithLifecycle()
    var pickTimeFor by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SetupBanners() }

        items(rows.filter { !it.group.group.isTimeSet }, key = { "unset-${it.group.group.id}" }) { row ->
            ActionBanner(
                title = stringResource(R.string.morning_prompt_title),
                body = stringResource(R.string.morning_prompt_body),
                actionLabel = stringResource(R.string.set_time),
                onAction = { pickTimeFor = row.group.group.id },
            )
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.today_empty),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        items(rows, key = { it.group.group.id }) { row ->
            DoseCard(
                row = row,
                onTaken = { vm.mark(row, DoseStatus.TAKEN) },
                onSkip = { vm.mark(row, DoseStatus.SKIPPED) },
                onSetTime = { pickTimeFor = row.group.group.id },
            )
        }
    }

    pickTimeFor?.let { groupId ->
        val current = rows.firstOrNull { it.group.group.id == groupId }?.group?.group
        TimePickerDialog(
            initialHour = current?.hour ?: 8,
            initialMinute = current?.minute ?: 0,
            onDismiss = { pickTimeFor = null },
            onConfirm = { hour, minute ->
                vm.setTime(groupId, hour, minute)
                pickTimeFor = null
            },
        )
    }
}

@Composable
private fun DoseCard(
    row: TodayRow,
    onTaken: () -> Unit,
    onSkip: () -> Unit,
    onSetTime: () -> Unit,
) {
    val group = row.group.group
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.status == DoseStatus.TAKEN) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = group.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (group.isTimeSet) {
                            Bn.time(group.hour!!, group.minute!!)
                        } else {
                            stringResource(R.string.time_not_set)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                StatusChip(row.status)
            }

            Spacer(Modifier.height(8.dp))

            row.group.sortedItems.forEach { item ->
                Text(
                    text = "• ${item.medicineName} — ${stringResource(foodRuleLabel(item.foodRule))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!group.isTimeSet) {
                TextButton(onClick = onSetTime) { Text(stringResource(R.string.set_time)) }
            } else if (row.status != DoseStatus.TAKEN) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTaken) { Text(stringResource(R.string.action_taken)) }
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: DoseStatus?) {
    val container = when (status) {
        DoseStatus.TAKEN -> Color(0xFF1B5E20)
        DoseStatus.MISSED -> MaterialTheme.colorScheme.error
        DoseStatus.SKIPPED -> MaterialTheme.colorScheme.outline
        null -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (status == null) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        Color.White
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(statusLabel(status))) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = content,
        ),
    )
}

/**
 * Surfaces the OS-level settings this app needs to be able to ring on time.
 * Re-checked on every resume, since the user grants them in system Settings.
 */
@Composable
private fun SetupBanners() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    val needsNotification = remember(tick) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
    }
    val needsExact = remember(tick) { !AlarmScheduler.canScheduleExact(context) }
    val needsFullScreen = remember(tick) { !Notifications.canUseFullScreen(context) }
    val needsBattery = remember(tick) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == false
    }

    Column {
        if (needsNotification) {
            ActionBanner(
                title = stringResource(R.string.perm_notification_title),
                body = stringResource(R.string.perm_notification_body),
                actionLabel = stringResource(R.string.grant),
                onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        if (needsExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActionBanner(
                title = stringResource(R.string.perm_exact_title),
                body = stringResource(R.string.perm_exact_body),
                actionLabel = stringResource(R.string.open_settings),
                onAction = {
                    context.safeStartActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${context.packageName}")),
                    )
                },
            )
        }
        if (needsFullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActionBanner(
                title = stringResource(R.string.perm_fullscreen_title),
                body = stringResource(R.string.perm_fullscreen_body),
                actionLabel = stringResource(R.string.open_settings),
                onAction = {
                    context.safeStartActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:${context.packageName}")),
                    )
                },
            )
        }
        if (needsBattery) {
            ActionBanner(
                title = stringResource(R.string.perm_battery_title),
                body = stringResource(R.string.perm_battery_body),
                actionLabel = stringResource(R.string.open_settings),
                onAction = {
                    context.safeStartActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                },
            )
        }
    }
}

private fun android.content.Context.safeStartActivity(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
