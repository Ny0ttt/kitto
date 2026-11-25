# M5 — Kitto as the phone's assistant

**Status:** decisions locked 2026-11-25 (Ny0t agreed to all five). Next: Part A spike. Optional pre-check: set the Claude app as the assistant and see if MIUI's power-hold opens it.

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

### Part B — Streaming replies
- B1. `SdkConversation`: `createStreaming` + `MessageAccumulator`; pass text deltas to a callback;
  return `accumulator.message()` as today. Close the stream on cancel.
- B2. `ChatViewModel`: the assistant bubble grows as text arrives; tool-call turns unchanged.
- B3. Same in the main app chat (one code path).

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
