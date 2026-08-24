# TransyncAI — Real-Time Speech Translation (EN→RU) — Design

Date: 2026-08-24
Status: Approved for planning

## Purpose

Android app that translates spoken English into Russian in near real time.
Speech recognition runs on-device (fast, no network round-trip for ASR);
translation of the recognized text is done via a cheap, fast cloud model
(Kimi K2.5). Target device for manual testing: POCO X6 Pro 5G.

## Requirements & constraints

- Source language for v1: English. Target: Russian.
- Language selection must be a real dropdown, driven by config data, not
  hardcoded to EN/RU — so adding language pairs later is a config change,
  not a code change.
- UI has a **Start/Stop button** for the mic (continuous always-on while
  running, not push-to-talk). Start opens the mic and begins streaming;
  Stop closes it and ends the session.
- UI has a **view mode switch** with three states: Both (transcription +
  translation), Transcription only, Translation only. In Transcription
  only mode, recognized text is **not** sent to the Kimi API at all — no
  wasted translation calls when the user only wants the source text.
- UI shows the panels selected by the current view mode.
- Kimi API key entered once by the user and stored on-device (Android
  Keystore-backed `EncryptedSharedPreferences`), never hardcoded or
  committed to source.
- Settings (context buffer size, supported language list, API base URL)
  must be dynamic: build-time defaults sourced like environment variables,
  overridable at runtime without a rebuild.
- Code follows SOLID; implementation follows TDD (red → green → refactor)
  for all non-UI, non-ASR logic.
- e2e testing via an Android emulator, in addition to on-device manual
  testing (emulator timing does not represent real hardware latency).

## Architecture

```
🎤 Mic
  │
  ▼
SpeechRecognizer (interface)
  └─ impl: SherpaOnnxAsrEngine
     (Sherpa-ONNX streaming Zipformer, English, INT8, on-device)
  │  partial/final source-language text, ~every 0.5–1s
  ▼
ContextBuffer
  (last N finalized phrases + current in-progress phrase;
   N = configurable buffer size)
  │  rolling context + current phrase
  ▼
Translator (interface)
  └─ impl: KimiTranslationClient
     (HTTPS POST to Kimi K2.5: context + current phrase + source/target
      language codes → translated text)
  │  translated text
  ▼
SessionViewModel
  (orchestrates the above, exposes UI state)
  │
  ▼
Compose UI
  (language dropdown, Start/Stop button, view mode switch,
   source-text panel, translation panel, settings entry point)
```

`Translator` is only invoked when the current view mode is Both or
Translation-only; in Transcription-only mode `SessionViewModel` skips the
Kimi call entirely for that segment.

## Components

| Component | Responsibility | Notes |
|---|---|---|
| `SpeechRecognizer` (interface) | Abstraction over any ASR engine | Enables swapping engines without touching callers (open/closed, dependency inversion) |
| `SherpaOnnxAsrEngine` | Implements `SpeechRecognizer` using Sherpa-ONNX streaming Zipformer INT8 | Model downloaded to app-private storage on first run, not bundled in APK |
| `ContextBuffer` | Holds rolling window of recent phrases + current phrase | Pure logic, no Android dependencies — fully unit-testable |
| `Translator` (interface) | Abstraction over any translation provider | Enables swapping providers later |
| `KimiTranslationClient` | Implements `Translator` via OkHttp HTTPS calls to Kimi K2.5 | Mockable via the interface for tests |
| `ConfigRepository` | Resolves settings: `BuildConfig` defaults (buffer size, language list, API base URL) overridable at runtime via `SharedPreferences` | Android's equivalent of env-var-driven config |
| `SessionViewModel` | Orchestrates mic → ASR → buffer → translation → UI state; holds `ViewMode` (Both / TranscriptionOnly / TranslationOnly) and gates the `Translator` call on it | Depends only on `SpeechRecognizer`/`Translator`/`ConfigRepository` abstractions |
| Compose UI (`MainScreen`) | Language dropdown, Start/Stop button, view mode switch, source/translation panels | Renders `SessionViewModel` state; panels shown depend on `ViewMode` |
| `SettingsScreen` | API key entry (Keystore-backed storage), buffer size, language list overrides | |

