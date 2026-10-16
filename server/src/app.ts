// Everything that isn't the MCP endpoint: the sign-in page used while connecting an AI,
// the phone app's API (sign-in, sync, purchases) and Google Play notifications.
import { type Env, now, upsertUser, isPro } from "./env";
import { verifyIdToken, exchangeCode, googleAuthUrl, checkPurchase } from "./google";

const page = (title: string, body: string) => new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#060B18;color:#F2F4F8;font:16px/1.5 Roboto,system-ui,sans-serif}
main{max-width:360px;padding:24px;text-align:center}h1{font-size:28px;margin:0 0 8px}p{color:#8A93A8;margin:0 0 28px}
a.btn{display:block;padding:18px;border-radius:30px;background:linear-gradient(180deg,#4A74FF,#2347F0);color:#fff;text-decoration:none;font-weight:500;box-shadow:0 8px 28px rgba(45,91,255,.55)}</style>
</head><body><main>${body}</main></body></html>`, { headers: { "content-type": "text/html; charset=utf-8" } });

const json = (data: unknown, status = 200) => Response.json(data, { status });

/** App tokens: random, stored in KV → userId, 180 days. */
async function newAppToken(env: Env, userId: string) {
  const t = "wny_" + crypto.randomUUID().replace(/-/g, "") + crypto.randomUUID().replace(/-/g, "");
  await env.OAUTH_KV.put("app:" + t, userId, { expirationTtl: 180 * 86400 });
  return t;
}
async function appUser(env: Env, req: Request) {
  const t = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  return t ? env.OAUTH_KV.get("app:" + t) : null;
}

export const appHandler = {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const p = url.pathname;

    // ---- AI connection sign-in (OAuth authorize → Google → back to the AI) ----
    if (p === "/authorize") {
      const info = await env.OAUTH_PROVIDER.parseAuthRequest(request);
      const client = await env.OAUTH_PROVIDER.lookupClient(info.clientId);
      if (!client) return new Response("Unknown client", { status: 400 });
      const key = crypto.randomUUID();
      await env.OAUTH_KV.put("pending:" + key, JSON.stringify(info), { expirationTtl: 600 });
      const who = client.clientName ? client.clientName.replace(/[<>&"]/g, "") : "Your AI";
      return page("Connect Why Not You", `<h1>Connect Why Not You</h1>
<p>${who} wants to see and change your alarms, plans and app limits. Sign in with the same Google account you use in the app.</p>
<a class="btn" href="${googleAuthUrl(env, env.PUBLIC_URL + "/auth/google", key)}">Continue with Google</a>`);
    }
    if (p === "/auth/google") {
      const key = url.searchParams.get("state") ?? ""; const code = url.searchParams.get("code") ?? "";
      const raw = await env.OAUTH_KV.get("pending:" + key);
      if (!raw || !code) return page("Sign-in expired", "<h1>That took too long</h1><p>Go back to your AI and try connecting again.</p>");
      await env.OAUTH_KV.delete("pending:" + key);
      const who = await exchangeCode(env, code, env.PUBLIC_URL + "/auth/google");
      if (!who) return page("Sign-in failed", "<h1>Couldn't sign you in</h1><p>Go back to your AI and try again.</p>");
      const userId = await upsertUser(env, who.sub, who.email);
      const info = JSON.parse(raw);
      const { redirectTo } = await env.OAUTH_PROVIDER.completeAuthorization({
        request: info, userId, metadata: { email: who.email }, scope: info.scope ?? [], props: { userId },
      });
      return Response.redirect(redirectTo, 302);
    }

    // ---- Phone app API ----
    if (p === "/api/login" && request.method === "POST") {
      const { idToken } = (await request.json()) as { idToken?: string };
      const who = idToken ? await verifyIdToken(env, idToken) : null;
      if (!who) return json({ error: "bad_token" }, 401);
      const userId = await upsertUser(env, who.sub, who.email);
      return json({ token: await newAppToken(env, userId), email: who.email, pro: await isPro(env, userId) });
    }

    if (p.startsWith("/api/")) {
      const userId = await appUser(env, request);
      if (!userId) return json({ error: "unauthorized" }, 401);

      // Phone → server: latest state. Server → phone: changes the AI asked for, plus Pro status.
      if (p === "/api/sync" && request.method === "POST") {
        const b = (await request.json()) as { push?: string; alarms?: unknown; plan?: string; tomorrow?: unknown; usage?: unknown; limits?: unknown; applied?: number[] };
        await env.DB.prepare(`UPDATE state SET alarms = COALESCE(?, alarms), plan = COALESCE(?, plan), tomorrow = COALESCE(?, tomorrow),
          usage = COALESCE(?, usage), limits = COALESCE(?, limits), updated_at = ? WHERE user_id = ?`)
          .bind(b.alarms ? JSON.stringify(b.alarms) : null, b.plan ?? null, b.tomorrow ? JSON.stringify(b.tomorrow) : null,
            b.usage ? JSON.stringify(b.usage) : null, b.limits ? JSON.stringify(b.limits) : null, now(), userId).run();
        if (typeof b.push === "string" && b.push.length > 20 && b.push.length < 4096) {
          await env.DB.prepare(`INSERT INTO devices (token, user_id, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, updated_at = excluded.updated_at`).bind(b.push, userId, now()).run();
        }
        if (b.applied?.length) {
          const ids = b.applied.filter((n) => Number.isInteger(n)).slice(0, 200);
          await env.DB.prepare(`UPDATE changes SET applied_at = ? WHERE user_id = ? AND id IN (${ids.map(() => "?").join(",")})`)
            .bind(now(), userId, ...ids).run();
        }
        const changes = await env.DB.prepare("SELECT id, kind, payload FROM changes WHERE user_id = ? AND applied_at IS NULL ORDER BY id LIMIT 50")
          .bind(userId).all<{ id: number; kind: string; payload: string }>();
        const actions = await env.DB.prepare("SELECT summary, created_at FROM actions WHERE user_id = ? ORDER BY id DESC LIMIT 10")
          .bind(userId).all<{ summary: string; created_at: number }>();
        return json({
          pro: await isPro(env, userId),
          changes: changes.results.map((c) => ({ id: c.id, kind: c.kind, payload: JSON.parse(c.payload) })),
          actions: actions.results,
        });
      }

      // A Google Play purchase from the phone: check it with Google, then unlock Pro.
      if (p === "/api/purchase" && request.method === "POST") {
        const { token, product } = (await request.json()) as { token?: string; product?: string };
        if (!token || !product) return json({ error: "missing" }, 400);
        const r = await checkPurchase(env, token, product);
        await savePurchase(env, token, userId, r);
        return json({ pro: r.active, expiry: r.expiry });
      }

      // Delete everything we hold for this person (Settings → Delete account).
      if (p === "/api/account" && request.method === "DELETE") {
        await env.DB.prepare("DELETE FROM users WHERE id = ?").bind(userId).run();
        return json({ deleted: true });
      }
      return json({ error: "not_found" }, 404);
    }

    // ---- Google Play real-time notifications (Pub/Sub push) ----
    if (p === "/play/rtdn" && request.method === "POST") {
      if (url.searchParams.get("k") !== env.RTDN_SECRET) return new Response("forbidden", { status: 403 });
      const body = (await request.json()) as { message?: { data?: string } };
      const data = body.message?.data ? JSON.parse(atob(body.message.data)) : null;
      const sub = data?.subscriptionNotification; const one = data?.oneTimeProductNotification;
      const token = sub?.purchaseToken ?? one?.purchaseToken; const product = sub?.subscriptionId ?? one?.sku ?? "pro_yearly";
      if (token) {
        const known = await env.DB.prepare("SELECT user_id FROM purchases WHERE token = ?").bind(token).first<{ user_id: string }>();
        const r = await checkPurchase(env, token, product);
        await savePurchase(env, token, known?.user_id ?? null, r);
      }
      return new Response("ok");
    }

    if (p === "/" || p === "") return page("Why Not You", `<h1>Why Not You</h1><p>This is the connection server for the Why Not You alarm app.</p><a class="btn" href="https://getwhynotyou.com">getwhynotyou.com</a>`);
    return new Response("Not found", { status: 404 });
  },
};

async function savePurchase(env: Env, token: string, userId: string | null, r: { product: string; expiry: number; state: string; active: boolean }) {
  await env.DB.prepare(`INSERT INTO purchases (token, user_id, product, expiry, state, updated_at) VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(token) DO UPDATE SET user_id = COALESCE(excluded.user_id, purchases.user_id), product = excluded.product,
    expiry = excluded.expiry, state = excluded.state, updated_at = excluded.updated_at`)
    .bind(token, userId, r.product, r.expiry, r.state, now()).run();
  const owner = userId ?? (await env.DB.prepare("SELECT user_id FROM purchases WHERE token = ?").bind(token).first<{ user_id: string }>())?.user_id;
  if (owner) await env.DB.prepare("UPDATE users SET pro_until = ?, pro_product = ? WHERE id = ?").bind(r.active ? r.expiry : 0, r.product, owner).run();
}
