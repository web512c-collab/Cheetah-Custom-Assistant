# Cheetah Custom Assistant — Zero-API-Key System Blueprint & Android Studio Guide

## 1. Zero-API-Key Architecture Overview
**Cheetah Custom Assistant** (`applicationId = com.aistudio.cheetahassistant.egxkar`, `minSdk = 24`, `targetSdk = 36`) operates **100% locally on-device** with zero external LLM/AI API keys, zero `INTERNET` permission in `AndroidManifest.xml`, and zero cloud inference SDKs.

### Modular Pipeline
1. **`assistant-system` (`com.example.assistant`)**
   - `CheetahVoiceInteractionService` (`VoiceInteractionService`) + `CheetahVoiceInteractionSessionService` + `CheetahVoiceInteractionSession` (`res/xml/voice_interaction_service.xml`, `res/layout/assistant_session_overlay.xml`).
   - `CheetahRecognitionService`: local `RecognitionService` bridging to `EgyptianOfflineSttManager`.
   - `CheetahAssistActivity` handling `Intent.ACTION_ASSIST` and `Intent.ACTION_VOICE_COMMAND`.
2. **`voice-pipeline` (`com.example.voice`)**
   - `WakeWordDetectionService` (with `CheetahWakeWordService` compatibility delegate): Foreground Service (`foregroundServiceType="microphone"`) with `VOICE_RECOGNITION` -> `MIC` fallback, adaptive noise-floor calibration, hysteresis VAD with 200ms pre-roll, and a **full in-service hands-free post-wake loop** (pause `AudioRecord` -> `AudioFocus` -> Egyptian TTS ack -> Main-Looper `EgyptianOfflineSttManager.listenOnce` -> NLU/Guardrails/Executor -> TTS reply -> 250ms echo guard -> resume `AudioRecord`).
   - `BundledYaSheetaAcousticModel`, `AudioFeatureExtractor` & `WakeWordEngine`: Loads `assets/models/cheetah_ya_sheeta_kws.bin` (`CHKW` v1 Int8-quantized 64->32->2 MLP + canonical 24x13 MFCC contour trained on synthesized phonetic realizations of `'يا شيتا'`, CRC32-verified and memory-mapped uncompressed via `MappedByteBuffer`), combined with 16kHz 13-CMVN MFCC + Sakoe-Chiba banded DTW user personalization and 14-D Goertzel spectral/ZCR Voice Match speaker verification (`filesDir/cheetah_voice_templates.json`, excluded from cloud backup).
   - `BootCompletedReceiver`: Auto-starts `WakeWordDetectionService` on API 24–33 and posts a compliant one-tap resume notification on API 34+.
   - `EgyptianPhoneticNormalizer`: Egyptian Colloquial Arabic phonetic folding (`ث->س`, `ذ->ز`, `ق->ا`, `ة->ه`, `أ/إ/آ->ا`, English/Arabized transliteration) + fuzzy Levenshtein wake matching.
   - `EgyptianOfflineSttManager`: Main-Looper-dispatched `SpeechRecognizer` using `createOnDeviceSpeechRecognizer` (API 31+) or `EXTRA_PREFER_OFFLINE = true` with `ar-EG`, timeout guard, and honest `OfflineSttStatus` capability inspection.
   - `EgyptianTtsManager`: `TextToSpeech` inspecting offline `ar-EG`/`ar` voices (`!isNetworkConnectionRequired`), `AudioAttributes(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH)`, transient `AudioFocus`, and Arabic speech text cleanup.
3. **`local-intelligence` (`com.example.intelligence` & `com.example.data`)**
   - `ConversationMemoryStore`: Persistent local text-only memory & personal shortcuts (`filesDir/cheetah_memory.json`, excluded from cloud backup).
   - `EgyptianColloquialNluParser`: Deterministic + fuzzy Egyptian dialect parser with contextual follow-ups (`ActiveAppContext`), memory recall, close-app guidance, compound commands, personal shortcuts, and Arabic+English code-switching.
   - `JarvisEgyptianResponder`: Authentic Egyptian Colloquial Arabic replies across 3 J.A.R.V.I.S.-inspired personas (`JARVIS_FORMAL_EGYPTIAN`, `FRIENDLY_EGYPTIAN`, `CONCISE_TACTICAL`).
4. **`persona-guardrails` (`com.example.guardrails`)**
   - `CommandGuardrailValidator`: Deterministic typed allowlist enforcing locked **Hard End-Line Rules** (financial transfers/OTP block, destructive wipe/uninstall block, credential protection) and configurable gates (Voice Match for comms, dialer confirmation, Accessibility automation toggle).
5. **`device-controller` (`com.example.controller`)**
   - `PackageResolver`, `DeviceActionExecutor`, `CheetahAccessibilityService`, `PermissionHealthChecker`.
6. **`settings-ui` (`com.example.ui`)**
   - `ConsoleScreen`, `SettingsScreen` + `SettingsDiagnosticsAndMemory` (16-point settings, live 12-field Wake-Word Diagnostic Console, Memory & Shortcuts Manager), and `ArchitectureScreen`.

---

## 2. Optional Native Offline Model Packaging
The shipped codebase runs immediately on Android 7.0+ with a lightweight pure-Kotlin footprint. To embed heavier C++/ONNX offline speech or quantized SLM binaries:
- Place ABI-specific `.so` files (`arm64-v8a`, `armeabi-v7a`) in `app/src/main/jniLibs/<abi>/` and assets under `app/src/main/assets/models/`.
- **Offline Wake Word / Speaker ID (`Porcupine` / `Sherpa-ONNX`):** ~6–15 MB assets, ~25–45 MB RAM.
- **Offline Egyptian STT (`Vosk` / `Whisper.cpp` / `Sherpa-ONNX`):** ~45–140 MB assets, ~140–280 MB RAM.
- **Quantized Local SLM (`llama.cpp` JNI Q4_K_M 1B–3B):** ~650 MB–1.8 GB asset pack, requires 4–6 GB+ RAM.

---

## 3. Honest Engineering Trade-Offs & Android OS Limits
- **Android Sandbox & `FLAG_SECURE`:** `AccessibilityService` cannot inspect or interact with banking apps, password managers, or `FLAG_SECURE` windows. Cheetah blocks financial/OTP commands at the guardrail layer and cannot force-stop third-party apps (offering `GLOBAL_ACTION_HOME` when Accessibility is enabled).
- **Calls & SMS:** Uses `Intent.ACTION_DIAL` and `Intent.ACTION_SENDTO` for manual user confirmation rather than high-risk `CALL_PHONE` / `SEND_SMS`.
- **Android 14+ Boot FGS Restriction:** `BOOT_COMPLETED` cannot launch a `microphone` foreground service directly on API 34+; `BootCompletedReceiver` posts a one-tap notification instead.
- **Privacy & Backups:** `backup_rules.xml` and `data_extraction_rules.xml` exclude `cheetah_voice_templates.json` and `cheetah_memory.json` from cloud backups and device transfers.
