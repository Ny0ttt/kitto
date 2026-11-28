/**
 * The gate's rules as small pure functions, so each can be unit-tested without Firebase or Claude.
 * index.ts calls them in order and turns a refusal into Claude's error shape.
 */

/** The only model Kitto may use through the backend. */
export const MODEL = "claude-haiku-5-5";
/** Kitto asks for 16000 today (SdkConversation.kt); nothing above that gets through. */
export const MAX_TOKENS_CAP = 16_000;
/** Biggest request body accepted. A long Kitto conversation with tool results is well under this. */
export const MAX_BODY_BYTES = 1_000_000;

/** Top-level request fields passed on to Claude. Anything else is refused, not silently dropped. */
const ALLOWED_FIELDS = new Set([
  "model",
  "max_tokens",
  "messages",
  "system",
  "tools",
  "tool_choice",
  "output_config",
  "metadata",
  "stream",
]);

export interface Refusal {
  status: number;
  type: string; // Claude's error type, so anthropic-java raises its matching exception
  message: string; // starts with a code Kitto can match on (B6), then words a person can read
}

/** A signed-in user's details that the allowlist needs (a subset of firebase-admin's DecodedIdToken). */
export interface SignedInUser {
  email?: string;
  email_verified?: boolean;
}

/**
 * Allowlist: the user's email must be verified (Google sign-in always is) and have a doc `allowlist/{email}`.
 * Doc IDs are the lowercase email. `exists` looks the doc up; passed in so tests need no Firestore.
 */
export async function checkAllowlist(
  user: SignedInUser,
  exists: (docPath: string) => Promise<boolean>,
): Promise<Refusal | null> {
  const email = user.email?.trim().toLowerCase();
  if (!email || user.email_verified !== true || email.includes("/")) {
    return notAllowed();
  }
  return (await exists(`allowlist/${email}`)) ? null : notAllowed();
}

function notAllowed(): Refusal {
  return {
    status: 403,
    type: "permission_error",
    message: "NOT_ALLOWED: this account isn't on Kitto's list yet. Ask Ny0t to add you.",
  };
}

/** Request rules. Returns null when the body may go to Claude unchanged. */
export function checkBody(body: unknown): Refusal | null {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return badRequest("The request body must be a JSON object.");
  }
  const fields = body as Record<string, unknown>;

  const unknown = Object.keys(fields).filter((key) => !ALLOWED_FIELDS.has(key));
  if (unknown.length > 0) {
    return badRequest(`Not allowed through Kitto's backend: ${unknown.join(", ")}.`);
  }
  if (fields.model !== MODEL) {
    return badRequest(`Only ${MODEL} is available through Kitto's backend.`);
  }
  const maxTokens = fields.max_tokens;
  if (typeof maxTokens !== "number" || !Number.isInteger(maxTokens) || maxTokens < 1 || maxTokens > MAX_TOKENS_CAP) {
    return badRequest(`max_tokens must be a whole number from 1 to ${MAX_TOKENS_CAP}.`);
  }
  if (!Array.isArray(fields.messages) || fields.messages.length === 0) {
    return badRequest("messages must be a non-empty list.");
  }
  if (fields.stream !== true) {
    return badRequest("Kitto's backend only serves streaming requests (stream: true).");
  }
  // messages, system and tools pass through untouched: Kitto's history must reach Claude exactly as sent.
  return null;
}

function badRequest(message: string): Refusal {
  return { status: 400, type: "invalid_request_error", message: `BAD_REQUEST: ${message}` };
}

export function appCheckFailed(): Refusal {
  return {
    status: 401,
    type: "authentication_error",
    message: "APP_CHECK_FAILED: this request didn't come from the Kitto app.",
  };
}

export function tooLarge(): Refusal {
  return { status: 413, type: "request_too_large", message: "BAD_REQUEST: the request is too large." };
}
