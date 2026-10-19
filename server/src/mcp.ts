// The AI connection: a small, stateless MCP server (JSON-RPC over Streamable HTTP, JSON responses).
// Every request arrives already authenticated by the OAuth provider; ctx.props.userId says whose data.
import { type Env, type Props, now, isPro } from "./env";
import { wakePhones, pushStatus } from "./push";
import { localNow, describe, nextRing, clock, type LocalNow } from "./when";

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
    datePlans: (j("limits", {}) as any)?.datePlans as Record<string, string> | undefined,
    updatedAt: Number(s?.updated_at ?? 0),
    tz: String((j("limits", {}) as any)?.tz || "UTC"),
  };
}

const nowLine = (n: LocalNow) => {
  const fake = { h: Math.floor(n.min / 60), m: n.min % 60 };
  return `Now for the user: ${["Mon","Tue","Wed","Thu","Fri","Sat","Sun"][n.wd]} ${n.y}-${String(n.mo).padStart(2, "0")}-${String(n.d).padStart(2, "0")}, ${clock(fake.h, fake.m)} (${n.tz}).`;
};

/** Same time and it would ring on the same day as an existing alarm. */
function duplicateOf(cand: any, alarms: any[], now: LocalNow): any | undefined {
  const cd: boolean[] = cand.days ?? [];
  const cNext = nextRing(cand, now)?.date;
  return alarms.find((x) => {
    if (x.on === false || Number(x.h) !== cand.h || Number(x.m) !== cand.m) return false;
    const xd: boolean[] = x.days ?? [];
    if (cd.some(Boolean) && xd.some(Boolean)) return cd.some((v, i) => v && xd[i]);
    return !!cNext && nextRing(x, now)?.date === cNext;
  });
}
const READ_BACK = "\nTell the user this line as it is, so they can check the day, time and message.";

async function queueOnly(env: Env, userId: string, kind: string, payload: unknown): Promise<number> {
  const r = await env.DB.prepare("INSERT INTO changes (user_id, kind, payload, created_at) VALUES (?, ?, ?, ?)").bind(userId, kind, JSON.stringify(payload), now()).run();
  return Number(r.meta.last_row_id);
}

/** Wait (up to ~8 s) for the phone to confirm it applied these changes, and say so plainly. */
async function delivery(env: Env, userId: string, ids: number[]): Promise<string> {
  if (!ids.length) return "";
  const q = `SELECT COUNT(*) AS n FROM changes WHERE user_id = ? AND applied_at IS NOT NULL AND id IN (${ids.map(() => "?").join(",")})`;
  for (let i = 0; i < 16; i++) {
    const r = await env.DB.prepare(q).bind(userId, ...ids).first<{ n: number }>();
    if ((r?.n ?? 0) >= ids.length) return "\n\nDelivered: the phone confirmed it has this.";
    await new Promise((res) => setTimeout(res, 500));
  }
  const r = await env.DB.prepare(q).bind(userId, ...ids).first<{ n: number }>();
  const done = r?.n ?? 0;
  return `\n\nNot confirmed yet: the phone has applied ${done} of ${ids.length}. It's probably offline or asleep; it will pick this up as soon as it's back online (within 15 minutes of reconnecting, or when the app is opened). Use check_delivery to see if it arrived.`;
}
async function logAction(env: Env, userId: string, summary: string) {
  await env.DB.prepare("INSERT INTO actions (user_id, summary, created_at) VALUES (?, ?, ?)").bind(userId, summary, now()).run();
}

