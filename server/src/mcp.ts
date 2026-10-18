// The AI connection: a small, stateless MCP server (JSON-RPC over Streamable HTTP, JSON responses).
// Every request arrives already authenticated by the OAuth provider; ctx.props.userId says whose data.
import { type Env, type Props, now, isPro } from "./env";
import { wakePhones, pushStatus } from "./push";

const PROTOCOL = "2025-06-18";

type Tool = {
  name: string;
  title: string;
  description: string;
  inputSchema: Record<string, unknown>;
  readOnly?: boolean;
  destructive?: boolean;
  run: (env: Env, userId: string, args: any) => Promise<string>;
};

const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

async function state(env: Env, userId: string) {
  const s = await env.DB.prepare("SELECT * FROM state WHERE user_id = ?").bind(userId).first<Record<string, string | number>>();
  const j = (k: string, d: unknown) => { try { return JSON.parse(String(s?.[k] ?? "")) } catch { return d } };
  return {
    alarms: j("alarms", []) as any[], plan: String(s?.plan ?? ""), tomorrow: j("tomorrow", []) as any[],
    usage: j("usage", {}) as Record<string, Record<string, number>>, limits: j("limits", {}) as any,
    updatedAt: Number(s?.updated_at ?? 0),
  };
}

async function queueOnly(env: Env, userId: string, kind: string, payload: unknown) {
  await env.DB.prepare("INSERT INTO changes (user_id, kind, payload, created_at) VALUES (?, ?, ?, ?)").bind(userId, kind, JSON.stringify(payload), now()).run();
}
async function logAction(env: Env, userId: string, summary: string) {
  await env.DB.prepare("INSERT INTO actions (user_id, summary, created_at) VALUES (?, ?, ?)").bind(userId, summary, now()).run();
}

async function queue(env: Env, userId: string, kind: string, payload: unknown, summary: string) {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO changes (user_id, kind, payload, created_at) VALUES (?, ?, ?, ?)").bind(userId, kind, JSON.stringify(payload), now()),
    env.DB.prepare("INSERT INTO actions (user_id, summary, created_at) VALUES (?, ?, ?)").bind(userId, summary, now()),
  ]);
  await wakePhones(env, userId);
}

const fmtAlarm = (a: any) => {
  const h = Number(a.h), m = Number(a.m);
  const t = `${h % 12 === 0 ? 12 : h % 12}:${String(m).padStart(2, "0")} ${h < 12 ? "am" : "pm"}`;
  const d: boolean[] = a.days ?? [];
  const days = d.every(Boolean) ? "every day" : d.slice(0, 5).every(Boolean) && !d[5] && !d[6] ? "weekdays" : d.some(Boolean) ? DAYS.filter((_, i) => d[i]).join(", ") : a.date ? `once on ${a.date}` : "once";
  const label = String(a.lbl ?? a.label ?? "").trim();
  return `${label ? `"${label}" · ` : ""}${t} · ${days} · ${a.on === false ? "off" : "on"} (id ${a.id})`;
};

/** Everything about one alarm, for list_alarms. */
const detailAlarm = (a: any) => {
  const vib = a.vib === false ? "off" : `on (${a.vibp ?? "pulse"})`;
  const ph = Number(a.ph ?? 21), pm = Number(a.pm ?? 0);
  const remind = a.pr === false ? "off" : `${ph % 12 === 0 ? 12 : ph % 12}:${String(pm).padStart(2, "0")} ${ph < 12 ? "am" : "pm"} the evening before`;
  return [
    fmtAlarm(a),
    `  sound: ${a.sound ? a.sound : "Chime (built-in)"} · volume ${a.vol ?? 80}% · ramp up ${a.ramp === false ? "off" : "on"}`,
    `  vibration: ${vib} · snooze ${a.snooze === false ? "off" : "on (5 min, max 2)"}`,
    `  after stopping: read the plan aloud ${a.brief === false ? "off" : `on (voice ${a.voice ?? "default"})`}`,
    `  planning reminder: ${remind}`,
    `  message read aloud: ${String(a.note ?? "").trim() ? `"${a.note}"${a.noteAi ? " (written by AI)" : ""}` : "none (reads the evening plan instead)"}`,
  ].join("\n");
};

