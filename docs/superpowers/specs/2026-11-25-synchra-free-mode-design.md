# Synchra — Free mode (on-device recognition and translation) — Design

Date: 2026-11-25

## Why

Today Synchra only works with paid keys: Soniox (recognition + translation) and, for
summaries, Anthropic. The customer wants a **free variant next to the paid one**, so the app
is usable without paying anyone and without any account: recognition and translation run on
the phone. Both variants stay; the user picks one in Settings.

## Goals

- A **Free / Paid switch** in Settings. Each mode shows only its own fields.
- **Paid mode** = exactly today's behaviour (Soniox + Claude). No regressions.
- **Free mode** works with **no key at all**: on-device recognition + on-device translation.
- Recognition in several languages, not only English (multilingual model).

## Non-goals

- **Summaries in Free mode.** The Summary button is hidden in Free mode.
- Any cloud LLM in Free mode (Gemini, Groq, OpenRouter, keyless endpoints). Gemini was
  considered and dropped: the free quota (~20 requests/day on Flash, ~500 on Flash-Lite) is
  too small for live translation and could cut a conversation off.
- Gemini Nano / ML Kit GenAI on-device summaries (few devices, beta) — maybe later.
- Changing anything in Paid mode beyond the Settings layout.

## Anti-goals (a failure even if it "works")

- Free mode sending audio or text anywhere. In Free mode nothing about the conversation
  leaves the phone (only model downloads go over the network).
- A ~650 MB download starting without the user asking for it.
- Paid users losing their keys or settings after the update.

## Approaches considered

Translation in Free mode:

| | Approach | Trade-off |
|---|---|---|
| **A (picked)** | **ML Kit on-device translation** | Free, offline, no key, no quota; plainer quality, ignores context |
| B | Gemini Flash-Lite | Better quality, uses context; key + internet; free quota runs out mid-conversation |
| C | ML Kit + optional Gemini | Picked first, then dropped by the customer: quota too small to be worth the extra code |

Recognition: the old app had English-only `nemotron-speech-streaming-en-0.6b` (removed in
`e552d43`). The customer chose the **multilingual Nemotron 3.5 ASR Streaming 0.6B**
(40 locales incl. English, Russian, Ukrainian; FLEURS WER ~8% en, ~9.6% ru at 560 ms with
the language given). Same transducer layout (encoder/decoder/joiner/tokens), so the old
sherpa-onnx code from git history is reused.

## What the user sees

### Settings

Top of Settings: a segmented switch **Free | Paid**.