async function queue(env: Env, userId: string, kind: string, payload: unknown, summary: string): Promise<string> {
  const id = await queueOnly(env, userId, kind, payload);
  await logAction(env, userId, summary);
  await wakePhones(env, userId);
  return delivery(env, userId, [id]);
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
const detailAlarm = (a: any, n: LocalNow) => {
  const vib = a.vib === false ? "off" : `on (${a.vibp ?? "pulse"})`;
  const ph = Number(a.ph ?? 21), pm = Number(a.pm ?? 0);
  const remind = a.pr === false ? "off" : `${ph % 12 === 0 ? 12 : ph % 12}:${String(pm).padStart(2, "0")} ${ph < 12 ? "am" : "pm"} the evening before`;
  return [
    describe(a, n),
    `  sound: ${a.sound ? a.sound : "Chime (built-in)"} · volume ${a.vol ?? 80}% · ramp up ${a.ramp === false ? "off" : "on"}`,
    `  vibration: ${vib} · snooze ${a.snooze === false ? "off" : "on (5 min, max 2)"}`,
    `  after stopping: read the plan aloud ${a.brief === false ? "off" : `on (voice ${a.voice ?? "default"})`}`,
    `  planning reminder: ${remind}`,
    `  message read aloud: ${String(a.note ?? "").trim() ? `"${a.note}"${a.noteAi ? " (written by AI)" : ""}` : "none (reads the evening plan instead)"}`,
    ...(a.fu ? [`  follow-up: ${a.fu}${a.fuN ? ` (repeat ${a.fuN})` : ""}`] : []),
    ...(a.auto ? ["  (created by a follow-up; removed after it rings)"] : []),
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
/** Checks a follow-up tree: question + yes/no branches, each { say, then }; then = "done" | repeat | new alarm. Max depth 3. */
function checkFollowup(f: any, depth = 1): any {
  if (f === undefined || f === null || f === "") return undefined;
  if (typeof f !== "object") throw new Error("followup must be an object: { question, yes: { say, then }, no: { say, then } }");
  if (depth > 3) throw new Error("followup is nested too deep (max 3 levels).");
  const q = String(f.question ?? "").trim();
  if (!q) throw new Error("followup.question is required.");
  const branch = (b: any, name: string) => {
    if (!b || typeof b !== "object") throw new Error(`followup.${name} is required: { say, then }`);
    const say = String(b.say ?? "").trim().slice(0, 300);
    const t = b.then ?? "done";
    if (t === "done") return { say, then: "done" };
    if (typeof t !== "object") throw new Error(`followup.${name}.then must be "done" or an object.`);
    const mins = Number(t.in_minutes);
    if (!Number.isInteger(mins) || mins < 1 || mins > 1440) throw new Error(`followup.${name}.then.in_minutes must be 1–1440 (minutes after the user answers).`);
    if (t.repeat_this_alarm) {
      const max = Number(t.max_repeats);
      if (!Number.isInteger(max) || max < 1 || max > 10) throw new Error(`followup.${name}.then.max_repeats is required for repeats (1–10).`);
      return { say, then: { in_minutes: mins, repeat_this_alarm: true, max_repeats: max } };
    }
    const al = t.alarm;
    if (!al || typeof al !== "object") throw new Error(`followup.${name}.then needs repeat_this_alarm or alarm: { label, message, followup }.`);
    return { say, then: { in_minutes: mins, alarm: { label: String(al.label ?? "").slice(0, 40), message: String(al.message ?? "").slice(0, 400), ...(al.followup ? { followup: checkFollowup(al.followup, depth + 1) } : {}) } } };
  };
  return { question: q.slice(0, 200), yes: branch(f.yes, "yes"), no: branch(f.no, "no") };
}

const FOLLOWUP_EXAMPLE = `Example (drying fruit): {"time":"21:00","label":"Citrus","message":"Check the lemon and orange slices.","followup":{"question":"Are they crisp?","yes":{"say":"Take them out, let them cool, then store in a jar.","then":"done"},"no":{"say":"OK, I'll check again in an hour.","then":{"in_minutes":60,"repeat_this_alarm":true,"max_repeats":3}}}}. A branch can also start a different alarm: "then":{"in_minutes":30,"alarm":{"label":"Fridge","message":"Put it in the fridge."}}.`;

const ALARM_PROPS = {
  time: { type: "string", description: "\"06:30\" (24 h) or \"6:30 am\"" },
  days: { type: "array", items: { type: "string" }, description: "Repeat on: any of mon..sun, or \"weekdays\", \"weekends\", \"every day\". Leave out for a one-time alarm." },
  date: { type: "string", description: "One-time alarm on a specific day, YYYY-MM-DD in the user's time zone. list_alarms starts with today's date for the user; use it to turn 'tomorrow' or 'Friday' into a date. Only without days." },
  label: { type: "string", maxLength: 40, description: "Short name shown on the alarm, e.g. \"Work\", \"Gym\", \"Flight\"." },
  message: { type: "string", maxLength: 400, description: "Read aloud when this alarm rings, instead of the evening plan. One to three short spoken sentences in second person. ALWAYS use this when the alarm is for something specific (what to do when it rings)." },
};
function oddHourNote(raw: string, h: number) {
  return !/am|pm/i.test(raw) && h >= 1 && h <= 4 ? `\nNote: that's ${clock(h, 0).replace(":00", "")} at night. If the user meant the afternoon, change it with update_alarm.` : "";
}
function buildAlarm(a: any, i = 0) {
  const { h, m } = parseTime(String(a.time));
  const days = parseDays(a.days) ?? DAYS.map(() => false);
  const date = days.some(Boolean) ? "" : parseDate(a.date);
  return { id: "ai" + (now() + i).toString(36), h, m, days, on: true, date,
    label: String(a.label ?? "").trim().slice(0, 40), message: String(a.message ?? "").trim().slice(0, 400),
    /* followup is parked for v1 (checkFollowup kept for later) */ };
}
const asPhone = (x: any) => ({ ...x, lbl: x.label, note: x.message, noteAi: !!x.message, fu: x.followup ? JSON.stringify(x.followup) : "" });

const tools: Tool[] = [
  {
    name: "get_tomorrow", title: "Get tomorrow", readOnly: true,
    description: "Tomorrow at a glance: the next alarm, the plan the user wrote for tomorrow, and tomorrow's calendar events (if shared). Call this before planning the user's day.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run(env, userId) {
      const s = await state(env, userId);
      const on = s.alarms.filter((a) => a.on !== false);
      return JSON.stringify({
        now: nowLine(localNow(s.tz)), alarms: on.map((x) => describe(x, localNow(s.tz))), plan: s.plan || null,
        calendar: s.tomorrow, lastSyncedFromPhone: s.updatedAt ? new Date(s.updatedAt).toISOString() : null,
        instantSync: await pushStatus(env, userId),
      });
    },
  },
  {
    name: "set_plan", title: "Set a plan",
    description: "Set a plan that is read aloud at an alarm. Attach it to: (a) one alarm with alarm_id (same as that alarm's message; read every time it rings), (b) a date with date YYYY-MM-DD (read by the first alarm that rings that day, unless that alarm has its own message), or (c) neither: the general plan for the next alarm without a message, cleared after it's read. An alarm's own message beats a date plan, which beats the general plan. Write one to three short spoken sentences in second person. An empty plan with alarm_id or date removes it.",
    inputSchema: { type: "object", properties: { plan: { type: "string", maxLength: 400 }, date: { type: "string", description: "YYYY-MM-DD, the user's local date" }, alarm_id: { type: "string" } }, required: ["plan"], additionalProperties: false },
    async run(env, userId, a) {
      const plan = String(a.plan ?? "").trim();
      if (a.alarm_id) {
        const s = await state(env, userId);
        const cur = s.alarms.find((x) => x.id === a.alarm_id);
        if (!cur) throw new Error(`No alarm with id ${a.alarm_id}. Call list_alarms first.`);
        const d = await queue(env, userId, "update_alarm", { id: a.alarm_id, message: plan }, `${plan ? "Set the message" : "Removed the message"} for ${fmtAlarm(cur).split(" (id")[0]}`);
        return (plan ? `Saved as the message for ${fmtAlarm(cur)}.` : `Removed the message from ${fmtAlarm(cur)}.`) + d;
      }
      if (a.date) {
        const date = parseDate(a.date);
        const d = await queue(env, userId, "set_plan", { plan, date }, plan ? `Set your plan for ${date}: ${plan.slice(0, 60)}` : `Removed your plan for ${date}`);
        return (plan ? `Saved the plan for ${date}. The first alarm that day reads it (unless it has its own message).` : `Removed the plan for ${date}.`) + d;
      }
      if (!plan) throw new Error("The plan is empty.");
      await env.DB.prepare("UPDATE state SET plan = ? WHERE user_id = ?").bind(plan, userId).run();
      const d = await queue(env, userId, "set_plan", { plan }, `Set your plan: ${plan.slice(0, 80)}`);
      return "Saved as the general plan, read at the next alarm without its own message." + d;
    },
  },
  {
    name: "list_alarms", title: "List alarms", readOnly: true,
    description: "Read all alarms on the user's phone, on and off, with everything about each: id, label, time, days, sound, volume, vibration, snooze, whether the plan is read aloud (and voice), and the evening planning reminder.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run(env, userId) {
      const s = await state(env, userId);
      if (!s.alarms.length) return "No alarms yet.";
      const n = localNow(s.tz);
      const sorted = [...s.alarms].sort((x, y) => (nextRing(x, n)?.inMin ?? 1e9) - (nextRing(y, n)?.inMin ?? 1e9) || (x.h * 60 + x.m) - (y.h * 60 + y.m));
      const plans = s.datePlans && Object.keys(s.datePlans).length ? "\n\nPlans by date:\n" + Object.entries(s.datePlans).sort().map(([d, p]) => `  ${d}: "${p}"`).join("\n") : "";
      const general = s.plan ? `\nGeneral plan (next alarm without a message): "${s.plan}"` : "";
      return nowLine(n) + "\n" + `${sorted.length} alarm${sorted.length === 1 ? "" : "s"}, soonest first (as of the phone's last sync${s.updatedAt ? ", " + new Date(s.updatedAt).toISOString() : ""}):\n\n` + sorted.map((x) => detailAlarm(x, n)).join("\n\n") + plans + general;
    },
  },
  {
    name: "create_alarm", title: "Create alarm",
    description: "Create one alarm. Without days it's a one-time alarm: it rings once (on date, or the next time that time comes up) and then switches itself off; ask whether it should repeat if the user didn't say. Each alarm can carry its own message that is read aloud when it rings. To set up many alarms at once (e.g. a whole week), use create_alarms. ",
    inputSchema: { type: "object", properties: ALARM_PROPS, required: ["time"], additionalProperties: false },
    async run(env, userId, a) {
      const s = await state(env, userId); const n = localNow(s.tz);
      const alarm = buildAlarm(a);
      if (!nextRing(alarm, n)) throw new Error(`That time has already passed. ${nowLine(n)}`);
      const dup = !a.allow_duplicate && duplicateOf(alarm, s.alarms, n);
      if (dup) return `Not created: there's already an alarm then: ${describe(dup, n)}. Ask the user whether to change that one (update_alarm) or really add a second (allow_duplicate: true).`;
      const d = await queue(env, userId, "create_alarm", alarm, `Added: ${describe(asPhone(alarm), n).split(" (id")[0]}`);
      return `Created: ${describe(asPhone(alarm), n)}` + oddHourNote(String(a.time), alarm.h) + d + READ_BACK;
    },
  },
  {
    name: "create_alarms", title: "Create several alarms",
    description: "Create up to 50 alarms in one go, e.g. a whole week planned on Sunday, each with its own time, days or date, label and message. Same fields as create_alarm. Read list_alarms first. For more than 5 alarms, show the user the list and get a yes before calling this. Alarms at the same time as an existing one are skipped and listed. Repeat the result lines to the user.",
    inputSchema: { type: "object", properties: { allow_duplicate: { type: "boolean" }, alarms: { type: "array", minItems: 1, maxItems: 50, items: { type: "object", properties: ALARM_PROPS, required: ["time"], additionalProperties: false } } }, required: ["alarms"], additionalProperties: false },
    async run(env, userId, a) {
      const s = await state(env, userId); const n = localNow(s.tz);
      const list = (a.alarms as any[]).map((x, i) => buildAlarm(x, i));
      const ok: any[] = []; const skipped: string[] = [];
      for (const [i, x] of list.entries()) {
        if (!nextRing(x, n)) { skipped.push(`${clock(x.h, x.m)}${x.date ? " on " + x.date : ""}: already passed`); continue; }
        const dup = !a.allow_duplicate && duplicateOf(x, [...s.alarms, ...ok.map(asPhone)], n);
        if (dup) { skipped.push(`${clock(x.h, x.m)}: same time as ${describe(dup, n)}`); continue; }
        ok.push(x); void i;
      }
      const ids: number[] = [];
      for (const alarm of ok) ids.push(await queueOnly(env, userId, "create_alarm", alarm));
      if (ok.length) { await logAction(env, userId, `Added ${ok.length} alarms`); await wakePhones(env, userId); }
      return `Created ${ok.length} alarm${ok.length === 1 ? "" : "s"}:\n` + ok.map((x) => "• " + describe(asPhone(x), n) + oddHourNote(String((a.alarms as any[])[list.indexOf(x)]?.time ?? ""), x.h)).join("\n")
        + (skipped.length ? `\n\nNot created (${skipped.length}):\n` + skipped.map((t) => "• " + t).join("\n") : "")
        + await delivery(env, userId, ids) + READ_BACK;
    },
  },
  {
    name: "update_alarm", title: "Change alarm",
    description: "Change an existing alarm by id (from list_alarms): time, days, date, label or message (an empty string removes label/message), or turn it on/off. ",
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
      const n = localNow(s.tz);
      const d = await queue(env, userId, "update_alarm", patch, `Changed: ${describe(after, n).split(" (id")[0]}`);
      return `Changed: ${describe(after, n)}` + (a.time ? oddHourNote(String(a.time), Number(after.h)) : "") + d + READ_BACK;
    },
  },
  {
    name: "get_alarm_history", title: "Follow-up answers", readOnly: true,
    description: "What the user answered to follow-up questions (yes / no / no answer), when, and what the phone did next. Use it to follow up, e.g. 'the citrus took 3 checks yesterday'.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run(env, userId) {
      const raw = await env.OAUTH_KV.get("history:" + userId);
      const list = raw ? (JSON.parse(raw) as any[]) : [];
      if (!list.length) return "No follow-up answers yet.";
      return list.slice(-40).reverse().map((h) => `${new Date(h.at).toISOString()} · ${h.label ? h.label + " · " : ""}"${h.question}" → ${h.answer} · next: ${h.next}`).join("\n");
    },
  },
  {
    name: "check_delivery", title: "Check what reached the phone", readOnly: true,
    description: "Shows the recent changes you made and whether the phone has confirmed each one, plus when the phone last synced.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    async run(env, userId) {
      const s = await state(env, userId);
      const rows = await env.DB.prepare("SELECT kind, payload, created_at, applied_at FROM changes WHERE user_id = ? ORDER BY id DESC LIMIT 25").bind(userId).all<{ kind: string; payload: string; created_at: number; applied_at: number | null }>();
      if (!rows.results.length) return "No changes sent yet.";
      const line = (r: any) => {
        const p = JSON.parse(r.payload);
        const what = r.kind === "create_alarm" ? `create ${fmtAlarm(asPhone(p)).split(" (id")[0]}` : r.kind === "set_plan" ? `plan${p.date ? " for " + p.date : ""}: ${String(p.plan).slice(0, 50)}` : `${r.kind.replace("_", " ")} ${p.id ?? p.app ?? ""}`;
        return `${r.applied_at ? "✓ on phone" : "… waiting"} · ${what} (sent ${new Date(r.created_at).toISOString()})`;
      };
      return `Phone last synced: ${s.updatedAt ? new Date(s.updatedAt).toISOString() : "never"}\n` + rows.results.map(line).join("\n");
    },
  },
  {
    name: "delete_alarm", title: "Delete alarm",
    description: "Delete an alarm by id (from list_alarms). This removes it from the phone completely; to pause it instead, use update_alarm with on: false. Always confirm with the user first, naming the alarm (day, time, label).",
    inputSchema: { type: "object", properties: { id: { type: "string" } }, required: ["id"], additionalProperties: false },
    destructive: true,
    async run(env, userId, a) {
      const s = await state(env, userId);
      const cur = s.alarms.find((x) => x.id === a.id);
      if (!cur) throw new Error(`No alarm with id ${a.id}. Call list_alarms first.`);
      const n = localNow(s.tz);
      const d = await queue(env, userId, "delete_alarm", { id: a.id }, `Deleted: ${describe(cur, n).split(" (id")[0]}`);
      return `Deleted: ${describe(cur, n)}` + d;
    },
  },
  {
    name: "set_app_limit", title: "Set app limit",
    description: "Phone down: after this many minutes in an app (e.g. Instagram, TikTok, YouTube), a reminder covers the screen. minutes: 1–60. Use minutes 0 to stop limiting that app.",
    inputSchema: { type: "object", properties: { app: { type: "string" }, minutes: { type: "integer", minimum: 0, maximum: 60 } }, required: ["app", "minutes"], additionalProperties: false },
    async run(env, userId, a) {
      const app = String(a.app).trim(); const minutes = Number(a.minutes);
      const d = await queue(env, userId, "set_app_limit", { app, minutes }, minutes ? `Limited ${app} to ${minutes} minutes` : `Stopped limiting ${app}`);
      return (minutes ? `Set: remind after ${minutes} min in ${app}.` : `${app} is no longer limited.`) + d;
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

// Phone down (app limits, screen time) is off for v1; the tools stay in the code for later.
const OFF = new Set(["set_app_limit", "get_screen_time", "get_alarm_history"]); // + follow-ups parked
const live = () => tools.filter((t) => !OFF.has(t.name));

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
        instructions: "Why Not You is the user's alarm app. Read list_alarms before changing anything. Every alarm can have a label and its own message (read aloud when it rings). For something that needs checking again later, create the later alarms too, each with its own message. When the user wants an alarm for a task, put what to do in that alarm's message, not in set_plan. Results come back as one plain line per alarm (day, date, time, label, repeat, message, time left): repeat them to the user so they can check. Ask only when needed: unclear am/pm, before deleting, and before creating more than 5 alarms. set_plan is only the general plan for the next alarm without a message, or a plan for a date. Keep spoken text short. Use create_alarms for many alarms at once. Each change reports whether the phone confirmed it.",
      });
    case "ping":
      return rpc(msg.id, {});
    case "tools/list":
      return rpc(msg.id, {
        tools: live().map((t) => ({ name: t.name, title: t.title, description: t.description, inputSchema: t.inputSchema,
          annotations: { readOnlyHint: !!t.readOnly, destructiveHint: !!t.destructive, openWorldHint: false } })),
      });
    case "tools/call": {
      const t = live().find((x) => x.name === msg.params?.name);
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
