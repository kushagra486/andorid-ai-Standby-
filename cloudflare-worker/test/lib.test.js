import test from "node:test";
import assert from "node:assert/strict";
import { validateAction, parseModelJson, shapeReply, validateRequest, safeEqual } from "../src/lib.js";
import worker from "../src/index.js";

test("allowlisted actions pass and are normalised", () => {
  assert.deepEqual(validateAction({ type: "set_timer", args: { minutes: "5" } }), { type: "SET_TIMER", args: { minutes: 5 } });
  assert.deepEqual(validateAction({ type: "SHOW_SCREEN", args: { screen: "Dashboard" } }), { type: "SHOW_SCREEN", args: { screen: "dashboard" } });
});

test("unknown actions, extra args, links and bad ranges are rejected", () => {
  assert.equal(validateAction({ type: "RUN_SHELL", args: { cmd: "ls" } }), null);
  assert.equal(validateAction({ type: "SET_TIMER", args: { minutes: 5, x: 1 } }), null);
  assert.equal(validateAction({ type: "SPOTIFY_PLAY", args: { query: "https://evil.example" } }), null);
  assert.equal(validateAction({ type: "SET_VOLUME", args: { level: 101 } }), null);
  assert.equal(validateAction({ type: "ADD_REMINDER", args: { text: "x", at: "24:00" } }), null);
  assert.equal(validateAction({ type: "SET_TIMER", args: { minutes: { nested: 1 } } }), null);
  assert.equal(validateAction("SET_TIMER"), null);
});

test("model json is extracted from fenced or chatty output", () => {
  assert.deepEqual(parseModelJson('Sure!\n```json\n{"reply":"Hi","action":null}\n```'), { reply: "Hi", action: null });
  assert.equal(parseModelJson("no json here"), null);
  const shaped = shapeReply({ reply: "ok", action: { type: "DELETE_FILES" } });
  assert.equal(shaped.action, null);
});

test("request validation", () => {
  assert.equal(typeof validateRequest({}), "string");
  assert.equal(typeof validateRequest({ text: "" }), "string");
  assert.equal(typeof validateRequest({ text: "x".repeat(501) }), "string");
  assert.deepEqual(validateRequest({ text: " hi ", context: { time: "9:41", big: "x".repeat(500) } }), { text: "hi", context: { time: "9:41" } });
});

test("token comparison", () => {
  assert.ok(safeEqual("abc", "abc"));
  assert.ok(!safeEqual("abc", "abd"));
  assert.ok(!safeEqual("", ""));
  assert.ok(!safeEqual("abc", "abcd"));
});

const env = (extra = {}) => ({
  NOVA_ACCESS_TOKEN: "secret-token",
  AI: { run: async () => ({ response: '{"reply":"Timer started","action":{"type":"SET_TIMER","args":{"minutes":3}}}' }) },
  ...extra,
});
const chat = (headers, body) => new Request("https://nova.test/v1/chat", { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });

test("health endpoint exposes no secrets", async () => {
  const r = await worker.fetch(new Request("https://nova.test/v1/health"), env({ GEMINI_API_KEY: "k" }));
  const j = await r.json();
  assert.equal(r.status, 200); assert.equal(j.provider, "gemini");
  assert.ok(!JSON.stringify(j).includes("secret-token") && !JSON.stringify(j).includes('"k"'));
});

test("chat requires the access token", async () => {
  assert.equal((await worker.fetch(chat({}, { text: "hi" }), env())).status, 401);
  assert.equal((await worker.fetch(chat({ authorization: "Bearer wrong" }, { text: "hi" }), env())).status, 401);
});

test("chat returns validated reply via Workers AI", async () => {
  const r = await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "set a 3 minute timer" }), env());
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { reply: "Timer started", action: { type: "SET_TIMER", args: { minutes: 3 } } });
});

test("rate limiting and bad input", async () => {
  const limited = env({ RATE_LIMITER: { limit: async () => ({ success: false }) } });
  assert.equal((await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "hi" }), limited)).status, 429);
  assert.equal((await worker.fetch(chat({ authorization: "Bearer secret-token" }, { nope: 1 }), env())).status, 400);
});

