package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.ui.screens.GgufStorageModeDialog
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CheetahAssistantTheme
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahSlate
import com.example.ui.theme.CheetahTextSecondary
import com.example.ui.viewmodel.AssistantViewModel
import com.example.voice.BootCompletedReceiver
import com.example.voice.CheetahRuntimeBus
import com.example.voice.EgyptianTtsManager

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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        extractSharedPlainText(intent)
        val resumeWakeFromBoot = intent?.getBooleanExtra(
            BootCompletedReceiver.EXTRA_RESUME_WAKE_LISTENER,
            false
        ) ?: false

        setContent {
            CheetahAssistantTheme {
                CheetahAppRoot(resumeWakeFromBoot = resumeWakeFromBoot)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedPlainText(intent)
    }

    private fun extractSharedPlainText(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            val shared = incoming.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                CheetahRuntimeBus.setIncomingSharedText(shared)
            }
        }
    }
}

@Composable
fun CheetahAppRoot(
    resumeWakeFromBoot: Boolean = false,
    viewModel: AssistantViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_CONSOLE

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val enrolledSamples by viewModel.enrolledSamples.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionHealth.collectAsStateWithLifecycle()
    val ttsStatus by viewModel.ttsStatus.collectAsStateWithLifecycle()
    val offlineSttStatus by viewModel.offlineSttStatus.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val transitionLog by viewModel.transitionLog.collectAsStateWithLifecycle()
    val localLlmStatus by viewModel.localLlmStatus.collectAsStateWithLifecycle()
    val streamingReplyText by viewModel.streamingReplyText.collectAsStateWithLifecycle()
    val isNetworkSearching by viewModel.isNetworkSearching.collectAsStateWithLifecycle()
    val memoryState by viewModel.memoryState.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val liveMicLevel by viewModel.liveMicLevel.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val traces by viewModel.traces.collectAsStateWithLifecycle()
    val isRecordingEnrollment by viewModel.isRecordingEnrollment.collectAsStateWithLifecycle()
    val enrollmentAmplitude by viewModel.enrollmentAmplitude.collectAsStateWithLifecycle()
    val enrollmentFeedbackAr by viewModel.enrollmentFeedbackAr.collectAsStateWithLifecycle()
    val pendingGgufImport by viewModel.pendingGgufImport.collectAsStateWithLifecycle()
    val incomingSharedText by viewModel.incomingSharedText.collectAsStateWithLifecycle()
    val pendingContinuationText by viewModel.pendingContinuationText.collectAsStateWithLifecycle()
    val pendingProposedAction by viewModel.pendingProposedAction.collectAsStateWithLifecycle()
    val isBenchmarkRunning by viewModel.isBenchmarkRunning.collectAsStateWithLifecycle()
    val isTestingSearch by viewModel.isTestingSearch.collectAsStateWithLifecycle()
    val searchTestResult by viewModel.searchTestResult.collectAsStateWithLifecycle()

    LaunchedEffect(resumeWakeFromBoot) {
        if (resumeWakeFromBoot) {
            viewModel.toggleContinuousListening(true)
        }
    }

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
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            viewModel.setMicFeatureEnabled(true)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            viewModel.setNotificationsFeatureEnabled(true)
        }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissions() }

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectPickedGgufUri(uri)
        }
    }

    // Bug A3 compliant permission switch handlers: never re-prompt if granted; route permanent denial to App Info
    val handleMicSwitchToggle: (Boolean) -> Unit = { wantOn ->
        if (!wantOn) {
            viewModel.setMicFeatureEnabled(false)
        } else {
            val osGranted = PermissionHealthChecker.inspect(context).hasMicPermission
            if (osGranted) {
                viewModel.setMicFeatureEnabled(true)
            } else if (PermissionHealthChecker.isPermissionPermanentlyDenied(
                    activity = activity,
                    permission = Manifest.permission.RECORD_AUDIO,
                    wasRequestedBefore = settings.micPermissionRequestedOnce
                )
            ) {
                runCatching {
                    context.startActivity(PermissionHealthChecker.buildAppDetailsSettingsIntent(context))
                }
            } else {
                viewModel.markMicPermissionRequestedOnce()
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val handleNotifSwitchToggle: (Boolean) -> Unit = { wantOn ->
        if (!wantOn) {
            viewModel.setNotificationsFeatureEnabled(false)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            viewModel.setNotificationsFeatureEnabled(true)
        } else {
            val osGranted = PermissionHealthChecker.inspect(context).hasNotificationPermission
            if (osGranted) {
                viewModel.setNotificationsFeatureEnabled(true)
            } else if (PermissionHealthChecker.isPermissionPermanentlyDenied(
                    activity = activity,
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    wasRequestedBefore = settings.notifPermissionRequestedOnce
                )
            ) {
                runCatching {
                    context.startActivity(PermissionHealthChecker.buildAppDetailsSettingsIntent(context))
                }
            } else {
                viewModel.markNotifPermissionRequestedOnce()
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    pendingGgufImport?.let { (_, meta) ->
        GgufStorageModeDialog(
            metadata = meta,
            freeInternalBytes = localLlmStatus.freeInternalBytes,
            onSelectMode = { mode -> viewModel.confirmImportGgufModel(mode) },
            onDismiss = { viewModel.cancelPendingGgufImport() }
        )
    }

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
                                streamingReplyText = streamingReplyText,
                                isNetworkSearching = isNetworkSearching,
                                localLlmStatus = localLlmStatus,
                                traces = traces,
                                incomingSharedText = incomingSharedText,
                                pendingContinuationText = pendingContinuationText,
                                pendingProposedAction = pendingProposedAction,
                                onStartVoiceCommand = { viewModel.startVoiceCommand() },
                                onSubmitTextCommand = { viewModel.submitTextCommand(it) },
                                onSubmitTextWithAttachment = { cmd, att ->
                                    viewModel.submitTextWithAttachment(cmd, att)
                                },
                                onConsumeSharedText = { viewModel.consumeIncomingSharedText() },
                                onReadClipboard = { viewModel.readClipboardText() },
                                onConfirmProposedAction = { viewModel.confirmProposedAction(it) },
                                onDismissProposedAction = { viewModel.dismissProposedAction() },
                                onContinueSpeech = { viewModel.continueSpeakingLongReply() },
                                onToggleContinuousListening = { viewModel.toggleContinuousListening(it) },
                                onToggleMute = { viewModel.toggleMute() },
                                onRequestMicPermission = { handleMicSwitchToggle(true) },
                                onCancelLlmGeneration = { viewModel.cancelLlmGeneration() },
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
                                offlineSttStatus = offlineSttStatus,
                                diagnostics = diagnostics,
                                transitionLog = transitionLog,
                                localLlmStatus = localLlmStatus,
                                memoryState = memoryState,
                                serviceRunning = serviceRunning,
                                liveMicLevel = liveMicLevel,
                                isRecordingEnrollment = isRecordingEnrollment,
                                enrollmentAmplitude = enrollmentAmplitude,
                                enrollmentFeedbackAr = enrollmentFeedbackAr,
                                isBenchmarkRunning = isBenchmarkRunning,
                                isTestingSearch = isTestingSearch,
                                searchTestResult = searchTestResult,
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
                                onUpdateIdentityAndGender = { name, aGender, vGender, vName ->
                                    viewModel.updateIdentityAndGender(name, aGender, vGender, vName)
                                },
                                onOpenSystemTtsSettings = {
                                    runCatching {
                                        context.startActivity(
                                            EgyptianTtsManager.buildTtsSystemSettingsIntent()
                                        )
                                    }
                                },
                                onSaveTypedWakePhrase = { viewModel.saveTypedWakePhrase(it) },
                                onRecordVoiceSample = { viewModel.recordVoiceWakeSample(it) },
                                onClearVoiceSamples = { viewModel.clearVoiceSamples() },
                                onUpdateWakeSensitivity = { viewModel.updateWakeSensitivity(it) },
                                onUpdateVoiceMatch = { en, thr, fb ->
                                    viewModel.updateVoiceMatch(en, thr, fb)
                                },
                                onUpdateWakeAckPhrase = { viewModel.updateWakeAckPhrase(it) },
                                onUpdatePersonaTone = { viewModel.updatePersonaTone(it) },
                                onUpdateTtsTuning = { r, p -> viewModel.updateTtsTuning(r, p) },
                                onTestEgyptianTts = { viewModel.testEgyptianTts() },
                                onToggleRule = { id, en -> viewModel.toggleRule(id, en) },
                                onToggleContinuousListening = { viewModel.toggleContinuousListening(it) },
                                onToggleMute = { viewModel.toggleMute() },
                                onToggleMicSwitch = handleMicSwitchToggle,
                                onToggleNotificationSwitch = handleNotifSwitchToggle,
                                onToggleBatterySwitch = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildBatteryOptimizationIntent(context)
                                        )
                                    }
                                },
                                onToggleAccessibilitySwitch = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildAccessibilitySettingsIntent()
                                        )
                                    }
                                },
                                onToggleOverlaySwitch = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildOverlayPermissionIntent(context)
                                        )
                                    }
                                },
                                onOpenAppSettings = {
                                    runCatching {
                                        context.startActivity(
                                            PermissionHealthChecker.buildAppDetailsSettingsIntent(context)
                                        )
                                    }
                                },
                                onRefreshPermissions = { viewModel.refreshPermissions() },
                                onPickGgufModel = { ggufPickerLauncher.launch(arrayOf("*/*")) },
                                onLoadGgufModel = { viewModel.loadGgufModel() },
                                onUnloadGgufModel = { viewModel.unloadGgufModel() },
                                onRemoveGgufModel = { viewModel.removeGgufModel() },
                                onCancelLlmGeneration = { viewModel.cancelLlmGeneration() },
                                onUpdateLlmConfig = { ctx, maxTok, temp, threads ->
                                    viewModel.updateLlmConfig(ctx, maxTok, temp, threads)
                                },
                                onUpdateAiBrainConfig = { useGen, mode, judge, evalCnt, showReason, showSrc, maxSec, prof, unloadMin ->
                                    viewModel.updateAiBrainConfig(useGen, mode, judge, evalCnt, showReason, showSrc, maxSec, prof, unloadMin)
                                },
                                onUpdateDomainLists = { trusted, untrusted ->
                                    viewModel.updateDomainLists(trusted, untrusted)
                                },
                                onRunBenchmark = { viewModel.runModelBenchmark() },
                                onSetInternetSearch = { en, url -> viewModel.setInternetSearch(en, url) },
                                onSelectSearchEngine = { mode -> viewModel.selectSearchEngine(mode) },
                                onUpdateAutoSearchOrder = { order -> viewModel.updateAutoSearchOrder(order) },
                                onTestSearxngConnection = { url -> viewModel.testSearxngConnection(url) },
                                onTestSearchEngine = { viewModel.testSearchWithSelectedEngine() },
                                onRunMicDiagnosticTest = { viewModel.runControlledMicDiagnosticTest() },
                                onMarkFalseActivation = { viewModel.markFalseActivation() },
                                onResetDiagnosticCounters = { viewModel.resetDiagnosticCounters() },
                                onClearTransitionLog = { viewModel.clearTransitionLog() },
                                onAddShortcut = { trig, cmd -> viewModel.addPersonalShortcut(trig, cmd) },
                                onDeleteShortcut = { id -> viewModel.deletePersonalShortcut(id) },
                                onDeleteMemoryEntry = { id -> viewModel.deleteMemoryEntry(id) },
                                onClearAllMemory = { viewModel.clearAllMemory() }
                            )
                        }

                        composable(ROUTE_ARCHITECTURE) {
                            ArchitectureScreen(
                                offlineSttStatus = offlineSttStatus,
                                ttsStatus = ttsStatus,
                                enrolledTemplatesCount = enrolledSamples.size,
                                internetSearchEnabled = settings.internetSearchEnabled,
                                localLlmStatus = localLlmStatus
                            )
                        }
                    }
                }
            }
        }
    }
}
