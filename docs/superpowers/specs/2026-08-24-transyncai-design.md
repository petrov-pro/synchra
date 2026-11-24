# TransyncAI — Real-Time Speech Translation (EN→RU) — Design

Date: 2026-08-24 (rewritten same day to reflect the real-time chunking architecture
and current implementation status — supersedes the original silence-triggered design)

## Goal

Build an Android application for **live English → Russian translation**.

The application runs on a **POCO X6 Pro 5G**.

The user speaks English into the phone microphone. The application must continuously
display the Russian translation with low latency.

The application must NOT wait for a long silence or for the entire sentence before
translating.

## Status legend

✅ Done · 🟡 Partial · ⬜ Not started

## Architecture

```text
Microphone
    ↓
Android AudioRecord
    ↓
Sherpa-ONNX
Streaming Zipformer English ASR
    ↓
English streaming text
    ↓
Soft-commit chunking buffer
    ↓
Translation queue (ordered)
    ↓
Kimi API (kimi-k2.6, thinking disabled)
English → Russian
    ↓
Russian translated text
    ↓
Android UI
```

## Components

### 1. Audio capture — ✅ Done

`MicAudioSource` (implements `AudioSource`), using Android `AudioRecord`:

- 16 kHz mono, 16-bit PCM (matches the Zipformer model's expected input).
- Continuous recording on its own background thread; runs until `stop()`.
- Not Android `SpeechRecognizer` — Sherpa-ONNX only, fully local.
- Audio capture never stops for translation: `SherpaOnnxAsrEngine.start()` spawns its
  own background init thread and returns immediately regardless of caller thread; ASR
  decoding and translation network calls run independently (see §5).

### 2. ASR — 🟡 Partial

`SherpaOnnxAsrEngine` (implements `SpeechRecognizer`), wrapping Sherpa-ONNX's streaming
Zipformer English INT8 model, fully on-device — no audio ever leaves the phone.

- ✅ Runs completely locally.
- ✅ Provides partial results (`AsrSegment(text, isFinal = false)`) and
  endpoint-finalized results (`isFinal = true`), matching the recognizer's own
  `isEndpoint()` signal.
- ⬜ **No explicit stable-vs-unstable token distinction from the ASR layer itself.**
  Sherpa-ONNX's transducer decoder doesn't expose per-word confidence/stability — the
  "unstable tail" concept described below is implemented one layer up, in the chunking
  buffer (§3/§4), not in the ASR wrapper. This is a deliberate simplification: treating
  the last N words of any partial update as provisionally unstable is a cheap, effective
  proxy that doesn't require per-word timestamps or a second model.
- Timestamps: not currently surfaced (`OnlineRecognizerResult` from Sherpa-ONNX can
  provide them; not wired through `AsrSegment` — not needed by anything downstream yet).

### 3/4. Translation buffer & chunking strategy — ✅ Done

**This is the core requirement and the part of the original design that was wrong.**
The first implementation triggered translation only on `isEndpoint()` (silence
detection). That was replaced with genuine soft-commit chunking:

`TranslationChunker` (pure Kotlin, no Android dependency, `app/src/main/java/com/petroff/transyncai/session/TranslationChunker.kt`):

- Tracks the growing ASR transcript word-by-word.
- Holds back the last `unstableTailWords` (default 2) words of any partial update as
  provisionally unstable, since a streaming transducer can still revise its most recent
  word(s) as more audio arrives.
- Once `minChunkWords` (configurable, default 8, settings range 3–15 — see §6b) new
  *stable* words have accumulated, emits that chunk for translation — **without waiting
  for a pause**.
- `onFinal()` (called when the recognizer's endpoint fires) flushes everything
  remaining, tail included, since an endpoint-finalized result is settled.
- Resets its committed-word counter on every endpoint (matches
  `SherpaOnnxAsrEngine`/`recognizer.reset(stream)` starting each new utterance's text
  from empty).

`isFinal`/endpoint detection is now only the **flush trigger** for whatever's left at
the end of an utterance — not the sole trigger for translating, matching the
requirement.

Avoiding extremely short chunks (e.g. "because", "and") is handled naturally: the
`minChunkWords` threshold means partial-update chunks are always at least that long;
only the end-of-utterance flush can produce a short tail, which is unavoidable (the
alternative — dropping trailing words — would lose real content).

### 5. Translation concurrency — ✅ Done

```text
Audio capture (background thread, MicAudioSource)
     ↓
ASR worker (background thread, SherpaOnnxAsrEngine's init thread)
     ↓
TranslationChunker (pure function calls from the ASR callback)
     ↓
Translation queue (Kotlin Channel, ordered, single consumer coroutine)
     ↓
Kimi worker (KimiTranslationClient, suspend fun on Dispatchers.IO)
     ↓
UI (StateFlow → Compose)
```

- ASR never waits on Kimi: `SherpaOnnxAsrEngine.start()` returns immediately; the ASR
  callback thread only *enqueues* a chunk (`Channel.trySend`, non-suspending) and
  continues decoding.
- While Kimi translates chunk #1, ASR keeps processing audio and building chunk #2 —
  genuinely concurrent, not just async-and-hope: the queue's single consumer coroutine
  processes chunks **in commit order**, so a later-committed chunk's network response
  arriving before an earlier one's can't scramble the displayed order.
- 🟡 Queue is currently unbounded (`Channel.UNLIMITED`) with no explicit cap or
  drop-oldest policy — see §10.

### 6. Kimi API — 🟡 Partial

`KimiTranslationClient` (implements `Translator`), OkHttp, OpenAI-compatible HTTP API.

- ✅ API key never hardcoded — stored via Keystore-backed `EncryptedSharedPreferences`,
  entered in Settings.
- ✅ Model: **`kimi-k2.6`** (not `kimi-k2.5`, which returns 404 — that model id doesn't
  exist on this account; verified directly against the live API. `kimi-k2.7-code[-highspeed]`
  are code-specialized, not a fit for natural-language translation; `kimi-k3` forces
  "thinking" mode on unconditionally, too slow for real-time short-phrase translation).
- ✅ Thinking/reasoning disabled via top-level `"thinking": {"type": "disabled"}` —
  *not* the OpenAI-SDK-style `chat_template_kwargs` wrapper originally assumed, which
  the live API silently ignores (verified: without the correct field, ~500-600
  "reasoning_tokens" were burned per two-word phrase — unacceptable latency).
- ✅ `temperature: 0.6` (this model rejects any other value when thinking is disabled;
  `1.0` when thinking is enabled — verified against the live API).
- 🟡 Prompt currently: *"You are a real-time interpreter. Translate CURRENT from
  {source} to {target}. Use CONTEXT only to disambiguate. Reply with only the
  translation, no extra text."* — does **not** yet explicitly instruct: preserve
  technical terminology / API names / code identifiers / product names; don't
  summarize; don't repeat already-translated text; handle incomplete phrases
  gracefully. This is real, not yet done — see §Remaining work.
- ✅ Uses previous context (`ContextBuffer`'s rolling window of completed utterances)
  to disambiguate the current chunk.

### 7. Context — ✅ Done (with one known simplification)

`ContextBuffer` holds the last N (configurable, default 6) **completed utterances**
(bounded by endpoint/isFinal, not by chunk) as rolling context for Kimi — not the
entire session history.

🟡 **Known gap**: if a single long utterance produces multiple chunks before its
endpoint fires (e.g. a 20-word sentence spoken without pausing), the *second* chunk's
context doesn't yet include the *first* chunk from the same in-progress utterance —
only completed-utterance context is available. In practice Kimi's per-chunk
instruction to "use CONTEXT only to disambiguate" combined with the chunk itself
generally produces coherent output, but true multi-chunk-within-one-utterance context
continuity is not implemented. Flagged as a deliberate scope boundary for this pass,
not an oversight.

### 8. Handling incomplete phrases — ✅ Done

- The chunker can and does hand the translator incomplete/mid-sentence phrases by
  design (§3/§4).
- ✅ The UI **appends** each new translated chunk to what's already displayed, rather
  than replacing it — `SessionViewModel`'s queue consumer does
  `translatedText = (previous + " " + newChunk).trim()` on each successful translation,
  so continuations read naturally instead of the display flickering/overwriting.

### 9. UI — 🟡 Partial

Current `MainScreen`:

```text
┌──────────────────────────────────────┐
│ TransyncAI                      ⚙    │
├──────────────────────────────────────┤
│ English → Russian                    │  (read-only; editable in Settings)
│ [Both] [Transcription] [Translation] │
│ [        Start / Stop        ]       │
├──────────────────────────────────────┤
│ Source (scrollable, auto-scrolls)    │
│ ...growing English text...           │
│                                       │
│ Translation (scrollable, auto-scr.)  │
│ ...growing Russian text...           │
└──────────────────────────────────────┘
```

- ✅ Both English and Russian shown (view-mode dependent: Both / Transcription-only /
  Translation-only).
- ✅ Both panels independently scrollable, auto-scroll to latest text — long transcripts
  no longer run off-screen with no way to see them.
- ✅ Errors shown inline (red banner).
- ✅ Language pair selection moved to Settings (not on the main screen) — main screen
  shows the active pair as read-only text.
- ⬜ Russian is **not** currently styled larger/more prominent than English — both
  panels use the same text style.
- ⬜ No separate "● Listening" / "● Translating" status indicators — only the
  Start/Stop button state and the (silent) in-flight network call. A chunk being
  translated isn't visually distinguished from an idle queue.

### 10. Error handling — 🟡 Partial

- ✅ Kimi failure: session keeps running, ASR keeps decoding, error surfaces as an
  inline banner (`errorMessage` in `SessionUiState`) — never crashes the session.
- ✅ Never loses microphone input: ASR and translation are fully decoupled (§5).
- ⬜ **No retry of failed translation chunks** — a failed chunk's text is simply
  dropped; the next chunk still attempts to send. Per the original requirement
  ("retry failed requests"), this is not yet implemented.
- ⬜ **No explicit queue depth cap / drop-oldest policy** — the translation queue is
  `Channel.UNLIMITED`. In practice this is unlikely to matter (each translation
  request/response is fast enough relative to chunk production that a backlog
  shouldn't build up), but it's not an enforced guarantee.
- 🟡 No network unavailability is not distinguished from other Kimi errors with a
  specific "Translation unavailable" string — the raw error message is shown instead
  (which is arguably more useful for debugging, but doesn't match the spec's exact
  wording).

### 11. Performance requirements — ⬜ Not measured

Target device: POCO X6 Pro 5G / Dimensity 8300-Ultra.

- No blocking network on the main thread: ✅ (`Dispatchers.IO` for OkHttp calls).
- No UI freezes: ✅ (Compose + coroutines throughout).
- Continuous ASR: ✅.
- 200–500ms ASR update latency, 1–2s perceived translation latency, CPU/RAM/battery
  under real continuous use: **not yet measured**. This needs a real on-device session
  with a real API key (the one thing that can't be verified on the emulator).

### 12. Privacy — ✅ Done

Only recognized text (chunks) is sent to Kimi. No audio (PCM/WAV) is ever uploaded —
`MicAudioSource` feeds samples directly into the on-device Sherpa-ONNX recognizer;
nothing else touches them.

## Settings screen — ✅ Done

Moved off the main screen entirely; `SettingsScreen` now holds:

- Kimi API key (masked input, Keystore-backed storage).
- Language pair dropdown (moved here from `MainScreen`).
- Context buffer size slider (2–20 completed phrases, default 6).
- **Translation chunk size slider** (3–15 words, default 8) — configures
  `TranslationChunker.minChunkWords` at runtime; `SessionViewModel` rebuilds its
  chunker from the live config value on every `start()`, so a change takes effect on
  the next session without needing a rebuild.

All three numeric/text settings follow the same two-layer pattern: a build-time
default in `local.properties` → `BuildConfig` field, overridable at runtime via
`SharedPreferences`.

## Tech stack

- Kotlin, Jetpack Compose, single-activity app.
- `minSdk 26`, `compileSdk`/`targetSdk 37`.
- Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin support — no separate
  `org.jetbrains.kotlin.android` plugin).
- OkHttp 4.12.0 + kotlinx.serialization for the Kimi client.
- Sherpa-ONNX prebuilt AAR (v1.13.6) — streaming Zipformer, English, INT8.
- Package id: `com.petroff.transyncai`, app name **TransyncAI**.
- Permissions: `RECORD_AUDIO`, `INTERNET`.

## Testing

- **Unit tests** (36 total): `ContextBuffer`, `ConfigRepository`, `TranslationChunker`
  (8 tests covering threshold behavior, unstable-tail handling, final-flush, reset),
  `KimiTranslationClient` (real request/response shape verified against the live API),
  `SessionViewModel` orchestration (11 tests, including chunk-triggered-without-isFinal
  and append-not-overwrite behavior) — all with `SpeechRecognizer`/`Translator` mocked
  via their interfaces.
- **Instrumented tests** (9 total) on an x86_64 emulator (API 34, KVM-accelerated):
  real Sherpa-ONNX decode of a bundled test WAV, real Compose UI rendering, and a full
  end-to-end test (real ASR → real `SessionViewModel`/chunker → real
  `KimiTranslationClient` → local `MockWebServer`, now using a repeating dispatcher
  since chunking can legitimately produce multiple translation calls per utterance).
- **Manual on-device verification** on the actual POCO X6 Pro 5G, with a real Kimi API
  key: done once for the pre-chunking architecture (confirmed the app installs, runs,
  and doesn't crash); **not yet redone against the chunking rewrite** — this is the
  next real-device check that matters most, since it's the only way to judge actual
  perceived latency (§11).

## Remaining work (honest list, not yet done)

1. **Kimi prompt**: add explicit instructions to preserve technical terms/API
   names/code identifiers/product names, forbid summarizing, forbid repeating
   already-translated text, and explicitly note the input may be an incomplete
   sentence.
2. **UI status indicators**: separate "● Listening" / "● Translating" indicators;
   Russian text styled as the primary/larger panel.
3. **Retry + bounded queue**: retry failed translation chunks (with backoff), cap the
   translation queue depth with a drop-oldest policy instead of unbounded growth.
4. **Within-utterance context continuity**: carry earlier chunks of the *same*
   still-in-progress utterance into later chunks' context, not just completed
   utterances.
5. **Real-device performance measurement**: ASR/translation latency, CPU/RAM/battery,
   dropped audio, translation error rate — with real continuous speech, especially
   technical vocabulary (Go, AWS, SQS, DynamoDB, Redis, Kubernetes, API, worker,
   consumer, producer, deployment, repository, pull request).
