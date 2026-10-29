package com.medremind.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medremind.app.alarm.Notifications
import com.medremind.app.cloud.CloudPrefs
import com.medremind.app.cloud.PatientSync
import com.medremind.app.ui.FamilyScreen
import com.medremind.app.ui.FamilyViewModel
import com.medremind.app.ui.HistoryScreen
import com.medremind.app.ui.MedViewModel
import com.medremind.app.ui.ScanReviewScreen
import com.medremind.app.ui.ScanUiState
import com.medremind.app.ui.ScanViewModel
import com.medremind.app.ui.ScheduleScreen
import com.medremind.app.ui.SettingsScreen
import com.medremind.app.ui.TodayScreen
import com.medremind.app.ui.theme.MedRemindTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MedViewModel by viewModels()
    private val scanViewModel: ScanViewModel by viewModels()
    private val familyViewModel: FamilyViewModel by viewModels()

    /** Set when a family notification opened the app. */
    private var openFamily by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openFamily = intent.getBooleanExtra(Notifications.EXTRA_OPEN_FAMILY, false)
        setContent {
            MedRemindTheme {
                AppScaffold(
                    viewModel = viewModel,
                    scanViewModel = scanViewModel,
                    familyViewModel = familyViewModel,
                    openFamily = openFamily,
                    onFamilyOpened = { openFamily = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_OPEN_FAMILY, false)) openFamily = true
    }

    override fun onResume() {
        super.onResume()
        // Roll the date, backfill missed doses, and make sure every alarm is still armed.
        viewModel.refresh()
        // Opening the app is a good moment to report in and pick up the family's changes.
        PatientSync.request(this)
    }
}

private enum class Tab(val titleRes: Int) {
    TODAY(R.string.tab_today),
    SCHEDULE(R.string.tab_schedule),
    HISTORY(R.string.tab_history),
    FAMILY(R.string.tab_family),
    SETTINGS(R.string.tab_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    viewModel: MedViewModel,
    scanViewModel: ScanViewModel,
    familyViewModel: FamilyViewModel,
    openFamily: Boolean,
    onFamilyOpened: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    LaunchedEffect(openFamily) {
        if (openFamily) {
            tab = Tab.FAMILY
            onFamilyOpened()
        }
    }

    val context = LocalContext.current
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    var pendingSource by remember { mutableStateOf<ScanSource?>(null) }
    // Saveable: on a low-memory phone the camera app can push ours out of memory while it is open.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scanViewModel.scan(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraPath?.let(::File) ?: return@rememberLauncherForActivityResult
        if (saved) scanViewModel.scan(Uri.fromFile(file), deleteAfter = file) else file.delete()
        cameraPath = null
    }

    fun launch(source: ScanSource) {
        when (source) {
            ScanSource.GALLERY -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            ScanSource.CAMERA -> {
                val file = File(context.cacheDir, "scans").apply { mkdirs() }.resolve("prescription.jpg")
                cameraPath = file.absolutePath
                runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                    .onFailure { cameraPath = null }
            }
        }
    }

    fun start(source: ScanSource) {
        if (CloudPrefs(context).scanConsent) launch(source) else pendingSource = source
    }

    if (scanState != ScanUiState.Idle) {
        ScanReviewScreen(
            state = scanState,
            vm = scanViewModel,
            onDone = {
                scanViewModel.close()
                tab = Tab.TODAY
            },
        )
        return
    }

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
                                    Tab.FAMILY -> Icons.Filled.FamilyRestroom
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
            Tab.SCHEDULE -> ScheduleScreen(
                vm = viewModel,
                onScanCamera = { start(ScanSource.CAMERA) },
                onScanGallery = { start(ScanSource.GALLERY) },
                modifier = contentModifier,
            )
            Tab.HISTORY -> HistoryScreen(viewModel, contentModifier)
            Tab.FAMILY -> FamilyScreen(familyViewModel, contentModifier)
            Tab.SETTINGS -> SettingsScreen(viewModel, contentModifier)
        }
    }

    pendingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingSource = null },
            title = { Text(stringResource(R.string.scan_consent_title)) },
            text = { Text(stringResource(R.string.scan_consent_body)) },
            confirmButton = {
                TextButton(onClick = {
                    CloudPrefs(context).scanConsent = true
                    pendingSource = null
                    launch(source)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSource = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private enum class ScanSource { CAMERA, GALLERY }
