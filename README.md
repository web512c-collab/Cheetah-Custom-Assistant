# Sheeta Custom Assistant (Android) — v0.5.0

**Sheeta Custom Assistant** (`versionName = "0.5.0"`, `versionCode = 6`, up from `versionCode = 5` in `v0.4.1`) is a self-contained, zero-API-key native Android personal assistant (`minSdk 24` / Android 7.0+, `targetSdk 36`, Kotlin + Jetpack Compose + optional `llama.cpp` JNI) built natively for **Egyptian Colloquial Arabic (العامية المصرية)**. See [CHANGELOG.md](CHANGELOG.md) for full release notes and **About** in-app for developer credits and open-source notices.

### Key Capabilities
- **Honest Offline Privacy & Zero API Keys:** Ships with zero bundled `.gguf` weights, zero API keys, and zero cloud AI SDKs (`android:allowBackup="false"`). All wake-word acoustics, Voice Match, deterministic NLU, and local `.gguf` inference run strictly on-device. **Internet search is OFF by default; only when you explicitly turn it ON do search queries (and optional top-2 HTTPS page excerpts <= 120 KB) leave the device**, and never mic audio, conversation memory, or personal data.
- **Bundled Synthesized Acoustic Wake Word & Resilient Background Service:** Ships a compact Int8-quantized keyword model (`assets/models/cheetah_ya_sheeta_kws.bin`, trained on synthesized phonetic realizations of `'يا شيتا'`) combined with user-enrolled MFCC-DTW templates and Voice Match, running inside `WakeWordDetectionService` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`, `START_STICKY`) with `onTaskRemoved()` restart scheduling, `WakeWatchdogReceiver`, a strict 5-state machine (`WakeStateMachine`), single-owner `MicrophoneArbitrator`, and non-nagging permission switches.
- **Unbundled Local `.gguf` Engine (`llama.cpp` JNI) & Expanded AI Controls:** Load your own `.gguf` model via SAF with estimated RAM & `tok/s` telemetry, presets (`Fast`, `Balanced`, `Deep`, `Custom`), live sampling (`topP`, `topK`, `minP`, `repeatPenalty`, `seed`, `batchSize`), custom system prompt, manual chat template override (`AUTO`, `ChatML`, `Llama 3`, `Gemma`, `Mistral`, `Qwen`, `Phi`), response style & language preference, editable stop sequences, thinking-tag stripping, SAF JSON export/import (`sheeta-ai-config.json`), and KV-cache prefix reuse.
- **Multi-Engine Keyless Parallel Search & Result Judging (`OnlineSearchManager`, `SearchJudgePipeline`):** Queries **DuckDuckGo**, **Wikipedia** (`ar`/`en`), **Mojeek**, and HTTPS-validated **SearXNG** in parallel (never Google or Bing), merges results with Reciprocal Rank Fusion (RRF) + multi-engine consensus boost, cleans optional HTTPS page excerpts (`<= 120 KB`), encloses web text inside `<<<UNTRUSTED_WEB_CONTENT>>>` delimiters, and uses your local `.gguf` model to cross-check & cite (`[1]`) answers with `HIGH`/`MEDIUM`/`LOW` confidence.
- **Voice, Assistant Gender & About Tab:** Customize assistant name, independent grammatical gender (`Male`/`Female`) for Egyptian Arabic replies, Arabic `TextToSpeech` voice selection, explicit Google/YouTube Android intents, and inspect version info, OSS notices, and GitHub repositories under the **About** tab.

---

## Building Locally

### Prerequisites
- **JDK 17** (or JDK 21)
- **Android SDK** with `platforms;android-36.1`, `build-tools;36.0.0`, **`ndk;28.2.13676358`**, and **`cmake;3.22.1`**
- Internet access during the first CMake configure step so CMake `FetchContent` downloads pinned `llama.cpp` tag `b12732` (shallow clone).

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
  --tests "com.example.SheetaAppRobolectricSmokeTest"

# Build Debug APK (arm64-v8a)
./gradlew assembleDebug
```
The Debug APK is output to:
`app/build/outputs/apk/debug/app-debug.apk` (signed with the persistent `debug.keystore` and identical `applicationId` so `v0.5.0` installs as an in-place update over `v0.4.1` and earlier builds).

---

## GitHub Actions CI/CD (`.github/workflows/android-build.yml`)

1. Triggers on `push` to `main`, `push` of `v*` tags, `release` (`published`), and `workflow_dispatch` (with optional `tag` input).
2. Uses **JDK 17 (Temurin)**, installs `platform-tools`, `platforms;android-36.1`, `build-tools;36.0.0`, `ndk;28.2.13676358`, and `cmake;3.22.1`, decodes `debug.keystore.base64`, runs all 7 core JVM unit test suites + non-blocking `SheetaAppRobolectricSmokeTest`, and builds `assembleDebug` (`arm64-v8a`).
3. Verifies the built APK is non-empty, renames it to `Sheeta-<tag>-debug.apk`, uploads **`sheeta-debug-apk`**, and on tag push / published release / tagged manual dispatch publishes or updates the **GitHub Pre-Release** (`permissions: contents: write`) attaching `Sheeta-<tag>-debug.apk` with notes from `CHANGELOG.md`.
