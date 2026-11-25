package com.example.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controller.DeviceActionExecutor
import com.example.controller.PermissionHealthChecker
import com.example.core.model.GuardrailVerdict
import com.example.data.ConversationMemoryStore
import com.example.data.SettingsRepository
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahAssistantTheme
import com.example.ui.theme.CheetahCrimson
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahSlate
import com.example.voice.EgyptianOfflineSttManager
import com.example.voice.EgyptianTtsManager
import com.example.voice.WakeWordDetectionService
import kotlinx.coroutines.launch

/**
 * Handles ACTION_ASSIST and ACTION_VOICE_COMMAND system assistant entry points.
 * Shares persistent ConversationMemoryStore and offline STT/TTS engines.
 */
class CheetahAssistActivity : ComponentActivity() {

    private lateinit var sttManager: EgyptianOfflineSttManager
    private lateinit var ttsManager: EgyptianTtsManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var memoryStore: ConversationMemoryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sttManager = EgyptianOfflineSttManager(this)
        ttsManager = EgyptianTtsManager(this)
        settingsRepo = SettingsRepository(this)
        memoryStore = ConversationMemoryStore(this)

        val fromWakeWord = intent.getBooleanExtra(WakeWordDetectionService.EXTRA_FROM_WAKE_WORD, false)
        val voiceMatchScore = if (intent.hasExtra(WakeWordDetectionService.EXTRA_VOICE_MATCH_SCORE)) {
            intent.getFloatExtra(WakeWordDetectionService.EXTRA_VOICE_MATCH_SCORE, 0f)
        } else null

        setContent {
            CheetahAssistantTheme {
                AssistOverlaySheet(
                    fromWakeWord = fromWakeWord,
                    voiceMatchScore = voiceMatchScore,
                    onClose = { finish() },
                    sttManager = sttManager,
                    ttsManager = ttsManager,
                    settingsRepo = settingsRepo,
                    memoryStore = memoryStore,
                    activity = this
                )
            }
        }
    }

    override fun onDestroy() {
        sttManager.stopAndRelease()
        ttsManager.release()
        super.onDestroy()
    }
}

@Composable
private fun AssistOverlaySheet(
    fromWakeWord: Boolean,
    voiceMatchScore: Float?,
    onClose: () -> Unit,
    sttManager: EgyptianOfflineSttManager,
    ttsManager: EgyptianTtsManager,
    settingsRepo: SettingsRepository,
    memoryStore: ConversationMemoryStore,
    activity: ComponentActivity
) {
    val scope = rememberCoroutineScope()
    var statusText by remember {
        mutableStateOf(
            if (fromWakeWord) "أيوة يا فندم! سمعت كلمة التنبيه وسامع أمرك..."
            else "تحت أمرك يا فندم... قول أو اكتب أمرك بالعامية المصرية"
        )
    }
    var transcript by remember { mutableStateOf("") }
    var typedInput by remember { mutableStateOf("") }
    var isBlocked by remember { mutableStateOf(false) }

    fun executeCommand(raw: String) {
        scope.launch {
            val settings = settingsRepo.currentSettings()
            val perms = PermissionHealthChecker.inspect(activity)
            val memState = memoryStore.current()
            val (_, parsedIntent) = EgyptianColloquialNluParser.parse(
                rawInput = raw,
                configuredWakePhrase = settings.wakePhrase,
                contextState = memState
            )
            val voicePassed = voiceMatchScore == null ||
                !settings.voiceMatchEnabled ||
                voiceMatchScore >= settings.voiceMatchThreshold ||
                settings.allowOwnerFallbackOnLowVoiceMatch

            when (val verdict = CommandGuardrailValidator.validate(
                intent = parsedIntent,
                settings = settings,
                permissions = perms,
                voiceMatchPassed = voicePassed
            )) {
                is GuardrailVerdict.Denied -> {
                    isBlocked = true
                    val reply = JarvisEgyptianResponder.formatGuardrailBlockReply(
                        verdict.reasonAr,
                        settings.personaTone
                    )
                    statusText = reply
                    memoryStore.recordTurn(raw, reply, parsedIntent.categoryName)
                    ttsManager.speak(reply, settings.ttsSpeechRate, settings.ttsPitch)
                }

                is GuardrailVerdict.Allowed -> {
                    isBlocked = false
                    val result = DeviceActionExecutor.execute(
                        context = activity,
                        intent = verdict.intent,
                        tone = settings.personaTone,
                        memoryState = memState
                    )
                    statusText = result.replyAr
                    memoryStore.recordTurn(
                        userText = raw,
                        assistantReply = result.replyAr,
                        intentCategory = verdict.intent.categoryName,
                        newAppContext = result.newAppContext,
                        newSearchQuery = result.newSearchQuery,
                        newOpenedApp = result.newOpenedApp
                    )
                    ttsManager.speak(result.replyAr, settings.ttsSpeechRate, settings.ttsPitch)
                }
            }
        }
    }

    fun startMic() {
        transcript = "جاري الاستماع بالعامية المصرية..."
        sttManager.startListening(
            onRmsDb = {},
            onPartialResult = { transcript = it },
            onFinalResult = { finalText ->
                transcript = finalText
                executeCommand(finalText)
            },
            onError = { transcript = it }
        )
    }

    LaunchedEffect(Unit) {
        startMic()
    }

    DisposableEffect(Unit) {
        onDispose { sttManager.stopAndRelease() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .border(
                    width = 1.dp,
                    color = if (isBlocked) CheetahCrimson.copy(alpha = 0.7f)
                    else CheetahCyan.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = CheetahSlate
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isBlocked) CheetahCrimson else CheetahCyan)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "شيتا — المساعد الشخصي",
                            style = MaterialTheme.typography.titleMedium,
                            color = CheetahCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("assist_overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق المساعد",
                            tint = Color.White
                        )
                    }
                }

                if (voiceMatchScore != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = CheetahAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Voice Match: ${(voiceMatchScore * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = CheetahAmber
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isBlocked) CheetahCrimson else Color.White
                )

                if (transcript.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = typedInput,
                        onValueChange = { typedInput = it },
                        placeholder = { Text("اكتب أمرك بالمصري...") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("assist_overlay_text_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { startMic() },
                        colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("assist_overlay_mic_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "استماع صوتي",
                            tint = Color(0xFF070B14)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (typedInput.isNotBlank()) {
                                val cmd = typedInput
                                typedInput = ""
                                executeCommand(cmd)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CheetahAmber),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("assist_overlay_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "تنفيذ الأمر",
                            tint = Color(0xFF070B14)
                        )
                    }
                }
            }
        }
    }
}
