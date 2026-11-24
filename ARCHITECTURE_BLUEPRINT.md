# Cheetah Custom Assistant — Zero-API-Key System Blueprint & Android Studio Guide

## 1. Zero-API-Key Architecture Overview
**Cheetah Custom Assistant** (`applicationId = com.aistudio.cheetahassistant.egxkar`, `minSdk = 24`, `targetSdk = 36`) operates **100% locally on-device** with zero external LLM/AI API keys, zero `INTERNET` permission in `AndroidManifest.xml`, and zero cloud inference SDKs.

### Modular Pipeline
1. **`assistant-system` (`com.example.assistant`)**
   - `CheetahVoiceInteractionService` (`VoiceInteractionService`) + `CheetahVoiceInteractionSessionService` + `CheetahVoiceInteractionSession` (`res/xml/voice_interaction_service.xml`, `res/layout/assistant_session_overlay.xml`).
   - `CheetahRecognitionService` (`RecognitionService` contract).
   - `CheetahAssistActivity` handling `Intent.ACTION_ASSIST`, `Intent.ACTION_VOICE_COMMAND`, and wake-word triggers.
2. **`voice-pipeline` (`com.example.voice`)**
   - `CheetahWakeWordService`: Foreground Service (`foregroundServiceType="microphone"`) running 16kHz PCM `AudioRecord` ring-buffer VAD with live notification Mute/Stop kill-switch.
   - `AudioFeatureExtractor` & `WakeWordEnrollmentRecorder`: 12-band Goertzel spectral energy + ZCR + spectral centroid cosine-similarity Voice Match verification stored locally in `filesDir/cheetah_voice_templates.json`.
   - `EgyptianPhoneticNormalizer`: Egyptian Colloquial Arabic phonetic folding (`ث->س`, `ذ->ز`, `ق->ا`, `ة->ه`, `أ/إ/آ->ا`, English/Arabized transliteration) + fuzzy Levenshtein wake matching.
   - `EgyptianOfflineSttManager`: Uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+) or `EXTRA_PREFER_OFFLINE = true` with `ar-EG`.
   - `EgyptianTtsManager`: Uses `TextToSpeech` tuned to `Locale.forLanguageTag("ar-EG")` with configurable rate & pitch.
3. **`local-intelligence` (`com.example.intelligence`)**
   - `EgyptianColloquialNluParser`: Deterministic + fuzzy Egyptian dialect parser (YouTube playback, Google web search, app opening via Egyptian aliases, volume/media keys, Wi-Fi/BT/Location panels, Egyptian number words for alarms/timers, calls/SMS preparation, offline battery/time status, Accessibility UI commands).
   - `JarvisEgyptianResponder`: Authentic Egyptian Colloquial Arabic replies across 3 J.A.R.V.I.S.-inspired personas (`JARVIS_FORMAL_EGYPTIAN`, `FRIENDLY_EGYPTIAN`, `CONCISE_TACTICAL`).
4. **`persona-guardrails` (`com.example.guardrails`)**
   - `CommandGuardrailValidator`: Deterministic typed allowlist enforcing locked **Hard End-Line Rules** (financial transfers/OTP block, destructive wipe/uninstall block, credential protection) and configurable gates (Voice Match for comms, dialer confirmation, Accessibility automation toggle).
5. **`device-controller` (`com.example.controller`)**
   - `PackageResolver`: `<queries>`-compliant app resolution for Egyptian/Arabized names.
   - `DeviceActionExecutor`: Executes real Android Intents (`ACTION_WEB_SEARCH`, YouTube search, `ACTION_SET_ALARM`/`ACTION_SET_TIMER`, `ACTION_DIAL`, `ACTION_SENDTO`, `Settings.Panel`, `AudioManager`).
   - `CheetahAccessibilityService`: Permission-gated screen scroll, back, home, and visible label tap.
   - `PermissionHealthChecker`: Live inspection + one-tap System Settings launchers (`RoleManager.ROLE_ASSISTANT`, `ACTION_VOICE_INPUT_SETTINGS`, `ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_MANAGE_OVERLAY_PERMISSION`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
6. **`settings-ui` (`com.example.ui`)**
   - `ConsoleScreen`, `SettingsScreen`, and `ArchitectureScreen` built with Jetpack Compose Material 3 + bundled `Cairo` Arabic/Latin font.

---

## 2. Optional Native Offline Model Packaging (Upgrading Beyond Built-in Kotlin DSP + System Offline `ar-EG`)
The shipped codebase runs immediately on Android 7.0+ with a ~12 MB footprint. To embed heavier C++/ONNX offline speech or quantized SLM binaries:
- Place ABI-specific `.so` files (`arm64-v8a`, `armeabi-v7a`) in `app/src/main/jniLibs/<abi>/` and acoustic/model assets under `app/src/main/assets/models/`.
- **Offline Wake Word / Speaker ID (`Porcupine` / `Sherpa-ONNX` KWS + Wespeaker):** ~6–15 MB assets, ~25–45 MB RAM.
- **Offline Egyptian STT (`Vosk` / `Whisper.cpp` tiny/base / `Sherpa-ONNX` Arabic):** ~45–140 MB assets, ~140–280 MB RAM.
- **Quantized Local SLM (`llama.cpp` JNI with Q4_K_M 1B–3B model):** ~650 MB–1.8 GB asset pack (via Play Asset Delivery), requires 4–6 GB+ device RAM.

---

## 3. Honest Engineering Trade-Offs & Android OS Limits
- **Android Sandbox & `FLAG_SECURE`:** `AccessibilityService` cannot inspect or interact with banking apps, password managers, or `FLAG_SECURE` windows. Cheetah explicitly blocks financial/OTP commands at the guardrail layer.
- **Calls & SMS:** Cheetah uses `Intent.ACTION_DIAL` and `Intent.ACTION_SENDTO` so the user manually confirms calls/messages instead of requesting high-risk `CALL_PHONE` / `SEND_SMS` permissions.
- **OEM Background Throttling (Android 7.0+):** Xiaomi MIUI/HyperOS, Samsung OneUI, Oppo ColorOS, and Huawei EMUI aggressively kill background services under Doze. Cheetah mitigates this via `FOREGROUND_SERVICE_MICROPHONE`, explicit Battery Optimization exemption requests, and OEM Autostart guidance in Settings.
- **Package Visibility (Android 11+):** Uses Play-policy-compliant `<queries>` declarations in `AndroidManifest.xml` instead of `QUERY_ALL_PACKAGES`.
