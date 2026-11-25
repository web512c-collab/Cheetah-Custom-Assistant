# Changelog

All notable changes to **Cheetah Custom Assistant** are documented in this file.

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
