# Changelog

All notable changes to **Cheetah Custom Assistant** are documented in this file.

## v0.4.0 (2026-11-26)

**Version:** `0.4.0` (`versionCode = 4`, up from `versionCode = 2` in `v0.2.0`)  
**Signing:** Preserves the exact same `debug.keystore` (`androiddebugkey`) signing configuration so `Cheetah-v0.4.0-debug.apk` installs as an in-place update over `v0.2.0` / earlier debug builds.

### Background and Service Fixes
- **True Background Listening Continuity (`WakeWordDetectionService`, `WakeWatchdogReceiver`, `BootCompletedReceiver`):** Added `FOREGROUND_SERVICE_TYPE_MICROPHONE`, `START_STICKY`, `onTaskRemoved()` restart scheduling via `AlarmManager`, periodic 15-minute `WakeWatchdogReceiver` health checks (with API 34+ recovery notification fallback), supervisor crash recovery around the acoustic capture loop, dynamic `cycleBudgetMs`, and OEM Autostart / Battery exemption guidance in Settings.
- **5-State Pipeline & Audio Arbitration (`WakeStateMachine`, `MicrophoneArbitrator`, `EgyptianOfflineSttManager`, `EgyptianTtsManager`):** Eliminated single-response pipeline freezes using a strict 5-state machine (`IDLE_WAKE_LISTENING -> WAKE_DETECTED -> COMMAND_LISTENING -> PROCESSING -> SPEAKING -> IDLE_WAKE_LISTENING`), per-state watchdog timeouts, `try/finally` cleanup after every turn, `AudioRecord` consecutive-error recovery, single-owner `MicrophoneArbitrator` mutex, main-thread `AtomicLong` STT session isolation, TTS timeout `stop()` + audio-focus release, live recovery/watchdog counters, and a 50-event State Transition Log in Diagnostics.

### Permissions Behavior
- **Non-Nagging Permission Switches (`MainActivity`, `SettingsScreen`, `PermissionHealthChecker`, `SettingsRepository`):** Removed automatic and repeated permission rationale dialogs; converted permissions into persisted ON/OFF feature switches (`micFeatureEnabled`, `notificationsFeatureEnabled`, `batteryExemptionFeatureEnabled`, `accessibilityFeatureEnabled`, `overlayFeatureEnabled`) that refresh live OS status on `ON_RESUME`, skip system prompts when already granted, and route permanent denials directly to Android App Info (`ACTION_APPLICATION_DETAILS_SETTINGS`).

### Local GGUF Model Support
- **Unbundled On-Device `llama.cpp` Engine (`CMakeLists.txt`, `cheetah_llama_jni.cpp`, `LlamaNativeBridge`, `LocalLlmManager`):** Built C++ JNI bindings targeting `arm64-v8a` with NDK `28.2.13676358` and CMake `3.22.1` (`FetchContent` pinned `llama.cpp` `b12732`), shipping zero weights in the APK. Added ProGuard/R8 JNI keep rules (`app/proguard-rules.pro`).
- **SAF Picking, Chat Templates & Health Scaling (`GgufHeaderParser`, `ChatTemplateFormatter`, `DeviceHealthMonitor`):** Supports SAF `.gguf` selection with persistable read permission (In-Place SAF FD or verified internal-storage copy), metadata/quantization inspection, pre-load RAM safety warnings, `tokenizer.chat_template` parsing with `ChatML`/`Llama 3`/`Gemma`/`Mistral`/`Qwen`/`Phi`/`Generic` formatting (+ missing-template warning), serialized `inferenceMutex`, background thread priority during wake FGS operation, `BatteryManager`/`PowerManager` battery & thermal scaling, C++ OOM/exception safety, auto-unload timer + on-demand auto-reload (`ensureModelLoadedIfConfigured()`), and a live `"Test the model"` (`tok/s`) benchmark.