const parseTime = (s: string) => {
  const m = /^\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*$/i.exec(s);
  if (!m) throw new Error(`Couldn't read the time "${s}". Use HH:MM (24 h) or e.g. 6:30 am.`);
  let h = Number(m[1]); const min = Number(m[2] ?? 0); const ap = m[3]?.toLowerCase();
  if (ap === "pm" && h < 12) h += 12; if (ap === "am" && h === 12) h = 0;
  if (h > 23 || min > 59) throw new Error(`"${s}" isn't a valid time.`);
  return { h, m: min };
};
const parseDays = (d?: string[]) => {
  if (!d || d.length === 0) return undefined;
  const set = new Set(d.map((x) => x.toLowerCase()));
  if (set.has("every day") || set.has("daily")) return DAYS.map(() => true);
  if (set.has("weekdays")) return DAYS.map((_, i) => i < 5);
  if (set.has("weekends")) return DAYS.map((_, i) => i >= 5);
  return DAYS.map((x) => [...set].some((s) => s.startsWith(x)));
};

const parseDate = (s?: string) => {
  if (!s) return "";
  const v = String(s).trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v) || isNaN(Date.parse(v))) throw new Error(`Couldn't read the date "${s}". Use YYYY-MM-DD.`);
  return v;
};
const ALARM_PROPS = {
  time: { type: "string", description: "\"06:30\" (24 h) or \"6:30 am\"" },
  days: { type: "array", items: { type: "string" }, description: "Repeat on: any of mon..sun, or \"weekdays\", \"weekends\", \"every day\". Leave out for a one-time alarm." },
  date: { type: "string", description: "One-time alarm on a specific day, YYYY-MM-DD (use the user's local date). Only without days." },
  label: { type: "string", maxLength: 40, description: "Short name shown on the alarm, e.g. \"Work\", \"Gym\", \"Flight\"." },
  message: { type: "string", maxLength: 400, description: "Read aloud when this alarm rings, instead of the evening plan. One to three short spoken sentences in second person." },
};
function buildAlarm(a: any, i = 0) {
  const { h, m } = parseTime(String(a.time));
  const days = parseDays(a.days) ?? DAYS.map(() => false);
  const date = days.some(Boolean) ? "" : parseDate(a.date);
  return { id: "ai" + (now() + i).toString(36), h, m, days, on: true, date,
    label: String(a.label ?? "").trim().slice(0, 40), message: String(a.message ?? "").trim().slice(0, 400) };
}
const asPhone = (x: any) => ({ ...x, lbl: x.label, note: x.message, noteAi: !!x.message });

