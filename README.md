# Cheetah Custom Assistant (Android) — v0.4.1

**Cheetah Custom Assistant** (`versionName = "0.4.1"`, `versionCode = 5`, up from `versionCode = 2` in `v0.2.0` and `4` in `v0.4.0`) is a self-contained, zero-API-key native Android personal assistant (`minSdk 24` / Android 7.0+, `targetSdk 36`, Kotlin + Jetpack Compose + optional `llama.cpp` JNI) built natively for **Egyptian Colloquial Arabic (العامية المصرية)**. See [CHANGELOG.md](CHANGELOG.md) for full release notes.

### Key Capabilities
- **Honest Offline Privacy & Zero API Keys:** Ships with zero bundled `.gguf` weights, zero API keys, and zero cloud AI SDKs. All wake-word detection, Voice Match, NLU, and local `.gguf` inference run strictly on-device. **Only cleaned search query text ever leaves the device, and only when the "Allow internet search" switch in Settings is explicitly switched ON** (it is **OFF** by default).
- **Resilient Background Listening & Non-Nagging Permissions:** `WakeWordDetectionService` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`, `START_STICKY`) runs continuously with `onTaskRemoved()` restart scheduling, `WakeWatchdogReceiver` health checks, a strict 5-state machine (`WakeStateMachine`), single-owner `MicrophoneArbitrator`, and persisted ON/OFF permission switches with zero repeated rationale dialogs.
- **Unbundled Local `.gguf` Support (`llama.cpp` JNI):** Load your own `.gguf` model via SAF (in-place FD or internal copy) with RAM safety checks, auto-detected chat templates (`ChatML`, `Llama 3`, `Gemma`, `Mistral`, `Qwen`, `Phi`), KV-cache prefix reuse, auto-unload/auto-reload, `DeviceHealthMonitor` battery/thermal scaling, and a live `tok/s` benchmark.
- **Unified AI Brain & Reasoning Modes (`AssistantBrain`):** Deterministic NLU and locked End-Line Guardrails (`CommandGuardrailValidator`) always run first. General tasks (13 `AiTaskType` categories) route to your loaded `.gguf` model in `Fast`, `Balanced`, or `Deep Thinking` mode with streaming-safe `<think>`/`<thought>`/`<reasoning>` stripping, concise spoken summaries (`"تحب أكمل الباقي؟"`), voice (`"وقف"`/`"كفاية"`) & UI Stop interruption, Android Share (`ACTION_SEND`), and explicit-only clipboard access.
- **Configurable Search Engine & Result Judging (`OnlineSearchManager`, `SearchJudgePipeline`):** Choose **DuckDuckGo** (default: Instant Answer JSON + public HTML endpoint), **Wikipedia** (`ar`/`en` MediaWiki + REST APIs), **SearXNG** (custom HTTPS instance with **Test connection**), or **Auto** (user-editable fallback order), test search live (count + `ms`), inspect engine badges in sources, and let the local model judge & cite results (`[1]`) with `HIGH`/`MEDIUM`/`LOW` confidence.
- **Voice, Assistant Gender & System Integration:** Customize the assistant's name, independent grammatical gender (`Male`/`Female`) for Egyptian Arabic replies/prompts, and Arabic `TextToSpeech` voice gender, while explicit Google/YouTube Android intent commands remain intact.

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

# Run core JVM unit tests
./gradlew :app:testDebugUnitTest \
  --tests "com.example.CheetahCoreUnitTest" \
  --tests "com.example.WakeWordBinaryModelTest" \
  --tests "com.example.WakeStateMachineUnitTest" \
  --tests "com.example.V030FeaturesUnitTest" \
  --tests "com.example.V040AiBrainUnitTest" \
  --tests "com.example.V041SearchEnginesUnitTest" \
  --tests "com.example.ExampleUnitTest"

# Build Debug APK (arm64-v8a)
./gradlew assembleDebug
```
The Debug APK is output to:
`app/build/outputs/apk/debug/app-debug.apk` (signed with the persistent `debug.keystore` so `v0.4.1` installs cleanly over `v0.2.0` / `v0.4.0`).

---

## GitHub Actions CI/CD (`.github/workflows/android-build.yml`)

1. Triggers on `push` to `main`, `push` of `v*` tags, `release` (`published`), and `workflow_dispatch` (with optional `tag` input).
2. Uses **JDK 17 (Temurin)**, installs `platform-tools`, `platforms;android-36.1`, `build-tools;36.0.0`, `ndk;28.2.13676358`, and `cmake;3.22.1`, decodes `debug.keystore.base64`, runs all 7 core JVM unit test suites + non-blocking `ExampleRobolectricTest`, and builds `assembleDebug` (`arm64-v8a`).
3. Verifies the built APK is non-empty, renames it to `Cheetah-<tag>-debug.apk`, uploads **`cheetah-debug-apk`**, and on tag push / published release / tagged manual dispatch publishes or updates the **GitHub Pre-Release** (`permissions: contents: write`) attaching `Cheetah-<tag>-debug.apk` with notes from `CHANGELOG.md`.
