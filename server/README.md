# Why Not You server

One Cloudflare Worker at `https://mcp.whynotyou.app` that does four jobs:

| Path | What it does |
|---|---|
| `/mcp` | The AI connection (MCP). Claude, ChatGPT, Perplexity, Grok and Le Chat add this as a custom connector. Protected by OAuth. |
| `/authorize`, `/token`, `/register`, `/.well-known/*` | The OAuth sign-in those AI apps expect (with dynamic client registration). Sign-in itself is "Continue with Google". |
| `/api/login`, `/api/sync`, `/api/purchase`, `/api/account` | The phone app: sign in with Google, upload alarms/plan/usage, download changes the AI made, unlock Pro after a Play purchase, delete the account. |
| `/play/rtdn` | Google Play renewal/cancellation notifications (Pub/Sub push). |

Data lives in D1 (`schema.sql`); OAuth grants and app tokens in KV. MCP tools: `get_tomorrow`, `set_plan`, `list_alarms`, `create_alarm`, `update_alarm`, `set_app_limit`, `get_screen_time`. Tool calls need Pro.

## Deploy (once the accounts exist)

```sh
cd server && npm install
npx wrangler login                                   # opens Cloudflare in the browser
npx wrangler d1 create whynotyou                     # copy the id into wrangler.toml
npx wrangler kv namespace create OAUTH_KV            # copy the id into wrangler.toml
npm run db:init
npx wrangler secret put GOOGLE_CLIENT_SECRET
npx wrangler secret put PLAY_SERVICE_ACCOUNT_JSON    # paste the whole JSON key
npx wrangler secret put RTDN_SECRET                  # any long random string
npm run deploy
```

Then in Google Cloud: OAuth client (Web) with redirect URI `https://mcp.whynotyou.app/auth/google`; put its client ID in `wrangler.toml` (`GOOGLE_CLIENT_ID`). In Play Console → Monetization setup → Real-time developer notifications: a Pub/Sub topic with a push subscription to `https://mcp.whynotyou.app/play/rtdn?k=<RTDN_SECRET>`.

## Check

```sh
npm run check                       # TypeScript
npx wrangler dev                    # local; POST /mcp without a token must return 401
```