## Data flow

1. User taps **Start** → mic opens, session starts.
2. `SherpaOnnxAsrEngine` streams recognized English text (partial + final
   segments) roughly every 0.5–1s.
3. `ContextBuffer` appends each segment, maintaining the configured window.
4. If `ViewMode` is Both or TranslationOnly, `SessionViewModel` sends
   `{context, current_phrase, source_lang, target_lang}` to
   `KimiTranslationClient` on each new segment. If `ViewMode` is
   TranscriptionOnly, this step is skipped — no API call is made.
5. Translated Russian text (when requested) returns and updates UI state.
6. Compose UI re-renders the panel(s) selected by the current view mode.
7. User taps **Stop** → mic closes, session ends.
8. User may switch view mode at any time, including mid-session; it only
   affects future segments, not a re-translation of past ones.

## Dynamic settings

Two layers:

- **Build-time defaults** (env-style): `local.properties` (gitignored) →
  `BuildConfig` fields for default buffer size, default language list,
  Kimi API base URL.
- **Runtime overrides**: `ConfigRepository` reads `BuildConfig` defaults,
  but `SettingsScreen` can override buffer size and the language list at
  runtime via `SharedPreferences`, so retuning doesn't require a rebuild.
- Language list is a real `List<LanguagePair>` data structure driving the
  dropdown; EN→RU is the only enabled pair in v1, others may be listed as
  disabled/"coming soon" or simply absent until added to config.

## Error handling

- Kimi request fails or no network: inline error banner, session keeps
  running, buffer keeps accumulating, retry on next segment — never crash
  the session.
- ASR engine failure: toast notification, auto-restart the recognizer.
- Missing/invalid API key: listen button disabled, routes to Settings.

## Tech stack

- Kotlin, Jetpack Compose, single-activity app. (Considered Java + XML
  Views; Kotlin chosen because Compose — needed for smooth ~0.5–1s live
  text updates — is Kotlin-first, and coroutines fit the streaming
  ASR/translation flow more cleanly than manual threading.)
- `minSdk 26`, `targetSdk` latest stable.
- OkHttp for the Kimi HTTPS client.
- Package id: `com.petroff.transyncai`, app name **TransyncAI**.
- Permissions: `RECORD_AUDIO`, `INTERNET`.

## Testing

- **TDD unit tests** (red → green → refactor) for `ContextBuffer`,
  `ConfigRepository`, `KimiTranslationClient`, and `SessionViewModel`
  orchestration logic — `SpeechRecognizer`/`Translator` mocked via their
  interfaces.
- **E2E on emulator**: x86_64 Android emulator (API 34, Google APIs image),
  KVM-accelerated. Pre-recorded English WAV injected into the emulator's
  virtual microphone via ADB; asserts the pipeline runs end-to-end (ASR
  triggers → Kimi call fires → UI shows translated text) via Compose/UI
  Automator test APIs.
- **Manual on-device verification** on POCO X6 Pro 5G is still required
  before considering the app "fast" in practice — emulator timing does not
  represent real hardware audio latency.

## Build/dev environment

- No Android SDK/Gradle present on this machine at design time — will
  install Android cmdline-tools, platform, build-tools, Gradle wrapper,
  and an x86_64 emulator image (API 34) as part of implementation.
- Deploy/test on real device via ADB (USB or wireless debugging) to POCO
  X6 Pro 5G.

## Open items for implementation planning

- Exact Sherpa-ONNX Android integration path (prebuilt AAR vs. building
  the JNI bindings from source) — to be resolved in the implementation
  plan.
- Exact Kimi K2.5 API request/response shape — to be confirmed against
  Kimi's API docs during implementation.
