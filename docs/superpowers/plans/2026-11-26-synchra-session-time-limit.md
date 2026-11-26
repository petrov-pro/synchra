# Session Time Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Paid-mode session with no touch in the app for the set time (default 1 h) stops and asks "Paused after 1 h. Continue?"; Continue appends to the same text and the same history record.

**Architecture:** The limit is a coroutine timer in `LiveSession`, re-armed by `onUserActivity()` (MainActivity touches). At the deadline the engine is stopped like a normal stop; when its `onFinished` arrives the session becomes paused (text saved under the session's record id). `continueSession()` starts the engine again with the saved text as a prefix. Engines are unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines (virtual time in tests), Room, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-26-synchra-session-time-limit-design.md`

## Global Constraints

- Paid mode only; Free mode never pauses.
- Setting values: Off (0) / 30 / 60 / 120 minutes; default 60.
- Dialog copy, exactly: `Paused after 1 h. Continue?` with buttons `Stop` and `Continue`; "1 h" is `limitLabel(minutes)`: 30 → `30 min`, 60 → `1 h`, 120 → `2 h`.
- Background notification copy: `Paused after 1 h — tap to continue` (same label), opens the app.
- Only touches inside the app reset the timer.
- One history record per conversation across any number of Continue.

## Review Focus

- Continue pressed before the stopped engine's `onFinished` arrived (Soniox flushes the last phrase late) — the flushed text must not be lost: the dialog appears only after `onFinished` (Task 3 test `pause waits for the engine to finish`).
- Continue when Start is impossible (Soniox key removed meanwhile) — show the usual start error and stay paused, text kept (Task 3 test `continue without a key stays paused`).
- The 5-minute silence auto-stop fires before the limit — no pause dialog afterwards (Task 3 test `silence auto-stop cancels the limit`).
- Limit changed in Settings during a session — the new value applies from the next re-arm (touch) (Task 3 test `limit is read when re-armed`).
- Stop pressed while paused — no new engine session, record keeps the text (Task 3 test `stop while paused ends the conversation`).

---

### Task 1: Setting `sessionLimitMinutes`

**Files:**
- Modify: `app/src/main/java/com/petroff/synchra/config/AppConfig.kt`, `config/ConfigRepository.kt`
- Test: `app/src/test/java/com/petroff/synchra/config/ConfigRepositoryTest.kt`

**Interfaces:**
- Produces: `AppConfig.sessionLimitMinutes: Int = 60`; `ConfigRepository.setSessionLimitMinutes(minutes: Int)`; `val SESSION_LIMIT_CHOICES = listOf(0, 30, 60, 120)`; `fun limitLabel(minutes: Int): String` (in AppConfig.kt).

- [ ] **Step 1: Failing tests** — `session limit defaults to one hour` (`getConfig().sessionLimitMinutes == 60`); `session limit is saved` (set 30 → 30; set 0 → 0); `an unknown stored limit falls back to one hour` (store "45" → 60); `limit labels` (`limitLabel(30)=="30 min"`, `limitLabel(60)=="1 h"`, `limitLabel(120)=="2 h"`).
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*ConfigRepositoryTest*'` → FAIL (unresolved).
- [ ] **Step 3:** Implement: store key `session_limit_minutes` as string; values outside `SESSION_LIMIT_CHOICES` read as 60.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `Settings: session time limit value`.

### Task 2: History save is an upsert

**Files:**
- Modify: `history/ConversationRecordDao.kt` (`@Insert(onConflict = OnConflictStrategy.REPLACE)`), `history/HistoryRepository.kt` (doc: addRecord replaces a record with the same id; summary fields of the replaced record are overwritten — paused records have none), `app/src/test/.../session/FakeHistoryRepository.kt` (replace by id).
- Test: `app/src/androidTest/java/com/petroff/synchra/history/` existing Room test file if present, else none (covered through Task 3 with the fake).

**Interfaces:**
- Produces: `HistoryRepository.addRecord(record)` replaces an existing id.

- [ ] **Step 1:** Change DAO and fake; `./gradlew :app:testDebugUnitTest` → PASS (no behaviour change for new ids).
- [ ] **Step 2:** Commit `History: saving a record with an existing id replaces it`.

### Task 3: Pause / continue in `LiveSession`

**Files:**
- Modify: `session/LiveSession.kt`, `session/SessionUiState.kt`
- Test: `app/src/test/java/com/petroff/synchra/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 `sessionLimitMinutes`, Task 2 upsert.
- Produces: `SessionUiState.pausedAfterMinutes: Int?` (non-null = paused, dialog shown); `LiveSession.onUserActivity()`, `LiveSession.continueSession()`; `stop()` while paused clears the pause.

Behaviour to implement:
- `start()` makes a new `recordId` and clears the prefix (as today). It arms the timer when mode is PAID and limit > 0: `delay(limit)` then pause. `onUserActivity()` re-arms (cancel + launch, reading the limit again) while listening.
- Pause: crash log `session paused after limit`, `speechTranslator.stop()`, `isListening=false`, remember `pausing=true`. In that session's `onFinished`: save the record under `recordId` (prefix + text), then `pausedAfterMinutes = limit`.
- `onUpdate` / `onFinished` show `joinTexts(prefix, live)`: prefix, blank line, new text (no blank line when either side is empty).
- `continueSession()`: prefix = current source/translation; same as `start()` but keeps prefix and `recordId`, clears `pausedAfterMinutes`. If `startProblem()` is non-null: set `errorMessage`, stay paused.
- Silence auto-stop and `stop()` cancel the timer.

- [ ] **Step 1: Failing tests** (virtual time via the test dispatcher):
  - `paid session pauses after the limit without activity` — start; `advanceTimeBy(60.minutes)`; assert `speechTranslator.stopped`; `finish("a","A")`; assert `pausedAfterMinutes == 60`, record saved with "a".
  - `pause waits for the engine to finish` — after the deadline, before `finish`, `pausedAfterMinutes == null`.
  - `user activity resets the limit` — 50 min, `onUserActivity()`, 50 min → not stopped; 10 more → stopped.
  - `free mode and an off limit never pause` — 3 h → not stopped.
  - `continue appends to the earlier text` — pause + finish("a","A"); `continueSession()`; `startCount == 2`; `emit("b","B")` → `sourceText == "a\n\nb"`, `translatedText == "A\n\nB"`.
  - `continue keeps one history record with the whole text` — then `finish("b","B")`; one record, source `"a\n\nb"`.
  - `continue without a key stays paused` — clear Soniox key, `continueSession()` → `startCount == 1`, still paused, `errorMessage != null`, text kept.
  - `stop while paused ends the conversation` — `stop()` → `pausedAfterMinutes == null`, `startCount == 1`, one record.
  - `silence auto-stop cancels the limit` — `autoStop()`, `finish`, 2 h → `pausedAfterMinutes == null`.
  - `limit is read when re-armed` — 10 min, set limit 30, `onUserActivity()`, 30 min → stopped.
  - `a new start clears the earlier text` — after continue + stop, `start()`, `emit("c","C")` → `"c"`; finish → two records.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*SessionViewModelTest*'` → FAIL.
- [ ] **Step 3:** Implement as above.
- [ ] **Step 4:** Same command, then full `:app:testDebugUnitTest` → PASS.
- [ ] **Step 5:** Commit `Session: pause after the time limit, continue on the same text`.

### Task 4: Dialog, touches, Settings row

**Files:**
- Modify: `ui/MainScreen.kt` (dialog), `ui/SynchraApp.kt` (wire state/callbacks), `MainActivity.kt` (`dispatchTouchEvent` → `onUserActivity()`, then super), `ui/SettingsScreen.kt` (Paid-only row "Session time limit": radio/segmented choices `Off`, `30 min`, `1 h`, `2 h`; help text: stops a forgotten session so it doesn't keep spending, any touch in the app restarts the count).
- Test: `app/src/androidTest/java/com/petroff/synchra/ui/MainScreenTest.kt`

**Interfaces:**
- Consumes: `pausedAfterMinutes`, `continueSession()`, `stop()`, `limitLabel`, `setSessionLimitMinutes`.

- [ ] **Step 1: Failing test** `paused session asks to continue` — `pausedAfterMinutes = 60` → node with text `Paused after 1 h. Continue?`; click `Continue` → callback once; click `Stop` → stop callback once.
- [ ] **Step 2:** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.synchra.ui.MainScreenTest` (phone connected) → FAIL.
- [ ] **Step 3:** Implement (AlertDialog, not dismissable by tapping outside).
- [ ] **Step 4:** Same command → PASS; `:app:testDebugUnitTest` → PASS.
- [ ] **Step 5:** Commit `UI: paused dialog, touches reset the limit, Settings choice`.

### Task 5: Background notification

**Files:**
- Modify: `session/RecordingService.kt` (pure fn `pausedNotificationText(minutes: Int) = "Paused after ${limitLabel(minutes)} — tap to continue"`; `fun showPaused(context, minutes)` posts a non-ongoing, auto-cancel notification, own id, content intent → MainActivity; `fun clearPaused(context)`), `AppContainer.kt` (collect `uiState.pausedAfterMinutes` distinct: non-null → `showPaused`, null → `clearPaused`).
- Test: `app/src/test/java/com/petroff/synchra/session/RecordingNotificationTextTest.kt`

- [ ] **Step 1: Failing test** `paused notification text` — `pausedNotificationText(60) == "Paused after 1 h — tap to continue"`.
- [ ] **Step 2:** run that test → FAIL. **Step 3:** implement. **Step 4:** PASS + full unit tests.
- [ ] **Step 5:** Install on the phone; confirm the app starts and Settings (Paid mode) shows the limit row with `1 h` selected. (Waiting out a real limit on the device is left to the user.)
- [ ] **Step 6:** Commit `Notification when a session pauses in the background`.
