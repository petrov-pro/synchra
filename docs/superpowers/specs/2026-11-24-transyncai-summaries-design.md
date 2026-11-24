# TransyncAI — Conversation history & Claude summaries — Design

Date: 2026-11-24

## Why

Every Start→Stop session is already saved locally (Room, `conversation_records`), but there
is no way to reach it: `HistoryScreen` exists and is not wired into navigation, and there is
no screen for a single conversation. The customer also wants a short summary of a
conversation, written by Claude, which also gives the conversation a meaningful name.

## What the customer gets

### Main screen
- A **History** button opens the list of saved conversations.
- A **Summary** button sits next to History. It is available once a session has been stopped
  and its record saved (not while listening, not before the first session). Tapping it opens
  the detail screen of the conversation that was just stopped, with its summary
  (generating it first if it doesn't exist yet).

### History list
- All saved conversations, newest first, stored only on the phone (as today).
- Each row's name:
  - no summary yet → **date and time only**, e.g. `24 Nov, 14:55` (the language pair is no
    longer part of the name);
  - summary done → the **title Claude returned**.
- Search and delete keep working as today.

### Conversation detail screen
- Opening a conversation **without** a summary shows the **original conversation** with the
  same view switch as the main screen (Both / Transcription / Translation), and a
  **Summary** button at the top. Tapping it asks Claude for the summary; when it arrives the
  screen switches to the summary view.
- Opening a conversation **with** a summary shows the **title and summary** first; the
  top button reads **Original** and switches to the full conversation (with the view
  switch). From the original view the button reads **Summary** again and switches back
  **without calling Claude**.
- A conversation is summarized **at most once**: if a summary is stored, it is shown, never
  regenerated.

### Summary content
- Claude (Haiku 5.5, via the existing `ClaudeTranslationClient` plumbing / Anthropic key in
  Settings) returns **a title and a summary**, both written in the conversation's
  **target language** (Russian for English → Russian).
- Input: the conversation's source text and its translation.

## Errors
- No Anthropic key: the Summary button shows a message pointing to Settings (Anthropic API
  key) instead of calling Claude.
- Claude call fails / no network / Claude is down: the app must never crash. Try **3 times**
  in total; if all fail, show an **error pop-up** and, once it is dismissed, stay on / return
  to **the screen where Summary was pressed** (main screen → main screen; conversation
  screen → that conversation, original view). Nothing is saved, so the customer can try
  Summary again later.
- While a summary is being generated, show progress and don't allow a second request.

## Data
- `conversation_records` gets a nullable `summary` column. `title` (already nullable) is set
  from Claude's title together with the summary.
- Database version 1 → 2 with a migration that **keeps existing records** (add column).
  A test must cover upgrading a version-1 database with records in it.

## Decided
- Local storage only (Room); no sync.
- Summary language = target language of the conversation.
- Summary is generated on demand (button), never automatically, and never twice.
- Claude model: `claude-haiku-5-5` (same as before, cheapest; summaries are short).

## Calls made after design review (not confirmed by the customer)
- **Transcription-only records** (no translation stored): still summarizable — Claude gets the
  source text only and still writes title + summary in the target language.
- **No manual renaming.** The name comes only from Claude's title; the old
  `setTitle`/`updateTitle` (from the earlier history design, never wired to UI) is replaced
  by one call that stores title + summary together.
- **Main-screen Summary button** targets the record saved by the last stopped session in
  this app run; it is unavailable after an app restart, after that record is deleted, or
  when the last session saved nothing (silence).

## Left to the builder
- Prompt wording and the exact structured format used to get title + summary back.
- Navigation route names, how the detail screen loads the record.
- Max length of title/summary (keep the title short enough for one list row).
- Whether the existing translation client is reshaped into a summarizer or a new class sits
  next to it (the translation use of Claude is gone; avoid leaving dead code).
