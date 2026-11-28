# Changelog

All notable changes to **Sheeta Custom Assistant** (formerly Cheetah Custom Assistant) are documented in this file.

## v0.5.1 (2026-11-28)

**Version:** `0.5.1` (`versionCode = 7`, up from `versionCode = 6` in `v0.5.0`)  
**Signing:** Preserves `applicationId = com.aistudio.cheetahassistant.egxkar`, Kotlin namespace `com.example.*`, `debug.keystore`, and `debug.keystore.base64` untouched. Fail-fast CI check rejects missing keystore files instead of generating mismatched random keys (if a previous APK came from an older random CI keystore run, uninstall once before installing `v0.5.1`).

### Offline & Google-Free Voice Core (`vosk-android`, `EgyptianOfflineSttManager`, `EgyptianTtsManager`)
- **Removed Google `SpeechRecognizer` & `CheetahRecognitionService`:** Eliminated `android.speech.SpeechRecognizer` and `RecognitionService` manifests/dependencies so speech-to-text never routes through Google Speech Services or requires cloud connectivity.
- **Added True Offline Vosk STT (`com.alphacephei:vosk-android:0.3.47`):** Captures 16 kHz Mono PCM directly from `AudioRecord` with real-time RMS/VAD endpointing, safe SAF `.zip` Vosk model importer (`SettingsVoiceAndPermissionsCards` -> `LocalSttModelSettingsCard`), and clean nested-directory validation.
- **Offline TTS & Google TTS Warning (`EgyptianTtsManager`):** Filters candidate voices strictly for `!isNetworkConnectionRequired`, exposes active `enginePackage`, and displays an honest Arabic notice if `com.google.android.tts` is the active system TTS engine.
- **Honest Network Isolation Boundary (`StaticNetworkBoundaryTest`):** Enabled `internetSearchEnabled = true` by default with clear disclosure in Settings/About/README, improved offline/DNS/timeout errors (`UnknownHostException`, `SocketTimeoutException`, `ConnectException`), and added static verification confirming zero network sockets, HTTP clients, `SpeechRecognizer`, or GMS imports exist outside `com.example.search`.

### OS Single-Truth Permissions & Clear Error Diagnostics (`PermissionHealthChecker`, `VoiceErrorMapper`, `ConsoleScreen`)
- **Removed Duplicate Permission Booleans:** Eliminated `micFeatureEnabled`, `notificationsFeatureEnabled`, `batteryExemptionFeatureEnabled`, `accessibilityFeatureEnabled`, and `overlayFeatureEnabled` from `AssistantSettings`; UI switches now reflect real Android OS state refreshed on `ON_RESUME`.
- **Non-Nagging & Safe Console:** `RECORD_AUDIO` system prompt runs only from Settings when tapped (routing revoked/denied states to App Info), while `ConsoleScreen` never triggers OS permission dialogs and shows an inline `"فتح الإعدادات"` notice instead.
- **Android 11+ Auto-Revoke Detection & Distinct Failure Mapping:** Added `isAutoRevokeWhitelisted` health check with a dismissible Settings banner + App Info button, and introduced `VoiceErrorMapper` giving distinct Egyptian Arabic errors for denied mic, OS background mic block, missing Vosk model, Vosk load error, hardware capture failure, and silence/noise.

