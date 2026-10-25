/*
 * Stremio: what the person is in the middle of watching on their Stremio account, as the picks' first tab.
 * Off until Home Settings → Stremio (or stremio=true) turns it on: until then nothing here talks to Stremio, and the
 * home screen is as without it. Turning it off keeps the sign-in, so turning it on again needs no new code.
 *  - Sign-in as on Stremio's own TV apps: the TV shows a code, the person approves it at link.stremio.com on a phone
 *    already signed in to Stremio. No password goes through the TV; the session key stays in this TV's localStorage.
 *  - The list is Stremio's Continue Watching: library items with a saved position, the last watched first.
 * Stremio's LG app (io.strem.tv) opens on its own home screen and takes no title to open, so a card opens Stremio,
 * where the same title is in its Continue Watching row.
 * Plain ES5, like the rest of web/.
 */
var Stremio = (function () {
  'use strict';

  var API = 'https://api.strem.io/api/', LINK = 'https://link.stremio.com/api/v2/', META = 'https://images.metahub.space';
  var STORE = 'fmtv.stremio', MAX = 12, EVERY = 5 * 60000;
  var saved = {};   // {key, email, items, at}
  try { saved = JSON.parse(localStorage.getItem(STORE)) || {}; } catch (e) {}

  var listeners = [], fetching = '', failedAt = 0, link = null;   // fetching: the key whose list is on its way

  function save() { try { localStorage.setItem(STORE, JSON.stringify(saved)); } catch (e) {} }
  function changed() { for (var i = 0; i < listeners.length; i++) listeners[i](); }

  /**
   * One request; done(error, json). Stremio's APIs allow pages from anywhere. link.stremio.com refuses "Origin: null"
   * (HTTP 500), which a desktop browser sends from a file:// page: preview over http. LG's sends file:// (webOS 6 tested).
   */
  function request(method, url, body, done) {
    var x = new XMLHttpRequest(), over = false;
    x.open(method, url, true);
    x.timeout = 15000;
    if (body) x.setRequestHeader('Content-Type', 'application/json');
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      var json = null;
      try { json = JSON.parse(x.responseText); } catch (e) {}
      if (!json) { done(new Error('HTTP ' + x.status)); return; }
      done(null, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(new Error('no connection')); } };
    x.send(body ? JSON.stringify(body) : null);
  }

  /** A call to Stremio's account API; done(error, result, stremioError). */
  function api(method, body, done) {
    request('POST', API + method, body, function (err, json) {
      if (err) { done(err); return; }
      if (json.error) { done(new Error(json.error.message || 'Stremio error'), null, json.error); return; }
      done(null, json.result);
    });
  }

  /** Episodes are "tt0903747:2:5"; anime ids ("kitsu:42189:1") are show:episode, without a season. */
  function episode(videoId) {
    var m = /^(.+):(\d+):(\d+)$/.exec(videoId || '');
    if (!m) return null;
    if (/^(kitsu|mal|anilist|anidb)$/.test(m[1])) return {season: 0, episode: +m[3]};
    return {season: +m[2], episode: +m[3]};
  }

  /** A library item as a card: the wide photo for the big card, the poster for the tall ones. */
  function card(li) {
    var s = li.state || {}, id = li._id, imdb = /^tt\d+$/.test(id), duration = s.duration || 0, offset = s.timeOffset || 0;
    return {
      stremio: true,
      id: id,
      title: li.name || '',
      ep: li.type === 'series' ? episode(s.video_id) : null,
      left: duration > offset ? Math.max(1, Math.round((duration - offset) / 60000)) : 0,
      // Pictures only from Stremio's own image server (by IMDb id): an add-on's own URLs could be any host.
      art: imdb ? META + '/background/medium/' + id + '/img' : '',
      poster: imdb ? META + '/poster/medium/' + id + '/img' : '',
      progress: duration ? Math.min(1, offset / duration) : 0
    };
  }

  /** "S2 E5 · 23 min left", in the language in use (the list is kept across a language change). */
  function meta(c) {
    var parts = [];
    if (c.ep && c.ep.season) parts.push(trf('S{season} E{episode}', {season: c.ep.season, episode: c.ep.episode}));
    else if (c.ep) parts.push(trf('E{episode}', {episode: c.ep.episode}));
    if (c.left) parts.push(trf('{n} min left', {n: c.left}));
    return parts.join(' \u00b7 ');
  }

  /** Stremio's Continue Watching: kept in the library (or only watched, "temp"), with a position, the last first. */
  function continueWatching(lib) {
    var list = (lib || []).filter(function (li) {
      return li && li._id && (!li.removed || li.temp) && li.type !== 'other' && li.state && li.state.timeOffset > 0;
    });
    list.sort(function (a, b) {
      var x = String(a.state.lastWatched || ''), y = String(b.state.lastWatched || '');
      return x < y ? 1 : x > y ? -1 : 0;
    });
    return list.slice(0, MAX).map(card);
  }

  function forget() {
    saved = {};
    save();
    changed();
  }

  /** Fetches the list when it's older than 5 minutes (or now, with force: back from Stremio, or just signed in). */
  function refresh(force) {
    if (!Prefs.stremio() || !saved.key || fetching === saved.key) return;
    var t = Date.now();
    if (!force && saved.at && t - saved.at < EVERY && t >= saved.at) return;
    if (!force && failedAt && t - failedAt < 60000 && t >= failedAt) return;
    var key = fetching = saved.key;
    api('datastoreGet', {authKey: key, collection: 'libraryItem', ids: [], all: true}, function (err, lib, e) {
      if (fetching === key) fetching = '';
      if (saved.key !== key) return;   // signed out meanwhile
      if (err) {
        failedAt = Date.now();
        if (e && e.code === 1) forget();   // "Session does not exist": signed out elsewhere
        return;
      }
      failedAt = 0;
      saved.items = continueWatching(lib);
      saved.at = Date.now();
      save();
      changed();
    });
  }

  function cancelLink() {
    if (link) clearTimeout(link.timer);
    link = null;
  }

  /**
   * Starts a sign-in: update(state) runs on each change, with state.code and state.qr (an image URL) to show,
   * state.error when Stremio didn't answer or the code expired (10 minutes: then OK asks for a new one, so a sheet
   * left open doesn't ask Stremio forever), and state.done once the person approved.
   */
  function startLink(update) {
    cancelLink();
    var mine = link = {code: '', qr: '', error: '', done: false, timer: 0};
    request('GET', LINK + 'create?type=Create', null, function (err, json) {
      if (link !== mine) return;
      var r = json && json.result;
      if (err || !r || !r.code) { mine.error = tr("Couldn't reach Stremio. Try again."); update(mine); return; }
      mine.code = r.code;
      mine.qr = r.qrcode || '';
      mine.expires = Date.now() + 10 * 60000;
      update(mine);
      poll();
    });
    function poll() {
      mine.timer = setTimeout(function () {
        if (link !== mine) return;
        if (Date.now() > mine.expires) { link = null; mine.error = tr('The code expired. Press OK for a new one.'); update(mine); return; }
        // Until it's approved, Stremio answers with an error ("Invalid or expired token"): asked again in 3 s.
        request('GET', LINK + 'read?type=Read&code=' + encodeURIComponent(mine.code), null, function (e2, j2) {
          if (link !== mine) return;
          var key = j2 && j2.result && j2.result.authKey;
          if (!key) { poll(); return; }
          link = null;
          saved = {key: key};
          save();
          mine.done = true;
          update(mine);
          api('getUser', {authKey: key}, function (e3, user) {
            if (!e3 && user && user.email && saved.key === key) { saved.email = user.email; save(); changed(); }
          });
          refresh(true);
        });
      }, 3000);
    }
  }

  // MARK: - Picks from Stremio's own catalogue (Cinemeta), for TVs with Stremio and no TMDB key

  var CINEMETA = 'https://v3-cinemeta.strem.io/catalog/';

  /** Cinemeta's genres are IMDb's, in English. */
  function genre(name) {
    switch (name) {
      case 'Action': return tr('genre|Action');
      case 'Adventure': return tr('genre|Adventure');
      case 'Animation': return tr('genre|Animation');
      case 'Biography': return tr('genre|Biography');
      case 'Comedy': return tr('genre|Comedy');
      case 'Crime': return tr('genre|Crime');
      case 'Documentary': return tr('genre|Documentary');
      case 'Drama': return tr('genre|Drama');
      case 'Family': return tr('genre|Family');
      case 'Fantasy': return tr('genre|Fantasy');
      case 'Film-Noir': return tr('genre|Film-Noir');
      case 'Game-Show': return tr('genre|Game-Show');
      case 'History': return tr('genre|History');
      case 'Horror': return tr('genre|Horror');
      case 'Music': return tr('genre|Music');
      case 'Musical': return tr('genre|Musical');
      case 'Mystery': return tr('genre|Mystery');
      case 'News': return tr('genre|News');
      case 'Reality-TV': return tr('genre|Reality-TV');
      case 'Romance': return tr('genre|Romance');
      case 'Sci-Fi': return tr('genre|Sci-Fi');
      case 'Short': return tr('genre|Short');
      case 'Sport': return tr('genre|Sport');
      case 'Talk-Show': return tr('genre|Talk-Show');
      case 'Thriller': return tr('genre|Thriller');
      case 'War': return tr('genre|War');
      case 'Western': return tr('genre|Western');
      default: return name;
    }
  }

  /** A catalogue entry as a pick, with the same fields and the same line as TMDB's ("Series · Drama · 2 seasons · Stremio"). */
  function pick(m, kind) {
    var parts = [], genres = m.genres || m.genre || [];
    if (kind === 'tv') parts.push(tr('Series'));
    if (genres.length) parts.push(genre(genres[0]));
    var runtime = parseInt(m.runtime, 10);
    if (kind === 'movie' && runtime > 0) {
      var h = Math.floor(runtime / 60), mm = runtime % 60;
      parts.push(h ? trf('{h}h {m}m', {h: h, m: mm}) : trf('{m}m', {m: mm}));
    } else if (kind === 'tv') {
      var seasons = 0, v = m.videos || [];
      for (var i = 0; i < v.length; i++) if (v[i].season > seasons) seasons = v[i].season;
      if (seasons > 0) parts.push(plural('plural|one:{n} season|other:{n} seasons', seasons));
    }
    parts.push('Stremio');
    return {id: m.id, kind: kind, title: m.name, backdrop: m.background, meta: parts.join(' · '), app: 'stremio', service: 'Stremio',
      detailed: true};
  }

  /**
   * The picks: what's popular on Stremio this week, then this year's releases, movies and shows in turn (as TMDB's
   * trending and recent releases are); done(error, list), with both when a list didn't come. Cinemeta needs no key
   * and no account. Titles are Cinemeta's, in English, as Stremio shows them.
   */
  function catalog(hideAnimation, done) {
    var year = new Date().getFullYear(), lists = {}, missing = 0, names = ['movie/top', 'series/top', 'movie/year/genre=' + year, 'series/year/genre=' + year];
    var i = 0;
    (function next() {   // one after the other: the TV's network is shared with what's playing
      if (i === names.length) { finish(); return; }
      var name = names[i++];
      request('GET', CINEMETA + name + '.json', null, function (err, json) {
        lists[name] = !err && json && json.metas ? json.metas : [];
        if (!lists[name].length) missing++;
        next();
      });
    })();
    function picks(name, kind, max) {
      var out = [], metas = lists[name];
      for (var j = 0; j < metas.length && out.length < max; j++) {
        var m = metas[j], genres = m.genres || m.genre || [];
        if (!m.id || !m.name || !m.background) continue;
        if (hideAnimation && genres.indexOf('Animation') >= 0) continue;
        out.push(pick(m, kind));
      }
      return out;
    }
    function finish() {
      var out = [], seen = {};
      function add(a, b) {
        for (var k = 0; k < Math.max(a.length, b.length) && out.length < 48; k++) {
          if (k < a.length && !seen[a[k].id]) { seen[a[k].id] = true; out.push(a[k]); }
          if (k < b.length && out.length < 48 && !seen[b[k].id]) { seen[b[k].id] = true; out.push(b[k]); }
        }
      }
      add(picks('movie/top', 'movie', 16), picks('series/top', 'tv', 16));
      add(picks('movie/year/genre=' + year, 'movie', 24), picks('series/year/genre=' + year, 'tv', 24));
      if (!out.length) { done(new Error("Stremio's catalogue returned nothing")); return; }
      done(missing ? new Error(missing + " of Stremio's lists didn't come") : null, out);
    }
  }

  function signOut() {
    var key = saved.key;
    cancelLink();
    if (key) api('logout', {authKey: key}, function () {});   // best effort: the key is forgotten here either way
    forget();
  }

  return {
    onChange: function (fn) { listeners.push(fn); },
    connected: function () { return !!saved.key; },
    email: function () { return saved.email || ''; },
    /** Continue Watching, as cards: [{stremio, id, title, meta, art, poster, progress}]. */
    items: function () {
      var list = Prefs.stremio() && saved.key && saved.items ? saved.items : [];
      for (var i = 0; i < list.length; i++) list[i].meta = meta(list[i]);
      return list;
    },
    refresh: refresh,
    startLink: startLink,
    cancelLink: cancelLink,
    signOut: signOut,
    catalog: catalog,
    /** Stremio's app among the TV's, or null. */
    app: function (apps) {
      for (var i = 0; i < apps.length; i++) if (/^io\.strem\./.test(apps[i].id) || /stremio/i.test(apps[i].title)) return apps[i];
      return null;
    }
  };
})();
