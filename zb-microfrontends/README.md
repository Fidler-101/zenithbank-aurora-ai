# ZenithBank — micro-frontend layout

Five independently deployable apps, one per backend service, plus a thin shared library. No app imports another app's code, and no app holds another's state — they only talk to each other through hyperlinks (real page loads) and through your API Gateway.

```
zb-microfrontends/
├── shared/
│   ├── tokens.css        ← design tokens + component styles (a "design system" package)
│   └── zb-common.js       ← session/auth helper + fetch wrapper (a thin "SDK" package)
├── auth/index.html         ← customer-service   (register, OTP, login)
├── banking/index.html      ← banking-service     (dashboard, transaction history, health score)
├── payments/index.html     ← payment-service     (send + manage payments)
├── status/index.html       ← transaction-status  (look up a payment's status)
└── aurora/index.html       ← aurora-ai-service   (chat)
```

## Why this shape

Each folder is a separate frontend "owned" by one backend service, the same way your actual backend is already split. That gets you the two things you asked for:

- **Independent deployability** — you could redeploy `payments/` (new payment form, new validation) without touching `banking/`, `auth/`, or any other app. In the original single-page version, every screen shipped as one bundle; here, each app is its own file with its own lifecycle.
- **Loose coupling** — the apps never call into each other's JavaScript or share in-memory state. They hand off two things only: a URL (a plain `<a href="../payments/index.html">`) and a session token, read through the shared `zb-common.js` helper. If you rewrote `aurora/` in React tomorrow, nothing else here would need to change, because the contract between apps is "a link and a token," not shared code.

## What's genuinely shared, and why that's still loose coupling

`shared/tokens.css` and `shared/zb-common.js` are consumed by every app, which is worth being honest about — it's not *zero* coupling. But it's the same kind of sharing as pulling in a versioned design-system or SDK package: each app decides for itself when to pick up a new version, none of them execute inside another's process, and the shared code has no opinion about any specific screen. That's different from the tight coupling you're trying to avoid (e.g. one giant shared JS module holding every screen's state).

## Session handoff between apps

`customer-service` returns a JWT in the login response body (not a cookie), so the frontend has to carry it forward itself. `zb-common.js` stores it in `localStorage` under one key (`zb_session_token`), which is available to every app **as long as they're served from the same origin** — different folders on the same host and port, not different ports. Each protected page calls `ZB.requireAuth(...)` on load, which reads that token and bounces to `auth/` if it's missing or expired.

If you want this to be production-grade rather than a student-project shortcut: have `customer-service` set the JWT as an `HttpOnly` cookie scoped to `path=/` instead. Then no frontend JavaScript needs to touch the token at all — the browser attaches it automatically on every request to the gateway, and a compromised page can't read it via `document.cookie` or `localStorage`. That's a backend change, not a frontend one, so I didn't make it here.

## Running it

1. Start `eureka-server`, `api-gateway`, and whichever backend services you're testing.
2. Serve the whole `zb-microfrontends/` folder from **one** static file server, so all five apps share an origin:
   ```
   cd zb-microfrontends
   python -m http.server 3000
   ```
3. Visit `http://localhost:3000/auth/index.html` to start.

Don't open the files with `file://` — the gateway's CORS policy only allows `http://localhost:*` origins, and `file://` won't match it.

## What to change if you split it further

Right now `banking/` covers both the dashboard and full transaction history as two tabs in one app — that's still one service's frontend, so client-side tabs are fine there. If you ever want the health-score view, spending breakdown, or transaction history to become their own deployable pieces (e.g. a separate analytics team owns them), the same pattern extends: new folder, same `shared/` imports, link to it from the sidebar.
