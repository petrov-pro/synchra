# Synchra — Session time limit — Design

Date: 2026-11-26

## Why

A customer who forgets to stop Synchra keeps paying Soniox for as long as anyone talks near
the phone (TV, colleagues). The 5-minute silence auto-stop doesn't help when there is speech.
A session with no activity in the app for a set time (1 h by default) pauses and asks whether
to continue.

## Goals

- **Paid mode only** (the point is Soniox spend; Free mode costs nothing).
- Setting in Settings (Paid mode): **Off / 30 min / 1 h / 2 h**, default **1 h**.
- Any touch on the app's screen while it is open resets the timer. Nothing outside the app
  counts; in the background the time runs.
- At the limit the session **stops at once** (microphone off, Soniox stream closed) and asks:

      Paused after 1 h. Continue?
                [Stop]  [Continue]

  ("1 h" follows the setting: "30 min", "2 h".)
- **Continue** starts listening again and appends to the same text (new paragraph), with the
  same language pair, view mode and contexts. **Stop** ends it as a normal stop does.
- In the background: the recording notification becomes "Paused after 1 h — tap to continue"
  and opens the app, where the dialog is shown. (Android 14+ doesn't allow starting the
  microphone from the background, so Continue happens in the app.)
- History: the text is saved when the session pauses, so it isn't lost if the customer never
  comes back; Continue and the final stop update **the same record**, not a second one.

## Non-goals

- Free mode, the silence auto-stop, billing estimates, a countdown inside the dialog.

## Anti-goals

- Losing text at a pause or on Continue, or duplicate history records.
- The microphone staying on while paused.

## Approaches

- **Chosen: a pause is a real stop of the engine; Continue starts a new engine session**
  and LiveSession glues the earlier text in front of the new one. Engines (Soniox, Free) know
  nothing about pausing. The timer lives in LiveSession; the Activity reports touches.
- Muting audio while keeping the Soniox stream open: the microphone would stay on in the
  background, and nothing is saved over a real stop. Dropped.

## Design

- `SessionTimeLimit` setting in AppConfig/ConfigRepository (minutes; 0 = off).
- `LiveSession`:
  - `onUserActivity()` resets the deadline; a coroutine checks it while listening in Paid mode.
  - At the deadline: `isPaused = true` in `SessionUiState`, engine stopped; `onFinished`
    saves/updates the record under the session's record id.
  - `continueSession()`: keeps `sourceText`/`translatedText` as a prefix, starts the engine,
    `onUpdate` shows prefix + new text joined by a blank line; `stop()` while paused just
    clears the pause.
  - `start()` (new session) clears the prefix and makes a new record id, as today.
- `HistoryRepository`: save is an upsert by id (`addRecord` replaces an existing id).
- UI: dialog on MainScreen when `isPaused`; MainActivity `dispatchTouchEvent` →
  `onUserActivity()`; Settings row for the limit (Paid mode only).
- `RecordingService`: the notification shows the paused text with a tap to open the app.

## Left to the builder

Exact strings beyond the dialog, where the setting sits in Settings, test layout.