**Paid** (today's fields, unchanged): Soniox API key (required), Anthropic API key
(optional, summaries).

**Free**: a **Speech model** row: status `Not downloaded (≈650 MB)` / `Downloading 37%` /
`Ready`, with **Download** / **Cancel** / **Delete**. A note recommends Wi-Fi.

Shared fields (Global context, crash reports) stay below, as now.

```
Settings
[ Free | Paid ]
Speech model     Ready ✓            [Delete]
                 ≈650 MB, Wi-Fi recommended
Global context   ...
Send crash reports  [x]
                        [Save]
```

The mode switch applies on **Save**, like the other fields. Download / Cancel / Delete act
immediately. While recording, the mode switch and Delete are disabled with
"Stop recording first".

### Main screen

- Same as today in both modes (Start/Stop, view modes, context, history).
- **Free mode: the Summary button is hidden.**
- Free mode, model not downloaded → Start is disabled with
  "Download the speech model in Settings".
- Free mode, the pair's ML Kit translation models are missing → they download on Start
  (≈30 MB per language) with the status "Preparing translation…"; recognition starts when
  they're ready. Transcription-only view mode needs no ML Kit models.
- Notification text in Free mode: "Listening" (nothing is sent).

### Conversation screen (History)

- Free mode: the **Summary** button (generate) is hidden. A summary saved earlier in Paid
  mode is still shown, with its Summary/Original toggle — nothing is generated.

### Languages

- Paid: Soniox's 60 languages, as now.
- Free source language: Nemotron 3.5's 19 "transcription-ready" + 13 "broad-coverage"
  locales (the 8 "adaptation-ready" ones need fine-tuning and are excluded), mapped to the
  app's ISO codes by language prefix (`en-US`/`en-GB` → `en`).
- Free target language: ML Kit Translation's supported languages.
- If the saved pair isn't supported in the current mode, the picker shows it as unavailable
  and Start is disabled with a message to pick another language. Switching modes never
  rewrites the saved languages.

## How it's built

### Seams that already exist

- `SpeechTranslator` (start/stop, `LiveTranscript` updates) — today only
  `SonioxSpeechTranslator`. `LiveSession` drives one.
- `Summarizer` / `SummaryJobs` — unchanged; only the UI hides the button in Free mode.

### New / restored pieces

- `config/Mode` enum `FREE | PAID`, stored in settings. **Default on upgrade:** `PAID` if
  a Soniox key is saved, else `FREE` (new installs start Free).
- `asr/` restored from git (`c2dd85d`): `SherpaOnnxAsrEngine`, `ModelFileProvider`,
  `ModelFiles`, `OkHttpFileDownloader` and their tests, switched to the Nemotron 3.5
  multilingual int8 560 ms model
  (`sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`, GitHub release
  `asr-models`, `.tar.bz2`: encoder.int8.onnx 627 MB, decoder 14 MB, joiner 9 MB,
  tokens.txt). The `.model-id` marker wipes an older model. The startup "legacy model
  cleanup" in `AppContainer` is removed.
- The recognizer gets the source language explicitly (sherpa per-stream `language`
  option). Verify it exists in the AAR's Kotlin/Java API first; if not, use auto-detect —
  the language list stays the same.
- sherpa-onnx AAR: latest release supporting Nemotron 3.5 (≥ the 1.13.6 used before), in
  `app/libs` as before.
- **Model download survives leaving the app:** WorkManager foreground (data-sync) worker
  with a progress notification, HTTP Range resume (per-file mirror preferred; otherwise
  resume the archive, then extract). Settings observes the work's progress.
- `translate/Translator` interface + `MlKitTranslator` (ML Kit on-device translation,
  model download per language).
- `session/TranslationChunker` restored from git (decides when a stable piece of text is
  translated; old ~8-word cadence).
- `local/LocalSpeechTranslator : SpeechTranslator` — mic → `SilenceGate` (only for the
  5-minute auto-stop) → sherpa recognizer → chunker → ML Kit → `LiveTranscript`.
  Transcription-only mode skips translation.
- `AppContainer` builds both engines; `LiveSession` gets a mode-dispatching
  `SpeechTranslator` that picks by `config.mode` at `start()` and sends `stop()` to the
  engine that started. `LiveSession.canStart` checks the current mode's needs (Paid: Soniox
  key; Free: model ready + pair supported).
- Settings UI: mode switch, per-mode fields, model download row.
- Main and Conversation screens: hide Summary in Free mode.
- Build: release as an App Bundle; `abiFilters` `arm64-v8a`, `armeabi-v7a`. Paid users
  carry the sherpa native libs (~15–20 MB per ABI after splitting); accepted.
- README: a "Free and paid modes" section.

## Testing

- Unit: mode default & migration; mode dispatcher (stop goes to the engine that started);
  `canStart` per mode; chunker (restored tests); `LocalSpeechTranslator` with fake
  recognizer/translator; language filters per mode.
- Instrumented: restored `SherpaOnnxAsrEngineInstrumentedTest` on a WAV clip (needs the
  model on the device), `ModelUpgradeInstrumentedTest`; Settings shows the right fields per
  mode; Summary hidden in Free.
- Bench (optional, host): add Nemotron 3.5 to `tools/asr-bench` and run it on Svarah to
  compare with the English model's 12%.

## Left to the builder

- Latest sherpa-onnx AAR version; ML Kit Translation dependency version.
- Download format: archive + on-device extraction, or individual files from a mirror.
- Chunk timing in `TranslationChunker` (start from the old values).
- Layout details of Settings within Material 3.
