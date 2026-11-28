# Sheeta Custom Assistant (Android) — v0.5.1

**Sheeta Custom Assistant** (`versionName = "0.5.1"`, `versionCode = 7`, up from `versionCode = 6` in `v0.5.0`) is a self-contained, zero-API-key native Android personal assistant (`minSdk 24` / Android 7.0+, `targetSdk 36`, Kotlin + Jetpack Compose + `vosk-android` offline STT + optional `llama.cpp` JNI) built natively for **Egyptian Colloquial Arabic (العامية المصرية)**. See [CHANGELOG.md](CHANGELOG.md) for full release notes and **About** in-app for developer credits and open-source notices.

### Key Capabilities & Honest Architecture
- **100% Google-Free Offline Voice Pipeline (Vosk STT + Offline TTS):** Uses `com.alphacephei:vosk-android` over direct `AudioRecord` (16 kHz Mono PCM + VAD endpointing) with SAF `.zip` local acoustic/language model import (`SpeechRecognizer` and `RecognitionService` removed completely). Prefers verified offline `TextToSpeech` voices (`!isNetworkConnectionRequired`) and explicitly warns if the device's active TTS engine is `com.google.android.tts`.
- **Personalized Acoustic Wake Word & Resilient Background FGS:** Requires `>= 5` user-recorded MFCC-DTW templates (`MAX_SAMPLES = 8`) with audio quality gating (`WakeWordEnrollmentRecorder.validateAudioQuality`) and dynamic intra-speaker calibration (`WakeThresholdCalibrator`); `CHKW-v1` is strictly demoted to a `<= 10%` auxiliary tie-breaker prior and never triggers standalone wake. `Voice Match` defaults OFF and never blocks wake or ordinary commands. `WakeWordDetectionService` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`, `START_STICKY`) promotes cleanly on every `onStartCommand`, handles FGS restriction fallbacks gracefully, includes a Console **Talk** button skipping wake straight into Vosk STT, a 10-say `"Test wake word"` counter, and a 3-stage Diagnostics `"Self-test"`.
- **Single-Truth OS Permissions & Clear Voice Error Mapping:** Removes duplicate permission booleans; always reads OS permission truth (`checkSelfPermission`, `NotificationManagerCompat`, `PowerManager`) on `ON_RESUME`, opens Android App Info on revocation/denial, checks API 30+ unused-app auto-revoke status, never pops system permission dialogs from Console, and maps each failure (`MIC_PERMISSION_DENIED`, `OS_BACKGROUND_MIC_BLOCKED`, `NO_LOCAL_STT_MODEL`, `STT_MODEL_LOAD_FAILED`, `MIC_HARDWARE_OR_CAPTURE_FAILED`, `NOISE_OR_NO_SPEECH`) to a distinct Egyptian Arabic message (`VoiceErrorMapper`).
- **Honest Network Isolation & Keyless Search (`com.example.search` only):** Ships zero bundled `.gguf` weights, zero API keys, and zero cloud LLM SDKs (`android:allowBackup="false"`). **Keyless internet search is ON by default** (so out-of-the-box online Q&A works via DuckDuckGo, Wikipedia, Mojeek, or custom HTTPS SearXNG; toggleable OFF in Settings). **Only cleaned search query text (and optional top-2 HTTPS page excerpts `<= 120 KB`) leaves the device from `com.example.search`**, and never microphone audio, conversation history, or personal data.
- **Unbundled Local `.gguf` Engine (`llama.cpp` JNI) & Consolidated UI:** Load your own `.gguf` model via SAF (`Fast`, `Balanced`, `Deep`, `Custom`), live sampling, custom system prompt, chat template override, and JSON config export/import inside a streamlined **3-tab** UI (**Console**, **Settings** with collapsed **"إعدادات متقدمة (Advanced)"** card, and **About**).

---

## Building Locally

### Prerequisites
- **JDK 17** (or JDK 21)
- **Android SDK** with `platforms;android-36.1`, `build-tools;36.0.0`, **`ndk;28.2.13676358`**, and **`cmake;3.22.1`**
- Internet access during initial dependency/CMake resolution (`vosk-android` from Maven Central and `llama.cpp` tag `b12732` via CMake `FetchContent`).

### Commands
```bash
# Make wrapper executable (Linux/macOS)
chmod +x gradlew

# Run all JVM unit & Robolectric smoke tests
./gradlew :app:testDebugUnitTest \
  --tests "com.example.CheetahCoreUnitTest" \
  --tests "com.example.WakeWordBinaryModelTest" \
  --tests "com.example.WakeStateMachineUnitTest" \
  --tests "com.example.V030FeaturesUnitTest" \
  --tests "com.example.V040AiBrainUnitTest" \
  --tests "com.example.V041SearchEnginesUnitTest" \
  --tests "com.example.V050SheetaFeaturesUnitTest" \
  --tests "com.example.V051RepairAndVoiceUnitTest" \
  --tests "com.example.StaticNetworkBoundaryTest" \
  --tests "com.example.SheetaAppRobolectricSmokeTest"

# Build Debug APK (arm64-v8a)
./gradlew assembleDebug
```
The Debug APK is output to:
`app/build/outputs/apk/debug/app-debug.apk` (signed deterministically with `debug.keystore` and identical `applicationId = com.aistudio.cheetahassistant.egxkar` so `v0.5.1` installs as an in-place update over `v0.5.0` and earlier `debug.keystore`-signed builds).

---

## GitHub Actions CI/CD (`.github/workflows/android-build.yml`)

1. Triggers on `push` to `main`, `push` of `v*` tags, `release` (`published`), and `workflow_dispatch` (with optional `tag` input).
2. Uses **JDK 17 (Temurin)**, installs `platform-tools`, `platforms;android-36.1`, `build-tools;36.0.0`, `ndk;28.2.13676358`, and `cmake;3.22.1`, decodes `debug.keystore.base64` (failing fast if missing — never generating a mismatched keystore), runs all **9 core JVM unit test suites** + non-blocking `SheetaAppRobolectricSmokeTest`, and builds `assembleDebug` (`arm64-v8a`).
3. Verifies the built APK is non-empty, renames it to `Sheeta-<tag>-debug.apk`, uploads **`sheeta-debug-apk`**, and on tag push / published release / tagged manual dispatch publishes or updates the **GitHub Pre-Release** (`permissions: contents: write`) attaching `Sheeta-<tag>-debug.apk` with notes extracted from `CHANGELOG.md`.
