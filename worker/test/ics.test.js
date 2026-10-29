import { test } from 'node:test';
import assert from 'node:assert/strict';
import { eventsBetween, dayBounds, zonedToUtc } from '../src/ics.js';

const cal = (...events) => ['BEGIN:VCALENDAR', 'VERSION:2.0', 'X-WR-TIMEZONE:Europe/London', ...events, 'END:VCALENDAR'].join('\r\n');
const ev = (props) => ['BEGIN:VEVENT', ...props, 'END:VEVENT'].join('\r\n');
const iso = (t) => new Date(t).toISOString().slice(0, 16);
const on = (text, ymd) => {
  const [from, to] = dayBounds(Date.parse(ymd + 'T12:00:00Z'));
  return eventsBetween(text, from, to).map((e) => `${e.allDay ? 'all day' : iso(e.start)} ${e.title}`);
};

test('London time zone: a summer and a winter wall clock land on the right UTC', () => {
  assert.equal(iso(zonedToUtc(2026, 7, 1, 9, 0, 0, 'Europe/London')), '2026-07-01T08:00');   // BST
  assert.equal(iso(zonedToUtc(2026, 10, 28, 9, 0, 0, 'Europe/London')), '2026-10-28T09:00');  // GMT
  assert.equal(iso(dayBounds(Date.parse('2026-07-01T12:00:00Z'))[0]), '2026-06-30T23:00');
});

test('one-off events: in a time zone, in UTC, all day; only the day asked for', () => {
  const t = cal(
    ev(['UID:a', 'DTSTART;TZID=Europe/London:20261028T140000', 'DTEND;TZID=Europe/London:20261028T150000', 'SUMMARY:Dentist']),
    ev(['UID:b', 'DTSTART:20261028T183000Z', 'DTEND:20261028T200000Z', 'SUMMARY:Dinner with Sam']),
    ev(['UID:c', 'DTSTART;VALUE=DATE:20261028', 'DTEND;VALUE=DATE:20261029', 'SUMMARY:Bin day']),
    ev(['UID:d', 'DTSTART;TZID=Europe/London:20261029T100000', 'DTEND;TZID=Europe/London:20261029T110000', 'SUMMARY:Tomorrow thing']),
  );
  assert.deepEqual(on(t, '2026-10-28'), ['all day Bin day', '2026-10-28T14:00 Dentist', '2026-10-28T18:30 Dinner with Sam']);
});

test('a weekly repeat stays 9:00 local across the clock change, and only on its days', () => {
  const t = cal(ev(['UID:w', 'DTSTART;TZID=Europe/London:20240101T090000', 'DTEND;TZID=Europe/London:20240101T093000',
    'RRULE:FREQ=WEEKLY;BYDAY=MO,WE', 'SUMMARY:Standup']));
  assert.deepEqual(on(t, '2026-07-01'), ['2026-07-01T08:00 Standup']);   // Wednesday, BST
  assert.deepEqual(on(t, '2026-10-28'), ['2026-10-28T09:00 Standup']);   // Wednesday, GMT
  assert.deepEqual(on(t, '2026-10-29'), []);                              // Thursday
});

test('EXDATE skips one; RECURRENCE-ID moves one; a cancelled occurrence goes', () => {
  const t = cal(
    ev(['UID:r', 'DTSTART;TZID=Europe/London:20261001T190000', 'DTEND;TZID=Europe/London:20261001T200000', 'RRULE:FREQ=DAILY',
      'EXDATE;TZID=Europe/London:20261027T190000', 'SUMMARY:Gym']),
    ev(['UID:r', 'RECURRENCE-ID;TZID=Europe/London:20261028T190000', 'DTSTART;TZID=Europe/London:20261028T070000',
      'DTEND;TZID=Europe/London:20261028T080000', 'SUMMARY:Gym (early)']),
    ev(['UID:r', 'RECURRENCE-ID;TZID=Europe/London:20261029T190000', 'DTSTART;TZID=Europe/London:20261029T190000',
      'DTEND;TZID=Europe/London:20261029T200000', 'STATUS:CANCELLED', 'SUMMARY:Gym']),
  );
  assert.deepEqual(on(t, '2026-10-26'), ['2026-10-26T19:00 Gym']);
  assert.deepEqual(on(t, '2026-10-27'), []);
  assert.deepEqual(on(t, '2026-10-28'), ['2026-10-28T07:00 Gym (early)']);
  assert.deepEqual(on(t, '2026-10-29'), []);
  assert.deepEqual(on(t, '2026-10-30'), ['2026-10-30T19:00 Gym']);
});

