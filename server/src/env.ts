import type { OAuthHelpers } from "@cloudflare/workers-oauth-provider";

export interface Env {
  DB: D1Database;
  OAUTH_KV: KVNamespace;
  OAUTH_PROVIDER: OAuthHelpers;
  PUBLIC_URL: string;
  PACKAGE_NAME: string;
  GOOGLE_CLIENT_ID: string;
  GOOGLE_CLIENT_SECRET: string;
  PLAY_SERVICE_ACCOUNT_JSON: string;
  RTDN_SECRET: string;
  /** Optional secret: comma-separated emails that get Pro without paying (testers). */
  PRO_EMAILS?: string;
}

export type Props = { userId: string };

export const now = () => Date.now();

export async function upsertUser(env: Env, sub: string, email: string): Promise<string> {
  const found = await env.DB.prepare("SELECT id FROM users WHERE google_sub = ?").bind(sub).first<{ id: string }>();
  if (found) { await env.DB.prepare("UPDATE users SET email = ? WHERE id = ?").bind(email, found.id).run(); return found.id; }
  const id = crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO users (id, google_sub, email, created_at) VALUES (?, ?, ?, ?)").bind(id, sub, email, now()),
    env.DB.prepare("INSERT INTO state (user_id, updated_at) VALUES (?, ?)").bind(id, now()),
  ]);
  return id;
}

export async function isPro(env: Env, userId: string): Promise<boolean> {
  const u = await env.DB.prepare("SELECT pro_until, email FROM users WHERE id = ?").bind(userId).first<{ pro_until: number; email: string }>();
  if (!u) return false;
  const testers = (env.PRO_EMAILS ?? "").toLowerCase().split(",").map((e) => e.trim()).filter(Boolean);
  return u.pro_until > now() || testers.includes((u.email ?? "").toLowerCase());
}
