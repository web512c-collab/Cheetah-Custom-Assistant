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
import com.example.data.ConversationMemoryStore
import com.example.data.SettingsRepository
import com.example.intelligence.AssistantCommandProcessor
import com.example.llm.LocalLlmManager
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahAssistantTheme
import com.example.ui.theme.CheetahCrimson
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahSlate
import com.example.voice.EgyptianOfflineSttManager
import com.example.voice.EgyptianTtsManager
import com.example.voice.MicOwner
import com.example.voice.MicrophoneArbitrator
import com.example.voice.WakeWordDetectionService
import kotlinx.coroutines.launch

/**
 * Handles ACTION_ASSIST and ACTION_VOICE_COMMAND system assistant entry points.
 * Uses unified AssistantCommandProcessor, MicrophoneArbitrator, and voice/gender prefs.
 */
class CheetahAssistActivity : ComponentActivity() {

    private lateinit var sttManager: EgyptianOfflineSttManager
    private lateinit var ttsManager: EgyptianTtsManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var memoryStore: ConversationMemoryStore
    private lateinit var commandProcessor: AssistantCommandProcessor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sttManager = EgyptianOfflineSttManager(this)
        ttsManager = EgyptianTtsManager(this)
        settingsRepo = SettingsRepository(this)
        memoryStore = ConversationMemoryStore(this)
        val localLlmManager = LocalLlmManager(applicationContext, settingsRepo)
        commandProcessor = AssistantCommandProcessor(applicationContext, memoryStore, localLlmManager)

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
                    commandProcessor = commandProcessor
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
    commandProcessor: AssistantCommandProcessor
) {
    val scope = rememberCoroutineScope()
    var assistantTitle by remember { mutableStateOf("شيتا — المساعد الشخصي") }
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
            assistantTitle = "${settings.assistantName} — المساعد الشخصي"
            ttsManager.applyVoicePreferences(settings.voiceGender, settings.selectedTtsVoiceName)
            val voicePassed = voiceMatchScore == null ||
                !settings.voiceMatchEnabled ||
                voiceMatchScore >= settings.voiceMatchThreshold ||
                settings.allowOwnerFallbackOnLowVoiceMatch

            val outcome = commandProcessor.process(
                rawInput = raw,
                settings = settings,
                voiceMatchPassed = voicePassed,
                voiceMatchScore = voiceMatchScore,
                onStopSpeech = { ttsManager.stop() }
            )
            isBlocked = outcome.isBlocked
            statusText = outcome.replyAr
            ttsManager.speak(
                text = outcome.spokenReplyAr,
                rate = settings.ttsSpeechRate,
                pitch = settings.ttsPitch,
                voiceGender = settings.voiceGender,
                selectedVoiceName = settings.selectedTtsVoiceName
            )
        }
    }

    fun startMic() {
        scope.launch {
            transcript = "جاري الاستماع بالعامية المصرية..."
            val res = MicrophoneArbitrator.withMicAccess(MicOwner.ASSIST_SESSION_STT) {
                sttManager.listenOnce(
                    timeoutMs = 10_000L,
                    onPartialResult = { transcript = it }
                )
            }
            res.fold(
                onSuccess = { finalText ->
                    transcript = finalText
                    executeCommand(finalText)
                },
                onFailure = { err ->
                    transcript = err.message ?: "تعذر الاستماع"
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val settings = settingsRepo.currentSettings()
        assistantTitle = "${settings.assistantName} — المساعد الشخصي"
        ttsManager.applyVoicePreferences(settings.voiceGender, settings.selectedTtsVoiceName)
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
                            text = assistantTitle,
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
