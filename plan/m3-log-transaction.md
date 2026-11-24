# Kitto Milestone 3 — `log_transaction` (first mutating tool)

**Status:** decisions locked 2026-11-24 (Ny0t picked all four recommendations). Part A written + early device test passed 2026-11-24. Part B written 2026-11-24 (GateText.kt formatter, prompt, clearer DECLINED_BY_USER; 27 unit tests pass), Kitto installed 12:01. Next: device rows.

## Decisions (2026-11-24)
1. Kitto picks the budget; asks only when two could fit.
2. Income included (`type: income`).
3. Budget alerts fire for Kitto writes, same as the app.
4. "via Kitto" tag on Buckt transaction rows, in M3.

## Goal
Ny0t says "I spent RM15 on lunch" → Kitto asks Allow/Deny → Buckt saves the transaction, stamped
`createdBy: { type: 'agent', agent: 'kitto' }`, and the budget's `spent` goes up exactly as if it was
logged in the app. First real use of the M2 gate.

## Scope
**In:** `log_transaction` in Buckt (spec + JS handler + actor stamp from the Binder caller) · a clearer
gate dialog in Kitto · system-prompt lines for logging · device tests.
**Out:** edit / delete tools · undo from Kitto · receipts · logging to commitments · iOS.

## What exists today (read 2026-11-24)
- Buckt tools: `agent-tools.json` asset (2 read-only tools) + `services/agentTools/index.ts`
  (`runAgentTool`: signed-in uid → `parseArgs` → `ensureCycleCurrent` → switch on name → resolve/reject).
- Write path: `services/firebase/transactions.ts` `addTransaction(uid, tx)` — one batch: set the
  transaction + `increment` on budget `spent` (expense with budget), `unallocatedSpent` (expense without
  budget) or `cycleIncome` (income). Same function the Log Spend screen uses.
- `Transaction` already has `createdBy?: Actor` (`types/transaction.ts`, added in M1 Part A2). Nothing writes it yet.
- `AgentToolsService.callTool` knows `Binder.getCallingUid()` but doesn't pass it to JS.
- Kitto: `ToolGate` already asks for any tool with `readOnly: false`; the dialog shows raw JSON
  (`ChatScreen.kt:85`).

## Parts

