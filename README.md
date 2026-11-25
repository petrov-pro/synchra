# Synchra

Live AI translation for Android: speak into the phone, and Synchra shows what you said and its
translation as you talk, without waiting for the end of a sentence.

> **⚠️ This is a vibe-coding project.** The app was written almost entirely by an AI coding
> agent ([Claude Code](https://claude.com/claude-code)), with a human setting the goals,
> trying the builds on a real phone and steering the result. Plans and design notes from that
> process are kept in [`docs/superpowers`](docs/superpowers). It is a personal project, not a
> production-grade product: expect rough edges.

## What it does

- **Live transcription and translation.** The source and target languages are chosen in
  Settings from the 60 languages Soniox supports; the main screen shows the pair in short form
  (for example `En-Ru`).
- **Three view modes:** both texts side by side, transcription only or translation only.
  Transcription-only sessions skip translation altogether.
- **Context.** A global context (in Settings) and a per-conversation note ("topic, participants,
  terms") help recognition and translation of names and jargon.
- **Recording in the background.** A foreground service keeps listening while the screen is off
  or another app is open.
- **History.** Every session is saved on the device and can be reopened, copied (long press) or
  deleted.
- **Summaries.** A finished conversation can be summarized by Claude into blocks: topic,
  overview, decisions and results.

## Technology

| Area | What is used |
|------|--------------|
| Language & platform | Kotlin, Android (minSdk 26, targetSdk 37), Java 17 |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Architecture | ViewModels + Kotlin coroutines/Flow, a small manual DI container (`AppContainer`) |
| Speech recognition & translation | [Soniox](https://soniox.com) real-time API over WebSocket (OkHttp) |
| Summaries | Anthropic Claude (`claude-haiku-5-5`) via the official `anthropic-java` SDK |
| Storage | Room (conversation history), EncryptedSharedPreferences (API keys and settings) |
| Serialization | kotlinx.serialization |
| Build | Gradle (Kotlin DSL), Android Gradle Plugin, KSP |
| Tests | JUnit 4, kotlinx-coroutines-test, MockWebServer, Compose UI tests, Espresso |
| Tooling | Python ASR/translation benchmarks in [`tools/asr-bench`](tools/asr-bench) |

## Benchmarks

The engine choices were made with the host-side benchmarks in [`tools/asr-bench`](tools/asr-bench),
run on 2026-11-24 against the [Svarah](https://github.com/AI4Bharat/Svarah) corpus (Indian-accented
English, 200 clips ≈ 0.3 h of audio). WER is word error rate on the transcript — lower is better.

**Speech recognition (200 clips):**

| Engine | Where it runs | WER |
|--------|---------------|-----|
| sherpa-onnx streaming Zipformer (2023) — the first version of the app | on device | 42% |
| NVIDIA Nemotron streaming 0.6B (int8, sherpa-onnx) | on device | 12% |
| **Soniox real-time (stt-rt-v6)** — used now | cloud | chosen after side-by-side tests on the phone |

Nemotron cut errors from 42% to 12%, but Soniox did better still in live tests on the phone,
transcribes and translates in one stream and needs no ~650 MB on-device model, so the on-device
path was dropped. Streaming the 200 clips through Soniox costs about $0.04–0.07.

How to run the benchmarks is described in
[`tools/asr-bench/README.md`](tools/asr-bench/README.md).

## Getting started

1. Open the project in Android Studio (or use the Gradle wrapper) and build:

   ```sh
   ./gradlew assembleDebug
   ```

2. Install it on a phone, open **Settings** and enter:
   - a **Soniox API key** — required for transcription and translation;
   - an **Anthropic API key** — optional, needed only for summaries.

   Keys are stored encrypted on the device and sent only to Soniox and Anthropic.

3. Pick the languages, go back and press **Start**.

### Tests

```sh
./gradlew testDebugUnitTest          # unit tests
./gradlew connectedDebugAndroidTest  # UI and database tests, needs a device or emulator
```
