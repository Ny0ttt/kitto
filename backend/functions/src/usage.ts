/**
 * Usage counting and limits (M6 B4).
 *
 * One Firestore doc per user per day: users/{uid}/usage/{yyyy-mm-dd}, day in Asia/Kuala_Lumpur.
 * Counts only (questions, calls, tokens, estimated cost); never any message text (PDPA, Part D).
 */
import { FieldValue, type Firestore } from "firebase-admin/firestore";
import type { Refusal } from "./checks.js";

/** Questions per user per day. Override with DAILY_QUESTION_LIMIT (functions/.env) for the B7 limit test. */
export const DAILY_QUESTIONS = intFromEnv("DAILY_QUESTION_LIMIT", 50);
/** Calls per user per minute: a stuck-loop guard, far above what one question's tool loop needs. */
export const CALLS_PER_MINUTE = intFromEnv("CALLS_PER_MINUTE_LIMIT", 20);

const TIME_ZONE = "Asia/Kuala_Lumpur";

function intFromEnv(name: string, fallback: number): number {
  const value = Number(process.env[name]);
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

/** "2026-11-28" and "2026-11-28T14:05" in Malaysia time. */
export function clock(now: Date): { day: string; minute: string } {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: TIME_ZONE,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(now)
      .map((p) => [p.type, p.value]),
  );
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, minute: `${day}T${parts.hour}:${parts.minute}` };
}

/**
 * A "question" is a request whose last message is a new user turn. Tool-loop calls end with a user message
 * made only of tool_result blocks, so one question's 2-3 calls count once.
 */
export function isNewQuestion(messages: unknown[]): boolean {
  const last = messages[messages.length - 1] as { role?: unknown; content?: unknown } | undefined;
  if (!last || last.role !== "user") return false;
  if (typeof last.content === "string") return true;
  if (!Array.isArray(last.content)) return false;
  return !last.content.every((block) => (block as { type?: unknown })?.type === "tool_result");
}

/** What the day's doc holds that the limits read. */
export interface UsageSoFar {
  questions?: number;
  minute?: string;
  minuteCalls?: number;
}

/**
 * Decides whether this call may go ahead, given the day's counts so far.
 * Returns the refusal, or the fields to write when the call is let through.
 */
export function decide(
  soFar: UsageSoFar,
  question: boolean,
  minute: string,
): { refusal: Refusal } | { write: Record<string, unknown> } {
  const questions = soFar.questions ?? 0;
  const minuteCalls = soFar.minute === minute ? (soFar.minuteCalls ?? 0) : 0;

  if (question && questions >= DAILY_QUESTIONS) {
    return {
      refusal: {
        status: 429,
        type: "rate_limit_error",
        message: `LIMIT_REACHED: you've used all ${DAILY_QUESTIONS} questions for today. It resets at midnight.`,
      },
    };
  }
  if (minuteCalls >= CALLS_PER_MINUTE) {
    return {
      refusal: {
        status: 429,
        type: "rate_limit_error",
        message: "RATE_LIMITED: too many requests in a minute. Wait a moment and try again.",
      },
    };
  }
  return {
    write: {
      questions: questions + (question ? 1 : 0),
      calls: FieldValue.increment(1),
      minute,
      minuteCalls: minuteCalls + 1,
      updatedAt: FieldValue.serverTimestamp(),
    },
  };
}

/** Checks the limits and counts the call in one transaction, so two calls at once can't both slip past. */
export async function reserve(
  db: Firestore,
  uid: string,
  question: boolean,
  now = new Date(),
): Promise<{ refusal: Refusal } | { day: string }> {
  const { day, minute } = clock(now);
  const ref = db.doc(`users/${uid}/usage/${day}`);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const result = decide((snap.data() ?? {}) as UsageSoFar, question, minute);
    if ("refusal" in result) return result;
    tx.set(ref, result.write, { merge: true });
    return { day };
  });
}

/** Gives a question back when Claude refused before answering (overloaded, bad request), so it isn't lost. */
export async function refundQuestion(db: Firestore, uid: string, day: string): Promise<void> {
  await db.doc(`users/${uid}/usage/${day}`).set({ questions: FieldValue.increment(-1) }, { merge: true });
}

/** Token counts from the stream's message_start / message_delta usage. */
export interface TokenUsage {
  input_tokens?: number | null;
  output_tokens?: number | null;
  cache_creation_input_tokens?: number | null;
  cache_read_input_tokens?: number | null;
}

/**
 * Estimated cost in US dollars, Claude Haiku 5.5 rates (checked 2026-11-28): $0.10 / $0.50 per million input /
 * output tokens for prompts up to 100K tokens, $0.50 / $2.50 above; cache reads 0.1x input, 5-minute cache
 * writes 1.25x input. An estimate for watching spend, not a bill.
 */
export function estimateCostUsd(u: TokenUsage): number {
  const input = u.input_tokens ?? 0;
  const cacheWrite = u.cache_creation_input_tokens ?? 0;
  const cacheRead = u.cache_read_input_tokens ?? 0;
  const output = u.output_tokens ?? 0;
  const long = input + cacheWrite + cacheRead > 100_000;
  const inRate = (long ? 0.5 : 0.1) / 1_000_000;
  const outRate = (long ? 2.5 : 0.5) / 1_000_000;
  return input * inRate + cacheWrite * inRate * 1.25 + cacheRead * inRate * 0.1 + output * outRate;
}

/** Adds one call's tokens and cost to the day's doc. */
export async function recordTokens(db: Firestore, uid: string, day: string, u: TokenUsage): Promise<void> {
  await db.doc(`users/${uid}/usage/${day}`).set(
    {
      input_tokens: FieldValue.increment(u.input_tokens ?? 0),
      output_tokens: FieldValue.increment(u.output_tokens ?? 0),
      cache_creation_input_tokens: FieldValue.increment(u.cache_creation_input_tokens ?? 0),
      cache_read_input_tokens: FieldValue.increment(u.cache_read_input_tokens ?? 0),
      cost_usd: FieldValue.increment(estimateCostUsd(u)),
    },
    { merge: true },
  );
}
