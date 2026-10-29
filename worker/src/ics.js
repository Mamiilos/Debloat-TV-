/**
 * Today's events from an iCal (ICS) feed, as Google Calendar's secret address serves it: VEVENTs with DTSTART in UTC
 * (Z), in a time zone (TZID=Europe/London) or as a date (all day), repeating ones (RRULE with FREQ, INTERVAL, COUNT,
 * UNTIL, BYDAY, BYMONTHDAY, BYMONTH; EXDATE), edited occurrences (RECURRENCE-ID) and cancellations.
 * Repeats are expanded in the event's own wall clock, so a weekly 9:00 stays 9:00 across the clocks changing.
 */

const DAY = 86400000;
const WEEKDAYS = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'];

/** Lines with folding undone ("\r\n " continues the line before). */
function unfold(text) {
  return text.replace(/\r\n/g, '\n').replace(/\n[ \t]/g, '').split('\n');
}

/** {name, params, value} of "NAME;P=V;Q=W:value". */
function property(line) {
  const colon = line.indexOf(':');
  if (colon < 0) return null;
  const [name, ...params] = line.slice(0, colon).split(';');
  const p = {};
  for (const x of params) { const i = x.indexOf('='); if (i > 0) p[x.slice(0, i).toUpperCase()] = x.slice(i + 1).replace(/^"|"$/g, ''); }
  return { name: name.toUpperCase(), params: p, value: line.slice(colon + 1) };
}

const unescape = (v) => v.replace(/\\n/gi, ' ').replace(/\\([,;\\])/g, '$1').trim();

// Building an Intl.DateTimeFormat is the slow part (Workers' free plan allows ~10 ms of CPU a request): one per zone.
const formatters = new Map();
function formatter(tz) {
  if (!formatters.has(tz)) {
    formatters.set(tz, new Intl.DateTimeFormat('en-GB', {
      timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    }));
  }
  return formatters.get(tz);
}
const fields = (t, tz) => Object.fromEntries(formatter(tz).formatToParts(new Date(t)).map((x) => [x.type, x.value]));

/** The UTC time of a wall-clock time in a time zone (two passes: right on both sides of a clock change). */
export function zonedToUtc(y, mo, d, h, mi, s, tz) {
  const guess = Date.UTC(y, mo - 1, d, h, mi, s);
  if (!tz || tz === 'UTC' || tz === 'Etc/UTC') return guess;
  const offsetAt = (t) => {
    const parts = fields(t, tz);
    return Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second) - t;
  };
  let t = guess - offsetAt(guess);
  t = guess - offsetAt(t);
  return t;
}

/** The wall-clock fields of a UTC time in a time zone. */
function wallClock(t, tz) {
  const parts = fields(t, tz || 'UTC');
  return { y: +parts.year, mo: +parts.month, d: +parts.day, h: +parts.hour, mi: +parts.minute, s: +parts.second };
}

/**
 * {allDay, tz, wall: {y, mo, d, h, mi, s}, day} of a DTSTART/DTEND/EXDATE/RECURRENCE-ID value. The UTC time comes from
 * utc(), only for the few near the day asked for: converting every event of a years-long feed costs too much CPU.
 */
function parseTime(prop, fallbackTz) {
  if (!prop) return null;
  const v = prop.value.trim();
  let m = /^(\d{4})(\d\d)(\d\d)$/.exec(v);
  if (m || prop.params.VALUE === 'DATE') {
    m = m || /^(\d{4})(\d\d)(\d\d)/.exec(v);
    const wall = { y: +m[1], mo: +m[2], d: +m[3], h: 0, mi: 0, s: 0 };
    return { allDay: true, tz: fallbackTz, wall, day: dayNumber(wall.y, wall.mo, wall.d) };
  }
  m = /^(\d{4})(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)(Z?)$/.exec(v);
  if (!m) return null;
  const wall = { y: +m[1], mo: +m[2], d: +m[3], h: +m[4], mi: +m[5], s: +m[6] };
  return { allDay: false, tz: m[7] ? 'UTC' : prop.params.TZID || fallbackTz, wall, day: dayNumber(wall.y, wall.mo, wall.d) };
}

function utc(pt) {
  if (pt.t === undefined) pt.t = zonedToUtc(pt.wall.y, pt.wall.mo, pt.wall.d, pt.wall.h, pt.wall.mi, pt.wall.s, pt.tz);
  return pt.t;
}