### AI Reasoning and Analysis Modes
- **Unified Deterministic-First AI Router (`AssistantBrain`, `AssistantCommandProcessor`, `TaskClassifier`, `PromptTemplateEngine`):** Routes every request strictly in order: (1) voice/text stop & continuation control, (2) deterministic Egyptian NLU + `CommandGuardrailValidator` + `DeviceActionExecutor`, (3) Search-and-Judge when fresh info is needed and search is enabled, (4) local `.gguf` brain across 13 `AiTaskType` categories (with optional model tie-breaker and automatic user-language matching), and (5) honest fallback with a direct Model Settings shortcut.
- **Thinking Modes, KV Prefix Reuse, Streaming & Inputs (`AiMode`, `ReasoningOutputParser`, `ConsoleAiOverlays`, `SettingsAiBrainSection`, `MainActivity`):** Added `FAST` / `BALANCED` / `DEEP_THINKING` modes, live elapsed thinking timer + configurable `maxThinkingSeconds` deadline, streaming-safe stripping of `<think>`, `<thought>`, `<|thought|>`, `[THOUGHT]`, and `<reasoning>` blocks (excluded from TTS; collapsible in UI when `showReasoning` is ON), rolling conversation summary (`ConversationMemoryStore.rollingSummary`) + KV-cache prefix reuse, 3-sentence/240-char concise TTS capping with `"تحب أكمل الباقي؟"` continuation, voice (`"وقف"`/`"كفاية"`) & UI Stop interruption, `ACTION_SEND` (`text/plain`) Share sheet attachment, explicit-only clipboard access, confirmation-gated `ProposedAction` cards validated by `CommandGuardrailValidator`, and 8 immediate DataStore settings.

### Search Result Judging
- **Anti-Injection Filtering, GBNF Scoring & Grounded Synthesis (`SearchJudgePipeline`):** Cleans HTML/navigation boilerplate, deduplicates and filters short/empty results, boosts/excludes user-editable trusted & untrusted domains, prioritizes recent entries on time-sensitive queries, isolates snippets inside `<<<UNTRUSTED_WEB_CONTENT>>>` delimiters, evaluates candidates via `RESULT_EVAL_GBNF` JSON (with defensive JSON repair & 1 automatic retry), cross-checks agreements and conflicts (`CrossCheckReport`), synthesizes `[1]`-cited answers with `HIGH`/`MEDIUM`/`LOW` confidence, displays toggleable source cards (`showSources`), and provides an honest heuristic fallback when no local model is loaded.

### Voice and Assistant Gender
- **Independent Identity, Grammar & TTS Voice (`SettingsScreen`, `SettingsLlmAndSearchCards`, `JarvisEgyptianResponder`, `EgyptianTtsManager`):** Added editable Assistant Name (default `"شيتا"`), independent **Assistant Gender** (adjusting Egyptian Arabic masculine/feminine phrasing across deterministic replies and LLM system prompts), and **TTS Voice Gender** (enumerates Arabic `TextToSpeech` voices with gender heuristic, manual voice picker, honest pitch-adjustment fallback notice, and System TTS settings link).

### Internet Search
- **Strict Opt-In Keyless HTTPS Search (`OnlineSearchManager`, `AndroidManifest.xml`, `SettingsLlmAndSearchCards`):** Added `"Allow internet search"` switch (**OFF by default**; `INTERNET` permission gated behind this opt-in switch). Queries keyless HTTPS Wikipedia, DuckDuckGo Instant Answer, and optional user-provided HTTPS SearXNG instances; **only the cleaned search query text leaves the device, and only when the search switch is ON**.

### Tests & CI/CD
- Added `WakeStateMachineUnitTest`, `V030FeaturesUnitTest`, and `V040AiBrainUnitTest` to `.github/workflows/android-build.yml`, automated NDK/CMake SDK provisioning, pre-release GitHub Release publishing (`Cheetah-v0.4.0-debug.apk`) with release notes extracted from `CHANGELOG.md`, and preserved `debug.keystore` update signing.

## v0.2.0 (2026-11-25)

**Version:** `0.2.0` (`versionCode = 2`)  
**Signing:** Preserves the same `debug.keystore` (`androiddebugkey`) signing certificate so `Cheetah-v0.2.0-debug.apk` installs cleanly as an in-place update over previous debug builds.

