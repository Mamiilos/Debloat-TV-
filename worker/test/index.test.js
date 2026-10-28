import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

function kv() {
  const m = new Map();
  return {
    m,
    async get(k, type) { const v = m.get(k); return v === undefined ? null : type === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, v); },
  };
}

let env, calls, ukhoDown;
const EVENTS = [
  { EventType: 'LowWater', DateTime: '2099-01-01T06:00:00', Height: 1.234 },
  { EventType: 'HighWater', DateTime: '2099-01-01T11:22:00', Height: 9.61 },
  { EventType: 'HighWater', DateTime: '2000-01-01T00:00:00', Height: 9 },   // past: left out
];

beforeEach(() => {
  env = { TV_TOKEN: 'secret-token', UKHO_KEY: 'ukho', STATIONS: '0451:Mersey,0461:Dee', ALLOWED_IPS: '', CACHE: kv() };
  calls = [];
  ukhoDown = false;
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), key: init.headers['Ocp-Apim-Subscription-Key'] });
    if (ukhoDown) return new Response('down', { status: 503 });
    return new Response(JSON.stringify(EVENTS), { status: 200 });
  };
  console.error = () => {};
});

const get = (path, headers = {}) => worker.fetch(new Request('https://w.example' + path, { headers }), env);
const authed = (path, extra = {}) => get(path, { Authorization: 'Bearer secret-token', ...extra });

test('refuses without the right token', async () => {
  assert.equal((await get('/v1/tides')).status, 401);
  assert.equal((await get('/v1/tides', { Authorization: 'Bearer secret-tokeN' })).status, 401);
  assert.equal((await get('/v1/tides', { Authorization: 'Bearer secret-token-longer' })).status, 401);
  assert.equal(calls.length, 0);
});

test('tides for both stations, UTC times, upcoming only, rounded heights', async () => {
  const r = await authed('/v1/tides');
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('Access-Control-Allow-Origin'), '*');
  const body = await r.json();
  assert.deepEqual(body.stations.map((s) => s.name), ['Mersey', 'Dee']);
  assert.deepEqual(body.stations[0].events, [
    { type: 'low', at: '2099-01-01T06:00:00Z', height: 1.2 },
    { type: 'high', at: '2099-01-01T11:22:00Z', height: 9.6 },
  ]);
  assert.equal(calls[0].key, 'ukho');
  assert.match(calls[0].url, /Stations\/0451\/TidalEvents/);
});

test('cached: a second request within 6 h does not call UKHO', async () => {
  await authed('/v1/tides');
  await authed('/v1/tides');
  assert.equal(calls.length, 2);   // once per station
});

test('UKHO down: the last answer, marked stale with the error; health records it', async () => {
  await authed('/v1/tides');
  for (const [k, v] of env.CACHE.m) if (k.startsWith('tides:')) env.CACHE.m.set(k, JSON.stringify({ ...JSON.parse(v), fetchedAt: 1 }));
  ukhoDown = true;
  const s = (await (await authed('/v1/tides')).json()).stations[0];
  assert.equal(s.stale, true);
  assert.match(s.error, /HTTP 503/);
  assert.equal(s.events.length, 2);
  const h = await (await get('/health')).json();
  assert.match(h.sources['tides:0451'].error, /HTTP 503/);
  assert.ok(h.sources['tides:0451'].okAt);
});

test('UKHO down with nothing cached: that station empty, the other still answers', async () => {
  globalThis.fetch = async (url) => (String(url).includes('0451') ? new Response('', { status: 500 }) : new Response(JSON.stringify(EVENTS)));
  const body = await (await authed('/v1/tides')).json();
  assert.equal(body.stations[0].events.length, 0);
  assert.equal(body.stations[0].stale, true);
  assert.equal(body.stations[1].events.length, 2);
});

test('IP allowlist', async () => {
  env.ALLOWED_IPS = '203.0.113.7';
  assert.equal((await authed('/v1/tides', { 'CF-Connecting-IP': '198.51.100.1' })).status, 403);
  assert.equal((await authed('/v1/tides', { 'CF-Connecting-IP': '203.0.113.7' })).status, 200);
});

test('preflight and health need no token, and health holds no secrets', async () => {
  const pre = await worker.fetch(new Request('https://w.example/v1/tides', { method: 'OPTIONS' }), env);
  assert.equal(pre.status, 204);
  assert.match(pre.headers.get('Access-Control-Allow-Headers'), /Authorization/);
  const h = await get('/health');
  assert.equal(h.status, 200);
  assert.doesNotMatch(await h.text(), /secret-token|ukho"/);
});