const tools: Tool[] = [
  {
    name: "get_tomorrow", title: "Get tomorrow", readOnly: true,
    description: "Tomorrow at a glance: the next alarm, the plan the user wrote for tomorrow, and tomorrow's calendar events (if shared). Call this before planning the user's day.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run(env, userId) {
      const s = await state(env, userId);
      const on = s.alarms.filter((a) => a.on !== false);
      return JSON.stringify({
        alarms: on.map(fmtAlarm), plan: s.plan || null,
        calendar: s.tomorrow, lastSyncedFromPhone: s.updatedAt ? new Date(s.updatedAt).toISOString() : null,
        instantSync: await pushStatus(env, userId),
      });
    },
  },
  {
    name: "set_plan", title: "Set tomorrow's plan",
    description: "Set the general plan for tomorrow (the evening plan). It is read aloud at the next alarm that has no message of its own, then cleared. For a message tied to one alarm or day, set message on that alarm instead (create_alarm / update_alarm). Write one or two short spoken sentences in second person, e.g. \"Finish the pitch deck before lunch, then go for a run.\" Replaces any existing plan.",
    inputSchema: { type: "object", properties: { plan: { type: "string", maxLength: 400 } }, required: ["plan"], additionalProperties: false },
    async run(env, userId, a) {
      const plan = String(a.plan ?? "").trim();
      if (!plan) throw new Error("The plan is empty.");
      await env.DB.prepare("UPDATE state SET plan = ? WHERE user_id = ?").bind(plan, userId).run();
      await queue(env, userId, "set_plan", { plan }, `Set your plan: ${plan.slice(0, 80)}`);
      return "Saved. The phone picks it up the next time it syncs, and reads it out at the alarm.";
    },
  },
  {
    name: "list_alarms", title: "List alarms", readOnly: true,
    description: "Read all alarms on the user's phone, on and off, with everything about each: id, label, time, days, sound, volume, vibration, snooze, whether the plan is read aloud (and voice), and the evening planning reminder.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run(env, userId) {
      const s = await state(env, userId);
      if (!s.alarms.length) return "No alarms yet.";
      const sorted = [...s.alarms].sort((x, y) => (x.h * 60 + x.m) - (y.h * 60 + y.m));
      return `${sorted.length} alarm${sorted.length === 1 ? "" : "s"} (as of the phone's last sync${s.updatedAt ? ", " + new Date(s.updatedAt).toISOString() : ""}):\n\n` + sorted.map(detailAlarm).join("\n\n");
    },
  },
  {
    name: "create_alarm", title: "Create alarm",
    description: "Create one alarm. Without days it's a one-time alarm: it rings once (on date, or the next time that time comes up) and then switches itself off; ask whether it should repeat if the user didn't say. Each alarm can carry its own message that is read aloud when it rings. To set up many alarms at once (e.g. a whole week), use create_alarms.",
    inputSchema: { type: "object", properties: ALARM_PROPS, required: ["time"], additionalProperties: false },
    async run(env, userId, a) {
      const alarm = buildAlarm(a);
      await queue(env, userId, "create_alarm", alarm, `Added an alarm: ${fmtAlarm(asPhone(alarm)).split(" (id")[0]}`);
      return `Created: ${fmtAlarm(asPhone(alarm))}${alarm.message ? ` · message: "${alarm.message}"` : ""}. It reaches the phone within seconds.`;
    },
  },
  {
    name: "create_alarms", title: "Create several alarms",
    description: "Create up to 50 alarms in one go, e.g. a whole week planned on Sunday, each with its own time, days or date, label and message. Same fields as create_alarm. Check list_alarms first so you don't duplicate existing ones.",
    inputSchema: { type: "object", properties: { alarms: { type: "array", minItems: 1, maxItems: 50, items: { type: "object", properties: ALARM_PROPS, required: ["time"], additionalProperties: false } } }, required: ["alarms"], additionalProperties: false },
    async run(env, userId, a) {
      const list = (a.alarms as any[]).map((x, i) => buildAlarm(x, i));
      for (const alarm of list) await queueOnly(env, userId, "create_alarm", alarm);
      await logAction(env, userId, `Added ${list.length} alarms`);
      await wakePhones(env, userId);
      return `Created ${list.length} alarms:\n` + list.map((x) => fmtAlarm(asPhone(x)) + (x.message ? ` · "${x.message}"` : "")).join("\n");
    },
  },
  {
    name: "update_alarm", title: "Change alarm",
    description: "Change an existing alarm by id (from list_alarms): time, days, date, label or message (an empty string removes the label or message), or turn it on/off.",
    inputSchema: { type: "object", properties: { id: { type: "string" }, ...ALARM_PROPS, on: { type: "boolean" } }, required: ["id"], additionalProperties: false },
    async run(env, userId, a) {
      const s = await state(env, userId);
      const cur = s.alarms.find((x) => x.id === a.id);
      if (!cur) throw new Error(`No alarm with id ${a.id}. Call list_alarms first.`);
      const patch: any = { id: a.id };
      if (a.time) Object.assign(patch, parseTime(String(a.time)));
      const days = parseDays(a.days); if (days) patch.days = days;
      if (typeof a.on === "boolean") patch.on = a.on;
      if (typeof a.label === "string") patch.label = a.label.trim().slice(0, 40);
      if (typeof a.message === "string") patch.message = a.message.trim().slice(0, 400);
      if (typeof a.date === "string") patch.date = parseDate(a.date);
      const after = { ...cur, ...patch, ...(patch.label !== undefined ? { lbl: patch.label } : {}), ...(patch.message !== undefined ? { note: patch.message, noteAi: true } : {}) };
      await queue(env, userId, "update_alarm", patch, `Changed alarm: ${fmtAlarm(after).split(" (id")[0]}`);
      return `Queued: ${fmtAlarm(after)}.`;
    },
  },
  {
    name: "delete_alarm", title: "Delete alarm",
    description: "Delete an alarm by id (from list_alarms). This removes it from the phone completely; to pause it instead, use update_alarm with on: false. Confirm with the user first if it isn't clear which alarm they mean.",
    inputSchema: { type: "object", properties: { id: { type: "string" } }, required: ["id"], additionalProperties: false },
    destructive: true,
    async run(env, userId, a) {
      const s = await state(env, userId);
      const cur = s.alarms.find((x) => x.id === a.id);
      if (!cur) throw new Error(`No alarm with id ${a.id}. Call list_alarms first.`);
      await queue(env, userId, "delete_alarm", { id: a.id }, `Deleted alarm: ${fmtAlarm(cur).split(" (id")[0]}`);
      return `Deleted: ${fmtAlarm(cur)}. It disappears from the phone at the next sync.`;
    },
  },
  {
    name: "set_app_limit", title: "Set app limit",
    description: "Phone down: after this many minutes in an app (e.g. Instagram, TikTok, YouTube), a reminder covers the screen. minutes: 1–60. Use minutes 0 to stop limiting that app.",
    inputSchema: { type: "object", properties: { app: { type: "string" }, minutes: { type: "integer", minimum: 0, maximum: 60 } }, required: ["app", "minutes"], additionalProperties: false },
    async run(env, userId, a) {
      const app = String(a.app).trim(); const minutes = Number(a.minutes);
      await queue(env, userId, "set_app_limit", { app, minutes }, minutes ? `Limited ${app} to ${minutes} minutes` : `Stopped limiting ${app}`);
      return minutes ? `Queued: remind after ${minutes} min in ${app}.` : `Queued: ${app} is no longer limited.`;
    },
  },
  {
    name: "get_screen_time", title: "Get screen time", readOnly: true,
    description: "Minutes per day in the apps the user limits, for the last N days (default 7).",
    inputSchema: { type: "object", properties: { days: { type: "integer", minimum: 1, maximum: 30 } }, additionalProperties: false },
    async run(env, userId, a) {
      const s = await state(env, userId); const n = Number(a?.days ?? 7);
      const keys = Object.keys(s.usage).sort().slice(-n);
      if (!keys.length) return "No screen time synced yet. The phone needs Phone down turned on.";
      return JSON.stringify(Object.fromEntries(keys.map((k) => [k, s.usage[k]])));
    },
  },
];

