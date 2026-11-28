import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { checkAllowlist, checkBody, MAX_TOKENS_CAP, MODEL } from "./checks.js";

const good = () => ({
  model: MODEL,
  max_tokens: MAX_TOKENS_CAP,
  stream: true,
  system: "You are Kitto.",
  tools: [],
  output_config: { effort: "low" },
  messages: [{ role: "user", content: "hi" }],
});

describe("checkAllowlist", () => {
  const listed = new Set(["allowlist/ny0t@example.com"]);
  const exists = async (path: string) => listed.has(path);

  it("lets a verified, listed email in", async () => {
    assert.equal(await checkAllowlist({ email: "ny0t@example.com", email_verified: true }, exists), null);
  });
  it("matches the email case-insensitively", async () => {
    assert.equal(await checkAllowlist({ email: "Ny0t@Example.com", email_verified: true }, exists), null);
  });
  it("refuses an email that isn't listed", async () => {
    assert.equal((await checkAllowlist({ email: "x@example.com", email_verified: true }, exists))?.status, 403);
  });
  it("refuses an unverified email even if listed", async () => {
    assert.equal((await checkAllowlist({ email: "ny0t@example.com", email_verified: false }, exists))?.status, 403);
  });
  it("refuses a user with no email", async () => {
    assert.equal((await checkAllowlist({ email_verified: true }, exists))?.status, 403);
  });
  it("refuses an email that would change the doc path", async () => {
    let looked = false;
    const spy = async () => ((looked = true), true);
    assert.equal((await checkAllowlist({ email: "a/b@example.com", email_verified: true }, spy))?.status, 403);
    assert.equal(looked, false);
  });
  it("uses the NOT_ALLOWED code", async () => {
    const r = await checkAllowlist({ email: "x@example.com", email_verified: true }, exists);
    assert.match(r!.message, /^NOT_ALLOWED:/);
    assert.equal(r!.type, "permission_error");
  });
});

describe("checkBody", () => {
  it("passes Kitto's normal request", () => {
    assert.equal(checkBody(good()), null);
  });
  it("passes tool_choice and metadata", () => {
    assert.equal(checkBody({ ...good(), tool_choice: { type: "auto" }, metadata: { user_id: "u" } }), null);
  });
  it("refuses another model", () => {
    assert.match(checkBody({ ...good(), model: "claude-opus-5-5" })!.message, /Only claude-haiku-5-5/);
  });
  it("refuses a missing model", () => {
    const { model: _model, ...rest } = good();
    assert.equal(checkBody(rest)?.status, 400);
  });
  it("refuses max_tokens over the cap, zero, or not a whole number", () => {
    for (const max_tokens of [MAX_TOKENS_CAP + 1, 0, 10.5, "100", undefined]) {
      assert.equal(checkBody({ ...good(), max_tokens })?.status, 400, `max_tokens=${String(max_tokens)}`);
    }
  });
  it("refuses unknown fields and names them", () => {
    const r = checkBody({ ...good(), temperature: 1, betas: ["x"] });
    assert.equal(r?.status, 400);
    assert.match(r!.message, /temperature, betas/);
  });
  it("refuses empty or missing messages", () => {
    assert.equal(checkBody({ ...good(), messages: [] })?.status, 400);
    assert.equal(checkBody({ ...good(), messages: "hi" })?.status, 400);
  });
  it("refuses non-streaming requests", () => {
    assert.equal(checkBody({ ...good(), stream: false })?.status, 400);
    const { stream: _stream, ...rest } = good();
    assert.equal(checkBody(rest)?.status, 400);
  });
  it("refuses a body that isn't an object", () => {
    for (const body of [null, [], "x", 5]) assert.equal(checkBody(body)?.status, 400);
  });
});