test('COUNT and UNTIL end a repeat; INTERVAL skips', () => {
  const t = cal(
    ev(['UID:c', 'DTSTART;TZID=Europe/London:20261026T120000', 'DTEND;TZID=Europe/London:20261026T130000', 'RRULE:FREQ=DAILY;COUNT=2', 'SUMMARY:Twice']),
    ev(['UID:u', 'DTSTART;TZID=Europe/London:20261026T080000', 'DTEND;TZID=Europe/London:20261026T083000', 'RRULE:FREQ=DAILY;UNTIL=20261027T235959Z', 'SUMMARY:Until Tue']),
    ev(['UID:i', 'DTSTART;TZID=Europe/London:20261026T170000', 'DTEND;TZID=Europe/London:20261026T180000', 'RRULE:FREQ=WEEKLY;INTERVAL=2', 'SUMMARY:Fortnightly']),
  );
  assert.deepEqual(on(t, '2026-10-27'), ['2026-10-27T08:00 Until Tue', '2026-10-27T12:00 Twice']);
  assert.deepEqual(on(t, '2026-10-28'), []);
  assert.deepEqual(on(t, '2026-11-02'), []);
  assert.deepEqual(on(t, '2026-11-09'), ['2026-11-09T17:00 Fortnightly']);
});

test('monthly on the 2nd Tuesday; yearly all-day birthdays', () => {
  const t = cal(
    ev(['UID:m', 'DTSTART;TZID=Europe/London:20260113T190000', 'DTEND;TZID=Europe/London:20260113T200000', 'RRULE:FREQ=MONTHLY;BYDAY=2TU', 'SUMMARY:Book club']),
    ev(['UID:y', 'DTSTART;VALUE=DATE:19900429', 'DTEND;VALUE=DATE:19900430', 'RRULE:FREQ=YEARLY', 'SUMMARY:Mum\\, birthday']),
  );
  assert.deepEqual(on(t, '2026-11-10'), ['2026-11-10T19:00 Book club']);   // 2nd Tuesday of November
  assert.deepEqual(on(t, '2026-11-03'), []);
  assert.deepEqual(on(t, '2027-04-29'), ['all day Mum, birthday']);
});

test('folded lines and escapes, as Google writes long titles', () => {
  const t = cal(['BEGIN:VEVENT', 'UID:f', 'DTSTART:20261028T100000Z', 'DTEND:20261028T110000Z',
    'SUMMARY:A very long title that Google folds onto a sec', ' ond line\\; with a semicolon', 'END:VEVENT'].join('\r\n'));
  assert.deepEqual(on(t, '2026-10-28'), ['2026-10-28T10:00 A very long title that Google folds onto a second line; with a semicolon']);
});

test('fast enough for a Worker: a feed with years of events', () => {
  const events = [];
  for (let i = 0; i < 1500; i++) {
    const d = new Date(Date.UTC(2019, 0, 1) + i * 86400000 * 1.3);
    const stamp = d.toISOString().replace(/[-:]/g, '').slice(0, 15);
    events.push(ev([`UID:x${i}`, `DTSTART;TZID=Europe/London:${stamp}`, `DTEND;TZID=Europe/London:${stamp}`, `SUMMARY:Old ${i}`]));
  }
  for (let i = 0; i < 200; i++) {
    events.push(ev([`UID:r${i}`, 'DTSTART;TZID=Europe/London:20190107T090000', 'DTEND;TZID=Europe/London:20190107T093000',
      `RRULE:FREQ=${['DAILY', 'WEEKLY'][i % 2]}`, `SUMMARY:Repeat ${i}`]));
  }
  const text = cal(...events);
  const [from, to] = dayBounds(Date.parse('2026-10-28T12:00:00Z'));
  eventsBetween(text, from, to);   // warm up
  const t0 = performance.now();
  const got = eventsBetween(text, from, to);
  const ms = performance.now() - t0;
  console.log(`# ${(text.length / 1024).toFixed(0)} KB feed, ${got.length} events today, ${ms.toFixed(1)} ms`);
  assert.ok(got.length >= 100);
  assert.ok(ms < 50, `${ms} ms`);
});
