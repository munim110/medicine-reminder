package com.medremind.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.medremind.app.R
import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.alarm.AlarmState
import com.medremind.app.alarm.DoseActions
import com.medremind.app.data.DoseStatus
import com.medremind.app.data.GroupWithItems
import com.medremind.app.data.ScheduleRepository
import com.medremind.app.ui.theme.MedRemindTheme
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

/** The full-screen alarm: shows exactly which medicines are due and waits for confirmation. */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        // Back must not dismiss a live alarm — only an explicit choice should.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        val groupId = intent.getLongExtra(AlarmScheduler.EXTRA_GROUP_ID, -1L)
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
        if (groupId < 0) {
            finish()
            return
        }

        setContent {
            MedRemindTheme {
                val group by produceState<GroupWithItems?>(initialValue = null, groupId) {
                    value = ScheduleRepository.get(this@AlarmActivity).group(groupId)
                }
                val snoozeMinutes by produceState(10, groupId) {
                    value = ScheduleRepository.get(this@AlarmActivity).settings().snoozeMinutes
                }

                // Close as soon as the service falls silent, so the screen does not sit open
                // through the gap between escalation attempts.
                LaunchedEffect(groupId) {
                    AlarmState.ringingGroupId
                        .dropWhile { it != groupId }
                        .collect { if (it != groupId) finish() }
                }

                AlarmScreen(
                    group = group,
                    snoozeMinutes = snoozeMinutes,
                    onTaken = { complete(groupId, scheduledAt, DoseStatus.TAKEN) },
                    onSkip = { complete(groupId, scheduledAt, DoseStatus.SKIPPED) },
                    onSnooze = {
                        val appContext = applicationContext
                        lifecycleScope.launch {
                            runCatching { DoseActions.snooze(appContext, groupId, scheduledAt) }
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun complete(groupId: Long, scheduledAt: Long, status: DoseStatus) {
        val appContext = applicationContext
        lifecycleScope.launch {
            runCatching { DoseActions.record(appContext, groupId, scheduledAt, status) }
            finish()
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        fun intent(context: Context, groupId: Long, scheduledAt: Long): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                data = Uri.parse("medremind://alarm/$groupId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmScheduler.EXTRA_GROUP_ID, groupId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            }
    }
}

@Composable
private fun AlarmScreen(
    group: GroupWithItems?,
    snoozeMinutes: Int,
    onTaken: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.alarm_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            group?.group?.let { g ->
                val time = if (g.isTimeSet) Bn.time(g.hour!!, g.minute!!) else ""
                Text(
                    text = listOf(g.label, time).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(20.dp)) {
                    val items = group?.sortedItems.orEmpty()
                    if (items.isEmpty()) {
                        Text(
                            text = stringResource(R.string.alarm_body_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        items.forEachIndexed { index, item ->
                            if (index > 0) Spacer(Modifier.height(16.dp))
                            Text(
                                text = "${Bn.num(index + 1)}. ${item.medicineName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(foodRuleLabel(item.foodRule)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.alarm_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onTaken,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = stringResource(R.string.action_taken),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onSnooze) {
                    Text("${Bn.num(snoozeMinutes)} মিনিট পরে")
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.action_skip))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
