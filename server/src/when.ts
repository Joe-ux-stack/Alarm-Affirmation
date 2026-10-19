// Turns an alarm into one plain line in the user's own time zone, e.g.
//   Tue 20 Oct, 6:30 am · "Work" · weekdays · says: "Stand-up at 9." · rings in 8h 25m
// so the AI can read back exactly what will happen.

const WD = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
const WD_NAME = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const MONTH = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export type LocalNow = { y: number; mo: number; d: number; wd: number; min: number; tz: string };

export function localNow(tz: string, at = Date.now()): LocalNow {
  let zone = tz;
  try { new Intl.DateTimeFormat("en-US", { timeZone: zone }); } catch { zone = "UTC"; }
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-US", {
    timeZone: zone, year: "numeric", month: "numeric", day: "numeric", weekday: "short", hour: "numeric", minute: "numeric", hourCycle: "h23",
  }).formatToParts(new Date(at)).map((p) => [p.type, p.value]));
  return { y: +parts.year, mo: +parts.month, d: +parts.day, wd: WD.indexOf(String(parts.weekday).toLowerCase().slice(0, 3)),
    min: (+parts.hour % 24) * 60 + +parts.minute, tz: zone };
}

/** Calendar maths on plain dates (no time zone involved). */
const dayNum = (y: number, mo: number, d: number) => Math.floor(Date.UTC(y, mo - 1, d) / 86400000);
const fromDayNum = (n: number) => { const t = new Date(n * 86400000); return { y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1, d: t.getUTCDate(), wd: (t.getUTCDay() + 6) % 7 }; };

export const clock = (h: number, m: number) => `${h % 12 === 0 ? 12 : h % 12}:${String(m).padStart(2, "0")} ${h < 12 ? "am" : "pm"}`;

/** When this alarm rings next, in the user's time zone. null = off or in the past. */
export function nextRing(a: any, now: LocalNow): { label: string; inMin: number; date: string } | null {
  if (a.on === false) return null;
  const h = Number(a.h), m = Number(a.m), at = h * 60 + m;
  const days: boolean[] = a.days ?? [];
  const today = dayNum(now.y, now.mo, now.d);
  let ahead = -1;
  if (!days.some(Boolean)) {
    if (a.date) {
      const [y, mo, d] = String(a.date).split("-").map(Number);
      ahead = dayNum(y, mo, d) - today;
      if (ahead < 0 || (ahead === 0 && at <= now.min)) return null;
    } else ahead = at > now.min ? 0 : 1;
  } else {
    for (let i = 0; i < 8; i++) { const wd = (now.wd + i) % 7; if (days[wd] && (i > 0 || at > now.min)) { ahead = i; break; } }
    if (ahead < 0) return null;
  }
  const t = fromDayNum(today + ahead);
  const word = ahead === 0 ? "Today" : ahead === 1 ? "Tomorrow" : WD_NAME[t.wd];
  return {
    label: `${word} ${t.d} ${MONTH[t.mo - 1]}, ${clock(h, m)}`,
    inMin: ahead * 1440 + at - now.min,
    date: `${t.y}-${String(t.mo).padStart(2, "0")}-${String(t.d).padStart(2, "0")}`,
  };
}

export const inWords = (min: number) => {
  const d = Math.floor(min / 1440), h = Math.floor((min % 1440) / 60), m = min % 60;
  return d > 0 ? `${d}d${h ? ` ${h}h` : ""}` : h > 0 ? `${h}h${m ? ` ${m}m` : ""}` : `${m} min`;
};

export function repeatWords(a: any): string {
  const d: boolean[] = a.days ?? [];
  if (d.every(Boolean)) return "every day";
  if (d.slice(0, 5).every(Boolean) && !d[5] && !d[6]) return "weekdays";
  if (!d.slice(0, 5).some(Boolean) && d[5] && d[6]) return "weekends";
  if (d.some(Boolean)) return "every " + WD_NAME.filter((_, i) => d[i]).join(", ");
  return "once";
}

/** The one line the AI should repeat to the user. */
export function describe(a: any, now: LocalNow): string {
  const label = String(a.lbl ?? a.label ?? "").trim();
  const next = nextRing(a, now);
  const msg = String((next && a.dn?.[next.date]) || a.note || a.message || "").trim();
  const when = next ? next.label : `${clock(Number(a.h), Number(a.m))} (off${a.on === false ? "" : ", already rang"})`;
  return [when, label ? `"${label}"` : "", repeatWords(a), msg ? `says: "${msg}"` : "", next ? `rings in ${inWords(next.inMin)}` : ""]
    .filter(Boolean).join(" · ") + ` (id ${a.id})`;
}
