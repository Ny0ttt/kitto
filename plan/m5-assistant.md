# M5 — Kitto as the phone's assistant

**Status:** decisions locked 2026-11-25. Pre-check done. **Part A DONE 2026-11-25 ~22:00 (GO)** — see "Part A result". Next: Part B (streaming).

## Goal
Long-press (or the phone's assistant gesture) opens Kitto over whatever app is on screen. Ny0t speaks,
Kitto answers as the text arrives (streaming) and can read the answer out loud. Writes still go
through the Allow / Deny gate.

## Scope
In: assistant role, voice input, streaming replies, spoken replies, a small assistant sheet.
Out: hotword ("Hey Kitto"), reading the screen of the app underneath, lock-screen use, prompt caching
(M6), backend for the API key (M6).

## Facts checked (2026-11-25)
- Poco: Android 12 (API 31), MIUI 14. Current assistant role holder = Google app
  (`GsaVoiceInteractionService`). `voice_recognition_service` = Google TTS recognizer
  (`com.google.android.tts/...GoogleTTSRecognitionService`).
- The Claude app on the phone registers its own `RecognitionService`, the same pattern Kitto needs.
- SDK `anthropic-java` 3.3.0 has `client.messages().createStreaming(params)` + `MessageAccumulator`
  (`accumulate(event)` per event, `message()` at the end). Only `SdkConversation.kt:54` changes; the
  tool loop (`AgentLoop`) keeps getting a whole `Message`.

## How Android's assistant works (short)
An app becomes a choice in Settings → Default apps → Digital assistant app when it declares:
- a `VoiceInteractionService` (permission `BIND_VOICE_INTERACTION`, meta-data `android.voice_interaction`
  → XML naming the session service and a recognition service, `supportsAssist="true"`),
- a `VoiceInteractionSessionService` that creates a `VoiceInteractionSession` per long-press,
- a `RecognitionService` (the XML must name one, even if Kitto doesn't use it for its own speech).
The system binds the service while Kitto is the chosen assistant (like the Binder services in M1),
and calls `onShow()` on a new session at each long-press.

## Decisions to lock
1. **Real assistant role (VoiceInteractionService), not a plain ASSIST activity.** *Recommend:* VIS.
   It is what the roadmap says, it is how Gemini/Claude do it, and it's the OS-level path in Ny0t's
   career plan. The session's `onShow()` starts Kitto's own Compose activity with
   `startAssistantActivity()`, so the UI stays plain Compose (no Compose inside the session window).
2. **Speech-to-text = Android `SpeechRecognizer` bound to Google's recognizer by name.** Never the
   "default" recognizer, because once Kitto is the assistant the default may point at Kitto's own stub
   (the spike checks this). Language: phone default first; Malay spot names are a known risk → test 3.
3. **Speak replies (TextToSpeech) only when the question was spoken.** Typed question → text only.
   The whole reply is spoken once it's done (not sentence by sentence) — simpler, and Haiku replies
   are short.
4. **Writes still need a tap on Allow.** No voice "yes" — a misheard "yes" can't log money.
   Kitto says "Tap Allow to save" when it speaks a gate.
5. **Each long-press starts a new conversation.** Assistant questions are one-offs; the full Kitto
   app (launcher icon) keeps its own chat. Also keeps conversations short (cost, thinking blocks).

## Parts
### Part A — Assistant spike (go / no-go)
- A1. Manifest + `KittoVoiceService` (VIS) + `KittoSessionService` + `KittoSession` (`onShow` →
  `startAssistantActivity`) + stub `KittoRecognitionService` + `res/xml/kitto_voice.xml`.
- A2. `AssistActivity`: translucent, bottom sheet saying "Kitto here" (no Claude yet).
- A3. Device: pick Kitto in Digital assistant app; test what opens it on MIUI (long-press power?
  gesture? home long-press with buttons?); time from press to sheet; check
  `settings get secure voice_recognition_service` before/after; check Google voice typing still
  works in the keyboard. **Go** = a physical gesture opens the sheet over another app.
  Undo after testing = pick Google again in the same settings screen.
  Poco uses 3-button nav (`navigation_mode=0`); MIUI has long-press Home set to screenshot
  (`long_press_home_key=screen_shot`). Ny0t can't/won't use power-hold → main trigger to test =
  **long-press Home** (Settings → Additional settings → Gesture shortcuts → Launch Google Assistant →
  Press and hold Home). Screenshot stays on Power + Volume down.
- A4. Fallback trigger owned by Kitto: **Quick Settings tile** (`TileService`, opens `AssistActivity`).
  Works with no system setting and whoever the assistant is.

### Part B — Streaming replies (widened 2026-11-26) — DONE in the main app (`36f752a`)
**Why widened:** streaming alone only shortens the *last* Claude call. M3 timing for a read question
was 4.8 s = Claude turn 1 (1.4 s, picks a tool, no text) → tool (1.4 s) → Claude turn 2 (1.9 s, answer).
Streaming moves the first answer text earlier, but the first ~2.8 s stays silent and the total time
doesn't change. Spoken replies wait for the full reply (decision 3), so streaming doesn't speed up
voice. What helps the *feel* during the silent part is the status line. It already existed
("thinking…" → "checking Buckt…" while the tool runs); the stream now sets "checking <App>…" as soon
as the `tool_use` block starts. Real speed-ups (prompt caching, warm apps, fewer round trips) stay in M6.
- B1. `SdkConversation`: `createStreaming` + `MessageAccumulator`; reports a `StreamUpdate` per event:
  `ToolStarted(claudeName)` from `content_block_start` with a `tool_use` block, `Text(piece)` from text
  deltas. Returns `accumulator.message()` as before, so `AgentLoop` and the history are unchanged.
  Stream closed on error/cancel (`use` + `ensureActive`). ✅
- B2. `ChatViewModel`: updates are applied on the main thread in order. `Text` → a reply bubble
  appears and grows, status clears. `ToolStarted` → status "checking <App>…" (app label from the
  routed tool), and any text written before the tool call ("Let me check…") is dropped. At the end
  the bubble is replaced with the final reply, so errors / give-up messages still show. ✅
- B3. Same code path in the assistant sheet → comes with the sheet chat (next).

**Part B result (2026-11-26 ~07:38, Poco, main app):** Ny0t: "yep, it works". Answers type in, no
crash, 30 unit tests pass. Logcat: 1st question tool + answer turn 5.2 s (cold bind after install),
2nd 1.4 s (warm). Replies were short (25–58 output tokens), so streaming shows most on longer answers.
The log has no send timestamp yet, so full press-to-answer time isn't measured.

### Part C — Voice input
- C1. `RECORD_AUDIO` permission (asked the first time the mic is used).
- C2. `VoiceInput` helper around `SpeechRecognizer` (Google's component, partial results shown live,
  errors → short message, falls back to typing).
- C3. Assistant sheet opens listening straight away; mic button in the main chat too.

### Part D — Spoken replies + sheet polish
- D1. `TextToSpeech` speaks the final reply when the question was spoken; stops when the sheet closes
  or Ny0t taps it.
- D2. Gate dialog inside the sheet; spoken hint "Tap Allow to save".
- D3. Sheet: listening state, partial text, streaming answer, close on outside tap.

## Device tests
| # | Test | Expect |
|---|------|--------|
| 1 | Long-press from the home screen | Sheet opens, listening; time it |
| 2 | Long-press over Buckt / Cleo / a browser | Sheet over the app; closing returns to it |
| 3 | Say "what's due for cleaning today?" | Right text recognised; streamed reply; spoken |
| 4 | Say a Malay spot name ("I cleaned singki dapur") | Recognised well enough, or Kitto asks; note the result |
| 5 | Say "I spent 12 ringgit on lunch" | Gate shows; spoken "Tap Allow"; Allow saves |
| 6 | Same → Deny | Nothing saved |
| 7 | Type instead of speaking | Text reply only, no speech |
| 8 | Main Kitto app chat | Streams too; old chat still works |
| 9 | No network | Clear error, no crash |
| 10 | Google voice typing in another app's keyboard after Kitto is assistant | Still works |
| 11 | Switch assistant back to Google | Google works again |

## Pre-check result (2026-11-25, Claude app as assistant)
- Long-press Home → MIUI `launch Google Assist` → system **does** show the session of whichever app holds
  the assistant role (Claude): AssistStructure built (screen content, 82 views), glow border
  (`AssistDisclosure`) shown. So MIUI routes to the default assistant. ✅
- Claude's session then started its overlay activity (`act=ASSIST … AppOnlyAssistantOverlay`) and MIUI
  blocked it: `MIUILOG- Permission Denied Activity`. Result: glow, then nothing.
- Cause: MIUI's own "start activity from background" permission. **Kitto's `startAssistantActivity()`
  plan hits the same wall.** Two ways out:
  1. MIUI app permission (Other permissions → "Display pop-up windows while running in the background"
     / "Start in background") → allow for Kitto (one-time). Being tested with Claude.
  2. Draw the UI inside the `VoiceInteractionSession` window itself (`onCreateContentView`), with no
     activity start, so nothing for MIUI to block. Compose there needs the lifecycle/saved-state owners set by hand.
- **Retest with option 1 → WORKS (18:50)**: Ny0t allowed Claude's background pop-up permission (MIUI app op
  `10021`, now `allow`) → long-press Home → Claude's overlay `Displayed +677ms`, ~0.8 s from press to
  screen. **Decision: keep `startAssistantActivity()` + this MIUI permission for Kitto** (one-time; for
  dev it can also be set with `adb shell appops set com.ny0t.kitto 10021 allow`). Kitto should spot the
  block and point the user to the setting (an app can't grant it to itself).

## Part A result (2026-11-25 ~22:00, Poco, Kitto as assistant + app op 10021 allowed)
- Long-press Home → "Kitto here" sheet over the current app. Log: session shown 22:00:45.520 → session hidden +90 ms → sheet first frame **346 ms** after onShow.
- `hide()` in `onShow` does NOT close the AssistActivity on MIUI → keep it (no onDestroy fallback needed).
- Tap outside → sheet closes, back to the app underneath (Ny0t confirmed).
- Quick Settings tile "Kitto" → sheet in **313 ms**.
- `voice_recognition_service` stayed on Google after the role switch (test 10 risk cleared).
- Verdict: **GO** — VoiceInteractionService + startAssistantActivity works on MIUI 14. Faster than the Claude app (~0.8 s).

## Risks
- **MIUI gestures**: MIUI may send long-press power to its own assistant / Google only. Spike A3
  finds out before anything else is built.
- **Recognizer default**: becoming the assistant may point the system recognizer at Kitto's stub and
  break voice typing elsewhere (test 10). Fix if so: stub forwards to Google's recognizer.
- **Malay + English mix**: Google's recognizer may mangle spot names; Kitto already asks when a name
  doesn't match (M4), so a bad guess can't write.
- **Background mic rules (Android 12)**: mic use must start from a visible activity — the sheet is one.

## Files (expected)
Kitto: `AndroidManifest.xml`, `res/xml/kitto_voice.xml`, `assist/KittoVoiceService.kt`,
`assist/KittoSessionService.kt`, `assist/KittoSession.kt`, `assist/KittoRecognitionService.kt`,
`assist/AssistActivity.kt`, `VoiceInput.kt`, `Speaker.kt`, `SdkConversation.kt`, `ChatViewModel.kt`,
`ChatScreen.kt`.

## Done when
Tests 1–11 pass or are noted on the Poco; Kitto unit tests green; Ny0t commits.
