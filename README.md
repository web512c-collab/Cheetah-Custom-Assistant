# Cheetah Custom Assistant (Android)

**Cheetah Custom Assistant** is a 100% self-contained, zero-external-API native Android personal assistant (`minSdk 24` / Android 7.0+, `targetSdk 36`, Kotlin + Jetpack Compose) built natively for **Egyptian Colloquial Arabic (العامية المصرية)**.

### Key Capabilities
- **Zero External APIs:** Runs completely offline on-device with no Gemini/OpenAI API keys and no `INTERNET` permission in `AndroidManifest.xml`.
- **System Digital Assistant Registration:** Implements `VoiceInteractionService`, `VoiceInteractionSessionService`, `VoiceInteractionSession`, `RecognitionService`, and `ACTION_ASSIST` / `ACTION_VOICE_COMMAND`.
- **Local Wake Word & Voice Match:** Transparent `FOREGROUND_SERVICE_MICROPHONE` service (`CheetahWakeWordService`) with real-time 16kHz PCM Goertzel spectral feature extraction + cosine-similarity speaker verification (`filesDir/cheetah_voice_templates.json`), mute/stop notification kill-switch, and single-mic STT coordination.
- **Egyptian Colloquial Arabic NLU & J.A.R.V.I.S. Persona:** Offline dialect parser + 3 selectable J.A.R.V.I.S.-style Egyptian personas + deterministic **Hard End-Line** security guardrails (`CommandGuardrailValidator`).
- **Comprehensive Settings Hub:** One-tap Default Assistant setup, Voice Recording OR Text Typing custom wake name, Voice Match controls, End-Line boundary toggles, and Permissions & Battery Optimization Health Center.

---

## Building Locally

### Prerequisites
- **JDK 17** (or JDK 21)
- **Android SDK** with `platforms;android-36.1` and `build-tools;36.0.0`

### Commands
```bash
# Make wrapper executable (Linux/macOS)
chmod +x gradlew

# Run unit tests
./gradlew :app:testDebugUnitTest

# Build Debug APK
./gradlew assembleDebug
```
The Debug APK is output to:
`app/build/outputs/apk/debug/app-debug.apk`

---

## GitHub Actions CI/CD (`.github/workflows/android-build.yml`)

The workflow runs automatically on:
- Every push to `main`
- Every tag starting with `v` (e.g., `v0.1.0`)
- Manual trigger via **Actions → Android CI - Build APK → Run workflow**

### What the Workflow Does
1. Sets up **JDK 17 (Temurin)**, **Android SDK** (`android-36.1` + `build-tools;36.0.0`), and **Gradle caching**.
2. Ensures `gradlew` is executable and prepares `debug.keystore` for the debug signing config.
3. Runs unit tests (`./gradlew :app:testDebugUnitTest`) and builds the Debug APK (`./gradlew assembleDebug`).
4. Uploads the Debug APK as the **`cheetah-debug-apk`** workflow artifact.
5. **Optional Signed Release APK:** If repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (set to `upload` to match `app/build.gradle.kts`), and `KEY_PASSWORD` are configured, decodes the keystore temporarily, builds `./gradlew assembleRelease`, uploads **`cheetah-release-apk`**, and deletes the temporary keystore file.
6. **GitHub Releases:** When you push a tag matching `v*`, publishes a GitHub Release automatically with the built APK(s) attached.

---

## How to Download the APK & Create a Release Tag

1. **Download from Workflow Artifacts:**
   - Open your repository on GitHub → **Actions** → select the latest green **Android CI - Build APK** run → scroll to **Artifacts** → click **`cheetah-debug-apk`**.
2. **Create a Release Tag (`v*`):**
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   Once the workflow finishes, open the repository's **Releases** tab to download the attached APK directly.
