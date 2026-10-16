// Wakes the user's phone(s) right after the AI changes something, so the change lands in seconds.
// Firebase Cloud Messaging HTTP v1, high-priority data message {type: "sync"}. Optional: without the secret, the phone still syncs every 15 min.
import { type Env, now } from "./env";
import { serviceAccessToken } from "./google";

let cached: { token: string; until: number } | null = null;

export async function wakePhones(env: Env, userId: string): Promise<void> {
  if (!env.FCM_SERVICE_ACCOUNT_JSON) return;
  try {
    const devices = await env.DB.prepare("SELECT token FROM devices WHERE user_id = ?").bind(userId).all<{ token: string }>();
    if (!devices.results.length) return;
    if (!cached || cached.until < now()) {
      cached = { token: await serviceAccessToken(env.FCM_SERVICE_ACCOUNT_JSON, "https://www.googleapis.com/auth/firebase.messaging"), until: now() + 50 * 60_000 };
    }
    const project = (JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON) as { project_id: string }).project_id;
    await Promise.all(devices.results.map(async ({ token }) => {
      const r = await fetch(`https://fcm.googleapis.com/v1/projects/${project}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${cached!.token}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token, data: { type: "sync" }, android: { priority: "HIGH", ttl: "3600s" } } }),
      });
      // Token no longer valid (app uninstalled / reinstalled): forget it.
      if (r.status === 404 || r.status === 400) await env.DB.prepare("DELETE FROM devices WHERE token = ?").bind(token).run();
    }));
  } catch { /* push is best effort; the 15-minute sync still catches up */ }
}
