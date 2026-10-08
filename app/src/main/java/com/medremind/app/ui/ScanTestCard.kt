package com.medremind.app.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medremind.app.R
import com.medremind.app.cloud.ScanClient
import com.medremind.app.cloud.ScanError
import com.medremind.app.cloud.ScanOutcome
import com.medremind.app.cloud.ScannedMedicine
import kotlinx.coroutines.launch

/** Debug builds only: proves photo → Worker → AI → app works before the real review screen exists. */
fun isDebugBuild(context: android.content.Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

@Composable
fun ScanTestCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        outcome = null
        scope.launch {
            outcome = ScanClient.scan(context, uri)
            busy = false
        }
    }

    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.scan_test_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.scan_test_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                enabled = !busy,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.scan_test_pick)) }

            if (busy) CircularProgressIndicator(Modifier.padding(top = 12.dp))

            when (val result = outcome) {
                null -> Unit
                is ScanOutcome.Failure -> Text(
                    text = stringResource(errorLabel(result.reason)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                is ScanOutcome.Success -> Column(Modifier.padding(top = 12.dp)) {
                    if (!result.result.isPrescription) {
                        Text(stringResource(R.string.scan_not_prescription), color = MaterialTheme.colorScheme.error)
                    }
                    result.result.medicines.forEach { MedicineLine(it) }
                    result.result.notes?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    Text(
                        text = stringResource(R.string.scan_left_today, Bn.num(result.result.scansLeftToday)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MedicineLine(m: ScannedMedicine) {
    // Parsed slots (সকাল+দুপুর+সন্ধ্যা+রাত) next to what was actually written, so a misreading shows.
    val slots = listOf(m.morning, m.noon, m.evening, m.night)
        .joinToString("+") { d -> d?.let { Bn.digits(if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) } ?: "?" }
    val details = buildList {
        add(if (m.doseText != null) "$slots (${m.doseText})" else slots)
        when (m.frequency) {
            "weekly" -> add(stringResource(R.string.scan_weekly))
            "monthly" -> add(stringResource(R.string.scan_monthly))
            "as_needed" -> add(stringResource(R.string.scan_as_needed))
        }
        m.foodRule?.let { add(stringResource(foodRuleLabel(it))) }
        m.durationText?.let { add(it) }
        if (m.confidence != "high") add(stringResource(R.string.scan_check_this))
    }.joinToString(" · ")

    Column(Modifier.padding(vertical = 4.dp)) {
        Text(listOfNotNull(m.name, m.strength).joinToString(" "), style = MaterialTheme.typography.titleSmall)
        Text(details, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun errorLabel(error: ScanError): Int = when (error) {
    ScanError.OFFLINE -> R.string.scan_error_offline
    ScanError.DAILY_LIMIT -> R.string.scan_error_limit
    ScanError.BUSY -> R.string.scan_error_busy
    ScanError.UNREADABLE_IMAGE -> R.string.scan_error_image
    ScanError.FAILED -> R.string.scan_error_failed
}