test("provider failure is reported without content", async () => {
  const broken = env({ AI: { run: async () => { throw new Error("boom"); } } });
  const r = await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "hi" }), broken);
  assert.equal(r.status, 502);
});

test("provider selection prefers keys, honours AI_PROVIDER, falls back to Workers AI", async () => {
  const { provider } = await import("../src/index.js");
  assert.equal(provider({ AI: {} }), "workers-ai");
  assert.equal(provider({ AI: {}, GROQ_API_KEY: "g" }), "groq");
  assert.equal(provider({ AI: {}, OPENROUTER_API_KEY: "o", NVIDIA_API_KEY: "n" }), "openrouter");
  assert.equal(provider({ AI: {}, GROQ_API_KEY: "g", AI_PROVIDER: "workers-ai" }), "workers-ai");
  assert.equal(provider({ AI: {}, AI_PROVIDER: "nvidia" }), "workers-ai");      // forced provider without key is ignored
  assert.equal(provider({}), "none");
});

test("OpenAI-compatible providers (Groq) are called with the key and validated", async () => {
  const realFetch = globalThis.fetch;
  let seen;
  globalThis.fetch = async (url, init) => {
    seen = { url, auth: init.headers.authorization, body: JSON.parse(init.body) };
    return new Response(JSON.stringify({ choices: [{ message: { content: '{"reply":"Hi!","action":{"type":"RUN_SHELL"}}' } }] }), { status: 200 });
  };
  try {
    const r = await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "hello" }), env({ GROQ_API_KEY: "gk" }));
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), { reply: "Hi!", action: null });
    assert.match(seen.url, /api\.groq\.com/);
    assert.equal(seen.auth, "Bearer gk");
    assert.equal(seen.body.model, "openai/gpt-oss-20b");
    assert.equal(seen.body.reasoning_effort, "low");
  } finally { globalThis.fetch = realFetch; }
});

test("unauthenticated requests are rate limited by IP before the token check", async () => {
  const keys = [];
  const limiter = env({ RATE_LIMITER: { limit: async ({ key }) => { keys.push(key); return { success: !key.startsWith("ip:") }; } } });
  const r = await worker.fetch(chat({ authorization: "Bearer wrong", "cf-connecting-ip": "1.2.3.4", "x-nova-install": "rotating" }, { text: "hi" }), limiter);
  assert.equal(r.status, 429);
  assert.deepEqual(keys, ["ip:1.2.3.4"]);
});


test("Groq model failure falls back to the next model, then to Workers AI", async () => {
  const realFetch = globalThis.fetch;
  const tried = [];
  globalThis.fetch = async (url, init) => {
    const body = JSON.parse(init.body); tried.push(body.model);
    if (body.model === "openai/gpt-oss-20b") return new Response('{"error":{"message":"model not available"}}', { status: 404 });
    return new Response(JSON.stringify({ choices: [{ message: { content: '{"reply":"From 8b","action":null}' } }] }), { status: 200 });
  };
  try {
    const r = await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "hi" }), env({ GROQ_API_KEY: "gk" }));
    assert.deepEqual(await r.json(), { reply: "From 8b", action: null });
    assert.deepEqual(tried, ["openai/gpt-oss-20b", "llama-3.1-8b-instant"]);

    // Every Groq model failing → the built-in Workers AI answers instead of an error.
    globalThis.fetch = async () => new Response("{}", { status: 500 });
    const r2 = await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "hi" }), env({ GROQ_API_KEY: "gk" }));
    assert.equal(r2.status, 200);
    assert.equal((await r2.json()).reply, "Timer started");

    // A rejected key is reported with the upstream status when there is no fallback.
    globalThis.fetch = async () => new Response("{}", { status: 401 });
    const noAi = env({ GROQ_API_KEY: "bad" }); delete noAi.AI;
    const r3 = await worker.fetch(chat({ authorization: "Bearer secret-token" }, { text: "hi" }), noAi);
    assert.equal(r3.status, 502);
    assert.deepEqual(await r3.json(), { error: "provider error", provider: "groq", upstream_status: 401 });
  } finally { globalThis.fetch = realFetch; }
});
