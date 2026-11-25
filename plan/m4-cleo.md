# Kitto Milestone 4 — Cleo, the second app

**Status:** decisions locked 2026-11-24 (Ny0t agreed to all five recommendations). Ny0t asked for thorough
backdate testing → tests 5a–5j. Part A A1+A2 written 2026-11-24 evening (Cleo service + background engine + spike `get_due_spots`), Cleo release installed on the Poco 21:09. Debug client `target` extra written, not installed. A3 PASSED 2026-11-25 → GO (cold 379 ms, warm ~10 ms). B1 done 2026-11-25. B2+B3 done 2026-11-25 (test 9 passed). Next: C1 Kitto prompt split.

## Goal
Ny0t asks Kitto "what's due for cleaning today?" or says "I just cleaned the bathroom", and it works the
same way Buckt does: Cleo answers through `IAgentTools`, writes go through the Allow/Deny gate, and Cleo's
own rules run exactly as if the clean was logged in the app. Finishes original Development Goal 3 and
proves Kitto is a platform, not a Buckt add-on.

## Scope
**In:** a Flutter bridge for `IAgentTools` in Cleo · 3 tools (`get_due_spots`, `get_spot_status`,
`log_clean`) · Kitto system prompt split into general + per-app parts · device tests incl. one cross-app question.
**Out:** `report_dirt` and other Cleo writes · edit/delete · iOS · Cleo's "upload existing data on sign-in"
backlog item · streaming (moved to M5).

## Done before M4 (2026-11-24)
- Cleo is in git (`d50deff`), `/cleo.db` ignored.
- Cleo signed with Buckt's shared key (release + debug); phone shows the same signature as Kitto (`9a16b1e7`).
- New SHA-1 in Firebase + new `google-services.json`; Google sign-in tested on the phone.
- Phone's 7 spots re-entered by hand (old data was local-only; see Cleo.md).

## What exists today (read 2026-11-24)
- **Buckt's bridge to copy:** `modules/kitto-agent-tools/android/` — `AgentToolsService.kt` (107 lines:
  Binder stub, signature check, caller package, hands the call to JS), `PendingCalls.kt` (callId → callback),
  manifest: signature `<permission com.ny0t.kitto.permission.AGENT_TOOLS>`, exported service with action
  `com.ny0t.kitto.action.AGENT_TOOLS`, meta-data `com.ny0t.kitto.tools` → `agent-tools.json`. AIDL files in
  `src/main/aidl/com/ny0t/kitto/`.
- **Kitto discovery:** `AppConnector.kt` finds every service with that action; `prefixFor` takes the last
  package segment → Cleo's tools become `cleo__get_due_spots` etc. No Kitto change needed for a second app.
- **Cleo's Android side** is a bare `MainActivity : FlutterActivity()`; Flutter 3.41.4, Dart ^3.11.1.
- **Cleo's write path:** `CleanLogActions.logClean(spot, cleanedAt, nextScheduledAt, notes, typicalTimeOfDay)`
  (`features/clean_logs/providers/clean_log_actions.dart`): inserts the log + Firestore push, MAX rule on
  `last_cleaned_at`, sets `next_scheduled_at`, resolves dirt reports, runs `RoutineAlgorithm`, regenerates
  scheduled entries, reschedules notifications. Uses a Riverpod `Ref` only to invalidate two providers →
  works from a `ProviderContainer` in the background.
- **Catch:** the next-due date is computed in the **UI sheet** (`log_clean_sheet.dart` `_calculateNext`,
  `_nextWeekdayAfter`, `_firstFutureHop`, all private). If a backdated clean gives a next date in the past,
  the sheet asks Ny0t for a date with a date picker. A tool can't show that picker.
- **Status:** `SpotWithDirt.status(dueSoonThreshold, reminderTiming)` in `spots_provider.dart`
  (notStarted / clean / dueSoon / overdue); both settings come from SharedPreferences.

## Decisions to lock
1. **Bridge approach.** *Recommend:* Kotlin `AgentToolsService` starts a background `FlutterEngine` running a
   Dart entry point (`@pragma('vm:entry-point') agentToolsMain`) and calls it over a `MethodChannel`.
   Same shape as Buckt's headless JS; writes reuse the Dart rules. (Alternative: Kotlin reads SQLite
   directly — fast reads, but writes would bypass or duplicate the Dart rules.)
2. **Engine lifetime.** *Recommend:* keep the engine warm for 5 minutes after the last call, then destroy it.
   Measure cold vs warm in Part A and adjust.
