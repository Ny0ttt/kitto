/**
 * Prompt caching (M6 B5), added here so Kitto's code doesn't change.
 *
 * Claude renders tools → system → messages. Two breakpoints, the pattern recommended for agent loops:
 *  1. An explicit marker on the last system block: caches Kitto's tools + system prompt (the part that never
 *     changes within a conversation) with a guaranteed read point.
 *  2. Top-level automatic caching: Claude moves a second marker to the end of the conversation as it grows,
 *     so a tool-loop call reads everything the previous call already sent.
 * Haiku 5.5: prefixes under 512 tokens silently don't cache; reads cost 0.1x input, 5-minute writes 1.25x.
 *
 * The prompt's content is not changed, only marked: a string system prompt becomes the same text as one block.
 * Every request gets the same treatment, so the prefix stays identical from call to call.
 */
type TextBlock = { type: string; text?: string; cache_control?: unknown; [key: string]: unknown };

const EPHEMERAL = { type: "ephemeral" } as const;

export function withCaching(body: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...body, cache_control: EPHEMERAL };

  if (typeof body.system === "string" && body.system.length > 0) {
    out.system = [{ type: "text", text: body.system, cache_control: EPHEMERAL }];
  } else if (Array.isArray(body.system) && body.system.length > 0) {
    const blocks = body.system as TextBlock[];
    const last = blocks[blocks.length - 1];
    out.system = [...blocks.slice(0, -1), last.cache_control ? last : { ...last, cache_control: EPHEMERAL }];
  }
  return out;
}
