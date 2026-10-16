// Google helpers: sign-in verification and the Play Developer API (service-account JWT, WebCrypto).
import type { Env } from "./env";

/** Verify a Google ID token (from the phone's "Continue with Google") and return the account. */
export async function verifyIdToken(env: Env, idToken: string): Promise<{ sub: string; email: string } | null> {
  const r = await fetch("https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken));
  if (!r.ok) return null;
  const t = (await r.json()) as Record<string, string>;
  const okIss = t.iss === "accounts.google.com" || t.iss === "https://accounts.google.com";
  if (!okIss || t.aud !== env.GOOGLE_CLIENT_ID || Number(t.exp) * 1000 < Date.now()) return null;
  if (t.email_verified !== "true" && t.email_verified !== (true as unknown as string)) return null;
  return { sub: t.sub, email: t.email };
}

/** Browser sign-in (used during the AI connection): exchange the code for the user's Google identity. */
export async function exchangeCode(env: Env, code: string, redirectUri: string) {
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code, client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: redirectUri, grant_type: "authorization_code",
    }),
  });
  if (!r.ok) return null;
  const j = (await r.json()) as { id_token?: string };
  return j.id_token ? verifyIdToken(env, j.id_token) : null;
}

export function googleAuthUrl(env: Env, redirectUri: string, state: string) {
  const u = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  u.searchParams.set("client_id", env.GOOGLE_CLIENT_ID);
  u.searchParams.set("redirect_uri", redirectUri);
  u.searchParams.set("response_type", "code");
  u.searchParams.set("scope", "openid email");
  u.searchParams.set("state", state);
  u.searchParams.set("prompt", "select_account");
  return u.toString();
}

// ---- Play Developer API ------------------------------------------------------------------

const b64url = (b: ArrayBuffer | Uint8Array | string) => {
  const bytes = typeof b === "string" ? new TextEncoder().encode(b) : new Uint8Array(b as ArrayBuffer);
  let s = ""; bytes.forEach((x) => (s += String.fromCharCode(x)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function playAccessToken(env: Env): Promise<string> {
  const sa = JSON.parse(env.PLAY_SERVICE_ACCOUNT_JSON) as { client_email: string; private_key: string };
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claim}`));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${header}.${claim}.${b64url(sig)}` }),
  });
  if (!r.ok) throw new Error("play auth failed: " + r.status);
  return ((await r.json()) as { access_token: string }).access_token;
}

export type PlayCheck = { product: string; expiry: number; state: string; active: boolean };

/** Check a subscription purchase token with Google (subscriptionsv2), or a one-time lifetime purchase. */
export async function checkPurchase(env: Env, token: string, product: string): Promise<PlayCheck> {
  const at = await playAccessToken(env);
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${env.PACKAGE_NAME}/purchases`;
  if (product === "pro_lifetime") {
    const r = await fetch(`${base}/products/${product}/tokens/${token}`, { headers: { authorization: `Bearer ${at}` } });
    if (!r.ok) throw new Error("play check failed: " + r.status);
    const j = (await r.json()) as { purchaseState?: number };
    const active = j.purchaseState === 0;
    return { product, expiry: active ? 4102444800000 : 0, state: active ? "PURCHASED" : "NOT_PURCHASED", active };
  }
  const r = await fetch(`${base}/subscriptionsv2/tokens/${token}`, { headers: { authorization: `Bearer ${at}` } });
  if (!r.ok) throw new Error("play check failed: " + r.status);
  const j = (await r.json()) as { subscriptionState?: string; lineItems?: { productId: string; expiryTime: string }[] };
  const li = j.lineItems?.[0];
  const expiry = li ? Date.parse(li.expiryTime) : 0;
  const state = j.subscriptionState ?? "UNKNOWN";
  const active = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(state) && expiry > Date.now();
  return { product: li?.productId ?? product, expiry, state, active };
}
