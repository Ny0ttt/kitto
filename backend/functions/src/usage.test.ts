import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CALLS_PER_MINUTE, clock, DAILY_QUESTIONS, decide, estimateCostUsd, isNewQuestion } from "./usage.js";

describe("clock (Malaysia time)", () => {
  it("rolls the day over at midnight Malaysia time, not UTC", () => {
    // 2026-11-28 15:59 UTC = 23:59 MYT; 16:00 UTC = 00:00 next day MYT
    assert.equal(clock(new Date("2026-11-28T15:59:00Z")).day, "2026-11-28");
    assert.equal(clock(new Date("2026-11-28T16:00:00Z")).day, "2026-11-29");
  });
  it("gives the minute in 24-hour time", () => {
    assert.equal(clock(new Date("2026-11-28T06:05:30Z")).minute, "2026-11-28T14:05");
    assert.equal(clock(new Date("2026-11-28T16:00:00Z")).minute, "2026-11-29T00:00");
  });
});

describe("isNewQuestion", () => {
  const toolResults = { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: "{}" }] };
  it("counts a user text turn", () => {
    assert.equal(isNewQuestion([{ role: "user", content: "hi" }]), true);
    assert.equal(isNewQuestion([{ role: "user", content: [{ type: "text", text: "hi" }] }]), true);
  });
  it("doesn't count a tool-loop call (last message only tool results)", () => {
    assert.equal(isNewQuestion([{ role: "user", content: "hi" }, { role: "assistant", content: [] }, toolResults]), false);
  });
  it("counts a follow-up question after an earlier tool loop", () => {
    assert.equal(isNewQuestion([toolResults, { role: "assistant", content: [] }, { role: "user", content: "and?" }]), true);
  });
  it("doesn't count when the last message isn't the user's", () => {
    assert.equal(isNewQuestion([{ role: "assistant", content: "x" }]), false);
    assert.equal(isNewQuestion([]), false);
  });
});

describe("decide", () => {
  const minute = "2026-11-28T14:05";
  it("lets the first question of the day through and counts it", () => {
    const r = decide({}, true, minute);
    assert.ok("write" in r);
    assert.equal(r.write.questions, 1);
    assert.equal(r.write.minuteCalls, 1);
  });
  it("doesn't add a question for a tool-loop call", () => {
    const r = decide({ questions: 5 }, false, minute);
    assert.ok("write" in r);
    assert.equal(r.write.questions, 5);
  });
  it("refuses a new question once the daily limit is used", () => {
    const r = decide({ questions: DAILY_QUESTIONS }, true, minute);
    assert.ok("refusal" in r);
    assert.equal(r.refusal.status, 429);
    assert.match(r.refusal.message, /^LIMIT_REACHED:/);
  });
  it("still lets a tool-loop call finish the last question", () => {
    assert.ok("write" in decide({ questions: DAILY_QUESTIONS }, false, minute));
  });
  it("refuses past the per-minute limit in the same minute", () => {
    const r = decide({ minute, minuteCalls: CALLS_PER_MINUTE }, false, minute);
    assert.ok("refusal" in r);
    assert.match(r.refusal.message, /^RATE_LIMITED:/);
  });
  it("resets the per-minute count in a new minute", () => {
    const r = decide({ minute: "2026-11-28T14:04", minuteCalls: CALLS_PER_MINUTE }, false, minute);
    assert.ok("write" in r);
    assert.equal(r.write.minuteCalls, 1);
  });
});

describe("estimateCostUsd", () => {
  it("prices a short prompt at the low rate card", () => {
    // 1M input = $0.10, 1M output = $0.50 → 10K in + 1K out = $0.001 + $0.0005
    assert.ok(Math.abs(estimateCostUsd({ input_tokens: 10_000, output_tokens: 1_000 }) - 0.0015) < 1e-12);
  });
  it("prices cache reads at 0.1x and writes at 1.25x", () => {
    // 50K tokens stays on the short-prompt card: input rate $0.10 per million
    assert.ok(Math.abs(estimateCostUsd({ cache_read_input_tokens: 50_000 }) - 0.0005) < 1e-12);
    assert.ok(Math.abs(estimateCostUsd({ cache_creation_input_tokens: 50_000 }) - 0.00625) < 1e-12);
  });
  it("uses the high rate card above 100K prompt tokens", () => {
    assert.ok(Math.abs(estimateCostUsd({ input_tokens: 200_000 }) - 0.1) < 1e-12);
  });
  it("treats missing counts as zero", () => {
    assert.equal(estimateCostUsd({}), 0);
  });
});