### Part A — Buckt: the tool
**A1. Spec** (`agent-tools.json`):
```json
{
  "name": "log_transaction",
  "description": "Record a new expense or income in Buckt for the signed-in user. Use the exact budget name from get_budget_balances. Leave budgetName out for spending that isn't in any budget.",
  "readOnly": false,
  "parameters": {
    "type": "object",
    "properties": {
      "type":        { "type": "string", "enum": ["expense", "income"] },
      "amount":      { "type": "number", "exclusiveMinimum": 0, "maximum": 100000 },
      "description": { "type": "string", "minLength": 1, "maxLength": 80 },
      "budgetName":  { "type": "string" },
      "date":        { "type": "string", "description": "Local date YYYY-MM-DD. Omit for now." }
    },
    "required": ["type", "amount", "description"],
    "additionalProperties": false
  },
  "returns": "{ saved, synced, currency, transaction: { date, type, amount, description, budgetName }, budgetLeft }"
}
```
No actor parameter (decided in M1: the AI can't label its own write).

**A2. Actor from the caller** (`AgentToolsService.kt`): in `callTool`, map `callingUid` → package name
(`packageManager.getPackagesForUid`) and pass `callerPackage` in the task data next to `callId`/`name`/`argsJson`.

**A3. Handler** (`services/agentTools/index.ts`, `logTransaction(uid, args, callerPackage)`):
- **Actor:** `com.ny0t.kitto` → `{ type: 'agent', agent: 'kitto' }`. Any other caller (the debug clients)
  → `PERMISSION_DENIED` for this tool. Read tools stay open to them.
- **Validate** (Buckt never trusts the schema alone) → `BAD_ARGS` with a message Claude can act on:
  - `type` is expense/income; `amount` a number > 0 and ≤ 100,000, rounded to 2 decimals.
  - `description` trimmed, 1–80 chars.
  - `budgetName`: only for expenses (ignored for income); matched case-insensitively against this
    cycle's budgets. No match → `BAD_ARGS "No budget named 'X'. Budgets: Food, Transport, …"` so Claude
    can retry or ask.
  - `date`: `YYYY-MM-DD`, not in the future, not before the current cycle start. Saved at 12:00 local
    that day; omitted → now.
- **Write:** `addTransaction(uid, { type, amount, description, budgetId?, budgetName?, date, createdAt, createdBy })`.
  Same batch and counters as the app.
- **Offline / slow network:** Firestore applies the write locally at once, but `batch.commit()` only
  resolves when the server confirms. Race it against **4 s**: confirmed → `synced: true`; not yet →
  `synced: false` (queued, Firestore syncs it later). Never let it run into the 15 s TIMEOUT: a timeout
  after a write makes Kitto report failure for something that was saved (→ duplicates on retry).
- **Return:** the saved transaction (local ISO date like the read tool) + `budgetLeft` for that budget
  (allocated − spent after this one; null when no budget).
- **Budget notifications (decided: yes):** the Log Spend screen fires "budget low / exceeded / large spend"
  after a write. Recommendation: **fire them for Kitto writes too**, same helpers, same settings: the
  money left is what matters, not who logged it.

**A4. Buckt UI (decided: yes, in M3):** recommendation: a small "via Kitto" tag on transaction rows where
`createdBy.type === 'agent'`. It's the point of the actor field (review what the AI added). Small, but
it's UI work in Buckt; can move to M3.1 if you'd rather keep M3 tight.

### Part B — Kitto
- **B1. Gate dialog:** show arguments as readable lines instead of raw JSON, for any tool:
  `Type: expense · Amount: 15 · Description: Lunch · Budget: Food`. Generic (key: value from the args JSON),
  no `log_transaction`-specific code in Kitto.
- **B2. System prompt** additions:
  - To log spending, use `buckt__log_transaction`. If unsure of the budget, call `get_budget_balances`
    first and use an exact budget name; if two could fit, ask Ny0t.
  - Never log without an amount from Ny0t. Don't guess amounts.
  - If a tool result says the user declined, don't retry it; say it wasn't saved.
  - **Explain the gate** (found in the early test, 2026-11-24): Claude can't see the Allow/Deny dialog, so a
    bare "user declined" contradicts the chat and it offered to retry. Prompt must say that mutating tools
    show Ny0t an Allow/Deny dialog and "declined" means Ny0t tapped Deny.
- **B3. Clearer declined result:** `DECLINED_BY_USER` → "Ny0t tapped Deny on the confirmation dialog.
  Nothing was saved." so the result explains itself even without the prompt.
  - After saving, confirm in one line (amount, budget, money left). If `synced: false`, say it will sync
    when online.
- **Unclear budget → ask (changed 2026-11-24 after device test: 'spent rm10 on xxxd' was put in Lunch).** Prompt: choose a budget only when the description clearly fits; otherwise ask, offering budget names + "no budget".
- **Today's date on each new user message** (`Today.kt` `withToday()` puts `[Today is …]` before the question; the chat shows the plain text): Claude has no clock, so "yesterday" failed in the device test. **Not in the system prompt:** a first version rebuilt the system prompt each turn (with HH:mm) and broke preserved thinking: Haiku 5.5 seals each thinking block to system + tools + earlier messages, so a changed system prompt → 400 "Invalid signature in thinking block… bound to a different conversation" on every later request. Rule: history and system prompt are append-only; `SdkConversation.system` is a fixed String.
- **Category choice (decided: Kitto picks):** recommendation above: **Kitto picks the budget, asks only when unsure.**
  The Allow/Deny dialog is already the check, so a question before every log would be friction.
- **Income (decided: include):** recommendation: **include it** (`type: income`). It's the same write path, one enum
  value, and "got paid RM200 for freelance" is a natural thing to say.

### Part C — tests
**Buckt unit tests:** none. Buckt has no Jest setup (checked 2026-11-24), so `logTransaction` is covered by
the device rows: validation cases, budget match, actor from caller, unknown caller rejected.

**Kitto unit test:** gate argument formatting (JSON → lines).

**Device (Poco, Buckt test account):**
| # | Say / setup | Expected |
|---|---|---|
| 1 | "I spent RM15 on lunch" | gate shows Expense / 15 / Lunch / Food → Allow → saved; Food spent +15; "via Kitto" in Buckt |
| 2 | Same, Deny | nothing saved; Kitto says it wasn't saved; no retry |
| 3 | "Got paid RM200 for freelance" | income saved; cycle income +200 |
| 4 | "Spent RM10 on xyzzy" (no clear budget) | Kitto asks or logs unallocated; never invents a budget |
| 5 | "I spent some money on lunch" (no amount) | Kitto asks for the amount, no tool call |
| 6 | Budget name wrong on purpose (e.g. via a debug call) | `BAD_ARGS` listing real budgets |
| 7 | Buckt phone offline-ish (Wi-Fi on, Buckt can't reach Firestore is hard to fake) → skip unless easy | `synced: false` path |
| 8 | Debug client calls `log_transaction` | `PERMISSION_DENIED` |
| 9 | Budget goes over after the log | Buckt's budget notification fires |
| 10 | Ask "what did I spend recently?" after #1 | the new transaction appears |

**Early device test, Part A only (2026-11-24, Kitto unchanged, raw-JSON dialog):** A (RM15 lunch, Allow)
PASSED, B (Deny) PASSED (nothing saved, no auto-retry, but Claude was confused, see B2/B3), C (RM200 income)
PASSED, D (recent shows the lunch) PASSED.

**Device results (Poco, 2026-11-24, Parts A + B):** row 1 PASSED (lunch, via Kitto tag) · row 2 PASSED
(Deny: clean reply after B3 + prompt) · row 3 PASSED (income) · row 4 PASSED ("xxxd" → Kitto asks, budget names +
"no budget") · row 5 PASSED (no amount → asks) · row 6 not run directly (BAD_ARGS path; Claude used exact names) ·
row 7 skipped (offline hard to fake) · row 8 PENDING (debug client, Anyit0 via adb) · row 9 PASSED ("Food is over
budget" notification) · row 10 PASSED · extra: "yesterday" → saved on 23 Nov PASSED.
**Timing (logcat, 12:55):** read question ≈ 4.8 s (Claude 1.4 s → Buckt warm ~1.4 s → Claude ~1.9 s); log question
≈ 3.9 s excluding the dialog (Claude 1.5 s, went straight to log_transaction → Buckt ~0.9 s → Claude ~1.5 s). First
request 1,687 input tokens (M2: 611; growth = log_transaction spec + longer prompt; date line ≈ 30). Feel-faster
idea for later: stream replies.

## Files
**Buckt:** `modules/kitto-agent-tools/android/src/main/assets/agent-tools.json` ·
`.../AgentToolsService.kt` (caller package) · `services/agentTools/index.ts` (handler) ·
transaction row component (if A4 = yes). Needs a **release rebuild + reinstall** (asset + native change);
`adb install -r` keeps the test account.
**Kitto:** `ChatScreen.kt` (dialog text) + a small formatter · `ChatViewModel.kt` (system prompt) ·
one unit test. No protocol or AIDL change.

## Risks & edge cases
- **Duplicate writes:** Claude retrying after an error, or Kitto's 20 s timeout after Buckt saved. The 4 s
  commit race removes the main cause; the system prompt says don't retry declined calls. A real
  idempotency key (Claude's `tool_use` id passed to Buckt) is possible later, but needs a protocol change.
- **Wrong amount/budget from the model:** the gate shows exactly what will be written. Fixing a mistake =
  delete it in Buckt (no undo tool in M3).
- **Debug clients share Buckt's key:** they could write as "kitto" if the actor came from the signature
  alone. Hence the package-name check and rejecting other callers for mutating tools.
- **Headless process dies before sync:** Firestore's local persistence keeps the queued write; it syncs
  next time Buckt runs. `synced: false` tells Ny0t.
- **Cycle rollover at payday:** `ensureCycleCurrent` already runs before every tool; a date before the
  new cycle start is rejected.

## Done when
Device rows 1–6, 8, 9, 10 pass, unit tests pass, Ny0t has logged a real spend
through Kitto. Ny0t commits both repos (Anyit0 doesn't run git).
