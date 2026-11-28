import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { withCaching } from "./caching.js";

const base = { model: "claude-haiku-5-5", max_tokens: 100, stream: true, messages: [{ role: "user", content: "hi" }] };

describe("withCaching", () => {
  it("turns a string system prompt into one marked block with the same text", () => {
    const out = withCaching({ ...base, system: "You are Kitto." });
    assert.deepEqual(out.system, [{ type: "text", text: "You are Kitto.", cache_control: { type: "ephemeral" } }]);
  });
  it("marks only the last block of a block-list system prompt", () => {
    const out = withCaching({ ...base, system: [{ type: "text", text: "a" }, { type: "text", text: "b" }] });
    assert.deepEqual(out.system, [
      { type: "text", text: "a" },
      { type: "text", text: "b", cache_control: { type: "ephemeral" } },
    ]);
  });
  it("leaves an existing marker on the last block alone", () => {
    const marked = { type: "text", text: "b", cache_control: { type: "ephemeral", ttl: "1h" } };
    const out = withCaching({ ...base, system: [marked] });
    assert.deepEqual(out.system, [marked]);
  });
  it("adds top-level automatic caching", () => {
    assert.deepEqual(withCaching({ ...base }).cache_control, { type: "ephemeral" });
  });
  it("works without a system prompt and doesn't add one", () => {
    const out = withCaching({ ...base });
    assert.equal("system" in out, false);
  });
  it("doesn't change messages, tools or the input object", () => {
    const tools = [{ name: "t", input_schema: { type: "object" } }];
    const input = { ...base, system: "s", tools };
    const out = withCaching(input);
    assert.equal(out.messages, input.messages);
    assert.equal(out.tools, tools);
    assert.equal(input.system, "s");
  });
  it("gives identical output for identical input (stable prefix)", () => {
    const a = JSON.stringify(withCaching({ ...base, system: "s" }));
    const b = JSON.stringify(withCaching({ ...base, system: "s" }));
    assert.equal(a, b);
  });
});
