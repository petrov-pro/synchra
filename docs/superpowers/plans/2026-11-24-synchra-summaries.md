# Conversation History & Claude Summaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up the saved-conversation history, add a per-conversation screen, and let the customer get a Claude-written title + summary for a conversation (once per conversation).

**Architecture:** Room table `conversation_records` gains a `summary` column (migration 1→2). A `Summarizer` (Claude Haiku 5.5 via anthropic-java, structured JSON output) replaces the now-unused `ClaudeTranslationClient`. A `ConversationViewModel` owns one record's screen state (summary vs. original view, 3-attempt summarize, error pop-up); a `HistoryViewModel` owns the list. `SessionViewModel` remembers the id of the record its last session saved, for the main-screen Summary button.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room 2.8.4, anthropic-java 3.8.0, OkHttp MockWebServer, JUnit4, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-24-synchra-summaries-design.md`

Repo root for all paths: `/home/petroff/dev/android/synchra/.claude/worktrees/synchra-impl`. Package root `app/src/main/java/com/petroff/synchra` is written `M/`, unit tests `app/src/test/java/com/petroff/synchra` as `T/`, instrumented tests `app/src/androidTest/java/com/petroff/synchra` as `I/`.

Commands: unit `./gradlew -q :app:testDebugUnitTest --tests '<pattern>'`; instrumented `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` (emulator AVD `synchra_avd`, start with `~/Android/Sdk/emulator/emulator -avd synchra_avd -no-window -no-audio &` if `adb devices` doesn't list it).

## Global Constraints

- Storage is local only (Room); no sync.
- Default name of a record without a summary: date and time only, e.g. `24 Nov, 14:55` (no language pair).
- Record with a summary: list and screen title = Claude's title.
- Summary and title are written in the conversation's **target language** (`targetLanguageName`).
- Summary is generated only on demand and **never twice**: a stored summary is shown, Claude is not called.
- Claude model `claude-haiku-5-5`, key = existing "Anthropic API key" setting (`AppConfig.apiKey`, base URL `AppConfig.apiBaseUrl`).
- On Claude failure: **3 attempts in total**, then an error pop-up; on dismiss stay on / return to the screen where Summary was pressed (main → main, conversation → that conversation, original view). Never crash. Nothing is saved on failure.
- No API key: Summary shows a message pointing to Settings (Anthropic API key) and makes no request.
- Empty balance or invalid key are **not retried** and get plain messages (exact copy):
  - Claude balance: `Your Anthropic balance is empty. Top it up at console.anthropic.com.`
  - Claude key: `Your Anthropic API key is invalid. Check it in Settings.`
  - Soniox balance (402): `Your Soniox balance is empty. Top it up at console.soniox.com.`
  - Soniox key (401): `Your Soniox API key is invalid. Check it in Settings.`
- While summarizing: progress shown, no second request.
- DB migration 1→2 must keep existing records.
- No manual renaming; remove `setTitle`/`updateTitle`.
- UI strings in English, like the rest of the app ("History", "Summary", "Original").

## Review Focus

1. Very long conversation (an hour of speech, ~60k chars): the summary request must not time out — summarizer client timeout 60 s, `maxTokens` 2048. (Task 2, test `long conversation is sent whole`.)
2. Claude answers with non-JSON, missing fields, or blank title/summary (refusal, truncation): counts as a failed attempt, never crashes, nothing saved. (Task 2 `malformed answer is a failure`, Task 3 retries.)
3. Summary tapped twice quickly / while already summarizing: exactly one request in flight. (Task 3 `second tap while summarizing is ignored`.)
4. Leaving the conversation screen while a summary is in flight: no crash, nothing half-saved, and the next open can summarize again. (Task 3 `cancelled summary saves nothing`.)
5. The main-screen Summary target record was deleted in History: the button becomes unavailable instead of opening a missing record. (Task 4 `refreshLastRecord clears a deleted record`.)

---

### Task 1: Storage — summary column, migration, repository API, default name

**Files:**
- Modify: `M/history/ConversationRecord.kt`, `M/history/ConversationRecordEntity.kt`, `M/history/ConversationRecordDao.kt`, `M/history/HistoryRepository.kt`, `M/history/RoomHistoryRepository.kt`, `M/history/SynchraDatabase.kt`, `M/AppContainer.kt` (add migration to the builder), `T/session/FakeHistoryRepository.kt`
- Test: `T/history/ConversationRecordTest.kt`, `I/history/ConversationRecordDaoTest.kt`, `I/history/RoomHistoryRepositoryTest.kt`, create `I/history/SynchraDatabaseMigrationTest.kt`

**Interfaces:**
- Produces:
  - `ConversationRecord(..., translatedText: String, summary: String? = null)`; `fun ConversationRecord.displayTitle(): String` — `title` when `summary != null && !title.isNullOrBlank()`, else timestamp formatted `"d MMM, HH:mm"` (`Locale.getDefault()`, system zone).
  - `ConversationRecordEntity.summary: String?` (column `summary TEXT`, nullable).
  - DAO: `suspend fun getById(id: String): ConversationRecordEntity?`; `@Query("UPDATE conversation_records SET title = :title, summary = :summary WHERE id = :id") suspend fun updateSummary(id: String, title: String, summary: String)`; remove `updateTitle`.
  - `HistoryRepository`: `suspend fun getRecord(id: String): ConversationRecord?`, `suspend fun setSummary(id: String, title: String, summary: String)`; remove `setTitle`.
  - `SynchraDatabase` version 2; `val MIGRATION_1_2 = object : Migration(1, 2) { ... "ALTER TABLE conversation_records ADD COLUMN summary TEXT" }` (top-level in `SynchraDatabase.kt`); `AppContainer` builder `.addMigrations(MIGRATION_1_2)`.

- [ ] **Step 1: Write the failing tests**
  - `ConversationRecordTest`: replace the language-pair fallback tests with
    - `displayTitle is date and time only without a summary` — record timestamp `ZonedDateTime.of(2026,11,24,14,55,0,0, ZoneId.systemDefault())`, title `null`, summary `null` → `assertEquals(DateTimeFormatter.ofPattern("d MMM, HH:mm").format(thatDateTime), displayTitle())` and `assertFalse(contains("→"))`.
    - `displayTitle ignores a title without a summary` — title `"Old"`, summary `null` → date form.
    - `displayTitle is the Claude title once summarized` — title `"Встреча"`, summary `"..."` → `"Встреча"`.
  - `ConversationRecordDaoTest`: replace `updateTitleChangesOnlyTheTitle` with `updateSummaryStoresTitleAndSummary` (insert, `updateSummary("1","T","S")`, `getById("1")` has title `"T"`, summary `"S"`, texts unchanged) and `getByIdReturnsNullForUnknownId`.
  - `RoomHistoryRepositoryTest`: replace `setTitleUpdatesTheStoredTitle` with `setSummaryIsReturnedByGetRecordAndGetRecords`; extend the round-trip test to assert `summary == null` for a fresh record.
  - `SynchraDatabaseMigrationTest.upgradingAVersion1DatabaseKeepsRecords`: create a file DB `context.getDatabasePath("migration-test.db")` (delete first) with `SQLiteDatabase.openOrCreateDatabase`, execute the v1 schema `CREATE TABLE IF NOT EXISTS conversation_records (id TEXT NOT NULL, timestamp INTEGER NOT NULL, title TEXT, sourceLanguageName TEXT NOT NULL, targetLanguageName TEXT NOT NULL, sourceText TEXT NOT NULL, translatedText TEXT NOT NULL, PRIMARY KEY(id))`, insert one row (`"r1"`, `"Hello"`, `"Привет"`), `version = 1`, close; then `Room.databaseBuilder(context, SynchraDatabase::class.java, "migration-test.db").addMigrations(MIGRATION_1_2).build()` → `getAll()` returns that row with its texts and `summary == null`.

- [ ] **Step 2: Run them to verify they fail**
  Unit: `--tests '*ConversationRecordTest*'` → FAIL (compile: no `summary`). Instrumented: compile fails.

- [ ] **Step 3: Implement the Interfaces above.** Update `FakeHistoryRepository` (unit test double) to the new interface (`getRecord`, `setSummary` updating its list); delete the old `displayTitle` KDoc wording about the customer's own title.

- [ ] **Step 4: Run to verify they pass**
  Unit `--tests '*ConversationRecordTest*'` PASS; instrumented classes `com.petroff.synchra.history.ConversationRecordDaoTest`, `...RoomHistoryRepositoryTest`, `...SynchraDatabaseMigrationTest` PASS.

- [ ] **Step 5: Commit** — `git commit -m "Store conversation summaries (Room v2 migration) and name unsummarized records by date"`

---

### Task 2: `ClaudeSummarizer` (replaces the translation client)

**Files:**
- Create: `M/summary/Summarizer.kt`, `M/summary/ClaudeSummarizer.kt`, `T/summary/ClaudeSummarizerTest.kt`
- Delete: `M/translate/ClaudeTranslationClient.kt`, `M/translate/Translator.kt`, `T/translate/ClaudeTranslationClientTest.kt`
- Modify: `app/src/debug/res/xml/network_security_config.xml` (comment: mention MockWebServer-based tests generally, not `ClaudeTranslationClient`)

**Interfaces:**
- Produces:
  - `data class ConversationSummary(val title: String, val summary: String)`
  - `interface Summarizer { suspend fun summarize(sourceText: String, translatedText: String, targetLanguageName: String): Result<ConversationSummary> }` — one attempt, never throws.
  - `class ClaudeSummarizer(baseUrl: String, apiKeyProvider: () -> String?, maxRetries: Int = 0) : Summarizer` — retries are the caller's job (Task 3), so the SDK does none by default.
  - `class SummaryException(val kind: Kind, message: String) : Exception(message)` with `enum class Kind { OUT_OF_CREDIT, INVALID_KEY, TEMPORARY }` — the failure inside `Result`. Classification: HTTP 401/403 → `INVALID_KEY`; HTTP 402, or any 4xx whose error message contains `credit balance` (Anthropic answers an empty balance with 400 "Your credit balance is too low…") → `OUT_OF_CREDIT`; everything else (network, 5xx, 429, 529, malformed answer) → `TEMPORARY`. Read the status from the SDK's `AnthropicServiceException.statusCode()`.

- [ ] **Step 1: Write the failing tests** (pattern: existing `ClaudeTranslationClientTest` in git history `c2dd85d` — MockWebServer, `baseUrl = server.url("/").toString().trimEnd('/')`, Anthropic message JSON response; the SDK sends the key as `Authorization: Bearer <key>`)
  - `returns title and summary parsed from Claude's JSON` — response text `{"title":"Встреча","summary":"Обсудили сроки."}` → `ConversationSummary("Встреча","Обсудили сроки.")`; request path `/v1/messages`, body contains `"model":"claude-haiku-5-5"`, `"type":"json_schema"`, `"thinking":{"type":"disabled"}`, `Russian` (target language), the source text and the translation.
  - `transcription-only conversation sends only the source text` — `translatedText = ""` → body contains the source and not `TRANSLATION:`.
  - `long conversation is sent whole` — 60_000-char source → body contains its last 20 chars.
  - `malformed answer is a failure` — response text `not json` → `isFailure`; and `{"title":"","summary":"x"}` → `isFailure`.
  - `server error is a failure` — 500 → `isFailure`, kind `TEMPORARY`, exactly 1 request (`maxRetries = 0`).
  - `empty balance is OUT_OF_CREDIT` — 400 `{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}` → kind `OUT_OF_CREDIT`.
  - `rejected key is INVALID_KEY` — 401 `{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}` → kind `INVALID_KEY`.
  - `missing api key fails without a request` — provider `{ null }` → `isFailure`, `server.requestCount == 0`.

- [ ] **Step 2: Run to verify failure** — `--tests '*ClaudeSummarizerTest*'` → compile FAIL.

- [ ] **Step 3: Implement `ClaudeSummarizer`.** Same SDK setup as the old translation client (cached client per key; `AnthropicOkHttpClient.builder().apiKey().baseUrl().maxRetries().timeout(Duration.ofSeconds(60))`), `.model("claude-haiku-5-5")`, `.maxTokens(2048L)`, `ThinkingConfigDisabled`, `OutputConfig.builder().effort(LOW).format(JsonOutputFormat with schema {title: string, summary: string}, both required, additionalProperties false)`. System prompt: summarize the conversation in `$targetLanguageName`; title ≤ 60 characters, one line; summary a few short paragraphs or bullet points with the key points, decisions and action items; reply only with the JSON. User message `ORIGINAL:\n$sourceText` plus `\n\nTRANSLATION:\n$translatedText` only when non-blank. Parse the first text block with `kotlinx.serialization.json.Json.parseToJsonElement`; blank title or summary → failure. Wrap everything in `runCatching` on `Dispatchers.IO`.

- [ ] **Step 4: Run to verify pass** — `--tests '*ClaudeSummarizerTest*'` PASS; whole unit suite still compiles (`./gradlew -q :app:testDebugUnitTest`).

- [ ] **Step 5: Commit** — `git commit -m "Add ClaudeSummarizer (title + summary via structured output); drop unused translation client"`

---

### Task 3: `ConversationViewModel` — one record's screen state

**Files:**
- Create: `M/history/ConversationViewModel.kt`, `T/history/ConversationViewModelTest.kt`, `T/summary/FakeSummarizer.kt`

**Interfaces:**
- Consumes: `HistoryRepository.getRecord/setSummary` (Task 1), `Summarizer` (Task 2), `ConfigRepository.getConfig().apiKey`, `ViewMode` (`M/session/ViewMode.kt`).
- Produces:
  - `enum class ConversationView { SUMMARY, ORIGINAL }`
  - `data class ConversationUiState(val record: ConversationRecord? = null, val view: ConversationView = ConversationView.ORIGINAL, val viewMode: ViewMode = ViewMode.BOTH, val isSummarizing: Boolean = false, val errorMessage: String? = null, val closeOnErrorDismiss: Boolean = false)`
  - `class ConversationViewModel(recordId: String, historyRepository: HistoryRepository, summarizer: Summarizer, configRepository: ConfigRepository, summarizeOnOpen: Boolean) : ViewModel()` with `val uiState: StateFlow<ConversationUiState>`, `fun onSummaryClick()`, `fun onOriginalClick()`, `fun setViewMode(mode: ViewMode)`, `fun dismissError()`.
  - Behavior: on init load the record; `view = SUMMARY` if it has a summary. `summarizeOnOpen` (main-screen Summary) runs `onSummaryClick()` after loading and sets `closeOnErrorDismiss = true` for its failure. `onSummaryClick`: stored summary → just `view = SUMMARY`; already summarizing → ignore; blank Anthropic key → `errorMessage = "Set your Anthropic API key in Settings to make summaries."`; else up to `SUMMARY_ATTEMPTS = 3` calls, first success → `setSummary` then state record updated + `view = SUMMARY`; all failed → `errorMessage = "Couldn't make the summary. Please try again later."`, view stays `ORIGINAL`. A failure of kind `OUT_OF_CREDIT` or `INVALID_KEY` stops the attempts at once and shows the exact Global Constraints copy for it. `dismissError` clears `errorMessage` (the UI reads `closeOnErrorDismiss` to navigate back).
  - `FakeSummarizer(results: MutableList<Result<ConversationSummary>>)` with `var calls: Int` and an optional `gate: CompletableDeferred<Unit>?` to hold a call open.

- [ ] **Step 1: Write the failing tests** (use `StandardTestDispatcher` + `Dispatchers.setMain`, `FakeHistoryRepository`, `DefaultConfigRepository(FakeSettingsStore(), "https://api.anthropic.com", DEFAULT_LANGUAGE_PAIRS)` with `setApiKey("sk-ant")`)
  - `record without summary opens on the original`
  - `record with summary opens on the summary and Summary never calls Claude` — `onOriginalClick()` → ORIGINAL; `onSummaryClick()` → SUMMARY; `summarizer.calls == 0`.
  - `summary success is saved and shown` — `FakeHistoryRepository` record now has title/summary; `view == SUMMARY`; `record.displayTitle() == title`.
  - `two failures then success is a success` — results `[fail, fail, ok]` → saved, `calls == 3`, no error.
  - `three failures show the error and save nothing` — `calls == 3`, `errorMessage != null`, `view == ORIGINAL`, record summary still null, `closeOnErrorDismiss == false`; `dismissError()` clears it.
  - `summarizeOnOpen failure asks to close on dismiss` — `closeOnErrorDismiss == true`.
  - `missing Anthropic key shows the Settings message without calling Claude`.
  - `empty balance is not retried and shows the top-up message` — results `[OUT_OF_CREDIT]` → `calls == 1`, `errorMessage == "Your Anthropic balance is empty. Top it up at console.anthropic.com."`.
  - `invalid key is not retried` — `calls == 1`, key message.
  - `second tap while summarizing is ignored` — gate held open, `onSummaryClick()` twice → `calls == 1`, `isSummarizing == true`.
  - `cancelled summary saves nothing` — gate held, `viewModel.viewModelScope.cancel()` (or clear via `ViewModelStore`), advance → record summary still null.

- [ ] **Step 2: Run to verify failure** — `--tests '*ConversationViewModelTest*'` FAIL (compile).
- [ ] **Step 3: Implement** per the Interfaces.
- [ ] **Step 4: Run to verify pass** — PASS.
- [ ] **Step 5: Commit** — `git commit -m "Add ConversationViewModel: summary/original views, 3-attempt summarize, error handling"`

---

### Task 4: `HistoryViewModel` and the main-screen Summary target

**Files:**
- Create: `M/history/HistoryViewModel.kt`, `T/history/HistoryViewModelTest.kt`
- Modify: `M/session/SessionUiState.kt`, `M/session/SessionViewModel.kt`, `T/session/SessionViewModelTest.kt`

**Interfaces:**
- Produces:
  - `class HistoryViewModel(historyRepository: HistoryRepository) : ViewModel()` with `val records: StateFlow<List<ConversationRecord>>`, `val searchQuery: StateFlow<String>`, `fun refresh()`, `fun setSearchQuery(query: String)` (blank → `getRecords()`, else `search(query)`), `fun delete(id: String)` (then refresh).
  - `SessionUiState.lastRecordId: String? = null` — id of the record saved by the last finished session in this app run; set after `addRecord`, cleared at `start()`.
  - `SessionViewModel.refreshLastRecord()` — clears `lastRecordId` if `historyRepository.getRecord(it) == null`.

- [ ] **Step 1: Write the failing tests**
  - `HistoryViewModelTest`: `refresh loads newest first`, `search filters and blank search shows all`, `delete removes the record`.
  - `SessionViewModelTest`: `finished session exposes the saved record id` (start, finish with text → `lastRecordId == historyRepository.addedRecords.single().id`); `silent session leaves no record id` (finish with blank text → null); `start clears the previous record id`; `refreshLastRecord clears a deleted record`.
- [ ] **Step 2: Run to verify failure** — `--tests '*HistoryViewModelTest*' --tests '*SessionViewModelTest*'` FAIL.
- [ ] **Step 3: Implement** per the Interfaces.
- [ ] **Step 4: Run to verify pass** — PASS, full unit suite PASS.
- [ ] **Step 5: Commit** — `git commit -m "Add HistoryViewModel; remember the last saved record for the Summary button"`

---

### Task 5: Screens and navigation

**Files:**
- Create: `M/ui/TranscriptPanels.kt` (move `TextPanel` + the panel `Column` out of `MainScreen.kt`), `M/ui/ConversationScreen.kt`, `I/ui/ConversationScreenTest.kt`
- Modify: `M/ui/MainScreen.kt`, `M/ui/HistoryScreen.kt` (no API change; it already uses `displayTitle()`), `M/ui/SynchraApp.kt`, `M/AppContainer.kt` (expose `val summarizer: Summarizer = ClaudeSummarizer(configRepository.getConfig().apiBaseUrl, { configRepository.getConfig().apiKey })`), `I/ui/MainScreenTest.kt`, `I/ui/HistoryScreenTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  - `@Composable fun TranscriptPanels(viewMode: ViewMode, sourceText: String, translatedText: String, modifier: Modifier = Modifier)` — keeps the `sourceTranslationDivider` test tag.
  - `@Composable fun ConversationScreen(state: ConversationUiState, onBack: () -> Unit, onSummaryClick: () -> Unit, onOriginalClick: () -> Unit, onViewModeChange: (ViewMode) -> Unit, onDismissError: () -> Unit)` — top bar: back, title `record.displayTitle()`, action `TextButton` "Summary" (when `view == ORIGINAL`) / "Original" (when `view == SUMMARY`), disabled while summarizing. Body: SUMMARY → title (`headlineSmall`) + summary text, scrollable; ORIGINAL → the three-way view switch (same labels as main: Both/Transcription/Translation) + `TranscriptPanels`. `CircularProgressIndicator` with text "Making summary…" while summarizing. `AlertDialog` with `errorMessage` and an "OK" button → `onDismissError`.
  - `MainScreen` new params: `onOpenHistory: () -> Unit = {}`, `onSummary: () -> Unit = {}`; a `Row` under Start/Stop with `OutlinedButton("History")` and `OutlinedButton("Summary", enabled = !uiState.isListening && uiState.lastRecordId != null)`.
  - Routes: `"history"`, `"conversation/{id}?summarize={summarize}"` (`summarize` boolean, default false). Main Summary → `conversation/$lastRecordId?summarize=true`; History row → `conversation/$id`. On `onDismissError`, if `state.closeOnErrorDismiss` → `popBackStack()`. `ConversationViewModel` via `viewModel(key = "conversation-$id", factory = ...)`; `HistoryViewModel.refresh()` and `SessionViewModel.refreshLastRecord()` in the existing `LaunchedEffect(backStackEntry)`.

- [ ] **Step 1: Write the failing UI tests**
  - `ConversationScreenTest`: `originalViewShowsTextsAndSummaryButton` (record without summary → "Summary" button, source & translation text visible, "Both" switch present); `summaryViewShowsTitleSummaryAndOriginalButton`; `tappingOriginalAndSummaryInvokesCallbacks`; `errorShowsPopupAndOkDismisses` (state with `errorMessage` → text visible; "OK" → `onDismissError` called); `progressShownAndButtonDisabledWhileSummarizing`.
  - `MainScreenTest`: `summaryButtonDisabledWithoutLastRecordAndWhileListening`; `historyAndSummaryButtonsInvokeCallbacks` (`lastRecordId = "r1"`).
  - `HistoryScreenTest`: `unsummarizedRecordIsNamedByDateAndSummarizedByTitle`.
- [ ] **Step 2: Run to verify failure** — `./gradlew -q :app:compileDebugAndroidTestKotlin` fails.
- [ ] **Step 3: Implement** per the Interfaces; MainScreen keeps all existing behavior (view-mode switch key gating, context button).
- [ ] **Step 4: Run to verify pass** — full unit suite and full `connectedDebugAndroidTest` on the emulator PASS.
- [ ] **Step 5: Manual check on the phone** (POCO, serial `4L9HDA9H9DKREIDM`, `ANDROID_SERIAL=4L9HDA9H9DKREIDM ./gradlew -q installDebug`): app starts with existing history intact (migration), History list shows date names, a session → Stop → Summary produces a Russian title + summary, reopening shows it without a new request (`adb logcat | grep Synchra/Claude` shows one call).
- [ ] **Step 6: Commit** — `git commit -m "Add History and conversation screens with Summary/Original and the main-screen Summary button"`

---

### Task 6: Plain Soniox balance / key errors

**Files:**
- Modify: `M/soniox/SonioxTranscript.kt`, `T/soniox/SonioxTranscriptTest.kt`, `T/soniox/SonioxSpeechTranslatorTest.kt`

**Interfaces:**
- Produces: `SonioxEvent.Error.message` — for `error_code` 402 the Soniox balance copy, for 401 the Soniox key copy (Global Constraints); other codes keep `"Soniox <code>: <error_message>"`. `SonioxSpeechTranslator` already stops audio, reports `onError` and finishes the session on any `Error` — unchanged.

- [ ] **Step 1: Write the failing tests** — `SonioxTranscriptTest`: `empty balance becomes the top-up message` (402 → exact copy), `rejected key becomes the settings message` (401 → exact copy), `other errors keep the API text` (500 → contains `"Soniox 500"`). Update `SonioxSpeechTranslatorTest.a server error is reported and the session still finishes` to expect the 402 copy.
- [ ] **Step 2: Run to verify failure** — `--tests '*Soniox*'` FAIL.
- [ ] **Step 3: Implement** the mapping in `SonioxTranscript.apply`.
- [ ] **Step 4: Run to verify pass** — `--tests '*Soniox*'` PASS.
- [ ] **Step 5: Commit** — `git commit -m "Show plain messages for an empty Soniox balance or invalid key"`

