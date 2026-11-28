# Sheeta Custom Assistant — Zero-API-Key System Blueprint & Android Studio Guide

## 1. Zero-API-Key & Network-Isolated Architecture Overview
**Sheeta Custom Assistant** (`applicationId = com.aistudio.cheetahassistant.egxkar`, `minSdk = 24`, `targetSdk = 36`, `versionName = "0.5.1"`, `versionCode = 7`) operates strictly offline for all audio, wake-word, speech-to-text, TTS, NLU, guardrails, and local `.gguf` inference, with `android:allowBackup="false"`. All network sockets and HTTPS URLs are statically isolated inside `com.example.search` (`OnlineSearchManager`), enforced by `StaticNetworkBoundaryTest`. Keyless internet search is **ON by default** (toggleable OFF in Settings) and transmits only the cleaned search query text (plus optional top-2 HTTPS page excerpts `<= 120 KB`) when triggered.

### Modular Pipeline
1. **`assistant-system` (`com.example.assistant`)**
   - `CheetahVoiceInteractionService` (`VoiceInteractionService`) + `CheetahVoiceInteractionSessionService` + `CheetahVoiceInteractionSession` (`res/xml/voice_interaction_service.xml`, `res/layout/assistant_session_overlay.xml`).
   - `CheetahAssistActivity` handling `Intent.ACTION_ASSIST` and `Intent.ACTION_VOICE_COMMAND` directly via offline Vosk STT + `VoiceErrorMapper`.
   - `SpeechRecognizer` and `RecognitionService` (`CheetahRecognitionService`) were intentionally removed in `v0.5.1` to guarantee 100% Google-free voice recognition.
2. **`voice-pipeline` (`com.example.voice`)**
   - **Offline STT Engine Decision (`vosk-android:0.3.47` vs `whisper.cpp`):** Vosk (`com.alphacephei:vosk-android:0.3.47`) was chosen instead of adding `whisper.cpp` JNI to avoid duplicate `ggml` native symbol collisions with our pinned `llama.cpp` (`b12732`) C++ engine, keep APK size lean (`arm64-v8a`), and allow users to import any compact Vosk `.zip` speech model (including Egyptian/Arabic `vosk-model-ar*`) via SAF (`EgyptianOfflineSttManager` + `AudioRecord` 16 kHz Mono PCM + VAD endpointing).
   - `WakeWordDetectionService`: Hardened Foreground Service (`foregroundServiceType="microphone"`, `START_STICKY`) calling `startForegroundCompat` on every `onStartCommand`, catching Android 14+ background FGS start restrictions gracefully with a tap-to-resume notification, holding DataStore updates/TTS outside the tight 40 ms audio loop, emitting live `WakeEvalTelemetry` events, and scheduling `AlarmManager` + `WakeWatchdogReceiver` recovery.
   - `WakeWordEngine`, `WakeThresholdCalibrator` & `WakeWordEnrollmentRecorder`: Primary user MFCC-DTW personalization (`MIN_REQUIRED_SAMPLES = 5`, `MAX_SAMPLES = 8`) with audio quality gating (`validateAudioQuality`: silence, `>3%` clipping, flat continuous noise rejection) and dynamic intra-speaker calibration. `BundledYaSheetaAcousticModel` (`assets/models/cheetah_ya_sheeta_kws.bin`, `CHKW-v1`) is capped at a `<= 0.10f` auxiliary tie-breaker prior and cannot trigger standalone wake. `Voice Match` defaults OFF and never blocks wake or ordinary commands.
   - `VoiceErrorMapper`: Maps OS mic denial, background mic restriction, missing Vosk `.zip`, Vosk load error, hardware capture failure, and silence/noise into distinct Egyptian Arabic diagnostic guidance.
   - `EgyptianTtsManager`: Prefers verified offline voices (`!isNetworkConnectionRequired`), surfaces active `enginePackage`, and warns honestly if `com.google.android.tts` is active.
   - `BootCompletedReceiver`: Auto-starts `WakeWordDetectionService` on API 24–33 and posts a compliant one-tap resume notification (`ic_stat_sheeta`) on API 34+.
