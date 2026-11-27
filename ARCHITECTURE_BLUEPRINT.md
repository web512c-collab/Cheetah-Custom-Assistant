# Sheeta Custom Assistant — Zero-API-Key System Blueprint & Android Studio Guide

## 1. Zero-API-Key Architecture Overview
**Sheeta Custom Assistant** (`applicationId = com.aistudio.cheetahassistant.egxkar`, `minSdk = 24`, `targetSdk = 36`, `versionName = "0.5.0"`) operates locally on-device with zero external LLM/AI API keys, zero cloud inference SDKs, and `android:allowBackup="false"`. Optional HTTPS internet search is **OFF by default** and never sends microphone audio, history, or PII.

### Modular Pipeline
1. **`assistant-system` (`com.example.assistant`)**
   - `CheetahVoiceInteractionService` (`VoiceInteractionService`) + `CheetahVoiceInteractionSessionService` + `CheetahVoiceInteractionSession` (`res/xml/voice_interaction_service.xml`, `res/layout/assistant_session_overlay.xml`).
   - `CheetahRecognitionService`: local `RecognitionService` bridging to `EgyptianOfflineSttManager`.
   - `CheetahAssistActivity` handling `Intent.ACTION_ASSIST` and `Intent.ACTION_VOICE_COMMAND`.
2. **`voice-pipeline` (`com.example.voice`)**
   - `WakeWordDetectionService`: Foreground Service (`foregroundServiceType="microphone"`, `START_STICKY`) with `VOICE_RECOGNITION` -> `MIC` fallback, adaptive noise-floor calibration, hysteresis VAD with 200ms pre-roll, 5-state `WakeStateMachine` + `WakeWatchdogReceiver` recovery, single-owner `MicrophoneArbitrator`, and hands-free post-wake loop.
   - `BundledYaSheetaAcousticModel`, `AudioFeatureExtractor` & `WakeWordEngine`: Loads `assets/models/cheetah_ya_sheeta_kws.bin` (`CHKW` v1 Int8-quantized 64->32->2 MLP + canonical 24x13 MFCC contour generated from **synthesized** phonetic realizations of `'يا شيتا'`, CRC32-verified and memory-mapped uncompressed via `MappedByteBuffer`), combined with 16kHz 13-CMVN MFCC + Sakoe-Chiba banded DTW user personalization and 14-D Goertzel spectral/ZCR Voice Match speaker verification (`filesDir/cheetah_voice_templates.json`).
   - `BootCompletedReceiver`: Auto-starts `WakeWordDetectionService` on API 24–33 and posts a compliant one-tap resume notification (`ic_stat_sheeta`) on API 34+.
   - `EgyptianPhoneticNormalizer`, `EgyptianOfflineSttManager` (`SpeechRecognizer` Main-Looper session), `EgyptianTtsManager` (independent `VoiceGender` selection + pitch fallback notice).
3. **`local-llm` (`com.example.llm` & `app/src/main/cpp/cheetah_llama_jni.cpp`)**
   - `LlamaNativeBridge` & `LocalLlmManager`: C++ JNI bindings to `llama.cpp` `b12732` (`arm64-v8a`), SAF `.gguf` loader (In-Place FD or internal copy), GGUF header inspection (`GgufHeaderParser`), chat template detection & manual override (`ChatTemplateFormatter`), expanded sampling (`topP`, `topK`, `minP`, `repeatPenalty`, `seed`, `batchSize`), custom system prompt, stop sequences, KV-cache prefix reuse, `DeviceHealthMonitor` thermal/battery scaling, auto-unload/auto-reload, and JSON config export/import.
4. **`local-intelligence` (`com.example.intelligence` & `com.example.data`)**
   - `AssistantBrain` & `AssistantCommandProcessor`: Deterministic-first router -> Egyptian NLU + Guardrails -> Search-and-Judge (when enabled) -> Local GGUF reasoning -> Honest offline fallback.
   - `ConversationMemoryStore`, `EgyptianColloquialNluParser`, `TaskClassifier`, `PromptTemplateEngine`, `ReasoningOutputParser`, `JarvisEgyptianResponder`, `SettingsRepository`.
5. **`keyless-search-judge` (`com.example.search`)**
   - `OnlineSearchManager`: Keyless HTTPS search across **DuckDuckGo**, **Wikipedia**, **Mojeek**, and HTTPS **SearXNG** (never Google/Bing) with parallel `AUTO` execution, Reciprocal Rank Fusion (RRF) + multi-engine consensus boost, optional `<= 120 KB` HTTPS page excerpt enrichment, LRU cache, and rate limiter.
   - `SearchJudgePipeline`: `<<<UNTRUSTED_WEB_CONTENT>>>` isolation, GBNF JSON evaluation (`RESULT_EVAL_GBNF`), cross-checking (`CrossCheckReport.consensusCount`), and cited synthesis.
6. **`persona-guardrails` & `device-controller` (`com.example.guardrails`, `com.example.controller`)**
   - `CommandGuardrailValidator`, `PackageResolver`, `DeviceActionExecutor`, `CheetahAccessibilityService`, `PermissionHealthChecker`.
7. **`ui` (`com.example.ui` & `com.example.core`)**
   - `ConsoleScreen` + `ConsoleAiOverlays`, `SettingsScreen` + `SettingsAiBrainSection` + `SettingsAiControlsCard` + `SettingsLlmAndSearchCards` + `SettingsDiagnosticsAndMemory`, `ArchitectureScreen`, and `AboutScreen` (`AppLinks`).

---

## 2. Unbundled `.gguf` Packaging Policy
No LLM weights are bundled in the APK. Users pick their own `.gguf` file via Android SAF at runtime (`LocalLlmManager`), keeping the APK small while allowing `Q4_K_M` 0.5B–3B models on capable `arm64-v8a` devices (with live `estimatedRamMb` warnings).

---

## 3. Honest Engineering Trade-Offs & Android OS Limits
- **Android Sandbox & `FLAG_SECURE`:** `AccessibilityService` cannot inspect or interact with banking apps, password managers, or `FLAG_SECURE` windows. Sheeta blocks financial/OTP commands at the guardrail layer and never requests `SYSTEM_ALERT_WINDOW`.
- **Calls & SMS:** Uses `Intent.ACTION_DIAL` and `Intent.ACTION_SENDTO` for manual user confirmation rather than high-risk `CALL_PHONE` / `SEND_SMS`.
- **Android 14+ Boot FGS Restriction:** `BOOT_COMPLETED` cannot launch a `microphone` foreground service directly on API 34+; `BootCompletedReceiver` posts a one-tap notification instead.
- **Privacy & Backups:** `android:allowBackup="false"`, plus `backup_rules.xml` and `data_extraction_rules.xml`, keep local voice templates, chat history, and AI settings private to the device.