/** Seconds of wall clock, for lengths without converting time zones (an event across a clock change is off by an hour). */
const wallSeconds = (pt) => pt.day * 86400 + pt.wall.h * 3600 + pt.wall.mi * 60 + pt.wall.s;

function parseRule(value) {
  const r = {};
  for (const part of value.split(';')) { const [k, v] = part.split('='); if (k) r[k.toUpperCase()] = v; }
  return {
    freq: r.FREQ, interval: Math.max(parseInt(r.INTERVAL || '1', 10), 1), count: r.COUNT ? parseInt(r.COUNT, 10) : null,
    until: r.UNTIL || null, byday: r.BYDAY ? r.BYDAY.split(',') : null,
    bymonthday: r.BYMONTHDAY ? r.BYMONTHDAY.split(',').map(Number) : null, bymonth: r.BYMONTH ? r.BYMONTH.split(',').map(Number) : null,
  };
}

/** Days from 1970-01-01 of a wall-clock date (date arithmetic without time zones). */
const dayNumber = (y, mo, d) => Math.floor(Date.UTC(y, mo - 1, d) / DAY);
const fromDayNumber = (n) => { const x = new Date(n * DAY); return { y: x.getUTCFullYear(), mo: x.getUTCMonth() + 1, d: x.getUTCDate() }; };
const weekday = (n) => (n + 4) % 7;   // 1970-01-01 was a Thursday
const daysInMonth = (y, mo) => new Date(Date.UTC(y, mo, 0)).getUTCDate();

/** "2TU" → is this day the 2nd Tuesday of its month ("-1FR": the last Friday)? A bare "TU" is any Tuesday. */
function matchesByday(spec, y, mo, d) {
  const m = /^([+-]?\d+)?([A-Z]{2})$/.exec(spec);
  if (!m || WEEKDAYS[weekday(dayNumber(y, mo, d))] !== m[2]) return false;
  if (!m[1]) return true;
  const nth = parseInt(m[1], 10);
  return nth > 0 ? Math.ceil(d / 7) === nth : Math.ceil((daysInMonth(y, mo) - d + 1) / 7) === -nth;
}

/**
 * The occurrences (day numbers and UTC starts) up to lastDay, following the rule day by day. Whether a day matches
 * doesn't depend on the days before it, so without COUNT the walk starts just before fromDay; with COUNT it has to
 * count from the very first occurrence.
 */
function expandDays(rule, start, fromDay, lastDay, untilT, tz) {
  const first = start.day;
  const out = [];
  let count = 0;
  for (let n = rule.count === null ? Math.max(first, fromDay - 2) : first; n <= lastDay; n++) {
    const { y, mo, d } = fromDayNumber(n);
    const delta = n - first;
    let ok = false;
    if (rule.freq === 'DAILY') ok = delta % rule.interval === 0;
    else if (rule.freq === 'WEEKLY') {
      const weekStart = (k) => k - ((weekday(k) + 6) % 7);   // RFC 5545 default WKST=MO
      const weeks = Math.round((weekStart(n) - weekStart(first)) / 7);
      ok = weeks % rule.interval === 0 && (rule.byday ? rule.byday.some((b) => b.slice(-2) === WEEKDAYS[weekday(n)]) : weekday(n) === weekday(first));
    } else if (rule.freq === 'MONTHLY') {
      const months = (y - start.wall.y) * 12 + (mo - start.wall.mo);
      ok = months % rule.interval === 0 && (rule.byday ? rule.byday.some((b) => matchesByday(b, y, mo, d))
        : (rule.bymonthday || [start.wall.d]).some((md) => (md > 0 ? md : daysInMonth(y, mo) + md + 1) === d));
    } else if (rule.freq === 'YEARLY') {
      ok = (y - start.wall.y) % rule.interval === 0 && (rule.bymonth || [start.wall.mo]).includes(mo)
        && (rule.byday ? rule.byday.some((b) => matchesByday(b, y, mo, d)) : (rule.bymonthday || [start.wall.d]).includes(d));
    }
    if (!ok) continue;
    if (n < fromDay - 1 && untilT === null) {   // before the window: count it without converting its time
      count++;
      if (rule.count !== null && count > rule.count) break;
      continue;
    }
    const t = start.allDay ? zonedToUtc(y, mo, d, 0, 0, 0, tz) : zonedToUtc(y, mo, d, start.wall.h, start.wall.mi, start.wall.s, start.tz);
    if (untilT !== null && t > untilT) break;
    count++;
    if (rule.count !== null && count > rule.count) break;
    if (n >= fromDay - 1) out.push({ day: n, t });
  }
  return out;
}

