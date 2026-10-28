/**
 * The TV's backend: holds the API keys (Wrangler secrets), calls the sources, and gives the TV small JSON. The TV
 * holds only TV_TOKEN, read-only and rotatable. KV keeps each source's last success and failure (/health), and the
 * answers of sources whose terms allow it (cached); never UKHO's.
 *
 *   GET /v1/tides       the next week of high and low water at STATIONS (UKHO's Admiralty API, asked every time)
 *   GET /v1/dashboard   counts for GitHub: the latest builds, reviews waiting, your PRs, Dependabot alerts (3 min cache)
 *   GET /health     each source's last fetch, for the TV's About screen and a monitor (no token: nothing private)
 *
 * Secrets: TV_TOKEN, UKHO_KEY, GITHUB_TOKEN (fine-grained, read-only). Vars: STATIONS ("0451:Mersey,0461:Dee"), ALLOWED_IPS (addresses or CIDR ranges, comma
 * separated; empty = any), GITHUB_RECENT (how many recently pushed repos, 6).
 * The dashboard holds counts only, never repo names or titles: the TV is a shared screen.
 */

const DAY = 86400000;
const KEEP = 3 * DAY;   // how long KV keeps a cached answer (to serve stale when its source is down) and health

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

/** An IPv4 or IPv6 address as 32 or 128 bits (a BigInt), or null. */
function bits(ip) {
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) {
    const parts = ip.split('.').map(Number);
    return parts.some((n) => n > 255) ? null : { v: 4, n: parts.reduce((a, n) => (a << 8n) | BigInt(n), 0n) };
  }
  if (!/^[0-9a-f:]+$/i.test(ip) || (ip.match(/::/g) || []).length > 1) return null;
  const [head, tail] = ip.includes('::') ? ip.split('::') : [ip, null];
  const a = head ? head.split(':') : [], b = tail ? tail.split(':') : [];
  const groups = tail === null ? a : [...a, ...Array(8 - a.length - b.length).fill('0'), ...b];
  if (groups.length !== 8 || groups.some((g) => !/^[0-9a-f]{1,4}$/i.test(g))) return null;
  return { v: 6, n: groups.reduce((acc, g) => (acc << 16n) | BigInt(parseInt(g, 16)), 0n) };
}

/** Whether ip is in range: an address, or a CIDR range (your home's IPv6 /64: each device has its own address). */
function inRange(ip, range) {
  const [base, len] = range.split('/');
  const a = bits(ip), b = bits(base);
  if (!a || !b || a.v !== b.v) return false;
  const size = a.v === 4 ? 32 : 128, prefix = len === undefined ? size : Number(len);
  if (!(prefix >= 0 && prefix <= size)) return false;
  const shift = BigInt(size - prefix);
  return a.n >> shift === b.n >> shift;
}

function allowedIp(request, env) {
  const list = (env.ALLOWED_IPS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const ip = request.headers.get('CF-Connecting-IP') || '';
  return !list.length || list.some((range) => inRange(ip, range));
}

function stations(env) {
  return (env.STATIONS || '0451:Mersey,0461:Dee').split(',').map((s) => {
    const [id, name] = s.split(':');
    return { id: id.trim(), name: (name || id).trim() };
  });
}

/** Notes a source's success or failure for /health (no data: some sources' terms forbid storing it). */
async function noteHealth(env, key, error) {
  const health = (await env.CACHE.get('health:' + key, 'json')) || {};
  const next = error ? { ...health, failedAt: Date.now(), error } : { ...health, okAt: Date.now() };
  await env.CACHE.put('health:' + key, JSON.stringify(next), { expirationTtl: KEEP / 1000 });
}

/** Fetches every time, for sources that may not be stored (UKHO's free Discovery tier forbids caching). */
async function live(env, key, load) {
  try {
    const data = await load();
    await noteHealth(env, key);
    return data;
  } catch (e) {
    console.error(JSON.stringify({ source: key, error: String(e.message || e) }));
    await noteHealth(env, key, String(e.message || e));
    throw e;
  }
}

/**
 * The cached answer for key, fetched again when older than fresh (only for sources whose terms allow caching). When the source fails, the last answer is served
 * with stale: true and the error, so the TV can say "stale since"; the failure is logged for Workers Logs.
 */
async function cached(env, key, fresh, load) {
  const old = await env.CACHE.get(key, 'json');
  if (old && Date.now() - old.fetchedAt < fresh) return { ...old, stale: false };
  try {
    const data = await load();
    const entry = { fetchedAt: Date.now(), data };
    await env.CACHE.put(key, JSON.stringify(entry), { expirationTtl: KEEP / 1000 });
    await noteHealth(env, key);
    return { ...entry, stale: false };
  } catch (e) {
    console.error(JSON.stringify({ source: key, error: String(e.message || e) }));
    await noteHealth(env, key, String(e.message || e));
    if (old) return { ...old, stale: true, error: String(e.message || e) };
    throw e;
  }
}

async function ukhoEvents(env, id) {
  const url = `https://admiraltyapi.azure-api.net/uktidalapi/api/V1/Stations/${encodeURIComponent(id)}/TidalEvents?duration=7`;
  const r = await fetch(url, { headers: { 'Ocp-Apim-Subscription-Key': env.UKHO_KEY } });
  if (!r.ok) {
    // UKHO's gateway says why (an invalid key, a missing subscription): its message, never the key.
    const why = (await r.text().catch(() => '')).match(/"message"\s*:\s*"([^"]{0,160})/);
    throw new Error(`UKHO ${id}: HTTP ${r.status}` + (why ? ` (${why[1]})` : ''));
  }
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
      const events = await live(env, 'tides:' + s.id, () => ukhoEvents(env, s.id));
      out.push({ ...s, fetchedAt: Date.now(), stale: false, events: events.filter((e) => Date.parse(e.at) > now - 3600000) });
    } catch (e) {
      out.push({ ...s, fetchedAt: 0, stale: true, error: String(e.message || e), events: [] });
    }
  }
  return { stations: out };
}

