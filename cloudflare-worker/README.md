# NØVA AI Worker (Cloudflare)

Optional cloud brain for general questions. The phone sends a short text; this Worker calls an
AI model and returns `{"reply": "...", "action": null | {...}}`. Actions are validated here **and**
again on the phone against the same allowlist. Nothing in the APK or Git contains a provider key.

| Endpoint | Purpose |
|---|---|
| `GET /v1/health` | `{ok, version, provider}` — no secrets |
| `POST /v1/chat` | `Authorization: Bearer <NOVA_ACCESS_TOKEN>`, body `{"text": "...", "context": {...}}` |

**Provider:** if the secret `GEMINI_API_KEY` is set, Gemini (`GEMINI_MODEL`) is used; otherwise
**Workers AI** (free daily allowance, no extra key) runs `WORKERS_AI_MODEL`.
**Limits:** 20 requests/minute per installation (rate-limiting binding), 500 characters per request.
**Privacy:** prompts and replies are never logged; only error status codes.

## Deploy from GitHub (recommended — nothing to install)

1. Cloudflare dashboard → **My Profile → API Tokens → Create Token → "Edit Cloudflare Workers"** template →
   Account resources: your account → Create → copy the token.
   (First time using Workers? Open **Workers & Pages** once so Cloudflare creates your `workers.dev` subdomain.)
2. GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**:
   * `CLOUDFLARE_API_TOKEN` = the token from step 1
   * `NOVA_ACCESS_TOKEN` = any long random string (e.g. from a password generator) — keep it, the phone needs it
   * optional `GEMINI_API_KEY`
3. **Actions → Deploy AI Worker → Run workflow**. The run summary shows the Worker URL.
4. Phone: **Settings → Cloud AI** → Use Cloud AI, Worker URL, Access token (same as step 2) → Test connection.

## Deploy from your computer

```bash
cd cloudflare-worker
npx wrangler login                       # opens the browser
npx wrangler deploy                      # → https://nova-ai.<your-subdomain>.workers.dev
openssl rand -hex 24                     # make an access token
npx wrangler secret put NOVA_ACCESS_TOKEN   # paste it
npx wrangler secret put GEMINI_API_KEY      # optional: from aistudio.google.com/apikey
```

Then on the phone: **Settings → Cloud AI** → Worker URL + the same access token → *Test connection*.

## Test

```bash
npm test        # node --test, no network needed
```

Free tiers and quotas change; ₹0 is a target, not a guarantee. If the quota runs out the phone
says so and all local commands keep working.