/** VEVENTs as {uid, summary, start, end, rule, exdates (UTC ms), recurrenceId, cancelled}. */
export function parseEvents(text, defaultTz = 'Europe/London') {
  const lines = unfold(text);
  const calTz = (lines.find((l) => l.startsWith('X-WR-TIMEZONE:')) || '').slice(14).trim() || defaultTz;
  const events = [];
  let cur = null;
  for (const line of lines) {
    if (line === 'BEGIN:VEVENT') { cur = { props: [] }; continue; }
    if (line === 'END:VEVENT') { if (cur) events.push(cur); cur = null; continue; }
    if (cur) { const p = property(line); if (p) cur.props.push(p); }
  }
  return events.map((e) => {
    const get = (n) => e.props.find((p) => p.name === n);
    const start = parseTime(get('DTSTART'), calTz);
    if (!start) return null;
    let end = parseTime(get('DTEND'), calTz);
    if (!end) {
      const d = fromDayNumber(start.day + (start.allDay ? 1 : 0));
      end = { ...start, wall: { ...start.wall, ...d }, day: start.day + (start.allDay ? 1 : 0), t: undefined };
    }
    const exdates = [];
    for (const p of e.props.filter((x) => x.name === 'EXDATE')) {
      for (const v of p.value.split(',')) { const t = parseTime({ ...p, value: v }, calTz); if (t) exdates.push(t); }
    }
    const rid = get('RECURRENCE-ID');
    return {
      uid: (get('UID') || {}).value || '', summary: unescape((get('SUMMARY') || {}).value || ''), start, end,
      rule: get('RRULE') ? parseRule(get('RRULE').value) : null, exdates,
      recurrenceId: rid ? parseTime(rid, calTz) : null,
      cancelled: ((get('STATUS') || {}).value || '').toUpperCase() === 'CANCELLED', calTz,
    };
  }).filter(Boolean);
}

/**
 * The events that overlap [from, to): [{title, start, end, allDay}] sorted by start, repeats expanded, edited
 * occurrences replacing their originals, cancelled ones and EXDATEs left out.
 */
export function eventsBetween(text, from, to, defaultTz = 'Europe/London') {
  const events = parseEvents(text, defaultTz);
  const fromDay = Math.floor(from / DAY), toDay = Math.floor(to / DAY);
  const near = (startPt, endPt) => endPt.day >= fromDay - 1 && startPt.day <= toDay + 1;   // a day either side covers any zone
  const overrides = new Map();
  for (const e of events) {
    if (e.recurrenceId !== null && e.recurrenceId.day >= fromDay - 2 && e.recurrenceId.day <= toDay + 2) {
      overrides.set(`${e.uid}|${utc(e.recurrenceId)}`, e);
    }
  }
  const out = [];
  const add = (e, start, end) => {
    if (e.cancelled || end <= from || start >= to) return;
    out.push({ title: e.summary, start, end, allDay: e.start.allDay });
  };
  for (const e of events) {
    if (e.recurrenceId !== null || !e.rule) {
      if (near(e.start, e.end)) add(e, utc(e.start), utc(e.end));
      continue;
    }
    const length = (wallSeconds(e.end) - wallSeconds(e.start)) * 1000;
    const tz = e.start.allDay ? e.calTz : e.start.tz;
    const untilPt = e.rule.until ? parseTime({ value: e.rule.until, params: {} }, tz) : null;
    if (untilPt && untilPt.day < fromDay - 1) continue;   // ended before today: nothing to expand
    const untilT = untilPt ? utc(untilPt) : null;
    const exdates = new Set(e.exdates.filter((x) => x.day >= fromDay - 2 && x.day <= toDay + 2).map(utc));
    for (const occ of expandDays(e.rule, e.start, Math.floor((from - length) / DAY), toDay + 1, untilT, tz)) {
      if (exdates.has(occ.t) || overrides.has(`${e.uid}|${occ.t}`)) continue;
      add(e, occ.t, occ.t + length);
    }
  }
  return out.sort((a, b) => a.start - b.start || a.title.localeCompare(b.title));
}

/** [from, to) of the local day containing t, in tz. */
export function dayBounds(t, tz = 'Europe/London') {
  const w = wallClock(t, tz);
  const from = zonedToUtc(w.y, w.mo, w.d, 0, 0, 0, tz);
  const n = fromDayNumber(dayNumber(w.y, w.mo, w.d) + 1);
  return [from, zonedToUtc(n.y, n.mo, n.d, 0, 0, 0, tz)];
}
