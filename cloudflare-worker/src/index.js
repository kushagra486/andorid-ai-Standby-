// NØVA AI proxy — Cloudflare Worker.
// The phone sends minimal text; the provider key stays here as a Worker secret.
// Endpoints: GET /v1/health, POST /v1/chat. No prompt or reply content is logged.
import { VERSION, SYSTEM_PROMPT, parseModelJson, safeEqual, shapeReply, validateRequest } from "./lib.js";

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });

function provider(env) {
  if (env.GEMINI_API_KEY) return "gemini";
  if (env.AI) return "workers-ai";
  return "none";
}

async function askGemini(env, text, context) {
  const model = env.GEMINI_MODEL || "gemini-2.5-flash";
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
      contents: [{ role: "user", parts: [{ text: `Context: ${JSON.stringify(context)}\nUser: ${text}` }] }],
      generationConfig: { responseMimeType: "application/json", maxOutputTokens: 300, temperature: 0.6 },
    }),
  });
  if (res.status === 429) throw Object.assign(new Error("provider quota"), { status: 429 });
  if (!res.ok) throw Object.assign(new Error(`provider ${res.status}`), { status: 502 });
  const data = await res.json();
  return data?.candidates?.[0]?.content?.parts?.map((p) => p.text || "").join("") || "";
}

async function askWorkersAI(env, text, context) {
  const model = env.WORKERS_AI_MODEL || "@cf/meta/llama-3.1-8b-instruct";
  const out = await env.AI.run(model, {
    messages: [
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user", content: `Context: ${JSON.stringify(context)}\nUser: ${text}` },
    ],
    max_tokens: 300,
  });
  return typeof out?.response === "string" ? out.response : JSON.stringify(out?.response ?? "");
}

async function rateLimited(env, key) {
  if (!env.RATE_LIMITER) return false;           // binding optional (wrangler.jsonc)
  const { success } = await env.RATE_LIMITER.limit({ key });
  return !success;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/v1/health" && request.method === "GET") {
      return json({ ok: true, version: VERSION, provider: provider(env) });
    }

    if (url.pathname !== "/v1/chat") return json({ error: "not found" }, 404);
    if (request.method !== "POST") return json({ error: "method not allowed" }, 405);

    // Authenticate: owner-issued access token (Worker secret NOVA_ACCESS_TOKEN).
    const auth = request.headers.get("authorization") || "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!env.NOVA_ACCESS_TOKEN || !safeEqual(token, env.NOVA_ACCESS_TOKEN)) return json({ error: "unauthorized" }, 401);

    // Rate limit per installation (falls back to client IP).
    const install = (request.headers.get("x-nova-install") || request.headers.get("cf-connecting-ip") || "anon").slice(0, 64);
    if (await rateLimited(env, install)) return json({ error: "rate limited" }, 429);

    if ((Number(request.headers.get("content-length")) || 0) > 4096) return json({ error: "too large" }, 413);
    let body;
    try { body = await request.json(); } catch { return json({ error: "invalid json" }, 400); }
    const req = validateRequest(body);
    if (typeof req === "string") return json({ error: req }, 400);

    const p = provider(env);
    if (p === "none") return json({ error: "no AI provider configured" }, 503);
    try {
      const raw = p === "gemini" ? await askGemini(env, req.text, req.context) : await askWorkersAI(env, req.text, req.context);
      const parsed = parseModelJson(raw);
      return json(shapeReply(parsed, parsed ? "" : raw));
    } catch (e) {
      console.log(JSON.stringify({ event: "provider_error", status: e.status || 500 }));   // no content logged
      return json({ error: "provider error" }, e.status === 429 ? 429 : 502);
    }
  },
};
