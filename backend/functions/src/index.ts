/**
 * Kitto backend: the `claude` function.
 *
 * Kitto's anthropic-java client points its base URL at this function and calls POST /v1/messages exactly as
 * it would call the Claude API. The function checks who is calling, then forwards the request to Claude with
 * the real API key (kept in Secret Manager, never on the phone) and streams the reply back as SSE.
 *
 * Gate, in order: path → size → signed in (B2) → App Check → allowlist → request rules (B3).
 * → limits + usage counting (B4, usage.ts) → prompt caching markers added (B5, caching.ts).
 *
 * API version: Kitto's anthropic-java 3.3.0 and this @anthropic-ai/sdk 1.x both send anthropic-version
 * 2026-10-21, so the events re-sent here have the shape Kitto expects. Keep the two SDKs on the same version.
 */
import Anthropic from "@anthropic-ai/sdk";
import { initializeApp } from "firebase-admin/app";
import { getAppCheck } from "firebase-admin/app-check";
import { getAuth, type DecodedIdToken } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";
import { onRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import type { Response } from "express";
import {
  appCheckFailed,
  checkAllowlist,
  checkBody,
  MAX_BODY_BYTES,
  tooLarge,
  type Refusal,
} from "./checks.js";
import { withCaching } from "./caching.js";
import { isNewQuestion, recordTokens, refundQuestion, reserve, type TokenUsage } from "./usage.js";

initializeApp();

const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");
/** True only inside the local emulator, which can't mint real App Check tokens. Never set when deployed. */
const IN_EMULATOR = process.env.FUNCTIONS_EMULATOR === "true";

export const claude = onRequest(
  {
    region: "us-central1", // next to Firestore (nam5) and Claude's servers; see plan/m6-hardening.md B1 note
    secrets: [ANTHROPIC_API_KEY],
    timeoutSeconds: 300, // a long tool-loop turn can stream for a while
    memory: "256MiB",
    minInstances: 0, // no idle cost; cold start accepted (measure in B7)
    maxInstances: 5, // ~10 family users; also caps runaway spend
    invoker: "public", // Cloud Run lets everyone reach it; our own checks below decide who gets an answer
  },
  async (req, res) => {
    if (req.method !== "POST" || req.path !== "/v1/messages") {
      sendError(res, 404, "not_found_error", "Only POST /v1/messages is served here.");
      return;
    }
    if ((req.rawBody?.length ?? 0) > MAX_BODY_BYTES) {
      refuse(res, tooLarge());
      return;
    }

    // 1. Who is calling: a valid Firebase ID token, sent the way anthropic-java sends an auth token.
    const token = /^Bearer (.+)$/.exec(req.get("authorization") ?? "")?.[1];
    if (!token) {
      sendError(res, 401, "authentication_error", "Sign in to use Kitto.");
      return;
    }
    let user: DecodedIdToken;
    try {
      user = await getAuth().verifyIdToken(token);
    } catch {
      sendError(res, 401, "authentication_error", "Your sign-in has expired. Sign in again.");
      return;
    }

    // 2. Really the Kitto app: a valid App Check token (Play Integrity, or a registered debug token).
    if (!IN_EMULATOR) {
      const appCheckToken = req.get("x-firebase-appcheck");
      try {
        if (!appCheckToken) throw new Error("missing");
        await getAppCheck().verifyToken(appCheckToken);
      } catch {
        refuse(res, appCheckFailed());
        return;
      }
    }

    // 3. On the list: allowlist/{email} exists in Firestore.
    const allowRefusal = await checkAllowlist(user, async (path) => (await getFirestore().doc(path).get()).exists);
    if (allowRefusal) {
      refuse(res, allowRefusal);
      return;
    }

    // 4. Request rules: Haiku only, max_tokens capped, known fields only, streaming only.
    const bodyRefusal = checkBody(req.body);
    if (bodyRefusal) {
      refuse(res, bodyRefusal);
      return;
    }

    // 5. Limits: 50 questions a day, 20 calls a minute. Counted before calling Claude, in one transaction.
    const db = getFirestore();
    const question = isNewQuestion((req.body as { messages: unknown[] }).messages);
    const reservation = await reserve(db, user.uid, question);
    if ("refusal" in reservation) {
      refuse(res, reservation.refusal);
      return;
    }
    const { day } = reservation;

    // 6. Forward to Claude and stream the reply back event by event.
    const client = new Anthropic({ apiKey: ANTHROPIC_API_KEY.value() });
    let stream;
    try {
      stream = await client.messages.create(withCaching(req.body as Record<string, unknown>) as unknown as Anthropic.MessageCreateParamsStreaming);
    } catch (err) {
      // Claude refused before streaming started (bad request, overloaded, ...): pass its status and error on,
      // and give the question back, since nothing was answered.
      if (question) await refundQuestion(db, user.uid, day).catch((e) => logger.error("refund failed", e));
      if (err instanceof Anthropic.APIError && err.status) {
        res.status(err.status).json(err.error ?? errorBody("api_error", err.message));
      } else {
        sendError(res, 502, "api_error", "Couldn't reach Claude. Try again.");
      }
      return;
    }

    // If Kitto hangs up (screen closed, cancelled), stop paying for the rest of the reply.
    res.on("close", () => stream.controller.abort());

    res.status(200);
    res.set({
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    res.flushHeaders();

    const usage: TokenUsage = {};
    try {
      for await (const event of stream) {
        res.write(`event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
        // message_start carries the input counts; message_delta carries the final totals.
        if (event.type === "message_start") Object.assign(usage, pickUsage(event.message.usage));
        if (event.type === "message_delta") Object.assign(usage, pickUsage(event.usage));
      }
    } catch (err) {
      if (!stream.controller.signal.aborted) {
        // Headers are already sent, so report the failure the way Claude does mid-stream: an `error` event.
        const message = err instanceof Error ? err.message : "Stream failed.";
        res.write(`event: error\ndata: ${JSON.stringify(errorBody("api_error", message))}\n\n`);
      }
    }
    res.end();

    // Counts only, after the reply is sent, so it never slows Kitto down. A failure here is logged, not shown.
    await recordTokens(db, user.uid, day, usage).catch((e) => logger.error("recording usage failed", e));
  },
);

/** Keeps only the token counts that are present (message_delta can leave some null). */
function pickUsage(u: object): TokenUsage {
  const out: Record<string, number> = {};
  for (const key of ["input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"]) {
    const value = (u as Record<string, unknown>)[key];
    if (typeof value === "number") out[key] = value;
  }
  return out;
}

/** Claude's error shape, so anthropic-java turns our refusals into its normal typed exceptions. */
function errorBody(type: string, message: string) {
  return { type: "error", error: { type, message } };
}

function sendError(res: Response, status: number, type: string, message: string) {
  res.status(status).json(errorBody(type, message));
}

function refuse(res: Response, refusal: Refusal) {
  // Our refusals won't change on retry; this header tells the SDK not to retry them (it retries 429s by default).
  res.set("x-should-retry", "false");
  sendError(res, refusal.status, refusal.type, refusal.message);
}