### Wake Word & Bundled Offline Acoustic Model
- **Bundled `CHKW` v1 Acoustic Model (`assets/models/cheetah_ya_sheeta_kws.bin`):** Added a 2,596-byte Int8-quantized keyword-spotting binary (`64 -> 32 -> 2` MLP + 24×13 canonical MFCC contour) trained via `scripts/generate_ya_sheeta_model.py` on synthesized phonetic realizations of Egyptian Arabic **`يا شيتا`** (`/jaː ˈʃiː.ta/`), packaged uncompressed (`noCompress += ["bin", "tflite"]`) for `MappedByteBuffer` loading.
- **`BundledYaSheetaAcousticModel` & DSP Tensor Extraction:** Added CRC32 integrity verification, uncompressed asset memory-mapping (`InputStream` fallback), and `AudioFeatureExtractor.extractKwsFeatureTensor` / `poolSegments64` (13 CMVN MFCCs + RMS + ZCR + spectral tilt).
- **Local `WakeWordDetectionService` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`):** Replaced direct reliance on `CheetahWakeWordService` with `WakeWordDetectionService` (while keeping `CheetahWakeWordService` as a backward-compatible delegate), providing out-of-the-box `'يا شيتا'` wake detection even with zero enrolled voice templates, adaptive noise-floor calibration, hysteresis VAD with 200 ms pre-roll, and a full hands-free post-wake loop (TTS ack -> pause `AudioRecord` -> Main-Looper `EgyptianOfflineSttManager` -> NLU/Guardrails/`DeviceActionExecutor` -> `ConversationMemoryStore` -> TTS reply -> resume `AudioRecord`).
- **Personalized MFCC-DTW + Voice Match Fusion (`WakeWordEngine`):** Combined bundled `'يا شيتا'` inference with user-enrolled MFCC-DTW templates (`VoiceTemplateStore`) and optional 14-D Goertzel spectral/ZCR Voice Match speaker verification with safe owner fallback.

### Egyptian Colloquial NLU, Memory, Guardrails & System Integration
- **Egyptian Colloquial NLU & Personas:** Added phonetic folding (`EgyptianPhoneticNormalizer`), contextual follow-ups (`ActiveAppContext`), personal voice shortcuts and conversation history (`ConversationMemoryStore`), compound commands, and J.A.R.V.I.S.-inspired Egyptian Arabic responses across 3 personas (`JarvisEgyptianResponder`).
- **Deterministic End-Line Guardrails & Device Control:** Added locked security guardrails (`CommandGuardrailValidator`) blocking financial transfers, credential/OTP harvesting, and destructive actions, alongside dialer/SMS confirmation gates (`DeviceActionExecutor`), `PackageResolver`, `PermissionHealthChecker`, and permission-gated `CheetahAccessibilityService`.
- **Android System Digital Assistant & Boot Continuity:** Integrated `CheetahVoiceInteractionService`, `CheetahVoiceInteractionSessionService`, `CheetahVoiceInteractionSession`, `CheetahRecognitionService`, `CheetahAssistActivity` (`ACTION_ASSIST` / `ACTION_VOICE_COMMAND`), and API 24–36 compliant `BootCompletedReceiver`.

### UI, Diagnostics, Build & CI
- **Console, Architecture & 16-Point Settings:** Updated `AssistantViewModel`, `ConsoleScreen`, `SettingsScreen`, `SettingsDiagnosticsAndMemory`, and `ArchitectureScreen` to surface live `CHKW-v1` model status, audio source telemetry, DTW/Voice Match scores, and memory/shortcut controls, plus custom adaptive launcher icons and bundled local fonts (`Cairo`, `Space Grotesk`, `JetBrains Mono`).
- **ProGuard / Manifest & Backup Privacy:** Registered `WakeWordDetectionService` in `AndroidManifest.xml` (with zero `INTERNET` permission), added R8 keep rules in `app/proguard-rules.pro`, and excluded local voice/memory files from cloud backup (`backup_rules.xml`, `data_extraction_rules.xml`).
- **Unit Tests & Release CI Pipeline:** Added `WakeWordBinaryModelTest` alongside `CheetahCoreUnitTest` and `ExampleUnitTest` (13 passing JVM tests) and updated `.github/workflows/android-build.yml` to build and attach versioned release APKs (e.g., `Cheetah-v0.2.0-debug.apk`).

## v0.1.0

- Initial Android project baseline (`minSdk 24`, `targetSdk 36`, Jetpack Compose, `versionCode = 1`, `versionName = "1.0"`), debug keystore CI setup, and offline Egyptian assistant foundation.
