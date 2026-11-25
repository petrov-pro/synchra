# Synchra Free Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Free mode next to the existing Paid mode: on-device recognition (sherpa-onnx + Nemotron 3.5 multilingual) and on-device translation (ML Kit), no keys, no summaries.

**Architecture:** `Mode` (FREE/PAID) lives in `AppConfig`. A new `LocalSpeechTranslator` implements the existing `SpeechTranslator` interface (mic → sherpa recognizer → `TranslationChunker` → ML Kit), and a `ModeSwitchingSpeechTranslator` picks Soniox or local per `start()`. The 650 MB model is fetched by a WorkManager foreground worker with HTTP Range resume, then extracted. UI: Free/Paid switch in Settings, model row, Summary hidden in Free.

**Tech Stack:** Kotlin, Jetpack Compose/Material 3, sherpa-onnx AAR v2.0.3, ML Kit Translation, WorkManager, Apache Commons Compress (bzip2/tar), OkHttp, JUnit4 + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-11-25-synchra-free-mode-design.md`

Old code to restore lives in git commit `c2dd85d` under `app/src/main/java/com/petroff/transyncai/…` (package was `com.petroff.transyncai`, now `com.petroff.synchra`). View any file with `git show c2dd85d:<path>`.

## Global Constraints

- Paid mode behaviour unchanged: Soniox + Claude, same keys, same texts.
- In Free mode nothing about the conversation leaves the phone; only model downloads use the network.
- The ~650 MB model download starts only when the user taps **Download**.
- Mode default when the `mode` key is absent: `PAID` if a Soniox key is saved, else `FREE`.
- Mode switch applies on **Save**; Download / Cancel / Delete act immediately; while recording the mode switch and Delete are disabled with the text `Stop recording first`.
- Model: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2` (475,271,763 bytes compressed); files used: `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`.
- sherpa-onnx AAR: `https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar` → `app/libs/sherpa-onnx-2.0.3.aar`.
- `abiFilters` = `arm64-v8a`, `armeabi-v7a`.
- Free source languages (ISO codes of the app's `SUPPORTED_LANGUAGES`): `en es fr it pt nl de tr ru ar hi ja ko vi uk pl sv cs no da bg fi hr sk zh hu ro et`.
- Free target languages: codes in ML Kit `TranslateLanguage.getAllLanguages()` that are also in `SUPPORTED_LANGUAGES`.
- UI copy (exact): Settings model row statuses `Not downloaded (≈650 MB)`, `Downloading 37%` (percent), `Ready`, `Download failed — tap Download to resume`; buttons `Download`, `Cancel`, `Delete`; note `Wi-Fi recommended`. Main errors: `Download the speech model in Settings`, `Pick a language the free mode supports`. Status `Preparing translation…`. Notification in Free: `Listening`.
- Code style: comments and identifiers in English, as in the project.

## Review Focus

1. **Process killed or network lost mid-download** → next Download resumes from the bytes already on disk (Range), never restarts at 0, and never treats a partial archive as a ready model. Test in Task 4.
2. **Stop pressed while ML Kit models or the recognizer are still loading** → no microphone opened, `onFinished` called exactly once, nothing saved if empty. Test in Task 6.
3. **Mode switched between start() and stop()** (Settings saved during a background recording is blocked in UI, but the dispatcher must still be safe) → `stop()` reaches the engine that started. Test in Task 7.
4. **Upgrade from the current Paid-only build** → user with a Soniox key stays in Paid with keys intact; legacy `filesDir/model` from the old English model (no matching `.model-id`) is wiped by the new model download, not by app start. Tests in Tasks 1 and 4.
5. **Saved language pair unsupported in Free** (e.g. source Afrikaans) → Start disabled with `Pick a language the free mode supports`; switching back to Paid restores Start without touching languages. Test in Task 7.

---

### Task 1: Mode in config

**Files:**
- Create: `app/src/main/java/com/petroff/synchra/config/Mode.kt`
- Modify: `app/src/main/java/com/petroff/synchra/config/AppConfig.kt`, `config/ConfigRepository.kt`
- Test: `app/src/test/java/com/petroff/synchra/config/ConfigRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class Mode { FREE, PAID }`; `AppConfig.mode: Mode`; `ConfigRepository.setMode(mode: Mode)`; settings key `"mode"` storing `"free"` / `"paid"`.

- [ ] **Step 1: Write failing tests** in `ConfigRepositoryTest`:
  - `modeDefaultsToPaidWhenSonioxKeySaved`: store has `soniox_api_key = "k"`, no `mode` → `getConfig().mode == Mode.PAID`.
  - `modeDefaultsToFreeOnFreshInstall`: empty store → `Mode.FREE`.
  - `setModePersists`: `setMode(Mode.FREE)` with a Soniox key present → `mode == Mode.FREE`, `sonioxApiKey == "k"` (keys untouched).
  - `unknownStoredModeFallsBackToDefault`: `mode = "garbage"`, no key → `Mode.FREE`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ConfigRepositoryTest*'` — expect compile failure (`Mode` unresolved).
- [ ] **Step 3: Implement** `Mode`, `AppConfig.mode` (default param `Mode.PAID` so other call sites compile), `DefaultConfigRepository` reading `KEY_MODE = "mode"` with the default rule from Global Constraints, `setMode`. Add `setMode` to any other `ConfigRepository` fakes (`grep -rn ': ConfigRepository' app/src`).
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `Config: Free/Paid mode, Paid kept for users with a Soniox key`.

### Task 2: Free-mode language rules

**Files:**
- Create: `app/src/main/java/com/petroff/synchra/config/ModeLanguages.kt`
- Test: `app/src/test/java/com/petroff/synchra/config/ModeLanguagesTest.kt`

**Interfaces:**
- Produces:
  - `val FREE_SOURCE_CODES: Set<String>` (the list in Global Constraints).
  - `fun sourceLanguagesFor(mode: Mode): List<Language>` — PAID: `SUPPORTED_LANGUAGES`; FREE: filtered by `FREE_SOURCE_CODES`, original order.
  - `fun targetLanguagesFor(mode: Mode, onDeviceTargetCodes: Set<String>): List<Language>` — FREE: filtered by `onDeviceTargetCodes`.
  - `fun isPairSupported(mode: Mode, source: Language, target: Language, onDeviceTargetCodes: Set<String>): Boolean`.

- [ ] **Step 1: Write failing tests:**
  - `paidSupportsEverything`: `sourceLanguagesFor(PAID) == SUPPORTED_LANGUAGES`.
  - `freeSourcesAreTheNemotronSet`: `sourceLanguagesFor(FREE).map { it.code }.toSet() == FREE_SOURCE_CODES` and contains `en`, `ru`, `uk`; does not contain `af`.
  - `everyFreeSourceCodeIsAnAppLanguage`: each of `FREE_SOURCE_CODES` has `languageForCode(it) != null` (catches a typo like `nb`).
  - `freeTargetsFollowOnDeviceList`: `targetLanguagesFor(FREE, setOf("ru","de")).map{it.code} == listOf("de","ru")` (app order).
  - `isPairSupported(FREE, af, ru, setOf("ru")) == false`; `(FREE, en, ru, setOf("ru")) == true`; `(PAID, af, ru, emptySet()) == true`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ModeLanguagesTest*'` — FAIL.
- [ ] **Step 3: Implement** in `ModeLanguages.kt` (pure Kotlin; ML Kit list is passed in, not read here).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `Languages available in Free mode`.

### Task 3: Restore the recognizer on sherpa-onnx 2.0.3 with Nemotron 3.5

**Files:**
- Add: `app/libs/sherpa-onnx-2.0.3.aar` (download from Global Constraints URL)
- Modify: `app/build.gradle.kts` (`implementation(files("libs/sherpa-onnx-2.0.3.aar"))`, `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }` in `defaultConfig`), `app/proguard-rules.pro` (`-keep class com.k2fsa.sherpa.onnx.** { *; }` — JNI looks classes/fields up by name)
- Create (restore from `c2dd85d`, new package): `asr/SpeechRecognizer.kt`, `asr/SherpaOnnxAsrEngine.kt`
- Create: `asr/RecognizerFactory.kt`
- Test (restore + adapt): `app/src/androidTest/java/com/petroff/synchra/asr/SherpaOnnxAsrEngineInstrumentedTest.kt`

**Interfaces:**
- Consumes: `AudioSource`, `MicAudioSource.SAMPLE_RATE`.
- Produces:
  - `data class AsrSegment(val text: String, val isFinal: Boolean)`; `interface SpeechRecognizer { fun start(onSegment: (AsrSegment) -> Unit, onError: (Throwable) -> Unit); fun stop() }` (`onError` is new: recognizer creation can fail).
  - `class SherpaOnnxAsrEngine(audioSource: AudioSource, recognizerFactory: () -> OnlineRecognizer, language: String)`; after `createStream()` it calls `stream.setOption("language", language)`.
  - `fun createNemotronRecognizer(paths: ModelPaths): OnlineRecognizer` in `RecognizerFactory.kt` — restore the old config from `git show c2dd85d:app/src/main/java/com/petroff/transyncai/AppContainer.kt` (feature 16 kHz/80, transducer paths, `numThreads = 4`, endpoint rules 1.2 s / 0.6 s). Take `modelType` from the model archive's `README.md` (old value `nemo_transducer`).
  - `ModelPaths` stays as in `c2dd85d` (`asr/ModelFiles.kt` is rewritten in Task 4; for this task create just `data class ModelPaths(encoder, decoder, joiner, tokens)`).

- [ ] **Step 1: Verify the AAR API.** Unzip `classes.jar` from the AAR and confirm `OnlineStream.setOption(String,String)`, `OnlineRecognizer(config=…)`, `EndpointRule`, `OnlineTransducerModelConfig` exist (`unzip -p app/libs/sherpa-onnx-2.0.3.aar classes.jar > /tmp/c.jar && unzip -l /tmp/c.jar | grep -i online`). If `setOption` is missing, pass `language = ""` (auto) and note it in the commit message.
- [ ] **Step 2: Restore the instrumented test** with the model pushed to the device (`adb push` the extracted dir to `/data/local/tmp/nemotron35/`; the test skips via `assumeTrue` if absent). Test `recognizesEnglishClip`: feed `test_wavs/en.wav` (from the archive) through `WavFileAudioSource` (exists in androidTest), assert the joined final text contains at least one expected word from the archive's README transcript.
- [ ] **Step 3: Run** `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.synchra.asr.SherpaOnnxAsrEngineInstrumentedTest` — FAIL (classes missing).
- [ ] **Step 4: Implement** the files above. `start()` runs recognizer creation on its own thread (as before) and reports exceptions to `onError` instead of crashing the thread; `stop()` before the recognizer is ready must not open the mic.
- [ ] **Step 5: Run** the instrumented test — PASS; `./gradlew assembleRelease` — succeeds (R8 rules OK).
- [ ] **Step 6: Commit** `On-device recognizer: sherpa-onnx 2.0.3 with Nemotron 3.5 multilingual`.

### Task 4: Model download — resumable, extracted, survives the app

**Files:**
- Create: `asr/ModelFiles.kt` (constants), `asr/ModelStore.kt`, `asr/ResumableDownloader.kt`, `asr/ModelArchiveExtractor.kt`, `asr/ModelDownloadWorker.kt`
- Modify: `app/build.gradle.kts` (`androidx.work:work-runtime-ktx`, `org.apache.commons:commons-compress`; latest stable versions), `AndroidManifest.xml` (`FOREGROUND_SERVICE_DATA_SYNC` permission; WorkManager's `SystemForegroundService` with `foregroundServiceType="dataSync"` via `tools:node="merge"`), `AppContainer.kt` (remove the legacy model cleanup block in `init`)
- Test: `app/src/test/java/com/petroff/synchra/asr/ModelStoreTest.kt`, `ResumableDownloaderTest.kt`, `ModelArchiveExtractorTest.kt`

**Interfaces:**
- Produces:
  - `ModelFiles.kt`: `MODEL_ARCHIVE_URL`, `MODEL_ID = MODEL_ARCHIVE_URL`, `MODEL_FILES = listOf("encoder.int8.onnx","decoder.int8.onnx","joiner.int8.onnx","tokens.txt")`.
  - `class ModelStore(modelDir: File)`: `fun isReady(): Boolean` (marker == `MODEL_ID` and all `MODEL_FILES` non-empty), `fun paths(): ModelPaths`, `fun prepareForDownload()` (wipes the dir if marker ≠ `MODEL_ID`, then writes the marker), `val archivePart: File` (`model.tar.bz2.part` in the dir), `fun delete()`.
  - `class ResumableDownloader(httpClient: OkHttpClient)`: `fun download(url: String, part: File, onProgress: (downloaded: Long, total: Long) -> Unit)` — if `part` has N bytes, sends `Range: bytes=N-`; on `206` appends, on `200` truncates and rewrites; throws on other codes; checks cancellation via `Thread.interrupted()`/coroutine `ensureActive` between buffers.
  - `object ModelArchiveExtractor { fun extract(archive: File, destDir: File, wanted: Set<String>) }` — bzip2+tar stream, writes only entries whose file name is in `wanted` (ignores the archive's top folder), each via `.part` + rename.
  - `class ModelDownloadWorker : CoroutineWorker` — `setForeground` with a progress notification (channel reused or new `model_download`), progress data key `"progress"` (0–100), unique work name `"speech-model-download"` (`ExistingWorkPolicy.KEEP`). Steps: `prepareForDownload` → download to `archivePart` → extract → delete archive. On failure returns `Result.failure()` keeping the `.part` for resume.
  - `class ModelDownloads(context)` (in the same file): `fun start()`, `fun cancel()`, `fun delete()` (cancel + `ModelStore.delete()`), `val state: Flow<ModelState>` where `sealed interface ModelState { NotDownloaded; Downloading(percent: Int); Ready; Failed }` built from WorkManager's `getWorkInfosForUniqueWorkFlow` + `ModelStore.isReady()`.

- [ ] **Step 1: Write failing tests:**
  - `ModelStoreTest.notReadyWhenMarkerIsForAnotherModel` (old English files + marker with the old HF URL → `isReady() == false`; after `prepareForDownload()` the dir holds only the new marker).
  - `ModelStoreTest.readyWhenMarkerAndAllFilesPresent`; `notReadyWhenAFileIsEmpty`.
  - `ResumableDownloaderTest.resumesFromExistingBytes`: part has `"hello "`, MockWebServer expects `Range: bytes=6-` and answers 206 `"world"` → part == `"hello world"`, last progress `(11, 11)`.
  - `ResumableDownloaderTest.restartsWhenServerIgnoresRange`: 200 with full body → part == full body.
  - `ResumableDownloaderTest.failsOnHttpError`: 500 → throws, part unchanged.
  - `ModelArchiveExtractorTest.extractsOnlyWantedFiles`: build a tiny `.tar.bz2` in the test (commons-compress) with `dir/tokens.txt`, `dir/README.md` → only `tokens.txt` in dest, content intact.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*asr*'` — FAIL.
- [ ] **Step 3: Implement** the classes; remove the legacy cleanup from `AppContainer.init`; expose `container.modelDownloads` and `container.modelStore`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Manual check on a device:** start the download, kill the app from recents at ~10%, reopen, Download → progress continues above 10%; at the end `Ready`.
- [ ] **Step 6: Commit** `Speech model download: resumable, in the background, extracted on device`.

### Task 5: TranslationChunker and ML Kit translator

**Files:**
- Create (restore): `session/TranslationChunker.kt`, test `app/src/test/java/com/petroff/synchra/session/TranslationChunkerTest.kt` (from `c2dd85d`, package renamed)
- Create: `translate/Translator.kt`, `translate/MlKitTranslator.kt`
- Modify: `app/build.gradle.kts` (`com.google.mlkit:translate`, latest stable)

**Interfaces:**
- Produces:
  - `TranslationChunker(minChunkWords = 8, unstableTailWords = 2)` as in `c2dd85d`.
  - `interface Translator { suspend fun prepare(source: String, target: String): Result<Unit>; suspend fun translate(text: String): Result<String>; fun close() }` — `prepare` downloads models if needed and opens the client for that pair.
  - `class MlKitTranslator : Translator` (ML Kit `Translation.getClient`, `downloadModelIfNeeded()` with no Wi-Fi condition, `await` via `kotlinx-coroutines-play-services` or `suspendCancellableCoroutine`).
  - `fun mlKitTargetCodes(): Set<String> = TranslateLanguage.getAllLanguages().toSet()` (in `MlKitTranslator.kt`).

- [ ] **Step 1:** Restore `TranslationChunkerTest`; run `./gradlew testDebugUnitTest --tests '*TranslationChunkerTest*'` — FAIL (class missing).
- [ ] **Step 2:** Restore `TranslationChunker`; run — PASS.
- [ ] **Step 3:** Implement `Translator`, `MlKitTranslator`, `mlKitTargetCodes`. Add an instrumented test `MlKitTranslatorInstrumentedTest.translatesEnglishToRussian` (needs network on first run): `prepare("en","ru")` succeeds, `translate("Good morning")` returns non-blank text different from the input.
- [ ] **Step 4: Run** the instrumented test — PASS.
- [ ] **Step 5: Commit** `On-device translation with ML Kit; restore the translation chunker`.

### Task 6: LocalSpeechTranslator

**Files:**
- Create: `local/LocalSpeechTranslator.kt`
- Test: `app/src/test/java/com/petroff/synchra/local/LocalSpeechTranslatorTest.kt` (with `FakeSpeechRecognizer`, `FakeTranslator` in the same test folder)

**Interfaces:**
- Consumes: `SpeechTranslator`, `LiveRequest`, `LiveTranscript` (`soniox/SonioxSpeechTranslator.kt`), `SpeechRecognizer`, `Translator`, `TranslationChunker`, `SilenceGate`.
- Produces: `class LocalSpeechTranslator(recognizerFactory: (language: String) -> SpeechRecognizer, translatorFactory: () -> Translator, scope: CoroutineScope, onPreparing: (Boolean) -> Unit = {}) : SpeechTranslator`.

Behaviour (the tests pin it):
- `start()`: if `targetLang != null`, calls `onPreparing(true)`, awaits `translator.prepare(source, target)`, then `onPreparing(false)`; failure → `onError("Couldn't download the translation model: …")` then `onFinished(empty)`. Then starts the recognizer.
- Source text = finalized phrases joined by `" "` + current partial. Translation text = translated chunks appended in spoken order (single consumer channel, as in the old `SessionViewModel.processTranslationQueue`).
- `SilenceGate` (`soniox/SilenceGate.kt`) is used only for the 5-minute auto-stop → `onAutoStopped()` then normal stop. `onSendingChanged` is never called with `true`.
- `stop()`: stops the recognizer, flushes `chunker.onFinal` of the current partial, waits for queued translations, then `onFinished` **exactly once**.
- Translation failure on a chunk → `onError(message)`; session continues.

- [ ] **Step 1: Write failing tests:**
  - `emitsSourceAndTranslationInOrder`: recognizer emits partial "one two … ten" (10 words), final; fake translator uppercases with a delay inverted per chunk → `onFinished` translation equals chunks in spoken order.
  - `transcriptionOnlyNeverPreparesOrTranslates` (`targetLang = null`): `FakeTranslator.prepareCalls == 0`, translation `""`.
  - `stopWhilePreparingNeverStartsRecognizer`: `prepare` suspends; call `stop()`; complete prepare → `FakeSpeechRecognizer.started == false`, `onFinished` called once.
  - `prepareFailureReportsAndFinishes`: `prepare` fails → one `onError` with `Couldn't download the translation model`, `onFinished` once.
  - `onFinishedExactlyOnceOnDoubleStop`.
  - `recognizerErrorIsReportedAndFinishes`: recognizer calls `onError` → `onError` + `onFinished` once.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*LocalSpeechTranslatorTest*'` — FAIL.
- [ ] **Step 3: Implement** `LocalSpeechTranslator`. The silence auto-stop timing: reuse `SilenceGate` the way `SonioxSpeechTranslator` does (read it first); the recognizer factory receives samples through a wrapping `AudioSource` the translator owns, so the gate sees them.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `LocalSpeechTranslator: on-device recognition and translation in one stream`.

### Task 7: Mode switching in the session

**Files:**
- Create: `session/ModeSwitchingSpeechTranslator.kt`
- Modify: `session/LiveSession.kt` (`hasSonioxKey` → `startProblem()`), `session/SessionUiState.kt` (`isPreparing: Boolean = false`, `mode: Mode = Mode.PAID`), `session/RecordingService.kt` (`recordingStatusText(isSending: Boolean, mode: Mode)`), `AppContainer.kt`
- Test: `app/src/test/java/com/petroff/synchra/session/ModeSwitchingSpeechTranslatorTest.kt`, `SessionViewModelTest.kt` (existing `LiveSession` tests live here), `RecordingNotificationTextTest.kt`

**Interfaces:**
- Consumes: `Mode`, `isPairSupported`, `mlKitTargetCodes`, `ModelStore.isReady()`, `LocalSpeechTranslator`.
- Produces:
  - `class ModeSwitchingSpeechTranslator(modeProvider: () -> Mode, paid: SpeechTranslator, free: SpeechTranslator) : SpeechTranslator` — remembers the engine chosen in `start()`; `stop()` goes to it.
  - `LiveSession(…, modelReady: () -> Boolean, onDeviceTargetCodes: () -> Set<String>)`. `startProblem(): String?` returns, in order: PAID & no Soniox key → `"Set your Soniox API key in Settings"` (today's text); FREE & !modelReady → `"Download the speech model in Settings"`; pair unsupported for the mode (target checked only when translation is requested) → `"Pick a language the free mode supports"`; else null. `canStart = startProblem() == null`.
  - `uiState.mode` mirrors `config.mode` (refreshed in `refreshCanStart()`), used by the UI to hide Summary.

- [ ] **Step 1: Write failing tests:**
  - `ModeSwitchingSpeechTranslatorTest.stopGoesToTheEngineThatStarted`: mode FREE at start, switch provider to PAID, `stop()` → free fake stopped, paid fake not.
  - `SessionViewModelTest.freeWithoutModelCannotStart` → `canStart == false`, `start()` sets `errorMessage == "Download the speech model in Settings"`.
  - `freeWithUnsupportedSourceCannotStart` (source `af`) → `"Pick a language the free mode supports"`; then `setMode(PAID)` + Soniox key + `refreshCanStart()` → `canStart == true`, languages unchanged.
  - `freeTranscriptionOnlyIgnoresTarget`: target not in on-device set, view `TRANSCRIPTION_ONLY` → `canStart == true`.
  - `freeNeedsNoSonioxKey`: FREE, model ready, `en→ru` supported, no keys → `canStart == true`.
  - `RecordingNotificationTextTest.freeModeSaysListening`: `recordingStatusText(false, Mode.FREE) == "Listening"`; Paid texts unchanged.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*session*'` — FAIL.
- [ ] **Step 3: Implement.** `AppContainer` builds `LocalSpeechTranslator(recognizerFactory = { lang -> SherpaOnnxAsrEngine(…, { createNemotronRecognizer(modelStore.paths()) }, lang) }, translatorFactory = ::MlKitTranslator, scope = appScope, onPreparing = { liveSession… })` — route `onPreparing` into `uiState.isPreparing` (simplest: `LiveSession` passes its own callback; pick one and keep `LocalSpeechTranslator`'s constructor as in Task 6). Pass `ModeSwitchingSpeechTranslator` to `LiveSession`.
- [ ] **Step 4: Run** all unit tests `./gradlew testDebugUnitTest` — PASS.
- [ ] **Step 5: Commit** `Session runs Soniox or on-device by mode`.

### Task 8: Settings, main and conversation screens

**Files:**
- Modify: `ui/SettingsScreen.kt`, `ui/SynchraApp.kt`, `ui/MainScreen.kt`, `ui/ConversationScreen.kt`, `ui/LanguagePicker.kt` (accept the language list to show)
- Test: `app/src/androidTest/java/com/petroff/synchra/ui/SettingsScreenTest.kt`, `MainScreenTest.kt`, conversation screen test (existing file in `ui/`)

**Interfaces:**
- Consumes: `Mode`, `ConfigRepository.setMode`, `ModelDownloads.state/start/cancel/delete`, `ModelState`, `sourceLanguagesFor`, `targetLanguagesFor`, `SessionUiState.mode/isPreparing/isListening`.
- Produces: `SettingsScreen(… , currentMode: Mode, onSaveMode: (Mode) -> Unit, modelState: ModelState, onDownloadModel, onCancelModel, onDeleteModel, isRecording: Boolean, sourceLanguages: List<Language>, targetLanguages: List<Language>)`; `MainScreen` hides the Summary `IconButton` when `uiState.mode == Mode.FREE` and shows `Preparing translation…` while `isPreparing`; `ConversationScreen(…, canSummarize: Boolean)` hides the generate button when false (toggle Summary/Original still shown if `record.summary != null`).

Settings layout (spec sketch): segmented `Free | Paid` (`SingleChoiceSegmentedButtonRow`, testTag `modeSwitch`) at the top, local state until Save. Paid → Soniox + Anthropic fields (unchanged). Free → "Speech model" `FieldRow` with status text and buttons per `ModelState` and note `Wi-Fi recommended`. Languages bar uses the lists for the **selected (unsaved)** mode. While `isRecording`: segmented switch and Delete disabled, supporting text `Stop recording first`. Save calls `onSaveMode` plus the existing saves.

- [ ] **Step 1: Write failing UI tests:**
  - `freeModeShowsModelRowNotKeys`: `currentMode = FREE` → node with text `Speech model` exists; `Soniox API key` does not.
  - `paidModeShowsKeysNotModelRow`.
  - `switchingModeAppliesOnSave`: click `Paid` segment → `onSaveMode` not called; click Save → called with `PAID`.
  - `modelStatesRender`: `NotDownloaded` → `Not downloaded (≈650 MB)` + `Download`; `Downloading(37)` → `Downloading 37%` + `Cancel`; `Ready` → `Ready` + `Delete`; `Failed` → `Download failed — tap Download to resume`.
  - `recordingDisablesModeAndDelete`: `isRecording = true`, `Ready` → `Delete` not enabled, `Stop recording first` shown.
  - `MainScreenTest.summaryHiddenInFree` / `summaryShownInPaid` (content description `Summary`).
  - Conversation: `canSummarize = false`, record without summary → no `Summary` button; record with summary → summary text shown.
- [ ] **Step 2: Run** `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.petroff.synchra.ui` — FAIL.
- [ ] **Step 3: Implement**; wire in `SynchraApp` (`canSummarize = config.mode == Mode.PAID`; Settings gets `container.modelDownloads.state.collectAsState`, `liveSession.uiState.isListening`; after Save call `liveSession.refreshCanStart()` as today).
- [ ] **Step 4: Run** — PASS. Also `./gradlew testDebugUnitTest` — PASS.
- [ ] **Step 5: Manual check on the phone:** fresh install → Free, Download model, speak English and Russian (source switched), translation appears live; airplane mode with models present → still works; switch to Paid → Soniox path and Summary as before.
- [ ] **Step 6: Commit** `Settings: Free/Paid switch and speech model; Summary hidden in Free`.

### Task 9: README and release build

**Files:**
- Modify: `README.md` (section "Free and paid modes": what each uses, model size, privacy — Free sends nothing; update Technology table with sherpa-onnx/Nemotron 3.5/ML Kit/WorkManager), `README.md` "Getting started" (Free needs no keys)
- Verify: `./gradlew bundleRelease`

- [ ] **Step 1:** Update README.
- [ ] **Step 2: Run** `./gradlew bundleRelease` — succeeds; `unzip -l app/build/outputs/bundle/release/*.aab | grep -c 'lib/x86'` → `0`.
- [ ] **Step 3: Commit** `README: free and paid modes`.
