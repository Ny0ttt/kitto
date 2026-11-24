# Kitto Milestone 2 — the AI loop (read-only tools)

**Status:** approved 2026-11-19. Part 0 (SDK spike) passed 2026-11-19: Java SDK 3.3.0 works on the Poco in debug, no desugaring needed. Next: Part A.
**Written:** 2026-11-19. Follows Milestone 1 (Buckt `master` @ `c7034a8`, debug client @ kitto `72c7189`).

## Goal

On the Poco, Ny0t types *"how much can I still spend on food this cycle?"* into Kitto and gets the
real answer from Buckt (e.g. "RM72 left in Food"). Kitto gets the number by calling Buckt's tool over
Binder, chosen by Claude. No number in the answer may come from anywhere but a tool result.

## Decisions (approved 2026-11-19)

| Area | Choice | Why |
|---|---|---|
| App stack | Native Kotlin, Jetpack Compose | Kitto is mostly Binder IPC; RN would add a bridge for nothing |
| Model | Claude Haiku 5.5 — `claude-haiku-5-5` | Cheap ($0.10 / $0.50 per MTok in/out), fast, good at tool use |
| API key | On the phone for now, from `local.properties` → `BuildConfig` (gitignored) | Dev on Ny0t's own phone only. **Must move to a backend before Play** |
| SDK | Official Anthropic Java SDK `com.anthropic:anthropic-java` (Kotlin uses the Java SDK) | Official SDK, typed tool-use blocks |
| Loop | Manual tool loop (not the beta Tool Runner) | We need our own gate before each call and the async Binder callback |

## Scope

**In:** Kitto app (one chat screen) · find apps that offer `com.ny0t.kitto.action.AGENT_TOOLS` · bind,
`listTools`, `callTool` · Claude tool loop · the confirmation gate (built, unused: both tools are
read-only) · per-question limits, timeouts, plain error messages · a shared `protocol` module.

**Out (later milestones):** mutating tools (`log_transaction` = M3) · Cleo · streaming replies ·
saved chat history (chat lives in memory only, gone when the app closes) · backend for the key ·
voice / house terminals · Play release.

## Project layout

**Hands-on (2026-11-19):** Ny0t builds M2 himself with Anyit0 coaching (see Kitto.md → Hands-On Rule).
The project is created from Android Studio's "Empty Activity" (Compose) template, so its Gradle files use
Studio's defaults (Kotlin DSL `.kts` + version catalog) rather than the debug client's Groovy files.
Target: compileSdk 36, minSdk 24, Java 17.

```
~/programmin/kitto/
  debug-client/          (M1, unchanged; keeps its own AIDL copy, it's throwaway)
  kitto-app/
    settings.gradle      :app, :protocol
    protocol/            Android library: IAgentTools.aidl, IToolCallback.aidl, ToolSpec.kt
    app/                 com.ny0t.kitto — the Kitto app
    keystore.properties  (local, gitignored) Buckt's key, same pattern as debug-client
    local.properties     (local, gitignored) sdk.dir + ANTHROPIC_API_KEY
  plan/
```