### Wake Word, Background Service & UI Clean-Up (`WakeWordEngine`, `WakeThresholdCalibrator`, `WakeWordDetectionService`, `SettingsScreen`)
- **Personalized MFCC-DTW Primary + Calibrated Thresholds:** Demoted bundled `CHKW-v1` to `<= 10%` auxiliary tie-breaker prior (`MAX_CHKW_TIE_BREAKER_PRIOR`), raised required user samples to `5` (`MAX_SAMPLES = 8`) with audio quality gating (`validateAudioQuality`), added `WakeThresholdCalibrator` dynamic intra-speaker calibration, defaulted `voiceMatchEnabled = false`, and guaranteed ordinary commands never fail due to speaker score.
- **Hardened `WakeWordDetectionService`:** Calls `startForegroundCompat` on every `onStartCommand`, catches Android 14+ FGS background exceptions cleanly with a one-tap notification fallback, keeps DataStore/TTS out of the 40 ms audio capture loop, and emits `WakeEvalTelemetry` diagnostics.
- **Usability & Deep Clean-Up:** Added Console **Talk** button (`"تحدث الآن (Talk)"`) bypassing wake word straight into Vosk STT, a 10-say `"Test wake word"` counter, a 3-stage Diagnostics `"Self-test"`, removed `ArchitectureScreen` tab (`4 -> 3` tabs), consolidated expert AI & diagnostic panels under one collapsed `"إعدادات متقدمة (Advanced)"` expandable card, and added `V051RepairAndVoiceUnitTest` + `StaticNetworkBoundaryTest`.

## v0.5.0 (2026-11-27)

**Version:** `0.5.0` (`versionCode = 6`, up from `versionCode = 5` in `v0.4.1`)  
**Signing:** Preserves the exact same `applicationId` (`com.aistudio.cheetahassistant.egxkar`), Kotlin package namespace (`com.example.*`), and `debug.keystore` (`androiddebugkey`) signing configuration so `Sheeta-v0.5.0-debug.apk` installs as an in-place update over `v0.4.1` and earlier builds.

### Rebrand & Identity ("Sheeta Custom Assistant")
- Renamed visible product branding across English & Arabic strings (`Sheeta Custom Assistant` / `مساعد شيتا الشخصي`), notification titles and channels (`BootCompletedReceiver`, `WakeWatchdogReceiver`, `WakeWordDetectionService`), VoiceInteractionService & Accessibility labels, assistant session overlay (`assistant_session_overlay.xml`), Console/Settings/Architecture headers, HTTP `User-Agent`, `settings.gradle.kts`, `metadata.json`, docs, and `.github/workflows/android-build.yml` (`Sheeta-<tag>-debug.apk`).

### About Tab (`AppLinks`, `AboutScreen`, `MainActivity`)
- Added dedicated **About** navigation tab (`"عن التطبيق"`, `ROUTE_ABOUT`) in bottom `NavigationBar` and adaptive `NavigationRail`.
- Displays app identity, live `BuildConfig.VERSION_NAME` & `BuildConfig.VERSION_CODE`, bilingual overview, developer credit (**Mohammad Joseph**), clickable `>= 48.dp` GitHub profile and repository links (`sheeta-custom-assistant`, `sheeta-operations-research`), honest network privacy statement, and Open-Source Software notices (`llama.cpp` MIT, Jetpack Compose / AndroidX / Kotlin / Material Icons Apache 2.0).

### Adaptive & Monochrome Launcher + Notification Icons
- Created custom vector adaptive launcher foreground (`ic_launcher_foreground.xml`), background (`ic_launcher_background.xml`), dedicated Android 13+ themed monochrome icon (`ic_launcher_monochrome.xml`), legacy mipmap PNGs (`48`–`192` px), and a dedicated 24dp monochrome white notification icon (`ic_stat_sheeta.xml`).

### Expanded Local AI Controls (`LocalLlmConfig`, `cheetah_llama_jni.cpp`, `LocalLlmManager`, `SettingsRepository`, `SettingsAiControlsCard`)
- **Presets & Sampling:** Added `FAST`, `BALANCED`, `DEEP`, and `CUSTOM` presets, plus live controls for `topP`, `topK`, `minP`, `repeatPenalty`, `seed` (random `-1` or fixed integer), and `batchSize` (`n_batch` / decode chunk capacity wired in JNI). Context length, thread count, and batch-size edits trigger clean model reload on next inference.
- **Prompt, Language & Template Controls:** Added sanitized editable `customSystemPrompt` (capped at 1,200 chars + `"Restore default"`), manual `chatTemplateOverride` (`AUTO`, `ChatML`, `Llama 3`, `Gemma`, `Mistral`, `Qwen`, `Phi`) with mismatch warning, `responseStyle` (`SHORT`, `BALANCED`, `DETAILED`), `aiLanguagePreference` (`EGYPTIAN_ARABIC`, `ENGLISH`, `MATCH_USER`), `spokenMaxChars` slider (`80..600`), editable `stopSequences`, `stripReasoningTags` toggle, `"Reset AI defaults"` (preserving model selection), estimated RAM telemetry (`estimatedRamMb`), and SAF JSON config export/import (`sheeta-ai-config.json`).

