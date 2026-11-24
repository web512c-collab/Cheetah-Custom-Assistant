# Project Manifesto: Cheetah Custom Assistant (100% Self-Contained Egyptian Colloquial Arabic Android Assistant)

## 1. Core Vision
Build **Cheetah Custom Assistant**, a 100% self-contained, zero-external-API Android personal assistant (targeting Android 7.0 Nougat / API 24 and above) inspired by Tony Stark's **J.A.R.V.I.S.** experience within real Android OS capabilities. The assistant must operate completely offline without any Google AI APIs, cloud keys, or external servers, natively understand and speak **Egyptian Colloquial Arabic (العامية المصرية / اللهجة العربية المصرية)**, enforce strict deterministic behavioral boundaries, execute device and app actions after one-time permission grants, and provide an intuitive, user-friendly **Settings** screen for effortless customization.

## 2. Non-Negotiable Principles
- **Strict Zero-API-Key & 100% Self-Contained Architecture:**
  - Do **not** use or require any Google Gemini/AI API keys, cloud inference endpoints, or external third-party runtime services.
  - All core modules—custom wake-word detection, on-device Voice Match speaker verification, Egyptian Colloquial Arabic Speech-to-Text (STT), Egyptian Colloquial Arabic Text-to-Speech (TTS), local NLU/SLM reasoning, persona guardrails, and action execution—must run locally on-device using bundled libraries, native binaries, and local assets.
  - When internet access is available and the user requests web or YouTube searches, trigger standard on-device Android Intents (`Intent.ACTION_WEB_SEARCH`, YouTube search/playback intents) without needing any external AI API key, while keeping full offline fallback intact.
- **Native Egyptian Colloquial Arabic (العامية المصرية / اللهجة العربية المصرية):**
  - The assistant must natively comprehend and respond in authentic, everyday **Egyptian Colloquial Arabic (العامية المصرية)**—not stiff Modern Standard Arabic (الفصحى) or robotic literal translations.
  - It must accurately parse Egyptian slang, everyday phrasing, Egyptian phonetic variations (such as ج / ق / ث / ذ pronunciations in speech), and natural Egyptian code-switching with English app or tech names (e.g., *"افتح يوتيوب وشغل أغنية كذا"*, *"دورلي على كذا في جوجل"*, *"افتح الواتساب"*, *"وطي الصوت شوية"*).
- **J.A.R.V.I.S.-Inspired Persona & Hard "End-Line" Boundaries:**
  - Deliver a smart, composed, proactive, and loyal tactical companion experience with customizable personality traits and strict behavioral limits ("end-line" rules) enforced by a deterministic command allowlist before executing any action.
- **User-Friendly Settings Hub (Effortless Control):**
  - Provide a clean, intuitive Settings screen that simplifies everyday life: one-tap shortcut to set **Cheetah Custom Assistant** as the phone's default digital assistant, custom wake-name setup via **voice recording OR text typing**, Voice Match enrollment, persona/boundary tuning, and a one-screen permissions & background health checklist.
- **One-Time Permissions & Transparent Privacy:**
  - Request required permissions once with clear Egyptian Arabic explanations, display a transparent foreground service notification during continuous microphone listening, and provide an instant kill-switch to mute or disable background listening anytime.

## 3. Functional Scope
- **Custom Wake Name & Local Voice Match (Voice or Text Setup):**
  - Continuous local microphone listening for a user-chosen wake name/phrase (e.g., *"يا شيتا"*, *"هاي شيتا"*, or any custom name chosen by the user), configured either by **speaking it** (Voice Enrollment) or **typing it** in Settings.
  - On-device Voice Match speaker verification so the assistant recognizes the owner's voice and minimizes false triggers.
- **Egyptian Colloquial Arabic Voice Pipeline (STT & TTS):**
  - Offline/local Speech-to-Text tuned for Egyptian Colloquial Arabic speech patterns and offline/local Text-to-Speech delivering natural-sounding Egyptian Arabic spoken responses through the phone speaker or connected headset.
