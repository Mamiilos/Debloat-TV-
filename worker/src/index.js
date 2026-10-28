/**
 * The TV's backend: holds the API keys (Wrangler secrets), calls the sources, caches their answers in KV, and gives
 * the TV small JSON. The TV holds only TV_TOKEN, read-only and rotatable.
 *
 *   GET /v1/tides   the next week of high and low water at STATIONS (UKHO's Admiralty API)
 *   GET /health     each source's last fetch, for the TV's About screen and a monitor (no token: nothing private)
 *
 * Secrets: TV_TOKEN, UKHO_KEY. Vars: STATIONS ("0451:Mersey,0461:Dee"), ALLOWED_IPS (comma separated; empty = any).
 */

const DAY = 86400000;
const TIDES_FRESH = 6 * 3600000;   // tide predictions don't change: 8 calls a day to UKHO at most
const KEEP = 3 * DAY;              // a stale answer stays, to serve when UKHO is down

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization',
  'Access-Control-Max-Age': '86400',
};

function json(body, status = 200, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...CORS, ...extra },
  });
}

/** Compares in constant time, so the token can't be guessed from how long a refusal takes. */
function sameSecret(a, b) {
  const x = new TextEncoder().encode(a), y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}

function allowedIp(request, env) {
  const list = (env.ALLOWED_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  return !list.length || list.includes(request.headers.get('CF-Connecting-IP') || '');
}

function stations(env) {
  return (env.STATIONS || '0451:Mersey,0461:Dee').split(',').map((s) => {
    const [id, name] = s.split(':');
    return { id: id.trim(), name: (name || id).trim() };
  });
}

/**
 * The cached answer for key, fetched again when older than fresh. When the source fails, the last answer is served
 * with stale: true and the error, so the TV can say "stale since"; the failure is logged for Workers Logs.
 */
async function cached(env, key, fresh, load) {
  const old = await env.CACHE.get(key, 'json');
  if (old && Date.now() - old.fetchedAt < fresh) return { ...old, stale: false };
  try {
    const data = await load();
    const entry = { fetchedAt: Date.now(), data };
    await env.CACHE.put(key, JSON.stringify(entry), { expirationTtl: KEEP / 1000 });
    await env.CACHE.put('health:' + key, JSON.stringify({ okAt: entry.fetchedAt }), { expirationTtl: KEEP / 1000 });
    return { ...entry, stale: false };
  } catch (e) {
    console.error(JSON.stringify({ source: key, error: String(e.message || e) }));
    const health = (await env.CACHE.get('health:' + key, 'json')) || {};
    await env.CACHE.put('health:' + key, JSON.stringify({ ...health, failedAt: Date.now(), error: String(e.message || e) }),
      { expirationTtl: KEEP / 1000 });
    if (old) return { ...old, stale: true, error: String(e.message || e) };
    throw e;
  }
}

async function ukhoEvents(env, id) {
  const url = `https://admiraltyapi.azure-api.net/uktidalapi/api/V1/Stations/${encodeURIComponent(id)}/TidalEvents?duration=7`;
  const r = await fetch(url, { headers: { 'Ocp-Apim-Subscription-Key': env.UKHO_KEY } });
  if (!r.ok) throw new Error(`UKHO ${id}: HTTP ${r.status}`);
  const list = await r.json();
  // UKHO's times are UTC without a zone: say so, so the TV shows them in its own time zone.
  return list
    .filter((e) => e.EventType === 'HighWater' || e.EventType === 'LowWater')
    .map((e) => ({
      type: e.EventType === 'HighWater' ? 'high' : 'low',
      at: /Z|[+-]\d\d:?\d\d$/.test(e.DateTime) ? e.DateTime : e.DateTime + 'Z',
      height: typeof e.Height === 'number' ? Math.round(e.Height * 10) / 10 : null,
    }));
}

async function tides(env) {
  const now = Date.now();
  const out = [];
  for (const s of stations(env)) {
    try {
      const c = await cached(env, 'tides:' + s.id, TIDES_FRESH, () => ukhoEvents(env, s.id));
      out.push({ ...s, fetchedAt: c.fetchedAt, stale: c.stale, error: c.error,
        events: c.data.filter((e) => Date.parse(e.at) > now - 3600000) });
    } catch (e) {
      out.push({ ...s, fetchedAt: 0, stale: true, error: String(e.message || e), events: [] });
    }
  }
  return { stations: out };
}

async function health(env) {
  const sources = {};
  for (const s of stations(env)) sources['tides:' + s.id] = (await env.CACHE.get('health:tides:' + s.id, 'json')) || {};
  return { ok: true, now: Date.now(), sources };
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (request.method !== 'GET') return json({ error: 'GET only' }, 405);
    if (!allowedIp(request, env)) return json({ error: 'not from an allowed address' }, 403);
    if (path === '/health') return json(await health(env));

    const auth = request.headers.get('Authorization') || '';
    if (!env.TV_TOKEN || !sameSecret(auth, 'Bearer ' + env.TV_TOKEN)) return json({ error: 'token' }, 401);
    try {
      if (path === '/v1/tides') return json(await tides(env));
    } catch (e) {
      console.error(JSON.stringify({ path, error: String(e.message || e) }));
      return json({ error: 'upstream' }, 502);
    }
    return json({ error: 'not found' }, 404);
  },
};