3. **`local-llm` (`com.example.llm` & `app/src/main/cpp/cheetah_llama_jni.cpp`)**
   - `LlamaNativeBridge` & `LocalLlmManager`: C++ JNI bindings to `llama.cpp` `b12732` (`arm64-v8a`), SAF `.gguf` loader, `GgufHeaderParser`, `ChatTemplateFormatter`, expanded sampling, custom system prompt, stop sequences, KV-cache prefix reuse, `DeviceHealthMonitor`, auto-unload/auto-reload, and JSON config export/import.
4. **`local-intelligence` (`com.example.intelligence` & `com.example.data`)**
   - `AssistantBrain`, `AssistantCommandProcessor`, `ConversationMemoryStore`, `EgyptianColloquialNluParser`, `TaskClassifier`, `PromptTemplateEngine`, `ReasoningOutputParser`, `JarvisEgyptianResponder`, `SettingsRepository` (OS single-truth permission health + `autoRevokeTipDismissed`), and `VoiceTemplateStore`.
5. **`keyless-search-judge` (`com.example.search`)**
   - `OnlineSearchManager` & `SearchJudgePipeline`: Sole module permitted to open network sockets (`OnlineSearchManager` only). Queries DuckDuckGo, Wikipedia (`ar`/`en`), Mojeek, and HTTPS SearXNG with RRF fusion, optional `<= 120 KB` HTTPS page excerpts, offline/timeout error classification, and `<<<UNTRUSTED_WEB_CONTENT>>>` GBNF evaluation.
6. **`persona-guardrails` & `device-controller` (`com.example.guardrails`, `com.example.controller`)**
   - `CommandGuardrailValidator`, `PackageResolver`, `DeviceActionExecutor`, `CheetahAccessibilityService`, `PermissionHealthChecker` (API 30+ `isAutoRevokeWhitelisted` check included).
7. **`ui` (`com.example.ui` & `com.example.core`)**
   - Streamlined 3-tab navigation (`ConsoleScreen` with Talk button & inline mic-settings banner + `ConsoleAiOverlays`, `SettingsScreen` + `SettingsVoiceAndPermissionsCards` + `SettingsLlmAndSearchCards` + `SettingsDiagnosticsAndMemory` + collapsed `"إعدادات متقدمة (Advanced)"` section for AI Brain / AI Controls / Diagnostic Console, and `AboutScreen`). (`ArchitectureScreen` removed in `v0.5.1`).

---

## 2. Unbundled Assets & Model Policy
- **Local LLM Weights (`.gguf`) & Local STT Packs (`vosk-model-*.zip`):** Zero large model weights are bundled in the APK. Users pick `.gguf` and Vosk `.zip` archives via Android SAF at runtime (`LocalLlmManager`, `EgyptianOfflineSttManager`).

---

## 3. Honest Engineering Trade-Offs & Android OS Limits
- **Android Sandbox & `FLAG_SECURE`:** `AccessibilityService` cannot inspect or interact with banking apps, password managers, or `FLAG_SECURE` windows. Sheeta blocks financial/OTP commands at the guardrail layer and never requests `SYSTEM_ALERT_WINDOW`.
- **Android 11+ Unused App Auto-Revoke & Android 14+ Boot FGS Restriction:** OS may hibernate permissions if unused (`PermissionHealthChecker` detects and guides user to App Info) and blocks direct `microphone` FGS launch from `BOOT_COMPLETED` on API 34+ (`BootCompletedReceiver` posts a one-tap notification instead).
- **Privacy & Backups:** `android:allowBackup="false"`, plus `backup_rules.xml` and `data_extraction_rules.xml`, keep local voice templates, chat history, imported Vosk models, and AI settings private to the device.
