// NØVA AI proxy — Cloudflare Worker.
// The phone sends minimal text; the provider key stays here as a Worker secret.
// Endpoints: GET /v1/health, POST /v1/chat. No prompt or reply content is logged.
import { VERSION, SYSTEM_PROMPT, parseModelJson, safeEqual, shapeReply, validateRequest } from "./lib.js";

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });

// OpenAI-compatible providers with free tiers. Model names change over time: override with
// the GROQ_MODEL / OPENROUTER_MODEL / NVIDIA_MODEL vars in wrangler.jsonc.
const OPENAI_COMPAT = {
  // Free-tier friendly models first; the configured GROQ_MODEL is always tried first.
  groq: { key: "GROQ_API_KEY", url: "https://api.groq.com/openai/v1/chat/completions", modelVar: "GROQ_MODEL", model: "openai/gpt-oss-20b", fallbacks: ["llama-3.1-8b-instant"] },
  openrouter: { key: "OPENROUTER_API_KEY", url: "https://openrouter.ai/api/v1/chat/completions", modelVar: "OPENROUTER_MODEL", model: "meta-llama/llama-3.3-70b-instruct:free" },
  nvidia: { key: "NVIDIA_API_KEY", url: "https://integrate.api.nvidia.com/v1/chat/completions", modelVar: "NVIDIA_MODEL", model: "meta/llama-3.3-70b-instruct" },
};

/** Which provider answers: AI_PROVIDER var if set and usable, else the first key found, else Workers AI. */
export function provider(env) {
  const usable = (p) =>
    (p === "gemini" && env.GEMINI_API_KEY) || (OPENAI_COMPAT[p] && env[OPENAI_COMPAT[p].key]) || (p === "workers-ai" && env.AI);
  if (env.AI_PROVIDER && usable(env.AI_PROVIDER)) return env.AI_PROVIDER;
  for (const p of ["groq", "openrouter", "nvidia", "gemini", "workers-ai"]) if (usable(p)) return p;
  return "none";
}

async function askOpenAICompat(env, name, text, context) {
  const cfg = OPENAI_COMPAT[name];
  const headers = { "content-type": "application/json", authorization: `Bearer ${env[cfg.key]}` };
  if (name === "openrouter") { headers["HTTP-Referer"] = "https://github.com/nova-zenfone"; headers["X-Title"] = "NOVA"; }
  // Try the configured model, then the built-in defaults: models get retired or moved off free tiers.
  const models = [...new Set([env[cfg.modelVar], cfg.model, ...(cfg.fallbacks || [])].filter(Boolean))];
  let last = null;
  for (const model of models) {
    const body = {
      model,
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: `Context: ${JSON.stringify(context)}\nUser: ${text}` },
      ],
      max_tokens: 700,
      temperature: 0.6,
    };
    // Reasoning models: keep thinking short so the answer fits, and don't return the reasoning.
    if (/gpt-oss/.test(model)) { body.reasoning_effort = "low"; body.include_reasoning = false; }
    const res = await fetch(cfg.url, { method: "POST", headers, body: JSON.stringify(body) });
    if (res.status === 429) throw Object.assign(new Error("provider quota"), { status: 429, upstream: 429 });
    if (res.status === 401 || res.status === 403) {
      throw Object.assign(new Error("provider rejected key"), { status: 502, upstream: res.status });   // another model won't help
    }
    if (!res.ok) { last = Object.assign(new Error(`provider ${res.status}`), { status: 502, upstream: res.status, model }); continue; }
    const data = await res.json();
    const content = data?.choices?.[0]?.message?.content || "";
    if (content.trim()) return content;
    last = Object.assign(new Error("empty reply"), { status: 502, upstream: 200, model });
  }
  throw last || Object.assign(new Error("no model"), { status: 502 });
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
      return json({ ok: true, version: VERSION, provider: provider(env) });   // provider name only, never keys
    }

    if (url.pathname !== "/v1/chat") return json({ error: "not found" }, 404);
    if (request.method !== "POST") return json({ error: "method not allowed" }, 405);

    // Rate limit by client IP first, so tokens can't be brute-forced by rotating headers.
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    if (await rateLimited(env, "ip:" + ip)) return json({ error: "rate limited" }, 429);

    // Authenticate: owner-issued access token (Worker secret NOVA_ACCESS_TOKEN).
    const auth = request.headers.get("authorization") || "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!env.NOVA_ACCESS_TOKEN || !safeEqual(token, env.NOVA_ACCESS_TOKEN)) return json({ error: "unauthorized" }, 401);

    // Then per installation.
    const install = (request.headers.get("x-nova-install") || ip).slice(0, 64);
    if (await rateLimited(env, "install:" + install)) return json({ error: "rate limited" }, 429);

    if ((Number(request.headers.get("content-length")) || 0) > 4096) return json({ error: "too large" }, 413);
    let body;
    try { body = await request.json(); } catch { return json({ error: "invalid json" }, 400); }
    const req = validateRequest(body);
    if (typeof req === "string") return json({ error: req }, 400);

    const p = provider(env);
    if (p === "none") return json({ error: "no AI provider configured" }, 503);
    const ask = (name) => name === "gemini" ? askGemini(env, req.text, req.context)
      : name === "workers-ai" ? askWorkersAI(env, req.text, req.context)
      : askOpenAICompat(env, name, req.text, req.context);
    let raw;
    try {
      raw = await ask(p);
    } catch (e) {
      // No content is logged — only which provider failed and its HTTP status.
      console.log(JSON.stringify({ event: "provider_error", provider: p, upstream: e.upstream || null, model: e.model || null }));
      // Fall back to the built-in Workers AI so the user still gets an answer.
      if (p !== "workers-ai" && env.AI) {
        try { raw = await ask("workers-ai"); } catch { /* fall through */ }
      }
      if (raw === undefined) {
        return json({ error: "provider error", provider: p, upstream_status: e.upstream || null }, e.status === 429 ? 429 : 502);
      }
    }
    const parsed = parseModelJson(raw);
    return json(shapeReply(parsed, parsed ? "" : raw));
  },
};