// MARK: GitHub

const DASHBOARD_FRESH = 3 * 60000;

async function github(env, path) {
  const r = await fetch('https://api.github.com' + path, {
    headers: { Authorization: 'Bearer ' + env.GITHUB_TOKEN, Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'freemytv-bff' },
  });
  if (!r.ok) {
    const e = new Error(`GitHub ${path.split('?')[0]}: HTTP ${r.status}`);
    e.status = r.status;
    throw e;
  }
  return r.json();
}

const searchCount = async (env, q) => (await github(env, '/search/issues?per_page=1&q=' + encodeURIComponent(q))).total_count;

/** Counts across the most recently pushed repos: never names (a repo without Actions or Dependabot counts as neither). */
async function dashboard(env) {
  const n = Math.min(Math.max(parseInt(env.GITHUB_RECENT || '6', 10) || 6, 1), 20);
  const repos = (await github(env, `/user/repos?sort=pushed&affiliation=owner&per_page=${n + 10}`))
    .filter((r) => !r.archived).slice(0, n);
  const builds = { passing: 0, failing: 0, running: 0 };
  let alerts = 0;
  await Promise.all(repos.map(async (r) => {
    const runs = await github(env, `/repos/${r.full_name}/actions/runs?per_page=1&branch=${encodeURIComponent(r.default_branch)}`)
      .catch(() => ({ workflow_runs: [] }));
    const run = runs.workflow_runs[0];
    if (run && run.status !== 'completed') builds.running++;
    else if (run && ['success', 'skipped', 'neutral'].includes(run.conclusion)) builds.passing++;
    else if (run) builds.failing++;
    // Off, or the token can't see them: 403/404. Counted as none, not as a failure of the whole card.
    const open = await github(env, `/repos/${r.full_name}/dependabot/alerts?state=open&per_page=100`).catch(() => []);
    alerts += Array.isArray(open) ? open.length : 0;
  }));
  const [reviews, mine, mineFailing] = await Promise.all([
    searchCount(env, 'is:pr is:open archived:false review-requested:@me'),
    searchCount(env, 'is:pr is:open archived:false author:@me'),
    searchCount(env, 'is:pr is:open archived:false author:@me status:failure'),
  ]);
  return { repos: repos.length, builds, reviews, pullRequests: { open: mine, failing: mineFailing }, alerts };
}

async function health(env) {
  const sources = {};
  for (const s of stations(env)) sources['tides:' + s.id] = (await env.CACHE.get('health:tides:' + s.id, 'json')) || {};
  if (env.GITHUB_TOKEN) sources.dashboard = (await env.CACHE.get('health:dashboard', 'json')) || {};
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
      if (path === '/v1/dashboard') {
        if (!env.GITHUB_TOKEN) return json({ error: 'no GITHUB_TOKEN' }, 503);
        const c = await cached(env, 'dashboard', DASHBOARD_FRESH, () => dashboard(env));
        return json({ ...c.data, fetchedAt: c.fetchedAt, stale: c.stale, error: c.error });
      }
    } catch (e) {
      console.error(JSON.stringify({ path, error: String(e.message || e) }));
      return json({ error: 'upstream' }, 502);
    }
    return json({ error: 'not found' }, 404);
  },
};