### Multi-Engine Parallel Search & Page Excerpt Comparison (`OnlineSearchManager`, `SearchJudgePipeline`, `ConsoleAiOverlays`)
- Strictly gated OFF by default; transmits only cleaned search query text (and up to 2 top HTTPS pages when enabled) — never mic audio, memory, or PII.
- Added **Mojeek** public HTML parser (`parseMojeekHtml`) alongside **DuckDuckGo**, **Wikipedia** (`ar`/`en`), and HTTPS-validated **SearXNG** (never Google or Bing).
- Added parallel `AUTO` execution (`coroutineScope` + `async` + per-engine timeout), per-engine enable/disable switches, editable engine order, Reciprocal Rank Fusion (`1 / (60 + rank)`) with multi-engine consensus boost, tracking-param URL deduplication (`contributingEngines`), HTTPS-only `<= 120 KB` page-excerpt enrichment (`fetchPageExcerpts`), strict `<<<UNTRUSTED_WEB_CONTENT>>>` delimiting, per-engine hit/latency reports in `"Test search"`, and multi-engine badges in Console sources.

### Cleanup, Security & Testing
- Removed `SYSTEM_ALERT_WINDOW` overlay permission and dead code (`CheetahWakeWordService`, unused `res/colors.xml`, unused `/public` assets, unused Gradle plugins/commented deps, placeholder `Example*Test` classes), set `android:allowBackup="false"`, enforced `>= 48.dp` touch height on Console source links, and added `V050SheetaFeaturesUnitTest` and `SheetaAppRobolectricSmokeTest`.

## v0.4.1 (2026-11-26)

**Version:** `0.4.1` (`versionCode = 5`, up from `versionCode = 4` in `v0.4.0`)  
**Signing:** Preserves the exact same `debug.keystore` (`androiddebugkey`) signing configuration so `Cheetah-v0.4.1-debug.apk` installs as an in-place update over `v0.2.0` / `v0.4.0`.

### New Feature — Search Engine Selection (`OnlineSearchManager`, `SettingsLlmAndSearchCards`, `SettingsRepository`)
- **4-Mode Keyless Search Engine Selector (Persisted in DataStore, Immediate):** Added DuckDuckGo (default: Instant Answer public JSON API + DuckDuckGo HTML public endpoint with graceful layout fallback), Wikipedia (`ar`/`en` automatic language matching + English fallback via MediaWiki/REST APIs), SearXNG (custom HTTPS-validated `/search?format=json` instance with a **"Test connection"** button), and **Auto** (user-editable priority order trying primary -> fallback engines automatically).
- **Diagnostics, Transparency & Guardrails (`ConsoleAiOverlays`, `V041SearchEnginesUnitTest`):** Every `WebSearchSource` now records domain, date (when available), and `engineName`, displayed clearly in on-screen source cards; added a **"Test search"** button reporting result count and response time (`ms`); preserved HTTPS-only enforcement, timeouts, caching, rate limiting, and zero-PII/query-only transmission when enabled (plus mocked HTTP unit tests).

### Fixed — GitHub Release APK Attachment (`.github/workflows/android-build.yml`)
- Added `release: types: [published]` and `workflow_dispatch` optional `tag` input triggers alongside `push` to `main` and `v*` tags, unified `release_meta` tag resolution, explicit `Cheetah-<tag>-debug.apk` existence verification, and explicit `tag_name` + `files` attachment on `softprops/action-gh-release@v2` (`prerelease: true`, `permissions: contents: write`) so the APK is always attached to GitHub Releases.

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
