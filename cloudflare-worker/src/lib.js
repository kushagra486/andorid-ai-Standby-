// Pure helpers shared by the Worker and its tests (no Cloudflare APIs here).

export const VERSION = "1.0.0";

/** Mirror of the Android allowlist (app/src/main/java/com/nova/actions/Actions.kt). */
export const ACTIONS = {
  SHOW_SCREEN: { screen: ["pet", "clock", "dual", "dashboard", "photos", "music", "assistant"] },
  PET_SLEEP: {},
  PET_WAKE: {},
  NIGHT_MODE: { mode: ["on", "off", "auto"] },
  SET_TIMER: { minutes: "number", label: "text" },
  CANCEL_TIMERS: {},
  ADD_REMINDER: { text: "text", in_minutes: "number", at: "hhmm" },
  MEDIA: { command: ["play", "pause", "toggle", "next", "previous"] },
  SPOTIFY_PLAY: { query: "text", kind: ["playlist", "artist", "album", "track", "any"] },
  SPOTIFY_TRANSFER: { device: "text" },
  SET_VOLUME: { level: "percent" },
  SET_BRIGHTNESS: { level: "percent" },
  SMART_HOME: { device: "text", command: ["on", "off", "toggle"] },
  OPEN_SETTINGS: {},
};

const URL_LIKE = /(:\/\/|www\.|\.(com|net|org|io|in)\b|<|>|\{|\}|`|;|\$\()/i;

/** Returns a cleaned action or null. Never throws. */
export function validateAction(a) {
  if (!a || typeof a !== "object") return null;
  const type = String(a.type || "").toUpperCase();
  const spec = ACTIONS[type];
  if (!spec) return null;
  const args = a.args && typeof a.args === "object" && !Array.isArray(a.args) ? a.args : {};
  const out = {};
  for (const [k, v] of Object.entries(args)) {
    const rule = spec[k];
    if (rule === undefined) return null;
    if (typeof v !== "string" && typeof v !== "number") return null;
    const s = String(v).trim();
    if (s.length > 80 || URL_LIKE.test(s)) return null;
    if (Array.isArray(rule)) { if (!rule.includes(s.toLowerCase())) return null; out[k] = s.toLowerCase(); continue; }
    if (rule === "number") { const n = Number(s); if (!isFinite(n) || n <= 0 || n > 43200) return null; out[k] = n; continue; }
    if (rule === "percent") { const n = Number(s); if (!Number.isInteger(n) || n < 0 || n > 100) return null; out[k] = n; continue; }
    if (rule === "hhmm") { if (!/^([01]?\d|2[0-3]):[0-5]\d$/.test(s)) return null; out[k] = s; continue; }
    if (!s) return null;
    out[k] = s;
  }
  return { type, args: out };
}

/** Pull the first JSON object out of model text (models sometimes wrap it in prose/fences). */
export function parseModelJson(text) {
  if (typeof text !== "string") return null;
  const cleaned = text.replace(/```(?:json)?/g, "");
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try { return JSON.parse(cleaned.slice(start, end + 1)); } catch { return null; }
}

/** Shape the final response: short reply text + validated action (or null). */
export function shapeReply(modelObj, fallbackText) {
  const reply = typeof modelObj?.reply === "string" ? modelObj.reply.slice(0, 600) : (fallbackText || "").slice(0, 600);
  const action = modelObj?.action ? validateAction(modelObj.action) : null;
  return { reply: reply || "Sorry, I don't have an answer for that.", action };
}

/** Validate the request body from the phone. Returns {text, context} or an error string. */
export function validateRequest(body) {
  if (!body || typeof body !== "object") return "body must be JSON";
  if (typeof body.text !== "string") return "text is required";
  const text = body.text.trim();
  if (!text || text.length > 500) return "text must be 1-500 characters";
  const context = {};
  if (body.context && typeof body.context === "object") {
    for (const [k, v] of Object.entries(body.context).slice(0, 6)) {
      if (typeof v === "string" && v.length <= 120) context[k] = v;
    }
  }
  return { text, context };
}

/** Constant-time string comparison for the access token. */
export function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length === 0) return false;
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}

export const SYSTEM_PROMPT = `You are NØVA, a small friendly robot desk companion living on an Android phone.
Answer in at most 2 short sentences (it may be spoken aloud). Be warm and concise.
You may propose ONE action from this allowlist when the user clearly asks for it:
${Object.entries(ACTIONS).map(([t, a]) => `- ${t}(${Object.keys(a).join(", ")})`).join("\n")}
Never invent other actions, never include links or code.
Respond ONLY with JSON: {"reply": "<text>", "action": null | {"type": "<TYPE>", "args": {...}}}`;
