package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.controller.PermissionHealthChecker
import com.example.ui.screens.ArchitectureScreen
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CheetahAssistantTheme
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahSlate
import com.example.ui.theme.CheetahTextSecondary
import com.example.ui.viewmodel.AssistantViewModel

private const val ROUTE_CONSOLE = "console"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ARCHITECTURE = "architecture"

private data class NavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

private val CHEETAH_DESTINATIONS = listOf(
    NavDestination(ROUTE_CONSOLE, "القيادة", Icons.Default.GraphicEq, "nav_console"),
    NavDestination(ROUTE_SETTINGS, "الإعدادات", Icons.Default.Settings, "nav_settings"),
    NavDestination(ROUTE_ARCHITECTURE, "الهندسة", Icons.Default.Architecture, "nav_architecture")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CheetahAssistantTheme {
                CheetahAppRoot()
            }
        }
    }
}

@Composable
fun CheetahAppRoot(viewModel: AssistantViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_CONSOLE

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val enrolledSamples by viewModel.enrolledSamples.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionHealth.collectAsStateWithLifecycle()
    val ttsStatus by viewModel.ttsStatus.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val liveMicLevel by viewModel.liveMicLevel.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val traces by viewModel.traces.collectAsStateWithLifecycle()
    val isRecordingEnrollment by viewModel.isRecordingEnrollment.collectAsStateWithLifecycle()
    val enrollmentAmplitude by viewModel.enrollmentAmplitude.collectAsStateWithLifecycle()
    val enrollmentFeedbackAr by viewModel.enrollmentFeedbackAr.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissions() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = CheetahObsidian,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = CheetahSlate) {
                        CHEETAH_DESTINATIONS.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(ROUTE_CONSOLE) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CheetahObsidian,
                                    selectedTextColor = CheetahCyan,
                                    indicatorColor = CheetahCyan,
                                    unselectedIconColor = CheetahTextSecondary,
                                    unselectedTextColor = CheetahTextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = CheetahSlate) {
                        CHEETAH_DESTINATIONS.forEach { dest ->
                            NavigationRailItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(ROUTE_CONSOLE) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CheetahObsidian,
                                    selectedTextColor = CheetahCyan,
                                    indicatorColor = CheetahCyan,
                                    unselectedIconColor = CheetahTextSecondary,
                                    unselectedTextColor = CheetahTextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_CONSOLE
                    ) {
                        composable(ROUTE_CONSOLE) {
                            ConsoleScreen(
                                settings = settings,
                                permissions = permissions,
                                serviceRunning = serviceRunning,
                                liveMicLevel = liveMicLevel,
                                pipelineState = pipelineState,
                                partialTranscript = partialTranscript,
                                traces = traces,
                                onStartVoiceCommand = { viewModel.startVoiceCommand() },
                                onSubmitTextCommand = { viewModel.submitTextCommand(it) },
                                onToggleContinuousListening = { viewModel.toggleContinuousListening(it) },
                                onToggleMute = { viewModel.toggleMute() },
                                onRequestMicPermission = {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onOpenSettingsTab = {
                                    navController.navigate(ROUTE_SETTINGS) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                settings = settings,
                                rules = rules,
                                enrolledSamples = enrolledSamples,
                                permissions = permissions,
                                ttsStatus = ttsStatus,
                                isRecordingEnrollment = isRecordingEnrollment,
                                enrollmentAmplitude = enrollmentAmplitude,
                                enrollmentFeedbackAr = enrollmentFeedbackAr,
                                onLaunchDefaultAssistantPicker = {
                                    roleLauncher.launch(
                                        PermissionHealthChecker.buildDefaultAssistantIntent(context)
                                    )
                                },
                                onOpenVoiceInputSettings = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildVoiceInputSettingsIntent()
                                        )
                                    }
                                },
                                onSaveTypedWakePhrase = { viewModel.saveTypedWakePhrase(it) },
                                onRecordVoiceSample = { viewModel.recordVoiceWakeSample(it) },
                                onClearVoiceSamples = { viewModel.clearVoiceSamples() },
                                onUpdateWakeSensitivity = { viewModel.updateWakeSensitivity(it) },
                                onUpdateVoiceMatch = { en, thr -> viewModel.updateVoiceMatch(en, thr) },
                                onUpdatePersonaTone = { viewModel.updatePersonaTone(it) },
                                onUpdateTtsTuning = { r, p -> viewModel.updateTtsTuning(r, p) },
                                onTestEgyptianTts = { viewModel.testEgyptianTts() },
                                onToggleRule = { id, en -> viewModel.toggleRule(id, en) },
                                onRequestMicPermission = {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onOpenAccessibilitySettings = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildAccessibilitySettingsIntent()
                                        )
                                    }
                                },
                                onOpenOverlaySettings = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildOverlayPermissionIntent(context)
                                        )
                                    }
                                },
                                onRequestBatteryExemption = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildBatteryOptimizationIntent(context)
                                        )
                                    }
                                },
                                onRefreshPermissions = { viewModel.refreshPermissions() }
                            )
                        }

                        composable(ROUTE_ARCHITECTURE) {
                            ArchitectureScreen(
                                isOnDeviceSttSupported = viewModel.isOnDeviceSttSupported,
                                ttsStatus = ttsStatus,
                                enrolledTemplatesCount = enrolledSamples.size
                            )
                        }
                    }
                }
            }
        }
    }
}