3. **Backdated clean whose next date lands in the past.** *Recommend:* use `_firstFutureHop` automatically
   (the date the app's picker already pre-fills) and say the chosen next date in the result, so Claude can
   tell Ny0t. (Alternative: refuse and make Claude ask — more round trips for a rare case.)
4. **Spot names.** *Recommend:* same rule as Buckt budgets — case-insensitive exact match, otherwise
   `BAD_ARGS` listing the real spot names; system prompt says unclear → ask. Spot names are Malay
   ("singki dapur"), so Claude must map "kitchen sink" by asking or by the listed names, never by guessing.
5. **"via Kitto" marker on clean logs.** Cleo has no actor field. *Recommend:* skip in M4 (needs a SQLite
   migration + Firestore field + UI); put it on Cleo's backlog. (Alternative: do it now for parity with Buckt.)

## Parts

### Part A — Flutter bridge spike (go / no-go)
- A1. Cleo `android/`: copy the two AIDL files; `AgentToolsService.kt` (Binder stub + `checkSignatures` +
  caller package, like Buckt); same `<permission>` + `<service>` + meta-data in the manifest (a second
  definition of the permission is allowed because both apps share the signing key).
- A2. Dart `agentToolsMain()`: init Flutter bindings + Firebase, open the DB, answer one tool over the
  MethodChannel. Spike tool: `get_due_spots` returning names only.
- A3. Device test with the debug client and Kitto: one call end to end; logcat timing for cold and warm
  engine; Cleo UI open vs closed at the same time.
- **Go / no-go:** works with the app closed; cold start acceptable (Buckt cold ≈1.5 s for comparison).

**A3 results (2026-11-25 06:49–06:52, Poco, Anyit0 ran on Ny0t's "you go")**: GO.
- Cold (Cleo process not running; checked with `pidof`, used `am kill` not force-stop so Cleo's alarms survive): bind 334 ms, engine cold start 346 ms, total 379 ms (Buckt cold ≈ 1.5 s).
- Warm: 6–7 ms in Cleo, 8–23 ms at the client.
- Cleo UI open at the same time: 4 ms, no errors; UI and background engine share one process (same pid).
- Data matches Cleo's screen: phone currently has 5 placeholder spots "spot"…"spot 5", all Not Started, no next dates. **Not** the 7 real spots from 11-24 — ask Ny0t why (reinstall wiped them? Firestore restore pending?). Backdate tests need real data or set-up test spots.
- No Firebase/sqflite errors in the background engine.
- Debug-client gotcha: repeating an identical `am start` only brings the task to front (no `onNewIntent`) → use `-f 0x20000000` (SINGLE_TOP) for repeat calls.

### Part B — Cleo tools
**B1 DONE 2026-11-25 ~07:05 (on "you go", Anyit0 wrote)**: `lib/features/clean_logs/services/next_schedule.dart` — `NextSchedule.afterClean` / `nextWeekdayAfter` / `firstFutureHop(now:)` (moved from the sheet, unchanged) + new `resolve(spot, cleanedAt, now:)` → `(next, adjusted)` for decision 3. Sheet now calls the service (no behaviour change). `test/next_schedule_test.dart` 12 tests (5a, 5b, multi-hop, 5c on-time/backdated, same-weekday, empty days, 5d learning, days w/o interval). Full `flutter analyze` clean, all 13 tests pass, release APK builds (not installed — no behaviour change). Both flags fixed the same day on Ny0t's go: (1) "due today" now counts as due — `isPast` compares calendar days (matches `SpotWithDirt.status`), `firstFutureHop` can return today; sheet uses `isPast`. (2) The three duplicate `_nextWeekdayAfter` copies now call `NextSchedule.nextWeekdayAfter`. 16 schedule tests; release installed 07:46 (data kept).
- B1. Move `_calculateNext` / `_nextWeekdayAfter` / `_firstFutureHop` out of the sheet into a shared
  service; sheet uses it (no behaviour change; unit tests).
**B2+B3 DONE 2026-11-25 ~09:20**: real tools + `tool_args.dart` (pure checks, 20 unit tests) + handlers (fresh ProviderContainer per call, prefs reload, older clean leaves next due alone, returns final nextDue + dirtReportsClosed); notifications init without permission prompt in the background engine. 36 Cleo tests pass. Debug client: reads OK (cold 400 ms), BAD_ARGS paths OK, **test 9 PASSED**.
- B2. `agent-tools.json` (3 tools):
  - `get_due_spots` (read): overdue + due-soon spots with status, due date, last cleaned, active dirt level.
  - `get_spot_status` (read, `spotName`): one spot's schedule, last cleaned, next due, dirt.
  - `log_clean` (write, gated): `spotName` (required), `date` (YYYY-MM-DD, optional, not future),
    `notes` (optional, ≤ 200). Returns `{ saved, spot, cleanedAt, nextDue, nextDueAdjusted }`.
- B3. Handlers: caller check (only `com.ny0t.kitto` may write) · unknown keys → `BAD_ARGS` · spot match
  per decision 4 · `logClean` via a `ProviderContainer` · decision 3 for past next dates.

### Part C — Kitto
**C1 DONE 2026-11-25 ~12:12**: prompt split into PROMPT_GENERAL + PROMPT_BUCKT + PROMPT_CLEO (fixed const); Kitto unit tests pass; installed. 
**C2 DONE 2026-11-25 ~15:00**: Ny0t ran tests 1, 2, 5a–5i, 6, 8, 10, 11 through Kitto — all pass. Test 9 re-run 14:59 (debug client → PERMISSION_DENIED, 1 ms). **M4 DONE** — Kitto commit left.
- C1. System prompt split: general rules (tools for facts, date line, gate, Deny wording, short replies)
  + a Buckt section (money) + a Cleo section (cleaning: spot names, ask when unclear, one-line confirm with
  next due date). Prompt stays fixed per conversation (thinking-block rule from M3).
- C2. Device tests (below).

## Device tests
| # | Test | Expect |
|---|------|--------|
| 1 | "what's due for cleaning today?" | `cleo__get_due_spots`, correct overdue list |
| 2 | "when did I last clean the tandas?" | `get_spot_status`, right date |
| 3 | "I cleaned singki dapur" → Allow | saved; Cleo shows it; next due updated; Firestore has the log |
| 4 | same → Deny | nothing saved, says so |
| 6 | unknown spot ("cleaned the garage") | asks, listing real spots; no write |

**Backdate tests (Ny0t asked for these specifically).** Set each spot up first and note its last cleaned /
next due before and after. Use a throwaway test spot where a case would mess up real schedules.
| # | Setup → what Ny0t says | Expect |
|---|------|--------|
| 5a | every-N-days spot → "cleaned X yesterday" | `cleanedAt` = yesterday; next due = yesterday + N (still future) |
| 5b | every-7-days spot → "cleaned X 10 days ago" | next due would be in the past → moved to first future hop (decision 3); reply says the new date; `nextDueAdjusted: true` |
| 5c | weekdays spot (e.g. Mon/Thu) → backdated clean | next due = next listed weekday after the clean, or first future one if that's past |
| 5d | learning-mode spot (no interval) → backdated clean | saved; next due stays empty; reply doesn't invent a date |
| 5e | spot last cleaned 3 days ago → "cleaned X last week" | log saved but `last_cleaned_at` unchanged (MAX rule); reply doesn't claim it's the latest clean |
| 5f | "Not Started" spot → backdated first clean | spot leaves Not Started; dates correct |
| 5g | spot with a dirt report from 2 days ago → clean dated 3 days ago | dirt report stays open (clean is older than the report) |
| 5h | same spot → clean dated today | dirt report resolved |
| 5i | "I'll clean X tomorrow" / a future date | no write; Claude says it can only log past or today (handler rejects future dates as a backstop) |
| 5j | named day: "cleaned X last Friday", and an explicit "on 20 Nov" | correct YYYY-MM-DD from the date line |
| 7 | backdated clean while Cleo shows the spot on screen | covered with test 11 |
| 8 | "what's due today and how much food budget is left?" | calls both apps in one answer |
| 9 | debug client calls `log_clean` | `PERMISSION_DENIED` |
| 10 | Cleo closed (swiped away) | still answers (cold engine); time it |
| 11 | Cleo open on screen during a Kitto write | no crash; note whether the UI refreshes |

## Risks
- **Two engines on one SQLite file** (Cleo UI + background engine): SQLite locking should cope; the UI
  won't see Kitto's write until it reloads (Riverpod state is per engine). Test 11.
- **Plugins in a background engine**: sqflite, Firebase, shared_preferences, notifications must all work
  without an Activity. This is what the spike proves.
- **Cold start**: a Flutter engine plus Firebase init may be slower than Buckt's JS.
- **Signing**: every Cleo build must use the shared key now (done in `build.gradle.kts`).

## Files (expected)
Cleo: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/aidl/com/ny0t/kitto/*.aidl`,
`android/app/src/main/kotlin/com/ny0ttt/cleo/AgentToolsService.kt` (+ pending-calls helper),
`android/app/src/main/assets/agent-tools.json`, `lib/agent_tools/` (entry point + handlers),
`lib/features/clean_logs/services/next_schedule.dart` (moved logic), `log_clean_sheet.dart`, tests.
Kitto: `ChatViewModel.kt` (prompt split).

## Done when
Tests 1–6, 5a–5j, 8–10 pass on the Poco, 11 noted; Part B1 has unit tests for the moved next-date logic
covering the same backdate cases (days / weekdays / learning / past result → first future hop); Kitto unit tests green; Cleo tests green; Ny0t commits both repos.