const rpc = (id: unknown, result: unknown) => Response.json({ jsonrpc: "2.0", id, result });
const rpcErr = (id: unknown, code: number, message: string) => Response.json({ jsonrpc: "2.0", id, error: { code, message } });

export async function handleMcp(request: Request, env: Env, props: Props): Promise<Response> {
  if (request.method === "GET") return new Response("Method Not Allowed", { status: 405, headers: { allow: "POST" } });
  if (request.method === "DELETE") return new Response(null, { status: 204 });
  if (request.method !== "POST") return new Response("Method Not Allowed", { status: 405 });

  let msg: any;
  try { msg = await request.json(); } catch { return rpcErr(null, -32700, "Parse error"); }
  if (Array.isArray(msg)) return rpcErr(null, -32600, "Batching is not supported");
  if (msg.id === undefined) return new Response(null, { status: 202 }); // notification

  switch (msg.method) {
    case "initialize":
      return rpc(msg.id, {
        protocolVersion: msg.params?.protocolVersion === "2025-03-26" ? "2025-03-26" : PROTOCOL,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "why-not-you", title: "Why Not You", version: "0.1.0" },
        instructions: "Why Not You is the user's alarm app. Read list_alarms before changing anything. Every alarm can have a label and its own message, which is read aloud when it rings; set_plan is the general plan for the next alarm without a message. Keep messages short and spoken. Use create_alarms to set up many alarms at once. Changes reach the phone within seconds.",
      });
    case "ping":
      return rpc(msg.id, {});
    case "tools/list":
      return rpc(msg.id, {
        tools: tools.map((t) => ({ name: t.name, title: t.title, description: t.description, inputSchema: t.inputSchema,
          annotations: { readOnlyHint: !!t.readOnly, destructiveHint: !!t.destructive, openWorldHint: false } })),
      });
    case "tools/call": {
      const t = tools.find((x) => x.name === msg.params?.name);
      if (!t) return rpcErr(msg.id, -32602, `Unknown tool: ${msg.params?.name}`);
      if (!(await isPro(env, props.userId)))
        return rpc(msg.id, { isError: true, content: [{ type: "text", text: "Connecting an AI needs Why Not You Pro. The user can start a free trial in the app (Settings → Try Pro free), signed in with this same Google account." }] });
      try {
        const text = await t.run(env, props.userId, msg.params?.arguments ?? {});
        return rpc(msg.id, { content: [{ type: "text", text }] });
      } catch (e) {
        return rpc(msg.id, { isError: true, content: [{ type: "text", text: (e as Error).message }] });
      }
    }
    default:
      return rpcErr(msg.id, -32601, `Method not found: ${msg.method}`);
  }
}
