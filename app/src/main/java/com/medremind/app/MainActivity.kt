package com.aigeolab.medremind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aigeolab.medremind.ui.HistoryScreen
import com.aigeolab.medremind.ui.MedViewModel
import com.aigeolab.medremind.ui.ScheduleScreen
import com.aigeolab.medremind.ui.SettingsScreen
import com.aigeolab.medremind.ui.TodayScreen
import com.aigeolab.medremind.ui.theme.MedRemindTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MedRemindTheme {
                AppScaffold(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Roll the date, backfill missed doses, and make sure every alarm is still armed.
        viewModel.refresh()
    }
}

private enum class Tab(val titleRes: Int) {
    TODAY(R.string.tab_today),
    SCHEDULE(R.string.tab_schedule),
    HISTORY(R.string.tab_history),
    SETTINGS(R.string.tab_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(viewModel: MedViewModel) {
    var tab by remember { mutableStateOf(Tab.TODAY) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                imageVector = when (entry) {
                                    Tab.TODAY -> Icons.Filled.Today
                                    Tab.SCHEDULE -> Icons.Filled.CalendarMonth
                                    Tab.HISTORY -> Icons.Filled.History
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(entry.titleRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (tab) {
            Tab.TODAY -> TodayScreen(viewModel, contentModifier)
            Tab.SCHEDULE -> ScheduleScreen(viewModel, contentModifier)
            Tab.HISTORY -> HistoryScreen(viewModel, contentModifier)
            Tab.SETTINGS -> SettingsScreen(viewModel, contentModifier)
        }
    }
}