**Shared AIDL, honest scope:** the `protocol` module is shared by Kitto only. Buckt keeps its copy
inside its Expo module (pulling a lib across two repos isn't worth it for one file pair). The AIDL
header already says "protocol v1"; any change means updating both copies. Revisit when Cleo joins.

## Parts

### Part 0 — SDK spike (first, ~1 hour)
Bare app, one button → one `messages().create` to `claude-haiku-5-5`, print the text.
**Proves the Java SDK runs on Android** (it uses OkHttp + Jackson; may need core-library desugaring
for `java.time` on minSdk 24, and R8 keep rules in release builds).
**If it can't be made to work:** stop and tell Ny0t. Fallback = raw HTTPS to `POST /v1/messages`
with OkHttp. That's a plan change, so it gets asked, not done silently.

### Part A — protocol module + finding/binding apps
- `protocol/`: the two AIDL files (moved from the debug client, unchanged) + `ToolSpec.kt` (parse
  `listTools()` JSON: `name`, `description`, `readOnly`, `parameters`, `returns`).
- `app/.../AppConnector.kt`:
  - `queryIntentServices(Intent(ACTION_AGENT_TOOLS))` → each provider app.
  - Manifest needs `<queries>` for the action (Android 11+ package visibility), as in the debug client.
  - Bind with `BIND_AUTO_CREATE` on first question, stay bound while Kitto is in the foreground,
    unbind in `onStop`.
    **As built (2026-11-24): binds and unbinds per call instead** (`AppConnector.withProvider`), costing
    a bind each call (~50 ms warm, ~300–480 ms cold). Accepted for M2; revisit if Part D latency feels slow.
  - `callTool` wrapped in `suspendCancellableCoroutine`; Kitto-side timeout 20 s (Buckt's own is 15 s).
    The timeout covers the bind as well as the call (fixed 2026-11-24; before, a bind that never
    connected hung forever).
- **Tool names sent to Claude are prefixed per app**: `buckt__get_budget_balances`. Claude's tool
  names allow `[a-zA-Z0-9_-]{1,64}`; the prefix is what lets Cleo's tools sit beside Buckt's later.
  Prefix = a short id from the provider (for M2: derived from package name, `com.navitech.buckt` →
  `buckt`).
- Missing `readOnly` = treated as **mutating** (safe default).

### Part B — the Claude loop (`app/.../AgentLoop.kt`)
One user question:
1. Request: `model claude-haiku-5-5`, `max_tokens 16000`, `output_config.effort = low` (chat; Haiku 5.5
   defaults to `medium`), thinking left at its default (adaptive), system prompt, tools from Part A,
   the conversation so far.
2. Check `stop_reason` **before** reading content:
   - `end_turn` → show the text, done.
   - `tool_use` → for every `tool_use` block: gate (Part C) → `callTool` → collect results. Return
     **all** results in **one** user message (`tool_result`; failures as `is_error: true`, never dropped).
     Loop to 1.
   - `refusal` → show "Claude declined this request." Haiku has no server-side fallback.
   - `max_tokens` → show what came back + "reply was cut off".
3. Limits: max **5** tool rounds per question → then stop with "I couldn't finish this one."
4. Parse tool inputs as JSON (never string matching). Buckt validates args itself (`BAD_ARGS`).
5. Log `usage` (input/output tokens) per question to logcat, to see the real cost.

**System prompt (short, final wording in code):** Kitto is Ny0t's assistant on his phone. Use the
tools for any figure about his money; never estimate or recall numbers. Currency is from the tool
result. If a result says `fromCache: true`, say the numbers may be out of date. If no tool can answer,
say so.

**Prompt caching: not in M2.** System prompt + 2 tools is below the minimum cacheable size, so a
breakpoint would silently do nothing. Revisit when the tool list grows.

**Conversation state:** messages kept in memory (ViewModel), assistant content appended **as returned**
(full blocks, not just text), so follow-ups like "and last week?" work.

### Part C — chat screen + gate
- Compose: message list, input box, send button. While a tool runs: "checking Buckt…" (cold start is
  ~1.5 s, up to ~4 s first run).
- **Gate:** before `callTool`, if the tool isn't read-only → dialog "Kitto wants to <tool> in <app>.
  Allow?" with the arguments shown. Deny → `tool_result` `is_error: true`, "user declined". No M2 tool
  hits this; it gets a unit test with a fake mutating tool instead.
- Errors shown in plain words: no providers found · Buckt refused (wrong signing key) · Buckt timed out ·
  no internet · API error (status + short message; 401 → "API key missing or wrong").

### Part D — device test (Poco, Buckt test account)
| # | Ask / setup | Expected |
|---|---|---|
| 1 | "How much can I still spend on food this cycle?" | RM72 (Food 100 / 28 spent), from `buckt__get_budget_balances` |
| 2 | "What did I spend recently?" | recent transactions, local dates (+08:00) |
| 3 | "And what's my total left?" (follow-up) | remaining 972 / spendable 900, uses context |
| 4 | "Delete all my transactions" | no tool exists → says it can't |
| 5 | Buckt force-stopped, ask #1 | works, "checking Buckt…" visible, ~1.5 s extra |
| 6 | Airplane mode | clear "no internet" message, no crash |
| 7 | Buckt uninstalled (or provider missing) | "no connected apps found" |
| 8 | Kitto built with the debug key | Buckt refuses → clear message |
| 9 | Fake mutating tool (unit test) | gate dialog appears; deny → `is_error` result |
| 10 | Token log for #1 | record input/output tokens → real cost per question |

**Results (Poco, 2026-11-24):**
- Rows 1, 2, 3 PASSED: RM72 food (28 of 100 spent), recent transactions, follow-up "total left" used context.
- Row 4 PASSED: "Delete all my transactions" → Kitto said it can't delete.
- Row 5 PASSED: Buckt cold, new pid started by the bind, gone after unbind (Force stop greyed, `stopped=false`).
- Row 6 PASSED: airplane mode → clear no-internet message, no crash.
- Row 7 PENDING: run at the next Buckt reinstall (keeps the test account).
- Row 8: proposed as covered by the A4 test (`debugclient.otherkey`, a different key, refused by Buckt);
  not re-run, since it would replace the working Kitto install.
- Row 9 PASSED: `ToolGateTest` (deny → `is_error`, "user declined").
- Row 10: ≈1,347 input / 64 output tokens per question (turn 1 611/34, turn 2 736/30).

## Files

**New:** `kitto-app/settings.gradle`, `build.gradle`, `gradle.properties`, wrapper (copied from
debug-client), `.gitignore` (same as debug-client) · `protocol/build.gradle`, 2 AIDL, `ToolSpec.kt` ·
`app/build.gradle` (Compose, coroutines, anthropic-java, `bucktKey` signing, `BuildConfig.ANTHROPIC_API_KEY`
from `local.properties`), `AndroidManifest.xml` (`INTERNET`, `<queries>`), `MainActivity.kt`,
`ChatViewModel.kt`, `ChatScreen.kt`, `AppConnector.kt`, `AgentLoop.kt`, `ToolGate.kt` · unit test for the gate.
**Changed:** none in Buckt. Debug client untouched.

## Risks & edge cases

- **Java SDK on Android** — Part 0 exists to find out early (desugaring, R8 rules, Jackson).
- **API key in the APK** — anyone with the APK can extract it. OK for Ny0t's own phone only; never
  share the APK; set a low monthly spend limit on the key in the Console.
- **Signing key vs Play (later, not M2):** the signature permission compares the *installed* Buckt's
  signature. A Play-installed Buckt carries Play's app-signing key, so a sideloaded Kitto must match
  that key, or both apps must ship through Play under keys that match. Testing in M2 uses the local
  release Buckt, so this doesn't block M2. Must be settled before Play.
- **Privacy / compliance (before any other user):** budget data goes to Anthropic's API. Needs a
  privacy-policy line, PDPA check, and the backend for the key. Not M2.
- **Binder callback on a binder thread** — resume the coroutine only; no UI work there.
- **Same app connected twice / service dies mid-call** — `onServiceDisconnected` / `binderDied` fail
  the pending call with a clear error, rebind on next question.

## Done when
Part D rows 1–8 pass on the Poco, row 9's test passes, tokens per question recorded, Ny0t has
used it himself. Ny0t commits (Anyit0 doesn't run git).

## Next (M3)
`log_transaction` in Buckt (mutating, gated, actor stamped from the Binder caller) + the
confirmation dialog for real.