- **Device & Application Control:**
  - Open installed apps, launch YouTube searches and media playback, trigger browser searches when online via Android Intents, control basic permitted device actions (volume, Wi-Fi/Bluetooth settings panels, alarms, calls/messages intents), and interact with app screens via optional `AccessibilityService` where explicitly granted by the user and permitted by Android OS security rules.
- **Comprehensive Settings Screen:**
  - **Default Digital Assistant Setup:** Live status indicator showing whether Cheetah is currently the active system assistant, plus a direct button launching Android's Default Assistant settings (`Settings.ACTION_VOICE_INPUT_SETTINGS`) with clear instructions for Android 7.0+.
  - **Custom Wake Name (Voice OR Text):** Set or rename the assistant either by **recording your voice** saying the wake phrase multiple times (creating an acoustic/speaker template) or by **typing** the desired name/phrase, alongside sensitivity adjustment and Voice Match toggle.
  - **Persona & Hard Boundaries ("End-Line" Rules):** Customize personality tone (e.g., formal J.A.R.V.I.S.-style Egyptian, friendly Egyptian, concise tactical), adjust voice rate/pitch, and inspect or customize allowed vs. blocked actions.
  - **Permissions & Background Health Center:** One-stop checklist for Microphone, Default Assistant role, Accessibility Service, Display Over Other Apps, and Battery Optimization exemption.

## 4. Technical Architecture & Implementation Targets
- **Modular Android Studio Structure:**
  1. `assistant-system`: Android 7.0+ (`minSdk 24`) `VoiceInteractionService`, `VoiceInteractionSessionService`, `VoiceInteractionSession`, and `Assist` entry points.
  2. `voice-pipeline`: Local Wake Word detector, local Voice Match speaker verifier, offline Egyptian Colloquial Arabic STT, and offline Egyptian Arabic TTS.
  3. `local-intelligence`: Self-contained on-device NLU / quantized local SLM + Egyptian Colloquial Arabic intent grammar + J.A.R.V.I.S.-style Egyptian response generator (zero external API keys).
  4. `persona-guardrails`: Persona profile manager, hard "end-line" boundary rules, and deterministic typed command allowlist validator.
  5. `device-controller`: One-time permission manager, Android Intent dispatcher (apps, YouTube, media, web search), and permission-gated `AccessibilityService` executor.
  6. `settings-ui`: Comprehensive Settings UI for Default Assistant binding, Voice/Text wake-name enrollment, Voice Match management, Persona/Boundaries customization, and Permissions checklist.
- **Self-Contained Packaging & Hardware Reality:**
  - Document bundled native libraries (`.so`), JNI wrappers, acoustic models, and local Egyptian Arabic language assets inside the project (`Gradle` / `assets` / local app storage), along with realistic APK/AAB size, RAM footprint, CPU/NPU guidance, and OEM background/battery behavior on Android 7.0+.

## 5. Deliverables Expected in Google AI Studio
1. **Complete Zero-API-Key System Architecture:** End-to-end blueprint for **Cheetah Custom Assistant** running independently on-device with no external API keys.
2. **Step-by-Step Android Studio Guide:** Setup for `minSdk 24`, `AndroidManifest.xml`, bundled offline libraries/models, system assistant services, foreground microphone service, and permission flows.
3. **Production-Ready Kotlin & XML Templates:** Concrete implementations for system digital assistant registration, continuous mic + Wake Word + Voice Match flow, the comprehensive Settings screen (Default Assistant launcher, Voice & Text wake-name setup, Persona/Guardrails, Permissions hub), Egyptian Colloquial Arabic intent parser, and device/app action executor.
4. **Honest Engineering Trade-Offs & OS Limits:** Clear explanation of offline model/asset size, RAM requirements, Android sandbox/Accessibility limits, and OEM background throttling.